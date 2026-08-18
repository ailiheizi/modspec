package com.modspec.agent

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

/** Structured event sink for one running script (Android impl wraps `module.log`). */
interface ScriptNotifier {
    /** Emit a structured event with a script context (script_id/generation). */
    fun scriptEvent(
        scriptId: String,
        generation: Long,
        event: String,
        message: String,
        packageName: String? = null,
    )

    /** Throttled per-method hook-hit observation. */
    fun hookHit(scriptId: String, generation: Long, method: String, packageName: String)
}

/** DexKit-backed lookups for scripts (isolated so tests can fake it). */
interface ScriptDexQueries {
    /** Find a class by package/strings/method-name hints. */
    fun findClass(
        apkPath: String,
        classLoader: ClassLoader,
        pkg: String?,
        usingStrings: List<String>?,
        methodName: String?,
    ): Class<*>?

    /**
     * Find a method by dex-level constraints. Returns null when the query is
     * empty or ambiguous; the host turns that into a deterministic diagnostic.
     */
    fun findMethod(
        apkPath: String,
        classLoader: ClassLoader,
        clazz: String?,
        methodName: String?,
        paramTypes: List<String>?,
        returnType: String?,
        usingStrings: List<String>?,
    ): Method?
}

/**
 * Per-process script host: owns the runtime's class resolution, hook
 * registration, event emission and lifecycle state. One host exists per
 * (script, process); it is replaced atomically on script switch/reload.
 *
 * The host is intentionally Android-free apart from the [ScriptNotifier] and
 * [ScriptDexQueries] abstractions, so the core behavior is JVM-testable.
 */
class ScriptHost(
    val scriptId: String,
    val scriptName: String,
    val engine: EngineKind,
    val generation: Long,
    val processPackage: String,
    val targetPackages: List<String>,
    private val classLoader: ClassLoader,
    private val apkPath: String,
    private val registry: HookRegistry,
    private val notifier: ScriptNotifier,
    internal var dexQueries: ScriptDexQueries,
    private val limits: ScriptLimitsView,
) {
    private val hookIds = AtomicLong(0)
    private val hitLimiter = RateLimiter(maxBurst = 4, refillPerSecond = 4.0)
    private val emitLimiter = RateLimiter(maxBurst = 8, refillPerSecond = 8.0)
    private val logLimiter = RateLimiter(maxBurst = 16, refillPerSecond = 8.0)
    private val circuitBreaker = CircuitBreaker(
        maxFailures = limits.circuitFailures,
        cooldownMs = limits.circuitCooldownMs,
    )

    val scriptInfo: Map<String, Any?> = mapOf(
        "id" to scriptId,
        "name" to scriptName,
        "engine" to engine.wireName,
        "generation" to generation,
        "process" to processPackage,
        "targets" to targetPackages,
    )

    fun newHookId(): String = "script:${scriptId}:${hookIds.incrementAndGet()}"

    fun resolveMethod(clazz: Any?, name: String, params: List<Any?>?): Method? {
        val resolved = resolveClass(clazz) ?: return null
        val (method, ambiguity) = ScriptReflection.findMethod(classLoader, resolved, name, params)
        if (method == null && ambiguity != null) {
            scriptError("target not found: $ambiguity")
        }
        return method
    }

    fun resolveConstructor(clazz: Any?, params: List<Any?>?): Constructor<*>? {
        val resolved = resolveClass(clazz) ?: return null
        val (constructor, ambiguity) = ScriptReflection.findConstructor(classLoader, resolved, params)
        if (constructor == null && ambiguity != null) {
            scriptError("target not found: $ambiguity")
        }
        return constructor
    }

    fun ambiguityFor(clazz: Any?, name: String, params: List<Any?>?): String {
        val resolved = resolveClass(clazz)
            ?: return "class not found: ${classLabel(clazz)}"
        val (_, ambiguity) = ScriptReflection.findMethod(classLoader, resolved, name, params)
        return ambiguity ?: "method not found: ${resolved.name}.$name"
    }

    fun registerHook(executable: Executable, phase: String, callback: (HookInvocation) -> Unit, hookId: String) {
        val registryPhase = when (phase) {
            "before" -> HookRegistry.HookPhase.BEFORE
            "replace" -> HookRegistry.HookPhase.REPLACE
            else -> HookRegistry.HookPhase.AFTER
        }
        val methodLabel = ScriptReflection.signature(executable)
        registry.register(
            executable = executable,
            phase = registryPhase,
            callback = { invocation ->
                if (hitLimiter.allow(methodLabel)) {
                    notifier.hookHit(scriptId, generation, methodLabel, processPackage)
                }
                callback.invoke(invocation)
            },
            id = hookId,
            scriptId = scriptId,
        )
        notifier.scriptEvent(
            scriptId,
            generation,
            "script_loaded",
            "hook installed: $methodLabel",
            processPackage,
        )
    }

    fun unhook(id: String): Boolean {
        val removed = registry.unregisterFamily(id)
        return removed > 0
    }

    fun findClass(name: String): Class<*>? = ScriptReflection.loadClass(classLoader, name)

    @Volatile
    private var waitClassCapMs: Long = limits.maxWaitClassMs

    /** Test hook: cap the waitForClass budget without rewriting the manifest. */
    fun limitWaitClassForTest(capMs: Long) {
        waitClassCapMs = capMs.coerceAtLeast(0L)
    }

    fun waitForClass(name: String, timeoutMs: Long): Class<*>? {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(0L, waitClassCapMs)
        var delay = 50L
        while (true) {
            findClass(name)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(delay)
            delay = (delay * 1.5).toLong().coerceAtMost(500L)
        }
    }

    fun emit(event: String, payload: Any?) {
        if (!emitLimiter.allow(event)) return
        val summary = payloadSummary(payload)
        notifier.scriptEvent(
            scriptId,
            generation,
            event,
            if (summary == null) event else "$event $summary",
            processPackage,
        )
    }

    fun log(parts: List<Any?>) {
        if (!logLimiter.allow("log")) return
        val message = parts.joinToString(" ") { it?.toString() ?: "null" }
        notifier.scriptEvent(scriptId, generation, "script_message", message, processPackage)
    }

    /**
     * Register a native PLT hook (xhook bridge). Loads the on-demand library
     * on first use, then registers the symbol; every hit emits a structured
     * `native_hit` event with the raw argument slots and the original result.
     * Observe-only by default (the original result is returned unchanged).
     */
    fun nativeHook(libRegex: String, symbol: String, id: String) {
        if (!isNativeLibReady()) {
            scriptError("native hook unavailable: ${NativeHookLib.libPath()} (deploy via PC first)")
            return
        }
        val hookId = NativeHookBridge.nextHookId.incrementAndGet()
        NativeHookBridge.eventSink = { hid, args, result ->
            val r = runCatching {
                if (logLimiter.allow("native_hit")) {
                    notifier.scriptEvent(
                        scriptId,
                        generation,
                        "native_hit",
                        "hook=$hid symbol=$symbol args=[${args.joinToString(",")}] result=$result",
                        processPackage,
                    )
                }
                result
            }.getOrDefault(result)
            r
        }
        if (!NativeHookBridge.register(libRegex, symbol, hookId)) {
            scriptError("native hook register failed: $libRegex / $symbol")
            return
        }
        NativeHookBridge.refresh(false)
    }

    private var nativeLibReady = false

    private fun isNativeLibReady(): Boolean {
        if (nativeLibReady) return true
        nativeLibReady = runCatching {
            System.load(NativeHookLib.libPath())
            true
        }.getOrDefault(false)
        return nativeLibReady
    }

    /**
     * Show an Android Toast in the hooked process (requires the `toast`
     * capability). Resolves the application Context reflectively via
     * `ActivityThread.currentApplication()`; on the JVM (unit tests) or when
     * the hidden API is unavailable this degrades to a script_message log
     * instead of failing. Rate-limited like `log`.
     */
    fun toast(text: String) {
        if (!logLimiter.allow("toast")) return
        val context = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentApplication").invoke(null)
            current as? android.content.Context
        }.getOrNull()
        if (context == null) {
            notifier.scriptEvent(scriptId, generation, "script_message", "toast (no ui): $text", processPackage)
            return
        }
        runCatching {
            val toast = android.widget.Toast.makeText(
                context,
                text.take(120),
                android.widget.Toast.LENGTH_SHORT,
            )
            toast.show()
        }.onFailure { error ->
            notifier.scriptEvent(scriptId, generation, "script_error", "toast failed: ${error.message}", processPackage)
        }
    }

    fun scriptError(message: String) {
        notifier.scriptEvent(scriptId, generation, "script_error", message.take(800), processPackage)
    }

    fun onCallbackError(hookId: String, error: Throwable) {
        scriptError("hook $hookId failed: ${error.javaClass.name}: ${error.message}")
        if (circuitBreaker.recordFailure()) {
            scriptError("circuit open after ${limits.circuitFailures} consecutive failures; callbacks suspended")
        }
    }

    val circuitOpen: Boolean get() = circuitBreaker.isOpen

    // --- reflection helpers -------------------------------------------------

    private fun resolveClass(clazz: Any?): Class<*>? = when (clazz) {
        is Class<*> -> clazz
        is String -> findClass(clazz)
        else -> null
    }

    private fun classLabel(clazz: Any?): String = when (clazz) {
        is Class<*> -> clazz.name
        is String -> clazz
        else -> clazz?.toString() ?: "?"
    }

    fun getField(obj: Any?, name: String): Any? {
        val clazz = obj?.javaClass ?: throw ScriptException("getField: null receiver")
        val field = findField(clazz, name)
            ?: throw ScriptException("field not found: ${clazz.name}.$name")
        return field.get(obj)
    }

    fun setField(obj: Any?, name: String, value: Any?) {
        val clazz = obj?.javaClass ?: throw ScriptException("setField: null receiver")
        val field = findField(clazz, name)
            ?: throw ScriptException("field not found: ${clazz.name}.$name")
        field.set(obj, ScriptReflection.coerce(value, field.type))
    }

    fun getStaticField(clazz: Any?, name: String): Any? {
        val resolved = resolveClass(clazz) ?: throw ScriptException("class not found: ${classLabel(clazz)}")
        val field = findField(resolved, name)
            ?: throw ScriptException("field not found: ${resolved.name}.$name")
        if (!Modifier.isStatic(field.modifiers)) {
            throw ScriptException("field is not static: ${resolved.name}.$name")
        }
        return field.get(null)
    }

    fun setStaticField(clazz: Any?, name: String, value: Any?) {
        val resolved = resolveClass(clazz) ?: throw ScriptException("class not found: ${classLabel(clazz)}")
        val field = findField(resolved, name)
            ?: throw ScriptException("field not found: ${resolved.name}.$name")
        if (!Modifier.isStatic(field.modifiers)) {
            throw ScriptException("field is not static: ${resolved.name}.$name")
        }
        field.set(null, ScriptReflection.coerce(value, field.type))
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val currentClass: Class<*> = current
            runCatching { currentClass.getDeclaredField(name) }.getOrNull()?.let { return it }
            current = currentClass.superclass
        }
        return null
    }

    fun callMethod(obj: Any?, name: String, args: List<Any?>): Any? {
        val clazz = obj?.javaClass ?: throw ScriptException("callMethod: null receiver")
        val method = ScriptReflection.findBestMatch(clazz, name, args.size)
            ?: throw ScriptException("method not found: ${clazz.name}.$name(${args.size} args)")
        return ScriptReflection.invoke(method, obj, args)
    }

    fun callStatic(clazz: Any?, name: String, args: List<Any?>): Any? {
        val resolved = resolveClass(clazz) ?: throw ScriptException("class not found: ${classLabel(clazz)}")
        return ScriptReflection.invokeStatic(resolved, name, args)
    }

    fun newInstance(clazz: Any?, args: List<Any?>): Any? {
        val resolved = resolveClass(clazz) ?: throw ScriptException("class not found: ${classLabel(clazz)}")
        val constructor = if (args.isEmpty()) {
            resolved.getDeclaredConstructor()
        } else {
            val (match, ambiguity) = ScriptReflection.findConstructor(classLoader, resolved, null)
            if (match == null && ambiguity != null) {
                throw ScriptException("cannot construct ${resolved.name}: $ambiguity")
            }
            // Prefer a constructor matching the argument count.
            resolved.declaredConstructors.firstOrNull { it.parameterTypes.size == args.size }
                ?: match ?: throw ScriptException("no constructor for ${resolved.name} with ${args.size} args")
        }
        val types = constructor.parameterTypes
        if (types.size != args.size) {
            throw ScriptException("argument count mismatch for ${resolved.name}: expected ${types.size}, got ${args.size}")
        }
        val converted = args.mapIndexed { index, arg ->
            ScriptReflection.coerce(arg, types[index]) ?: if (arg != null) arg else null
        }
        return try {
            constructor.isAccessible = true
            constructor.newInstance(*converted.toTypedArray())
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw ScriptException("constructor ${resolved.name} threw ${error.cause?.javaClass?.name}: ${error.cause?.message}")
        }
    }

    fun dexFindClass(pkg: String?, usingStrings: List<String>?, methodName: String?): Class<*>? =
        dexQueries.findClass(apkPath, classLoader, pkg, usingStrings, methodName)

    fun dexFindMethod(
        clazz: Any?,
        methodName: String?,
        params: List<Any?>?,
        returnType: String?,
        usingStrings: List<String>?,
        unique: Boolean,
    ): Method? {
        val classSpec = when (clazz) {
            is String -> clazz
            is Class<*> -> clazz.name
            else -> null
        }
        val paramSpecs = params?.map {
            when (it) {
                is String -> it
                is Class<*> -> it.name
                else -> null
            }
        }?.filterNotNull()
        val found = dexQueries.findMethod(
            apkPath = apkPath,
            classLoader = classLoader,
            clazz = classSpec,
            methodName = methodName,
            paramTypes = paramSpecs,
            returnType = returnType,
            usingStrings = usingStrings,
        )
        if (found == null && unique) {
            scriptError(
                "dexkit lookup failed for method=${methodName} class=${classSpec ?: "*"} params=${paramSpecs ?: "*"} strings=${usingStrings ?: "*"}; query was not unique or not found",
            )
        }
        return found
    }

    /** Unhook everything and report unload. */
    fun dispose() {
        val removed = registry.unregisterScript(scriptId)
        notifier.scriptEvent(
            scriptId,
            generation,
            "script_unloaded",
            "unloaded ${removed} hook(s)",
            processPackage,
        )
    }

    private fun payloadSummary(payload: Any?): String? {
        if (payload == null) return null
        val text = payload.toString()
        return text.take(200)
    }
}

/** Effective limits for one script run. */
data class ScriptLimitsView(
    val executionMs: Long,
    val callbackMs: Long,
    val maxWaitClassMs: Long,
    val circuitFailures: Int,
    val circuitCooldownMs: Long,
) {
    companion object {
        fun fromManifest(manifest: ScriptManifest): ScriptLimitsView = ScriptLimitsView(
            executionMs = manifest.defaultExecutionMs().toLong(),
            callbackMs = manifest.defaultCallbackMs().toLong(),
            maxWaitClassMs = manifest.defaultWaitClassMs().toLong(),
            circuitFailures = HookRegistry.DEFAULT_BREAKER_MAX_FAILURES,
            circuitCooldownMs = HookRegistry.DEFAULT_BREAKER_COOLDOWN_MS,
        )
    }
}
