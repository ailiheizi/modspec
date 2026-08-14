package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * Static/fake acceptance tests for the real `xiaomi/security-center/macro-gate`
 * script package. The canonical `src/main.js` and the Lua example run through
 * the real engine stack against JVM stand-ins for the macro gate surface:
 *
 *  - legacy 12.3.x `O3$b` (resolved by static name via [MiuiClassLoader]);
 *  - 12.7.x MacroUtil (resolved by DexKit evidence string, provided by
 *    [MacroUtilDexQueries]) exposing the same `g`/`h(Context,String,boolean)`.
 *
 * Real-device acceptance happens with `modspec script run`.
 */
class MacroGateAcceptanceTest {

    // --- fake Security Center 12.3.x surface ---------------------------------

    /** `O3` with inner class `b` — binary name `…$O3$b`, like the obfuscated app. */
    class O3 {
        class B {
            companion object {
                @JvmField
                var g: Boolean = false
            }

            /** Stands in for `h(Context, String, boolean)` (Context unavailable on JVM). */
            fun h(ctx: Any, game: String, flag: Boolean): Boolean = flag
        }
    }

    private val o3bClass: Class<*> =
        Class.forName("com.modspec.agent.MacroGateAcceptanceTest${'$'}O3${'$'}B")

    /** Aliases `com.miui.securitycenter.O3$b` onto the test stand-in class. */
    private class MiuiClassLoader(
        parent: ClassLoader,
        private val alias: Class<*>,
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "com.miui.securitycenter.O3${'$'}b") return alias
            return super.loadClass(name, resolve)
        }
    }

    // --- fake Security Center 12.7.x MacroUtil surface ------------------------

    /**
     * Stands in for the obfuscated MacroUtil (12.7.x, e.g. `R3.b`): the same
     * `g`/`h(Context, String, boolean)` gate methods. `f` (2-arg wrapper) is
     * not present so the test also proves only the expected methods are hooked.
     */
    class MacroUtil {
        fun g(ctx: Any, game: String, flag: Boolean): Boolean = flag
        fun h(ctx: Any, game: String, flag: Boolean): Boolean = flag
        fun unrelated(ctx: Any, x: Int): Boolean = true
    }

    private val macroUtilClass: Class<*> = MacroUtil::class.java

    private val MACRO_EVIDENCE = "content://com.xiaomi.macro.MacroStatusProvider/game_macro_change"

    /** DexKit stand-in: resolves MacroUtil by the MacroStatusProvider evidence string. */
    private class MacroUtilDexQueries : ScriptDexQueries {
        override fun findClass(
            apkPath: String,
            classLoader: ClassLoader,
            pkg: String?,
            usingStrings: List<String>?,
            methodName: String?,
        ): Class<*>? {
            val wanted = usingStrings?.any { it.contains("game_macro_change") } == true
            return if (wanted) MacroUtil::class.java else null
        }

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

    private val manifestText: String = scriptText("manifest.toml")

    private fun scriptSource(name: String): String = scriptText("src/$name")

    /** Reads the canonical package from the repo, falling back to test resources. */
    private fun scriptText(rel: String): String {
        val direct = File("../../scripts/xiaomi/security-center/macro-gate/$rel")
        if (direct.isFile) return direct.readText()
        val resource = javaClass.classLoader.getResource("scripts/xiaomi/security-center/macro-gate/$rel")
            ?: error("macro-gate package not found at $rel (run from the agent/app module dir)")
        return resource.readText()
    }

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

    private class TestInstaller(
        val installed: MutableList<Pair<Method, (HookRegistry.HookChain) -> Any?>> = mutableListOf(),
        val uninstalled: MutableList<Method> = mutableListOf(),
    ) {
        val install: (java.lang.reflect.Executable, (HookRegistry.HookChain) -> Any?) -> HookRegistry.InstalledHook =
            { executable, dispatcher ->
                val method = executable as Method
                installed += method to dispatcher
                HookRegistry.InstalledHook { uninstalled += method }
            }
    }

    private class Fixture(
        val classLoader: ClassLoader,
        private val manifestText: String,
    ) {
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
                override fun onCircuitOpen(scriptId: String, failures: Int) {}
            },
        )
        val host = ScriptHost(
            scriptId = "xiaomi/security-center/macro-gate",
            scriptName = "Macro gate",
            engine = EngineKind.JS,
            generation = 1L,
            processPackage = "com.miui.securitycenter",
            targetPackages = listOf("com.ChillyRoom.DungeonShooter"),
            classLoader = classLoader,
            apkPath = "",
            registry = registry,
            notifier = notifier,
            dexQueries = object : ScriptDexQueries {
                override fun findClass(apkPath: String, classLoader: ClassLoader, pkg: String?, usingStrings: List<String>?, methodName: String?): Class<*>? = null
                override fun findMethod(apkPath: String, classLoader: ClassLoader, clazz: String?, methodName: String?, paramTypes: List<String>?, returnType: String?, usingStrings: List<String>?): Method? = null
            },
            limits = ScriptLimitsView.fromManifest(ScriptManifestParser.parse(manifestText)),
        )
        val bridge = ScriptBridgeImpl(host)

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

    // --- acceptance ----------------------------------------------------------

    @Test
    fun canonical_js_macro_gate_allows_only_the_target_game() {
        val fixture = Fixture(MiuiClassLoader(javaClass.classLoader, o3bClass), manifestText)
        val runtime = RhinoRuntime(fixture.bridge)

        assertTrue(runtime.compile(scriptSource("main.js"), "main.js").isEmpty())
        val error = runtime.run(scriptSource("main.js"), "main.js", System.currentTimeMillis() + 10_000)
        assertNull("entrypoint failed: $error", error)

        // The hook was installed on the fake O3$b.h.
        val method = o3bClass.declaredMethods.first { it.name == "h" }
        val demo = O3.B()

        // Target game: macro allowed (true) without invoking the original.
        assertEquals(
            true,
            fixture.invoke(method, demo, listOf(Any(), "com.ChillyRoom.DungeonShooter", false)),
        )
        // Any other game keeps original behavior (flag=false here).
        assertEquals(
            false,
            fixture.invoke(method, demo, listOf(Any(), "com.other.game", false)),
        )

        // Structured events: macro_allowed emitted exactly once, no hook_error.
        val events = fixture.notifier.events.map { it.first }
        assertEquals(1, events.count { it == "macro_allowed" })
        assertFalse("unexpected hook_error: ${fixture.notifier.events}", "hook_error" in events)
        assertFalse("unexpected script_error: ${fixture.notifier.events}", "script_error" in events)

        fixture.registry.unregisterScript("xiaomi/security-center/macro-gate")
        assertEquals(1, fixture.installer.uninstalled.size)
        runtime.dispose()
    }

    @Test
    fun lua_example_macro_gate_allows_only_the_target_game() {
        val fixture = Fixture(MiuiClassLoader(javaClass.classLoader, o3bClass), manifestText)
        val runtime = LuaRuntime(fixture.bridge)

        assertTrue(runtime.compile(scriptSource("main.lua"), "main.lua").isEmpty())
        val error = runtime.run(scriptSource("main.lua"), "main.lua", System.currentTimeMillis() + 10_000)
        assertNull("entrypoint failed: $error", error)

        val method = o3bClass.declaredMethods.first { it.name == "h" }
        val demo = O3.B()

        assertEquals(true, fixture.invoke(method, demo, listOf(Any(), "com.ChillyRoom.DungeonShooter", false)))
        assertEquals(false, fixture.invoke(method, demo, listOf(Any(), "com.other.game", false)))

        val events = fixture.notifier.events.map { it.first }
        assertEquals(1, events.count { it == "macro_allowed" })
        assertFalse("unexpected hook_error: ${fixture.notifier.events}", "hook_error" in events)
        runtime.dispose()
    }

    @Test
    fun missing_class_emits_deterministic_hook_error() {
        // Classloader WITHOUT the alias and DexKit queries that resolve nothing:
        // O3$b / MacroUtil never resolve; waitForClass is capped by the test
        // wait budget so the test stays fast.
        val fixture = Fixture(javaClass.classLoader, manifestText)
        val manifest = ScriptManifestParser.parse(manifestText)
        fixture.host.limitWaitClassForTest(300L)
        val runtime = RhinoRuntime(fixture.bridge)

        assertNull(runtime.run(scriptSource("main.js"), "main.js", System.currentTimeMillis() + 10_000))

        val events = fixture.notifier.events
        assertTrue("expected hook_error, got $events", events.any { it.first == "hook_error" })
        assertTrue(events.none { it.first == "macro_allowed" })
        assertTrue(fixture.installer.installed.isEmpty())
        runtime.dispose()
    }

    @Test
    fun macro_util_path_hooks_g_and_h_allowing_only_the_target_game() {
        // Security Center 12.7.x: O3$b is gone; DexKit resolves MacroUtil by the
        // MacroStatusProvider evidence string, and g() + h() are both hooked.
        val fixture = Fixture(javaClass.classLoader, manifestText)
        fixture.host.dexQueries = MacroUtilDexQueries()
        val runtime = RhinoRuntime(fixture.bridge)

        assertTrue(runtime.compile(scriptSource("main.js"), "main.js").isEmpty())
        val error = runtime.run(scriptSource("main.js"), "main.js", System.currentTimeMillis() + 10_000)
        assertNull("entrypoint failed: $error", error)

        // Both gate methods were installed (g and h), unrelated methods untouched.
        val hookNames = fixture.installer.installed.map { it.first.name }.sorted()
        assertEquals(listOf("g", "h"), hookNames)

        val macro = MacroUtil()
        for (method in fixture.installer.installed.map { it.first }) {
            // Target game: macro allowed (true) without invoking the original.
            assertEquals(
                "method ${method.name} must allow the target game",
                true,
                fixture.invoke(method, macro, listOf(Any(), "com.ChillyRoom.DungeonShooter", false)),
            )
            // Any other game keeps original behavior (flag=false here).
            assertEquals(
                "method ${method.name} must keep original behavior for others",
                false,
                fixture.invoke(method, macro, listOf(Any(), "com.other.game", false)),
            )
        }

        val events = fixture.notifier.events.map { it.first }
        assertEquals(1, events.count { it == "macro_allowed" })
        assertFalse("unexpected hook_error: ${fixture.notifier.events}", "hook_error" in events)
        assertFalse("unexpected script_error: ${fixture.notifier.events}", "script_error" in events)
        assertTrue(fixture.notifier.events.any { it.second.contains("macro_util") })

        fixture.registry.unregisterScript("xiaomi/security-center/macro-gate")
        assertEquals(2, fixture.installer.uninstalled.size)
        runtime.dispose()
    }

    @Test
    fun lua_macro_util_path_hooks_g_and_h() {
        val fixture = Fixture(javaClass.classLoader, manifestText)
        fixture.host.dexQueries = MacroUtilDexQueries()
        val runtime = LuaRuntime(fixture.bridge)

        assertTrue(runtime.compile(scriptSource("main.lua"), "main.lua").isEmpty())
        val error = runtime.run(scriptSource("main.lua"), "main.lua", System.currentTimeMillis() + 10_000)
        assertNull("entrypoint failed: $error", error)

        val hookNames = fixture.installer.installed.map { it.first.name }.sorted()
        assertEquals(listOf("g", "h"), hookNames)

        val macro = MacroUtil()
        for (method in fixture.installer.installed.map { it.first }) {
            assertEquals(
                true,
                fixture.invoke(method, macro, listOf(Any(), "com.ChillyRoom.DungeonShooter", false)),
            )
            assertEquals(
                false,
                fixture.invoke(method, macro, listOf(Any(), "com.other.game", false)),
            )
        }
        runtime.dispose()
    }

    @Test
    fun manifest_of_acceptance_package_validates() {
        val files = listOf(
            ScriptFile("src/main.js", scriptSource("main.js")),
            ScriptFile("src/main.lua", scriptSource("main.lua")),
        )
        val manifest = ScriptManifestParser.parse(manifestText)
        assertEquals("js", manifest.runtime)
        assertEquals(listOf("com.miui.securitycenter"), manifest.packages)
        assertEquals(listOf("com.ChillyRoom.DungeonShooter"), manifest.targetPackages)
        val errors = ScriptBundleValidator.validate(manifest, manifestText, files)
        assertTrue("bundle rejected: $errors", errors.isEmpty())
    }
}
