package com.modspec.agent

import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从 libxposed RemoteFile 加载规则并注册 hook（主路径）。
 * 降级：/data/local/tmp/modspec/rules（legacy）。
 *
 * Hooks are multiplexed through the shared [HookRegistry]: one installed
 * handle per method regardless of how many rules target it, deterministic
 * composition, and a shutdown path that actually unhooks every handle
 * (previously `activeHandles.clear()` dropped bookkeeping without unhooking,
 * and one `O3.b` hook could shadow later hooks on the same method).
 */
class RuleEngine(private val module: XposedModule) {

    private var lastGeneration: Long = -1L

    private val installer = XposedHookInstaller(module)
    private val registry = HookRegistry(
        installHook = { executable, dispatcher -> installer.install(executable, dispatcher) },
        events = object : HookRegistry.HookEvents {
            override fun onCallbackError(scriptId: String, hookId: String, error: Throwable) {
                logEvent(
                    event = "hook_error",
                    message = "rule hook $hookId failed: ${error.javaClass.name}: ${error.message}",
                )
            }

            override fun onCallbackSlow(scriptId: String, hookId: String, elapsedMs: Long) {
                logEvent(
                    event = "hook_error",
                    message = "rule hook $hookId took ${elapsedMs}ms (budget exceeded)",
                )
            }

            override fun onReplaceSuperseded(scriptId: String, hookId: String) {
                logEvent(
                    event = "hook_error",
                    message = "rule hook $hookId superseded by a later replace on the same method",
                )
            }

            override fun onCircuitOpen(scriptId: String, consecutiveFailures: Int) {
                logEvent(
                    event = "hook_error",
                    message = "rule circuit open after $consecutiveFailures consecutive failures",
                )
            }
        },
    )

    @Synchronized
    fun reloadIfNeeded() {
        val prefs = module.getRemotePreferences(RemotePrefsManager.DEFAULT_GROUP)
        val generation = prefs.getLong(ModuleReloader.KEY_RULES_GENERATION, 0L)
        if (generation <= lastGeneration && Companion.compiledRules.isNotEmpty()) return

        val activeNames = prefs.getString(RemoteRulesManager.KEY_ACTIVE_RULES, null)?.let { json ->
            runCatching { parseActiveRuleNames(json) }.getOrElse { error ->
                logEvent(
                    event = "hook_error",
                    message = "invalid active_rules for generation $generation: ${error.message}",
                    generation = generation,
                )
                return
            }
        }
        shutdown()
        Companion.compiledRules.clear()
        lastGeneration = generation

        val androidSdk = android.os.Build.VERSION.SDK_INT
        val loaded = loadFromRemoteFiles(androidSdk, activeNames)
            || loadFromLegacySharedDir(androidSdk, activeNames)

        log("loaded ${Companion.compiledRules.size} rule file(s), gen=$generation, remote=$loaded")
    }

    private fun loadFromRemoteFiles(androidSdk: Int, activeNames: Set<String>?): Boolean {
        if (!hasRemoteCap()) return false
        return runCatching {
            val names = module.listRemoteFiles().filter {
                it.endsWith(RemoteRulesManager.RULE_SUFFIX) && (activeNames == null || it in activeNames)
            }
            if (names.isEmpty()) return false
            for (name in names) {
                runCatching {
                    module.openRemoteFile(name).use { pfd ->
                        val text = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                            .bufferedReader().readText()
                        RuleParser.parse(text, androidSdk).let { Companion.compiledRules += it }
                    }
                }.onFailure { error ->
                    logEvent("hook_error", message = "failed to load $name: ${error.message}")
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun loadFromLegacySharedDir(androidSdk: Int, activeNames: Set<String>?): Boolean {
        val rulesDir = File(AgentStorage.SHARED_RULES_DIR)
        if (!rulesDir.isDirectory) return false
        rulesDir.listFiles()
            ?.filter {
                it.isFile && it.name.endsWith(RemoteRulesManager.RULE_SUFFIX) &&
                    (activeNames == null || it.name in activeNames)
            }
            ?.forEach { file ->
                RuleParser.parseFile(file, androidSdk)?.let { Companion.compiledRules += it }
                    ?: logEvent("hook_error", message = "failed to load ${file.name}")
            }
        return Companion.compiledRules.isNotEmpty()
    }

    private fun hasRemoteCap(): Boolean =
        (module.frameworkProperties and XposedInterface.PROP_CAP_REMOTE) != 0L

    private fun parseActiveRuleNames(json: String): Set<String> {
        val ids = JSONArray(json)
        return (0 until ids.length())
            .map { AgentStorage.safeRuleName(ids.getString(it)) + RemoteRulesManager.RULE_SUFFIX }
            .toSet()
    }

    @Synchronized
    fun onPackageLoaded(param: PackageLoadedParam) {
        reloadIfNeeded()
        val pkg = param.packageName
        val classLoader = param.defaultClassLoader
        val apkPath = runCatching { param.applicationInfo.sourceDir }.getOrDefault("")

        for (rule in Companion.compiledRules) {
            if (rule.packages.isNotEmpty() && pkg !in rule.packages) continue
            for (hook in rule.hooks) {
                if (hook.phase != PHASE_PACKAGE_LOADED) continue
                registerHook(classLoader, apkPath, hook, rule.metaId, pkg)
            }
        }
    }

    @Synchronized
    fun onSystemServerStarting(param: SystemServerStartingParam) {
        reloadIfNeeded()
        val loader = param.classLoader
        for (rule in Companion.compiledRules) {
            for (hook in rule.hooks) {
                if (hook.phase != PHASE_SYSTEM_SERVER) continue
                registerHook(loader, apkPath = "", hook, rule.metaId, "system")
            }
        }
    }

    /** Unhook every installed rule hook and clear all bookkeeping. */
    @Synchronized
    fun shutdown() {
        registry.shutdown()
        Companion.lastHitLogMs.clear()
        lastGeneration = -1L
    }

    private fun registerHook(
        classLoader: ClassLoader,
        apkPath: String,
        hook: ParsedHook,
        ruleId: String,
        packageName: String,
    ) {
        val method = resolveMethod(classLoader, apkPath, hook)
        if (method == null) {
            logEvent(
                event = "hook_error",
                ruleId = ruleId,
                packageName = packageName,
                message = "target not found: ${hook.className ?: hook.dexQuery?.className}.${hook.methodName ?: hook.dexQuery?.methodName}",
            )
            return
        }

        val hookId = "rule:$ruleId:${method.declaringClass.name}.${method.name}"
        val hitKey = "$ruleId:${method.declaringClass.name}.${method.name}"
        val registered = runCatching {
            registry.register(
                executable = method,
                phase = HookRegistry.HookPhase.BEFORE,
                callback = { invocation ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    val last = Companion.lastHitLogMs.getOrPut(hitKey) { AtomicLong(0L) }
                    val previous = last.get()
                    if (now - previous >= HIT_LOG_INTERVAL_MS && last.compareAndSet(previous, now)) {
                        logEvent(
                            event = "hook_hit",
                            ruleId = ruleId,
                            packageName = packageName,
                            message = "${method.declaringClass.name}.${method.name}",
                        )
                    }
                    when (hook.actionKind) {
                        "skip" -> invocation.skipWith(null)
                        "observe" -> Unit
                        "return_const" -> invocation.skipWith(resolveConst(hook.returnConst))
                        else -> Unit
                    }
                },
                id = hookId,
                scriptId = "rule",
            )
        }
        registered.onFailure { error ->
            logEvent(
                event = "hook_error",
                ruleId = ruleId,
                packageName = packageName,
                message = "failed to register ${method.declaringClass.name}.${method.name}: ${error.message}",
            )
            return
        }
        logEvent(
            event = "hook_loaded",
            ruleId = ruleId,
            packageName = packageName,
            message = "${method.declaringClass.name}.${method.name} (${hook.resolver})",
        )
    }

    private fun resolveMethod(
        classLoader: ClassLoader,
        apkPath: String,
        hook: ParsedHook,
    ): Method? {
        return when (hook.resolver) {
            "dexkit" -> {
                val query = hook.dexQuery ?: return null
                DexKitResolver.findMethod(apkPath, classLoader, query)
            }
            else -> findStaticMethod(
                classLoader,
                hook.className ?: return null,
                hook.methodName ?: return null,
                hook.signature,
            )
        }
    }

    private fun resolveConst(value: Any?): Any? = value

    private fun findStaticMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        signature: String?,
    ): Method? {
        val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return null
        if (signature != null) {
            val paramTypes = SignatureParser.parseParams(signature)
            return runCatching { clazz.getDeclaredMethod(methodName, *paramTypes) }.getOrNull()
                ?: clazz.methods.firstOrNull { m ->
                    m.name == methodName && m.parameterTypes.contentEquals(paramTypes)
                }
        }
        return clazz.declaredMethods.firstOrNull { it.name == methodName && !Modifier.isAbstract(it.modifiers) }
            ?: clazz.methods.firstOrNull { it.name == methodName }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        module.log(Log.INFO, TAG, message)
    }

    private fun logEvent(
        event: String,
        message: String,
        ruleId: String? = null,
        packageName: String? = null,
        generation: Long? = null,
    ) {
        val payload = JSONObject()
            .put("event", event)
            .put("message", message)
        if (ruleId != null) payload.put("rule_id", ruleId)
        if (packageName != null) payload.put("package", packageName)
        val effectiveGeneration = generation ?: lastGeneration
        if (effectiveGeneration >= 0) payload.put("generation", effectiveGeneration)
        log(payload.toString())
    }

    companion object {
        private const val TAG = "ModspecRuleEngine"
        private const val PHASE_PACKAGE_LOADED = "on_package_loaded"
        private const val PHASE_SYSTEM_SERVER = "on_system_server_starting"

        private val compiledRules = mutableListOf<ParsedRuleFile>()
        private val lastHitLogMs = ConcurrentHashMap<String, AtomicLong>()
        private const val HIT_LOG_INTERVAL_MS = 250L
    }
}

/** JNI-style descriptor → Java [Class] list. */
private object SignatureParser {
    fun parseParams(signature: String): Array<Class<*>> {
        val start = signature.indexOf('(')
        val end = signature.indexOf(')')
        if (start < 0 || end < 0) return emptyArray()
        val params = signature.substring(start + 1, end)
        val types = mutableListOf<Class<*>>()
        var i = 0
        while (i < params.length) {
            val (type, consumed) = readType(params, i)
            types += type
            i += consumed
        }
        return types.toTypedArray()
    }

    private fun readType(desc: String, offset: Int): Pair<Class<*>, Int> {
        return when (desc[offset]) {
            'Z' -> Boolean::class.javaPrimitiveType!! to 1
            'B' -> Byte::class.javaPrimitiveType!! to 1
            'C' -> Char::class.javaPrimitiveType!! to 1
            'S' -> Short::class.javaPrimitiveType!! to 1
            'I' -> Int::class.javaPrimitiveType!! to 1
            'J' -> Long::class.javaPrimitiveType!! to 1
            'F' -> Float::class.javaPrimitiveType!! to 1
            'D' -> Double::class.javaPrimitiveType!! to 1
            'V' -> Void.TYPE to 1
            'L' -> {
                val end = desc.indexOf(';', offset)
                val name = desc.substring(offset + 1, end).replace('/', '.')
                Class.forName(name) to (end - offset + 1)
            }
            '[' -> {
                val (component, consumed) = readType(desc, offset + 1)
                java.lang.reflect.Array.newInstance(component, 0).javaClass to (consumed + 1)
            }
            else -> error("bad signature at $offset in $desc")
        }
    }
}
