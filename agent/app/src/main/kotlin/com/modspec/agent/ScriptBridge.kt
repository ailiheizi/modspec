package com.modspec.agent

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Raised for user-visible script errors (becomes a structured `script_error` event). */
class ScriptException(message: String) : Exception(message)

/**
 * Script-visible view of one hook invocation. Wrapped into a JS object /
 * Lua userdata by the engine adapters, so both runtimes share the same shape:
 *
 * ```
 * ctx.thisObject   — receiver (null for static methods)
 * ctx.args         — writable argument list
 * ctx.arg(i)       — one argument
 * ctx.setArg(i, v) — replace one argument
 * ctx.result       — return value (readable in after; writable in after/replace)
 * ctx.callOriginal() / ctx.proceed() — run the original with current args (replace only)
 * ctx.skip(v)      — skip the original and return v
 * ctx.method / ctx.name / ctx.clazz — the hooked target
 * ```
 */
class InvocationView internal constructor(
    private val invocation: HookInvocation,
    private val toastFn: (String) -> Unit,
) {
    val thisObject: Any? get() = invocation.receiver
    val args: MutableList<Any?> get() = invocation.args
    val method: String get() = invocation.executable.name
    val name: String get() = invocation.executable.name
    val clazz: String get() = invocation.executable.declaringClass.name
    val isConstructor: Boolean get() = invocation.executable is Constructor<*>

    var result: Any?
        get() = invocation.result
        set(value) {
            invocation.result = value
        }

    fun argCount(): Int = invocation.args.size

    fun arg(index: Int): Any? = invocation.arg(index)

    fun setArg(index: Int, value: Any?) {
        invocation.setArg(index, value)
    }

    fun callOriginal(): Any? = invocation.callOriginal()

    fun proceed(): Any? = invocation.callOriginal()

    fun skip(value: Any?) {
        invocation.skipWith(value)
    }

    /**
     * Toast with the hook receiver as context when available (an Application
     * or Activity — reliable inside callbacks), otherwise via the host's
     * reflective fallback.
     */
    fun toast(text: String) {
        val context = invocation.receiver as? android.content.Context
        if (context != null) {
            runCatching {
                android.widget.Toast.makeText(
                    context,
                    text.take(120),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                toastFn("[toast failed: ${error.message}] $text")
            }
        } else {
            toastFn(text)
        }
    }

    fun throwable(): Throwable? = null
}

/** What the bridge exposes to scripts (per-process). */
interface ScriptBridgeApi {
    /**
     * Register a hook with before/after/replace callbacks. At least one
     * callback must be present; each is registered as its own multiplexed
     * entry under the shared [id] family (see [ScriptBridgeImpl.unhook]).
     */
    fun hook(
        clazz: Any?,
        method: Any?,
        params: List<Any?>?,
        before: ScriptHookCallback?,
        after: ScriptHookCallback?,
        replace: ScriptHookCallback?,
        id: String?,
    ): String

    fun hookConstructor(
        clazz: Any?,
        params: List<Any?>?,
        before: ScriptHookCallback?,
        after: ScriptHookCallback?,
        replace: ScriptHookCallback?,
        id: String?,
    ): String

    fun unhook(id: String): Boolean

    fun emit(event: String, payload: Any?)

    fun log(vararg parts: Any?)

    /** Show an Android Toast (requires the `toast` capability). */
    fun toast(text: String)

    /**
     * Native PLT hook via the on-demand xhook bridge (requires the
     * `native_hook` capability). [id] is script-chosen; every hit emits a
     * structured `native_hit` event (args + original result) and keeps
     * original behavior unless the sink overrides the result.
     */
    fun nativeHook(libRegex: String, symbol: String, id: String)

    fun findClass(name: String): Class<*>

    fun findClassOrNull(name: String): Class<*>?

    fun waitForClass(name: String, timeoutMs: Long): Class<*>?

    fun findMethod(clazz: Any?, name: String, params: List<Any?>?): Method

    fun findMethodOrNull(clazz: Any?, name: String, params: List<Any?>?): Method?

    fun findConstructor(clazz: Any?, params: List<Any?>?): Constructor<*>

    fun findConstructorOrNull(clazz: Any?, params: List<Any?>?): Constructor<*>?

    fun getField(obj: Any?, name: String): Any?

    fun setField(obj: Any?, name: String, value: Any?)

    fun getStaticField(clazz: Any?, name: String): Any?

    fun setStaticField(clazz: Any?, name: String, value: Any?)

    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any?

    fun callStatic(clazz: Any?, name: String, vararg args: Any?): Any?

    fun newInstance(clazz: Any?, vararg args: Any?): Any?

    fun dexFindClass(pkg: String?, usingStrings: List<String>?, methodName: String?): Class<*>?

    fun dexFindMethod(
        clazz: Any?,
        methodName: String?,
        params: List<Any?>?,
        returnType: String?,
        usingStrings: List<String>?,
        unique: Boolean,
    ): Method?

    fun getTargets(): List<String>

    fun getPackage(): String

    fun scriptInfo(): Map<String, Any?>
}

/**
 * The Kotlin-side API shared by the JS (Rhino) and Lua (LuaJ) adapters.
 * All methods take and return plain Java values; the adapters own the
 * script-value conversions.
 */
class ScriptBridgeImpl(
    private val host: ScriptHost,
) : ScriptBridgeApi {

    override fun hook(
        clazz: Any?,
        method: Any?,
        params: List<Any?>?,
        before: ScriptHookCallback?,
        after: ScriptHookCallback?,
        replace: ScriptHookCallback?,
        id: String?,
    ): String {
        val executable = when (method) {
            is java.lang.reflect.Method -> method
            is String -> host.resolveMethod(clazz, method, params)
            else -> null
        } ?: throw ScriptException(host.ambiguityFor(clazz, method?.toString() ?: "?", params))
        val hookId = id?.takeIf { it.isNotBlank() } ?: host.newHookId()
        registerPhases(executable, hookId, before, after, replace)
        return hookId
    }

    override fun hookConstructor(
        clazz: Any?,
        params: List<Any?>?,
        before: ScriptHookCallback?,
        after: ScriptHookCallback?,
        replace: ScriptHookCallback?,
        id: String?,
    ): String {
        val executable = host.resolveConstructor(clazz, params)
            ?: throw ScriptException(host.ambiguityFor(clazz, "<init>", params))
        val hookId = id?.takeIf { it.isNotBlank() } ?: host.newHookId()
        registerPhases(executable, hookId, before, after, replace)
        return hookId
    }

    /** Register each present callback as a multiplexed entry in one family. */
    private fun registerPhases(
        executable: java.lang.reflect.Executable,
        hookId: String,
        before: ScriptHookCallback?,
        after: ScriptHookCallback?,
        replace: ScriptHookCallback?,
    ) {
        var registered = 0
        if (before != null) {
            host.registerHook(executable, "before", { invocation -> before.invoke(invocation) }, "$hookId#before")
            registered++
        }
        if (replace != null) {
            host.registerHook(executable, "replace", { invocation -> replace.invoke(invocation) }, "$hookId#replace")
            registered++
        }
        if (after != null) {
            host.registerHook(executable, "after", { invocation -> after.invoke(invocation) }, "$hookId#after")
            registered++
        }
        if (registered == 0) {
            throw ScriptException("hook requires a before/after/replace callback")
        }
    }

    override fun unhook(id: String): Boolean = host.unhook(id)

    override fun emit(event: String, payload: Any?) {
        host.emit(event, payload)
    }

    override fun log(vararg parts: Any?) {
        host.log(parts.toList())
    }

    override fun toast(text: String) {
        host.toast(text)
    }

    override fun nativeHook(libRegex: String, symbol: String, id: String) {
        host.nativeHook(libRegex, symbol, id)
    }

    override fun findClass(name: String): Class<*> =
        host.findClass(name) ?: throw ScriptException("class not found: $name")

    override fun findClassOrNull(name: String): Class<*>? = host.findClass(name)

    override fun waitForClass(name: String, timeoutMs: Long): Class<*>? =
        host.waitForClass(name, timeoutMs)

    override fun findMethod(clazz: Any?, name: String, params: List<Any?>?): Method =
        host.resolveMethod(clazz, name, params)
            ?: throw ScriptException(host.ambiguityFor(clazz, name, params))

    override fun findMethodOrNull(clazz: Any?, name: String, params: List<Any?>?): Method? =
        host.resolveMethod(clazz, name, params)

    override fun findConstructor(clazz: Any?, params: List<Any?>?): Constructor<*> =
        host.resolveConstructor(clazz, params)
            ?: throw ScriptException(host.ambiguityFor(clazz, "<init>", params))

    override fun findConstructorOrNull(clazz: Any?, params: List<Any?>?): Constructor<*>? =
        host.resolveConstructor(clazz, params)

    override fun getField(obj: Any?, name: String): Any? = host.getField(obj, name)

    override fun setField(obj: Any?, name: String, value: Any?) {
        host.setField(obj, name, value)
    }

    override fun getStaticField(clazz: Any?, name: String): Any? =
        host.getStaticField(clazz, name)

    override fun setStaticField(clazz: Any?, name: String, value: Any?) {
        host.setStaticField(clazz, name, value)
    }

    override fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? =
        host.callMethod(obj, name, args.toList())

    override fun callStatic(clazz: Any?, name: String, vararg args: Any?): Any? =
        host.callStatic(clazz, name, args.toList())

    override fun newInstance(clazz: Any?, vararg args: Any?): Any? =
        host.newInstance(clazz, args.toList())

    override fun dexFindClass(
        pkg: String?,
        usingStrings: List<String>?,
        methodName: String?,
    ): Class<*>? = host.dexFindClass(pkg, usingStrings, methodName)

    override fun dexFindMethod(
        clazz: Any?,
        methodName: String?,
        params: List<Any?>?,
        returnType: String?,
        usingStrings: List<String>?,
        unique: Boolean,
    ): Method? = host.dexFindMethod(clazz, methodName, params, returnType, usingStrings, unique)

    override fun getTargets(): List<String> = host.targetPackages

    override fun getPackage(): String = host.processPackage

    override fun scriptInfo(): Map<String, Any?> = host.scriptInfo
}

/** Engine type for script packages. */
enum class EngineKind(val wireName: String) {
    JS("js"),
    LUA("lua");

    companion object {
        fun fromName(name: String): EngineKind? =
            entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Reflection helpers shared by the bridge: class resolution with the target
 * app ClassLoader, method/constructor selection with explicit overload
 * diagnostics, and field/method invocation with argument coercion.
 */
object ScriptReflection {

    fun loadClass(loader: ClassLoader, name: String): Class<*>? = runCatching {
        when {
            name.endsWith("[]") -> {
                val component = loadClass(loader, name.dropLast(2))
                    ?: return null
                java.lang.reflect.Array.newInstance(component, 0).javaClass
            }
            name == "boolean" -> Boolean::class.javaPrimitiveType
            name == "byte" -> Byte::class.javaPrimitiveType
            name == "char" -> Char::class.javaPrimitiveType
            name == "short" -> Short::class.javaPrimitiveType
            name == "int" -> Int::class.javaPrimitiveType
            name == "long" -> Long::class.javaPrimitiveType
            name == "float" -> Float::class.javaPrimitiveType
            name == "double" -> Double::class.javaPrimitiveType
            name == "void" -> Void.TYPE
            name.contains('/') -> loader.loadClass(name.replace('/', '.'))
            else -> loader.loadClass(name)
        }
    }.getOrNull()

    /** "int" / "boolean" / "java.lang.String" / "android.content.Context" / Class → Class. */
    fun parseParamType(loader: ClassLoader, spec: Any?): Class<*>? = when (spec) {
        is Class<*> -> spec
        is String -> loadClass(loader, spec)
        else -> null
    }

    fun parseParams(loader: ClassLoader, specs: List<Any?>?): Array<Class<*>>? {
        if (specs == null) return null
        val types = specs.map { parseParamType(loader, it) ?: return null }.toTypedArray()
        return types
    }

    fun primitiveBoxed(primitive: Class<*>): Class<*> = when (primitive.name) {
        "boolean" -> java.lang.Boolean::class.java
        "byte" -> java.lang.Byte::class.java
        "char" -> java.lang.Character::class.java
        "short" -> java.lang.Short::class.java
        "int" -> java.lang.Integer::class.java
        "long" -> java.lang.Long::class.java
        "float" -> java.lang.Float::class.java
        "double" -> java.lang.Double::class.java
        else -> primitive
    }

    /**
     * Find a declared method by name and optional parameter types. Returns a
     * deterministic ambiguity description when the name matches several
     * overloads and no parameter list disambiguates.
     */
    fun findMethod(
        loader: ClassLoader,
        clazz: Class<*>,
        name: String,
        params: List<Any?>?,
    ): Pair<Method?, String?> {
        val declared = clazz.declaredMethods.filter { it.name == name }
        val candidates = declared + clazz.methods.filter {
            it.name == name && declared.none { d -> sameSignature(d, it) }
        }
        val unique = candidates.distinctBy { signature(it) }
        if (unique.isEmpty()) return null to "method not found: ${clazz.name}.$name"
        if (params != null) {
            val types = parseParams(loader, params)
            if (types != null) {
                val exact = unique.firstOrNull {
                    it.parameterTypes.contentEquals(types)
                } ?: unique.firstOrNull {
                    it.parameterTypes.size == types.size &&
                        it.parameterTypes.zip(types).all { (actual, wanted) ->
                            actual == wanted ||
                                actual.isAssignableFrom(primitiveBoxed(wanted)) ||
                                primitiveBoxed(actual) == primitiveBoxed(wanted)
                        }
                }
                if (exact != null) return exact to null
            }
            // Parameter types were unresolvable (e.g. app classes not yet
            // loadable) or matched nothing: a name-unique method is still a
            // deterministic, unambiguous target.
            return when (unique.size) {
                1 -> unique.first() to null
                else -> null to "ambiguous overloads for ${clazz.name}.$name: " +
                    unique.joinToString("; ") { signature(it) }
            }
        }
        return when {
            unique.size == 1 -> unique.first() to null
            else -> null to "ambiguous overloads for ${clazz.name}.$name: " +
                unique.joinToString("; ") { signature(it) }
        }
    }

    fun findConstructor(
        loader: ClassLoader,
        clazz: Class<*>,
        params: List<Any?>?,
    ): Pair<Constructor<*>?, String?> {
        val declared = clazz.declaredConstructors.toList()
        if (params != null) {
            val types = parseParams(loader, params)
            if (types == null) return null to "invalid parameter list for ${clazz.name}.<init>: $params"
            val exact = declared.firstOrNull { it.parameterTypes.contentEquals(types) }
            return exact to null
        }
        return when {
            declared.size == 1 -> declared.first() to null
            else -> null to "ambiguous constructors for ${clazz.name}: " +
                declared.joinToString("; ") { signature(it) }
        }
    }

    fun signature(executable: java.lang.reflect.Executable): String = buildString {
        append(executable.declaringClass.name).append('.')
        if (executable is Constructor<*>) {
            append("<init>")
        } else {
            append(executable.name)
        }
        append('(')
        executable.parameterTypes.forEachIndexed { index, type ->
            if (index > 0) append(", ")
            append(type.name)
        }
        append(')')
    }

    private fun sameSignature(a: Method, b: Method): Boolean =
        a.name == b.name && a.parameterTypes.contentEquals(b.parameterTypes)

    private fun signature(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString(",") { it.name }})"

    private fun signature(constructor: Constructor<*>): String =
        "<init>(${constructor.parameterTypes.joinToString(",") { it.name }})"

    /** Assign a value to a primitive/boxed/string target; coerces when possible. */
    fun coerce(value: Any?, target: Class<*>): Any? {
        if (value == null) return null
        val boxed = primitiveBoxed(target)
        if (boxed.isInstance(value)) return value
        return when (boxed) {
            java.lang.Integer::class.java -> (value as? Number)?.toInt()
                ?: value.toString().toIntOrNull()
            java.lang.Long::class.java -> (value as? Number)?.toLong()
                ?: value.toString().toLongOrNull()
            java.lang.Short::class.java -> (value as? Number)?.toShort()
            java.lang.Byte::class.java -> (value as? Number)?.toByte()
            java.lang.Float::class.java -> (value as? Number)?.toFloat()
            java.lang.Double::class.java -> (value as? Number)?.toDouble()
            java.lang.Boolean::class.java -> (value as? Boolean)
                ?: value.toString().toBooleanStrictOrNull()
            java.lang.Character::class.java -> (value as? Char) ?: (value as? String)?.firstOrNull()
            java.lang.String::class.java -> value.toString()
            else -> null
        }
    }

    /** Best-effort invocation with the declared parameter types. */
    fun invoke(executable: java.lang.reflect.Executable, receiver: Any?, args: List<Any?>): Any? {
        val method = executable as? Method ?: throw ScriptException("not a method: $executable")
        val types = method.parameterTypes
        if (args.size != types.size) {
            throw ScriptException(
                "argument count mismatch for ${signature(method)}: expected ${types.size}, got ${args.size}",
            )
        }
        val converted = args.mapIndexed { index, arg ->
            coerce(arg, types[index]) ?: if (arg != null && !types[index].isPrimitive) arg else null
        }
        return try {
            method.isAccessible = true
            method.invoke(receiver, *converted.toTypedArray())
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw ScriptException(
                "${signature(method)} threw ${error.cause?.javaClass?.name}: ${error.cause?.message}",
            )
        } catch (error: IllegalArgumentException) {
            throw ScriptException("cannot call ${signature(method)} with ${converted}: ${error.message}")
        }
    }

    fun invokeStatic(clazz: Class<*>, name: String, args: List<Any?>): Any? {
        val method = findBestMatch(clazz, name, args.size)
            ?: throw ScriptException("method not found: ${clazz.name}.$name(${args.size} args)")
        return invoke(method, null, args)
    }

    fun findBestMatch(clazz: Class<*>, name: String, arity: Int): Method? {
        // Walk the inheritance chain (subclass first) so inherited methods
        // like Activity.getWindow() resolve for callMethod/callStatic.
        val candidates = generateSequence<Class<*>>(clazz) { it.superclass }
            .flatMap { level -> level.declaredMethods.asSequence() }
            .filter { it.name == name && it.parameterTypes.size == arity }
            .toList()
        if (candidates.size == 1) return candidates.first()
        return candidates.sortedWith(
            compareBy<Method> { Modifier.isStatic(it.modifiers).not() }
                .thenBy { it.parameterTypes.count { p -> p.isPrimitive } },
        ).firstOrNull()
    }
}
