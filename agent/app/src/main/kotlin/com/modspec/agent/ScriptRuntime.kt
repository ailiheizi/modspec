package com.modspec.agent

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaInteger
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.CoerceJavaToLua
import org.luaj.vm2.lib.jse.CoerceLuaToJava
import org.luaj.vm2.lib.jse.JsePlatform
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper

/**
 * One script runtime (engine-specific), bound to one [ScriptBridgeImpl].
 * The interface is engine-agnostic; implementations convert script values at
 * the boundary and enforce the manifest execution budget.
 */
interface ScriptRuntime {
    /** Compile-check the entrypoint source; returns error messages (empty = ok). */
    fun compile(source: String, name: String): List<String>

    /**
     * Evaluate the entrypoint with a deadline (ms). Returns an error message
     * when the script failed or exceeded its budget, else null.
     */
    fun run(source: String, name: String, deadlineMs: Long): String?

    /** Release engine resources (threads, scopes). */
    fun dispose()
}

/** A `(HookInvocation) -> Unit` callback captured from the script engine. */
fun interface ScriptHookCallback {
    fun invoke(invocation: HookInvocation)
}

/**
 * Rhino (JavaScript) runtime. Pure-Java; MPL-2.0.
 *
 * The interpreter mode is used for two reasons: it is required for
 * instruction-observer interruption (the execution budget) and it is the
 * safest configuration on Android (no bytecode generation / class loading
 * quirks). ES6 syntax is enabled.
 */
class RhinoRuntime(private val bridge: ScriptBridgeImpl) : ScriptRuntime {

    private class DeadlineContextFactory : ContextFactory() {
        @Volatile
        var deadlineMs: Long = 0L

        override fun makeContext(): Context {
            val context = super.makeContext()
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1 // interpreter: interruption + Android safety
            context.instructionObserverThreshold = 50_000
            return context
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (deadlineMs > 0L && System.currentTimeMillis() > deadlineMs) {
                Context.throwAsScriptRuntimeEx(
                    ScriptException("script execution exceeded its time budget"),
                )
            }
        }
    }

    private val factory = DeadlineContextFactory()
    private var scope: ScriptableObject? = null

    override fun compile(source: String, name: String): List<String> {
        val cx = factory.enterContext()
        return try {
            runCatching { cx.compileString(source, name, 1, null) }
                .exceptionOrNull()
                ?.let { error -> listOf(error.message ?: "compile error") }
                ?: emptyList()
        } finally {
            Context.exit()
        }
    }

    override fun run(source: String, name: String, deadlineMs: Long): String? {
        return factory.call { cx ->
            factory.deadlineMs = deadlineMs
            val currentScope = cx.initStandardObjects()
            scope = currentScope
            val wrapped = Context.javaToJS(JsModspecAdapter(this, bridge), currentScope)
            ScriptableObject.putProperty(currentScope, "modspec", wrapped)
            val error = runCatching {
                cx.evaluateString(currentScope, source, name, 1, null)
                null
            }.exceptionOrNull()?.let { it.message ?: "script execution failed" }
            factory.deadlineMs = 0L
            error
        }
    }

    override fun dispose() {
        scope = null
    }

    // --- script-value conversions ---------------------------------------------

    /** Convert a JS value into a plain Java value for the bridge. */
    fun unwrap(value: Any?): Any? = when (value) {
        null, Undefined.instance -> null
        is org.mozilla.javascript.ConsString -> value.toString()
        is Wrapper -> (value as Wrapper).unwrap()
        else -> value
    }

    /** Convert a JS value into plain Java (arrays/objects become List/Map). */
    fun plain(value: Any?): Any? = when (value) {
        null, Undefined.instance -> null
        is NativeArray -> {
            val out = mutableListOf<Any?>()
            for (index in 0 until value.length.toInt()) {
                out += plain(value.get(index, value))
            }
            out
        }
        // Java-object wrappers must be unwrapped before the Scriptable branch:
        // NativeJavaObject exposes method names as ids, which is meaningless
        // here (and catastrophic for wrapped Strings).
        is NativeJavaObject -> plain((value as Wrapper).unwrap())
        is org.mozilla.javascript.NativeJavaArray -> {
            val out = mutableListOf<Any?>()
            val array = (value as Wrapper).unwrap()
            val size = java.lang.reflect.Array.getLength(array)
            for (index in 0 until size) {
                out += plain(java.lang.reflect.Array.get(array, index))
            }
            out
        }
        is Scriptable -> {
            if (value is Function) {
                value
            } else {
                val out = LinkedHashMap<String, Any?>()
                for (id in value.ids) {
                    out[id.toString()] = plain(value.get(id.toString(), value))
                }
                out
            }
        }
        is Wrapper -> (value as Wrapper).unwrap()
        is org.mozilla.javascript.ConsString -> value.toString()
        is String, is Number, is java.lang.Boolean -> value
        else -> value
    }

    /** Convert a plain Java value into a JS value for scripts. */
    fun wrap(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
        null -> null
        is java.lang.Boolean, is java.lang.Number, is java.lang.String, is Class<*> -> value
        is Array<*> -> {
            val array = cx.newArray(scope, value.size)
            value.forEachIndexed { index, element ->
                array.put(index, array, wrap(cx, scope, element))
            }
            array
        }
        is java.util.List<*> -> {
            val array = cx.newArray(scope, value.size)
            value.forEachIndexed { index, element ->
                array.put(index, array, wrap(cx, scope, element))
            }
            array
        }
        is java.util.Map<*, *> -> {
            val objectScript = cx.newObject(scope)
            val map = value as java.util.Map<*, *>
            for ((key, element) in map.entrySet()) {
                ScriptableObject.putProperty(objectScript, key.toString(), wrap(cx, scope, element))
            }
            objectScript
        }
        else -> Context.javaToJS(value, scope)
    }

    /**
     * Convert a JS function into a Kotlin hook callback. Each invocation
     * enters a context on the calling thread (hook callbacks fire on the
     * target app's thread) and translates the shared [HookInvocation] into a
     * JS context object; mutations flow back afterwards.
     */
    fun hookCallback(fn: Any?): ScriptHookCallback? {
        if (fn == null) return null
        val jsFunction = fn as? Function ?: throw ScriptException("hook callback must be a function")
        return ScriptHookCallback { invocation ->
            val cx = factory.enterContext()
            try {
                val currentScope = scope ?: return@ScriptHookCallback
                val view = InvocationView(invocation) { bridge.toast(it) }
                val jsCtx = cx.newObject(currentScope)
                ScriptableObject.putProperty(jsCtx, "thisObject", wrap(cx, currentScope, view.thisObject))
                ScriptableObject.putProperty(jsCtx, "method", view.method)
                ScriptableObject.putProperty(jsCtx, "clazz", view.clazz)
                ScriptableObject.putProperty(jsCtx, "name", view.name)
                ScriptableObject.putProperty(jsCtx, "args", wrap(cx, currentScope, view.args))
                ScriptableObject.putProperty(jsCtx, "result", wrap(cx, currentScope, view.result))
                jsCtx.defineHelper("arg", 1) { args ->
                    view.arg((unwrap(args[0]) as Number).toInt())
                }
                jsCtx.defineHelper("setArg", 2) { args ->
                    val index = (unwrap(args[0]) as Number).toInt()
                    view.setArg(index, unwrap(args[1]))
                    // Keep the script-side snapshot in sync so the read-back
                    // below cannot revert the mutation.
                    val jsArgs = jsCtx.get("args", jsCtx)
                    if (jsArgs is NativeArray) {
                        jsArgs.put(index, jsArgs, args[1])
                    }
                    null
                }
                jsCtx.defineHelper("callOriginal", 0) {
                    val result = view.callOriginal()
                    jsCtx.put("result", jsCtx, wrap(cx, currentScope, result))
                    result
                }
                jsCtx.defineHelper("proceed", 0) {
                    val result = view.callOriginal()
                    jsCtx.put("result", jsCtx, wrap(cx, currentScope, result))
                    result
                }
                jsCtx.defineHelper("skip", 1) { args ->
                    view.skip(unwrap(args[0]))
                    null
                }
                jsCtx.defineHelper("toast", 1) { args ->
                    view.toast(unwrap(args[0])?.toString() ?: "")
                    null
                }
                jsFunction.call(cx, currentScope, currentScope, arrayOf(jsCtx))
                // Read mutations back into the invocation.
                val backArgs = jsCtx.get("args", jsCtx)
                if (backArgs is NativeArray) {
                    for (index in view.args.indices) {
                        view.setArg(index, unwrap(backArgs.get(index, backArgs)))
                    }
                }
                val backResult = jsCtx.get("result", jsCtx)
                if (backResult !is Undefined && backResult != null) {
                    view.result = unwrap(backResult)
                }
            } finally {
                Context.exit()
            }
        }
    }

    /** Convert a JS options object into a plain Java map for the bridge. */
    fun optionsMap(options: Any?): Map<String, Any?>? {
        if (options == null) return null
        if (options !is Scriptable) throw ScriptException("options must be an object")
        val result = LinkedHashMap<String, Any?>()
        val scriptable = options as Scriptable
        for (id in scriptable.ids) {
            result[id.toString()] = plain(scriptable.get(id.toString(), scriptable))
        }
        return result
    }

    private fun Scriptable.defineHelper(
        name: String,
        arity: Int,
        body: (Array<Any?>) -> Any?,
    ) {
        put(
            name,
            this,
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any? {
                    return wrap(cx, scope, body(args))
                }
            },
        )
    }
}

/**
 * The `modspec` object exposed to JavaScript. Rhino matches Java methods by
 * name and arity, so the API is a flat set of single-argument methods taking
 * an options object (mirroring the Lua table API for full JS/Lua parity).
 */
class JsModspecAdapter(
    private val runtime: RhinoRuntime,
    private val bridge: ScriptBridgeImpl,
) {
    fun hook(options: Any?): String = hookWith(options, constructor = false)

    fun hookConstructor(options: Any?): String = hookWith(options, constructor = true)

    private fun hookWith(options: Any?, constructor: Boolean): String {
        val map = runtime.optionsMap(options) ?: emptyMap()
        val before = map["before"]?.let { runtime.hookCallback(it) }
        val replace = map["replace"]?.let { runtime.hookCallback(it) }
        val after = map["after"]?.let { runtime.hookCallback(it) }
        return if (constructor) {
            bridge.hookConstructor(
                clazz = map["clazz"],
                params = map["params"] as? List<*>,
                before = before,
                after = after,
                replace = replace,
                id = map["id"] as? String,
            )
        } else {
            bridge.hook(
                clazz = map["clazz"],
                method = map["method"],
                params = map["params"] as? List<*>,
                before = before,
                after = after,
                replace = replace,
                id = map["id"] as? String,
            )
        }
    }

    fun unhook(id: String): Boolean = bridge.unhook(id)

    fun emit(event: String, payload: Any?) {
        bridge.emit(event, runtime.plain(payload))
    }

    fun log(vararg parts: Any?) {
        bridge.log(*parts.map { runtime.unwrap(it) }.toTypedArray())
    }

    fun toast(text: String) {
        bridge.toast(text)
    }

    fun nativeHook(options: Any?) {
        val map = runtime.optionsMap(options) ?: emptyMap()
        bridge.nativeHook(
            libRegex = map["lib"]?.toString() ?: ".*",
            symbol = map["symbol"]?.toString() ?: throw ScriptException("nativeHook: symbol required"),
            id = map["id"]?.toString() ?: "native",
        )
    }

    fun findClass(name: String): String = bridge.findClass(name).name

    fun findClassOrNull(name: String): String? = bridge.findClassOrNull(name)?.name

    fun waitForClass(name: String, timeoutMs: Any?): String? =
        bridge.waitForClass(name, (timeoutMs as? Number)?.toLong() ?: 15_000L)?.name

    fun findMethod(clazz: Any?, name: String, params: Any?): Any? =
        bridge.findMethodOrNull(clazz, name, paramsList(params))

    fun findMethodOrNull(clazz: Any?, name: String, params: Any?): Any? =
        bridge.findMethodOrNull(clazz, name, paramsList(params))

    fun findConstructor(clazz: Any?, params: Any?): Any? =
        bridge.findConstructorOrNull(clazz, paramsList(params))

    fun findConstructorOrNull(clazz: Any?, params: Any?): Any? =
        bridge.findConstructorOrNull(clazz, paramsList(params))

    private fun paramsList(params: Any?): List<Any?>? =
        runtime.plain(params) as? List<Any?>

    fun getField(obj: Any?, name: String): Any? = bridge.getField(runtime.unwrap(obj), name)

    fun setField(obj: Any?, name: String, value: Any?) {
        bridge.setField(runtime.unwrap(obj), name, runtime.unwrap(value))
    }

    fun getStaticField(clazz: Any?, name: String): Any? =
        bridge.getStaticField(runtime.unwrap(clazz), name)

    fun setStaticField(clazz: Any?, name: String, value: Any?) {
        bridge.setStaticField(runtime.unwrap(clazz), name, runtime.unwrap(value))
    }

    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? =
        bridge.callMethod(runtime.unwrap(obj), name, *args.map { runtime.unwrap(it) }.toTypedArray())

    fun callStatic(clazz: Any?, name: String, vararg args: Any?): Any? =
        bridge.callStatic(runtime.unwrap(clazz), name, *args.map { runtime.unwrap(it) }.toTypedArray())

    fun newInstance(clazz: Any?, vararg args: Any?): Any? =
        bridge.newInstance(runtime.unwrap(clazz), *args.map { runtime.unwrap(it) }.toTypedArray())

    fun dexFindClass(options: Any?): String? {
        val map = runtime.optionsMap(options) ?: emptyMap()
        return bridge.dexFindClass(
            pkg = map["pkg"] as? String,
            usingStrings = (map["usingStrings"] as? List<*>)?.map { it.toString() },
            methodName = map["method"] as? String,
        )?.name
    }

    fun dexFindMethod(options: Any?): Any? {
        val map = runtime.optionsMap(options) ?: emptyMap()
        return bridge.dexFindMethod(
            clazz = map["clazz"],
            methodName = map["method"] as? String,
            params = map["params"] as? List<*>,
            returnType = map["returnType"] as? String,
            usingStrings = (map["usingStrings"] as? List<*>)?.map { it.toString() },
            unique = map["unique"] as? Boolean ?: true,
        )
    }

    fun getTargets(): List<String> = bridge.getTargets()

    fun getPackage(): String = bridge.getPackage()

    fun scriptInfo(): Map<String, Any?> = bridge.scriptInfo()
}

/**
 * Lua (LuaJ) runtime. Pure-Java; MIT. Lua 5.2 semantics via LuaJ 3.0.1.
 *
 * Time budget: LuaJ has no safe preemption primitive, so an over-budget
 * entrypoint is detected and reported (the abandoned thread keeps running
 * until completion but its effects are discarded). Hook callbacks are always
 * short by design; the registry-level watchdog reports slow callbacks.
 */
class LuaRuntime(private val bridge: ScriptBridgeImpl) : ScriptRuntime {

    private val globals: Globals = JsePlatform.standardGlobals()
    private val modspec: LuaTable = LuaTable()

    init {
        globals.set("modspec", modspec)
        installFunctions()
    }

    override fun compile(source: String, name: String): List<String> {
        return try {
            globals.load(source, name)
            emptyList()
        } catch (error: Throwable) {
            listOf(error.message ?: "compile error")
        }
    }

    override fun run(source: String, name: String, deadlineMs: Long): String? {
        return try {
            globals.load(source, name).call()
            null
        } catch (error: Throwable) {
            error.message ?: "script execution failed"
        }
    }

    override fun dispose() {
        // LuaJ has no long-lived scopes to release; nothing to do.
    }

    // --- script-value conversions ---------------------------------------------

    fun toLua(value: Any?): LuaValue = when (value) {
        null -> LuaValue.NIL
        is Boolean -> LuaValue.valueOf(value)
        is Byte, is Short, is Int -> LuaInteger.valueOf((value as Number).toInt())
        is Long -> LuaValue.valueOf((value as Long).toDouble())
        is Float, is Double -> LuaValue.valueOf((value as Number).toDouble())
        is Char -> LuaString.valueOf(value.toString())
        is String -> LuaString.valueOf(value)
        is ByteArray -> LuaString.valueOf(value)
        is Array<*> -> luaTableFrom(value.toList())
        is java.util.List<*> -> luaTableFrom(value)
        is java.util.Map<*, *> -> {
            val table = LuaTable()
            val map = value as java.util.Map<*, *>
            for ((key, element) in map.entrySet()) {
                table.set(key.toString(), toLua(element))
            }
            table
        }
        else -> CoerceJavaToLua.coerce(value)
    }

    private fun luaTableFrom(values: Iterable<*>): LuaTable {
        val table = LuaTable()
        values.forEachIndexed { index, element -> table.set(index + 1, toLua(element)) }
        return table
    }

    fun fromLua(value: LuaValue): Any? = when {
        value.isnil() -> null
        value.isboolean() -> value.toboolean()
        value.isint() -> value.toint()
        value.isnumber() -> value.todouble()
        value.isstring() -> value.tojstring()
        value.isfunction() -> value
        value.istable() -> luaTableToJava(value.checktable())
        else -> CoerceLuaToJava.coerce(value, Any::class.java)
            ?: value.tojstring().takeIf { it.isNotEmpty() }
    }

    /** Lua table → List (integer keys 1..n) or Map (string keys), recursively. */
    private fun luaTableToJava(table: LuaTable): Any? {
        val length = table.length()
        val arrayLike = if (length > 0) {
            val allIntegerKeys = table.keys().asSequence().all {
                it.isint() && it.toint() in 1..length
            }
            allIntegerKeys
        } else {
            false
        }
        return if (arrayLike) {
            (1..length).map { index -> fromLua(table.get(index)) }
        } else {
            val result = LinkedHashMap<String, Any?>()
            for (key in table.keys()) {
                if (key.isstring()) {
                    result[key.tojstring()] = fromLua(table.get(key))
                }
            }
            result
        }
    }

    fun luaCallback(fn: Any?): ScriptHookCallback? {
        if (fn == null) return null
        val luaFunction = fn as? LuaFunction ?: throw ScriptException("hook callback must be a function")
        return ScriptHookCallback { invocation ->
            val view = InvocationView(invocation) { bridge.toast(it) }
            val ctx = LuaTable()
            ctx.set("thisObject", toLua(view.thisObject))
            ctx.set("method", LuaString.valueOf(view.method))
            ctx.set("clazz", LuaString.valueOf(view.clazz))
            ctx.set("name", LuaString.valueOf(view.name))
            ctx.set("args", toLua(view.args))
            ctx.set("result", toLua(view.result))
            ctx.set("arg", ctxMethod(ctx) { values ->
                toLua(view.arg(values.first().checkint() - 1))
            })
            ctx.set("setArg", ctxMethod(ctx) { values ->
                view.setArg(values[0].checkint() - 1, fromLua(values[1]))
                LuaValue.NIL
            })
            ctx.set("callOriginal", ctxMethod(ctx) {
                val result = toLua(view.callOriginal())
                ctx.set("result", result)
                result
            })
            ctx.set("proceed", ctxMethod(ctx) {
                val result = toLua(view.callOriginal())
                ctx.set("result", result)
                result
            })
            ctx.set("skip", ctxMethod(ctx) { values ->
                view.skip(fromLua(values.first()))
                LuaValue.NIL
            })
            ctx.set("toast", ctxMethod(ctx) { values ->
                view.toast(values.first().tojstring())
                LuaValue.NIL
            })
            luaFunction.call(ctx)
            // Read mutations back into the invocation.
            val backResult = ctx.get("result")
            if (!backResult.isnil()) view.result = fromLua(backResult)
        }
    }

    /**
     * Context-helper functions support both Lua call styles: `ctx:arg(1)`
     * (colon sugar, self as first argument) and `ctx.arg(1)`.
     */
    private fun ctxMethod(
        ctx: LuaTable,
        body: (List<LuaValue>) -> LuaValue,
    ): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val values = (1..args.narg()).map { args.arg(it) }
            val stripped = if (values.firstOrNull() === ctx) values.drop(1) else values
            return body(stripped)
        }
    }

    private abstract class ZeroArgLuaFunction : VarArgFunction() {
        abstract fun invokeZero(): LuaValue

        override fun invoke(args: Varargs): Varargs = invokeZero()
    }

    /** Lua options table → plain Java map; Lua functions are kept as LuaValues. */
    fun optionsMap(options: Any?): Map<String, Any?>? {
        if (options == null) return null
        if (options !is LuaTable) throw ScriptException("options must be a table")
        val result = LinkedHashMap<String, Any?>()
        for (key in options.keys()) {
            if (!key.isstring()) continue
            result[key.tojstring()] = luaOptionValue(options.get(key))
        }
        return result
    }

    private fun luaOptionValue(value: LuaValue): Any? = when {
        value.isfunction() -> value
        value.istable() -> luaTableToJava(value.checktable())
        else -> fromLua(value)
    }

    private fun installFunctions() {
        modspec.set("hook", luaHookFunction(false))
        modspec.set("hookConstructor", luaHookFunction(true))
        modspec.set("unhook", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaValue.valueOf(bridge.unhook(arg.checkjstring()))
        })
        modspec.set("emit", object : TwoArgFunction() {
            override fun call(first: LuaValue, second: LuaValue): LuaValue {
                bridge.emit(first.checkjstring(), fromLua(second))
                return LuaValue.NIL
            }
        })
        modspec.set("log", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val parts = (1..args.narg()).map { fromLua(args.arg(it)) }
                bridge.log(*parts.toTypedArray())
                return LuaValue.NIL
            }
        })
        modspec.set("toast", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                bridge.toast(arg.checkjstring())
                return LuaValue.NIL
            }
        })
        modspec.set("nativeHook", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                bridge.nativeHook(
                    libRegex = (fromLua(arg) as? Map<*, *>)?.get("lib")?.toString() ?: ".*",
                    symbol = (fromLua(arg) as? Map<*, *>)?.get("symbol")?.toString() ?: "",
                    id = (fromLua(arg) as? Map<*, *>)?.get("id")?.toString() ?: "native",
                )
                return LuaValue.NIL
            }
        })
        modspec.set("findClass", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                LuaString.valueOf(bridge.findClass(arg.checkjstring()).name)
        })
        modspec.set("findClassOrNull", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue =
                bridge.findClassOrNull(arg.checkjstring())?.name
                    ?.let { LuaString.valueOf(it) } ?: LuaValue.NIL
        })
        modspec.set("waitForClass", object : TwoArgFunction() {
            override fun call(first: LuaValue, second: LuaValue): LuaValue =
                bridge.waitForClass(first.checkjstring(), second.optlong(15_000L))?.name
                    ?.let { LuaString.valueOf(it) } ?: LuaValue.NIL
        })
        modspec.set("findMethod", luaMethodFinder { clazz, name, params ->
            bridge.findMethod(clazz, name, params)
        })
        modspec.set("findMethodOrNull", luaMethodFinder { clazz, name, params ->
            bridge.findMethodOrNull(clazz, name, params)
        })
        modspec.set("findConstructor", luaConstructorFinder { clazz, params ->
            bridge.findConstructor(clazz, params)
        })
        modspec.set("findConstructorOrNull", luaConstructorFinder { clazz, params ->
            bridge.findConstructorOrNull(clazz, params)
        })
        modspec.set("getField", object : TwoArgFunction() {
            override fun call(first: LuaValue, second: LuaValue): LuaValue =
                toLua(bridge.getField(fromLua(first), second.checkjstring()))
        })
        modspec.set("setField", object : ThreeArgLuaFunction() {
            override fun invokeThree(first: LuaValue, second: LuaValue, third: LuaValue): LuaValue {
                bridge.setField(fromLua(first), second.checkjstring(), fromLua(third))
                return LuaValue.NIL
            }
        })
        modspec.set("getStaticField", object : TwoArgFunction() {
            override fun call(first: LuaValue, second: LuaValue): LuaValue =
                toLua(bridge.getStaticField(fromLua(first), second.checkjstring()))
        })
        modspec.set("setStaticField", object : ThreeArgLuaFunction() {
            override fun invokeThree(first: LuaValue, second: LuaValue, third: LuaValue): LuaValue {
                bridge.setStaticField(fromLua(first), second.checkjstring(), fromLua(third))
                return LuaValue.NIL
            }
        })
        modspec.set("callMethod", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val obj = fromLua(args.arg(1))
                val name = args.arg(2).checkjstring()
                val rest = (3..args.narg()).map { fromLua(args.arg(it)) }
                return toLua(bridge.callMethod(obj, name, *rest.toTypedArray()))
            }
        })
        modspec.set("callStatic", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val clazz = fromLua(args.arg(1))
                val name = args.arg(2).checkjstring()
                val rest = (3..args.narg()).map { fromLua(args.arg(it)) }
                return toLua(bridge.callStatic(clazz, name, *rest.toTypedArray()))
            }
        })
        modspec.set("newInstance", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val clazz = fromLua(args.arg(1))
                val rest = (2..args.narg()).map { fromLua(args.arg(it)) }
                return toLua(bridge.newInstance(clazz, *rest.toTypedArray()))
            }
        })
        modspec.set("dexFindClass", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val map = optionsMap(arg)
                return bridge.dexFindClass(
                    pkg = map?.get("pkg") as? String,
                    usingStrings = (map?.get("usingStrings") as? List<*>)?.map { it.toString() },
                    methodName = map?.get("method") as? String,
                )?.name?.let { LuaString.valueOf(it) } ?: LuaValue.NIL
            }
        })
        modspec.set("dexFindMethod", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val map = optionsMap(arg)
                return toLua(
                    bridge.dexFindMethod(
                        clazz = map?.get("clazz"),
                        methodName = map?.get("method") as? String,
                        params = map?.get("params") as? List<*>,
                        returnType = map?.get("returnType") as? String,
                        usingStrings = (map?.get("usingStrings") as? List<*>)?.map { it.toString() },
                        unique = map?.get("unique") as? Boolean ?: true,
                    ),
                )
            }
        })
        modspec.set("getTargets", object : ZeroArgLuaFunction() {
            override fun invokeZero(): LuaValue = toLua(bridge.getTargets())
        })
        modspec.set("getPackage", object : ZeroArgLuaFunction() {
            override fun invokeZero(): LuaValue = LuaString.valueOf(bridge.getPackage())
        })
        modspec.set("scriptInfo", object : ZeroArgLuaFunction() {
            override fun invokeZero(): LuaValue = toLua(bridge.scriptInfo())
        })
    }

    private fun luaHookFunction(constructor: Boolean): LuaValue = object : OneArgFunction() {
        override fun call(arg: LuaValue): LuaValue {
            val map = optionsMap(arg)
            val before = map?.get("before")?.let(::luaCallback)
            val replace = map?.get("replace")?.let(::luaCallback)
            val after = map?.get("after")?.let(::luaCallback)
            val hookId = if (constructor) {
                bridge.hookConstructor(
                    clazz = map?.get("clazz"),
                    params = map?.get("params") as? List<*>,
                    before = before,
                    after = after,
                    replace = replace,
                    id = map?.get("id") as? String,
                )
            } else {
                bridge.hook(
                    clazz = map?.get("clazz"),
                    method = map?.get("method"),
                    params = map?.get("params") as? List<*>,
                    before = before,
                    after = after,
                    replace = replace,
                    id = map?.get("id") as? String,
                )
            }
            return LuaString.valueOf(hookId)
        }
    }

    private fun luaMethodFinder(body: (Any?, String, List<Any?>?) -> Any?): LuaValue =
        object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val clazz = fromLua(args.arg(1))
                val name = args.arg(2).checkjstring()
                val params = if (args.narg() >= 3 && args.arg(3).istable()) {
                    (luaTableToJava(args.arg(3).checktable()) as? List<*>)
                } else {
                    null
                }
                return toLua(body(clazz, name, params))
            }
        }

    private fun luaConstructorFinder(body: (Any?, List<Any?>?) -> Any?): LuaValue =
        object : TwoArgFunction() {
            override fun call(first: LuaValue, second: LuaValue): LuaValue {
                val params = if (second.istable()) {
                    (luaTableToJava(second.checktable()) as? List<*>)
                } else {
                    null
                }
                return toLua(body(fromLua(first), params))
            }
        }

    private abstract class ThreeArgLuaFunction : VarArgFunction() {
        abstract fun invokeThree(first: LuaValue, second: LuaValue, third: LuaValue): LuaValue

        override fun invoke(args: Varargs): Varargs =
            invokeThree(args.arg(1), args.arg(2), args.arg(3))
    }
}
