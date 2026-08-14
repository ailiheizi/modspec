package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * JVM tests running the real Rhino (JS) and LuaJ (Lua) engines against JVM
 * classes through the real [ScriptBridgeImpl] / [ScriptHost] / [HookRegistry]
 * stack — no Android stubs, no device.
 */
class ScriptEngineTest {

    /** Stand-in for a hooked app class (like an obfuscated target). */
    class Demo {
        var greetingCalls = 0

        fun greet(name: String): String {
            greetingCalls++
            return "hi $name"
        }

        fun macroAllowed(game: String, flag: Boolean): Boolean = flag

        companion object {
            @JvmField
            var macroFeatureEnabled = false

            @JvmField
            var functionId = 10020103
        }
    }

    private val demoClass: Class<*> = Demo::class.java

    private class FakeNotifier(
        val events: MutableList<Triple<String, String, String>> = mutableListOf(),
    ) : ScriptNotifier {
        override fun scriptEvent(
            scriptId: String,
            generation: Long,
            event: String,
            message: String,
            packageName: String?,
        ) {
            events += Triple(event, message, scriptId)
        }

        override fun hookHit(scriptId: String, generation: Long, method: String, packageName: String) {
            events += Triple("script_hit", method, scriptId)
        }
    }

    private class FakeDex : ScriptDexQueries {
        override fun findClass(
            apkPath: String,
            classLoader: ClassLoader,
            pkg: String?,
            usingStrings: List<String>?,
            methodName: String?,
        ): Class<*>? = null

        override fun findMethod(
            apkPath: String,
            classLoader: ClassLoader,
            clazz: String?,
            methodName: String?,
            paramTypes: List<String>?,
            returnType: String?,
            usingStrings: List<String>?,
        ): Method? = null
    }

    private class TestInstaller(
        val installed: MutableList<Pair<Method, (HookRegistry.HookChain) -> Any?>> = mutableListOf(),
        val uninstalled: MutableList<Method> = mutableListOf(),
    ) {
        val install: (java.lang.reflect.Executable, (HookRegistry.HookChain) -> Any?) -> HookRegistry.InstalledHook =
            { executable, dispatcher ->
                installed += (executable as Method) to dispatcher
                HookRegistry.InstalledHook { uninstalled += executable as Method }
            }
    }

    private class Fixture {
        val notifier = FakeNotifier()
        val installer = TestInstaller()
        val registry = HookRegistry(
            installHook = installer.install,
            events = object : HookRegistry.HookEvents {
                override fun onCallbackError(scriptId: String, hookId: String, error: Throwable) {
                    notifier.events += Triple("script_error", "${error.javaClass.name}: ${error.message}", scriptId)
                }

                override fun onCallbackSlow(scriptId: String, hookId: String, elapsedMs: Long) {}
                override fun onReplaceSuperseded(scriptId: String, hookId: String) {}
                override fun onCircuitOpen(scriptId: String, failures: Int) {
                    notifier.events += Triple("script_error", "circuit open after $failures failures", scriptId)
                }
            },
        )
        val host = ScriptHost(
            scriptId = "test/engine",
            scriptName = "Engine test",
            engine = EngineKind.JS,
            generation = 1L,
            processPackage = "com.example.target",
            targetPackages = listOf("com.ChillyRoom.DungeonShooter"),
            classLoader = Demo::class.java.classLoader,
            apkPath = "",
            registry = registry,
            notifier = notifier,
            dexQueries = FakeDex(),
            limits = ScriptLimitsView.fromManifest(
                ScriptManifestParser.parse(
                    """
                    script_version = "1"
                    [meta]
                    id = "test/engine"
                    name = "Engine test"
                    [engine]
                    runtime = "js"
                    """.trimIndent(),
                ),
            ),
        )
        val bridge = ScriptBridgeImpl(host)

        /** Invoke a hooked method exactly as the framework chain would. */
        fun invoke(method: Method, receiver: Any?, args: List<Any?>): Any? {
            val dispatcher = installer.installed.first { it.first == method }.second
            val liveArgs = args.toMutableList()
            val chain = object : HookRegistry.HookChain {
                override val executable: java.lang.reflect.Executable get() = method
                override val receiver: Any? get() = receiver
                override val args: MutableList<Any?> get() = liveArgs

                override fun proceed(): Any? = proceedWith(liveArgs)

                override fun proceedWith(chainArgs: List<Any?>): Any? {
                    liveArgs.clear()
                    liveArgs.addAll(chainArgs)
                    return method.invoke(receiver, *liveArgs.toTypedArray())
                }
            }
            return dispatcher(chain)
        }
    }

    private val jsScript = """
        modspec.log("hello", "from", "js");
        modspec.emit("macro_allowed", { game: modspec.getTargets()[0] });
        var clazz = modspec.findClassOrNull("com.modspec.agent.ScriptEngineTest${'$'}Demo");
        if (clazz == null) { throw new Error("class not found"); }
        modspec.hook({
            clazz: "com.modspec.agent.ScriptEngineTest${'$'}Demo",
            method: "greet",
            params: ["java.lang.String"],
            before: function(ctx) {
                ctx.setArg(0, "patched " + ctx.arg(0));
            },
            after: function(ctx) {
                ctx.result = ctx.result + "!";
            },
            id: "js-greet"
        });
        modspec.hook({
            clazz: "com.modspec.agent.ScriptEngineTest${'$'}Demo",
            method: "macroAllowed",
            replace: function(ctx) {
                if (ctx.arg(0) === "com.ChillyRoom.DungeonShooter") {
                    ctx.result = true;
                    return;
                }
                ctx.callOriginal();
            },
            id: "js-macro"
        });
    """

    private val luaScript = """
        modspec.log("hello", "from", "lua")
        modspec.emit("macro_allowed", { game = modspec.getTargets()[1] })
        modspec.hook({
            clazz = "com.modspec.agent.ScriptEngineTest${'$'}Demo",
            method = "greet",
            params = { "java.lang.String" },
            before = function(ctx)
                ctx:setArg(1, "patched " .. ctx:arg(1))
            end,
            after = function(ctx)
                ctx.result = ctx.result .. "!"
            end,
            id = "lua-greet"
        })
        modspec.hook({
            clazz = "com.modspec.agent.ScriptEngineTest${'$'}Demo",
            method = "macroAllowed",
            replace = function(ctx)
                if ctx:arg(1) == "com.ChillyRoom.DungeonShooter" then
                    ctx.result = true
                    return
                end
                ctx:callOriginal()
            end,
            id = "lua-macro"
        })
    """

    private fun fixtureFor(runtime: String): Pair<Fixture, ScriptRuntime> {
        val fixture = Fixture()
        val engineKind = if (runtime == "lua") EngineKind.LUA else EngineKind.JS
        val host = fixture.host
        return fixture to (if (runtime == "lua") {
            LuaRuntime(fixture.bridge)
        } else {
            RhinoRuntime(fixture.bridge)
        })
    }

    @Test
    fun js_engine_hooks_and_composes() {
        val fixture = Fixture()
        val runtime = RhinoRuntime(fixture.bridge)

        assertTrue(runtime.compile(jsScript, "main.js").isEmpty())
        assertNull(runtime.run(jsScript, "main.js", System.currentTimeMillis() + 10_000))

        val demo = Demo()
        val greet = demoClass.declaredMethods.first { it.name == "greet" }
        val macro = demoClass.declaredMethods.first { it.name == "macroAllowed" }

        // before mutates the arg, after appends "!", original ran once.
        val result = fixture.invoke(greet, demo, listOf("Bob"))
        assertEquals("hi patched Bob!", result)
        assertEquals(1, demo.greetingCalls)

        // replace: true only for the target game.
        assertEquals(true, fixture.invoke(macro, demo, listOf("com.ChillyRoom.DungeonShooter", false)))
        assertEquals(false, fixture.invoke(macro, demo, listOf("com.other.game", false)))

        // structured events: script_message, macro_allowed, script_hit, script_loaded.
        val events = fixture.notifier.events.map { it.first }
        assertTrue("script_message" in events)
        assertTrue("macro_allowed" in events)
        assertTrue("script_hit" in events)
        assertTrue("script_loaded" in events)
        assertTrue(events.none { it == "script_error" })

        // unhook path: disposing the script tears the installed hooks down.
        fixture.registry.unregisterScript("test/engine")
        assertEquals(2, fixture.installer.uninstalled.size)
        runtime.dispose()
    }

    @Test
    fun lua_engine_hooks_and_composes() {
        val fixture = Fixture()
        val runtime = LuaRuntime(fixture.bridge)

        assertTrue(runtime.compile(luaScript, "main.lua").isEmpty())
        assertNull(runtime.run(luaScript, "main.lua", System.currentTimeMillis() + 10_000))

        val demo = Demo()
        val greet = demoClass.declaredMethods.first { it.name == "greet" }
        val macro = demoClass.declaredMethods.first { it.name == "macroAllowed" }

        val result = fixture.invoke(greet, demo, listOf("Bob"))
        assertEquals("hi patched Bob!", result)
        assertEquals(1, demo.greetingCalls)

        assertEquals(true, fixture.invoke(macro, demo, listOf("com.ChillyRoom.DungeonShooter", false)))
        assertEquals(false, fixture.invoke(macro, demo, listOf("com.other.game", false)))

        val events = fixture.notifier.events.map { it.first }
        assertTrue("script_message" in events)
        assertTrue("macro_allowed" in events)
        assertTrue("script_hit" in events)
        assertTrue(events.none { it == "script_error" })
        runtime.dispose()
    }

    @Test
    fun js_compile_errors_are_reported() {
        val fixture = Fixture()
        val runtime = RhinoRuntime(fixture.bridge)
        val errors = runtime.compile("function broken( {", "main.js")
        assertTrue(errors.isNotEmpty())
        runtime.dispose()
    }

    @Test
    fun js_execution_budget_is_enforced() {
        val fixture = Fixture()
        val runtime = RhinoRuntime(fixture.bridge)
        val source = "while (true) { modspec.log('spin'); }"
        val error = runtime.run(source, "main.js", System.currentTimeMillis() + 300)
        assertTrue(error?.contains("time budget") == true)
        runtime.dispose()
    }

    @Test
    fun reflection_helpers_work_from_js() {
        val fixture = Fixture()
        val runtime = RhinoRuntime(fixture.bridge)
        val script = """
            var clazz = modspec.findClass("com.modspec.agent.ScriptEngineTest${'$'}Demo");
            var instance = modspec.newInstance(clazz);
            var result = modspec.callMethod(instance, "greet", "direct");
            modspec.emit("bridge_result", { value: result });
            modspec.setStaticField(clazz, "macroFeatureEnabled", true);
            modspec.setStaticField(clazz, "functionId", 10020103);
            modspec.getStaticField(clazz, "functionId");
            var fields = modspec.getStaticField(clazz, "macroFeatureEnabled");
            modspec.emit("bridge_fields", { enabled: fields });
        """
        val runError = runtime.run(script, "main.js", System.currentTimeMillis() + 10_000)
        assertNull(runError)
        assertTrue(Demo.macroFeatureEnabled)
        assertTrue(fixture.notifier.events.any { it.second.contains("bridge_result") })
        assertTrue(fixture.notifier.events.any { it.second.contains("direct") })
        assertTrue(fixture.notifier.events.any { it.second.contains("enabled=true") })
        runtime.dispose()
    }
}
