package com.modspec.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * JVM tests for the shared [HookRegistry] multiplexer: deterministic callback
 * order, replace semantics (latest wins, superseded re-arm), callOriginal,
 * argument/result mutation, same-method multi-hook composition (the O3.b
 * regression), unhook/teardown, circuit breaking and the no-handle-leak
 * guarantee.
 */
class HookRegistryTest {

    // --- fixtures ------------------------------------------------------------

    class Fixture {
        val events = mutableListOf<String>()
        var installedCount = 0
        var uninstalledCount = 0
        var installed: MutableList<Pair<Method, (HookRegistry.HookChain) -> Any?>> = mutableListOf()
        var dispatcherByMethod: MutableMap<Method, (HookRegistry.HookChain) -> Any?> = mutableMapOf()

        val registry = HookRegistry(
            installHook = { executable, dispatcher ->
                installedCount++
                val method = executable as Method
                installed += method to dispatcher
                dispatcherByMethod[method] = dispatcher
                HookRegistry.InstalledHook {
                    uninstalledCount++
                    dispatcherByMethod.remove(method)
                }
            },
            events = object : HookRegistry.HookEvents {
                override fun onCallbackError(scriptId: String, hookId: String, error: Throwable) {
                    events += "error:$scriptId:$hookId:${error.message}"
                }

                override fun onCallbackSlow(scriptId: String, hookId: String, elapsedMs: Long) {
                    events += "slow:$scriptId:$hookId:$elapsedMs"
                }

                override fun onReplaceSuperseded(scriptId: String, hookId: String) {
                    events += "superseded:$scriptId:$hookId"
                }

                override fun onCircuitOpen(scriptId: String, failures: Int) {
                    events += "circuit:$scriptId:$failures"
                }
            },
        )

        /** Invoke the registered dispatcher for a method, like the framework would. */
        fun invoke(method: Method, receiver: Any? = null, args: List<Any?> = emptyList()): Any? {
            val dispatcher = dispatcherByMethod[method]
                ?: error("method not hooked: ${method.name}")
            val chain = object : HookRegistry.HookChain {
                var originalCalls = 0
                override val executable: java.lang.reflect.Executable get() = method
                override val receiver: Any? get() = receiver
                override val args: MutableList<Any?> = args.toMutableList()
                override fun proceed(): Any? {
                    originalCalls++
                    return originalResult
                }

                override fun proceedWith(args: List<Any?>): Any? {
                    originalCalls++
                    this.args.clear()
                    this.args.addAll(args)
                    return originalResult
                }
            }
            return dispatcher(chain)
        }

        val originalResult: Any? = "original-value"
    }

    private fun methodOf(name: String = "replace", arity: Int = 2): Method {
        val clazz = java.lang.String::class.java
        return clazz.declaredMethods.first { it.name == name && it.parameterTypes.size == arity }
    }

    // --- composition semantics ----------------------------------------------

    @Test
    fun before_callbacks_run_in_registration_order() {
        val fixture = Fixture()
        val method = methodOf("concat", 1)
        val order = mutableListOf<String>()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, { order += "first" }, "h1", "s1")
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, { order += "second" }, "h2", "s2")

        fixture.invoke(method)

        assertEquals(listOf("first", "second"), order)
        assertEquals(1, fixture.installedCount)
    }

    @Test
    fun same_method_is_installed_once_even_with_many_hooks() {
        val fixture = Fixture()
        val method = methodOf()
        for (index in 0 until 5) {
            fixture.registry.register(
                method,
                HookRegistry.HookPhase.BEFORE,
                {},
                "h$index",
                "s1",
            )
        }
        assertEquals(1, fixture.installedCount)
    }

    @Test
    fun args_mutations_flow_to_original_and_other_hooks() {
        val fixture = Fixture()
        val method = methodOf()
        val seen = mutableListOf<String>()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, { inv ->
            seen += inv.arg(0) as String
            inv.setArg(0, "mutated")
        }, "h1", "s1")
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, { inv ->
            seen += inv.arg(0) as String
        }, "h2", "s1")

        val result = fixture.invoke(method, args = listOf("original", 42))

        assertEquals(listOf("original", "mutated"), seen)
        assertEquals("original-value", result)
    }

    @Test
    fun replace_without_callOriginal_skips_original_and_sets_result() {
        val fixture = Fixture()
        val method = methodOf()
        val chain = TrackedChain(method)
        fixture.registry.register(method, HookRegistry.HookPhase.REPLACE, { inv ->
            inv.result = "replaced"
        }, "r1", "s1")

        val result = fixture.registry.dispatch(method, chain)

        assertEquals(0, chain.originalCalls)
        assertEquals("replaced", result)
    }

    @Test
    fun replace_can_call_original_and_mutate_result() {
        val fixture = Fixture()
        val method = methodOf()
        fixture.registry.register(method, HookRegistry.HookPhase.REPLACE, { inv ->
            val original = inv.callOriginal()
            inv.result = "$original-modified"
        }, "r1", "s1")

        val result = fixture.invoke(method)

        assertEquals("original-value-modified", result)
    }

    @Test
    fun replace_latest_wins_and_earlier_is_superseded() {
        val fixture = Fixture()
        val method = methodOf()
        fixture.registry.register(method, HookRegistry.HookPhase.REPLACE, { inv ->
            inv.result = "first"
        }, "r1", "s1")
        fixture.registry.register(method, HookRegistry.HookPhase.REPLACE, { inv ->
            inv.result = "second"
        }, "r2", "s1")

        assertEquals("second", fixture.invoke(method))

        // Removing the latest re-arms the earlier replace.
        fixture.registry.unregister("r2")
        assertEquals("first", fixture.invoke(method))
    }

    @Test
    fun after_callbacks_run_after_original_in_order_and_can_override_result() {
        val fixture = Fixture()
        val method = methodOf()
        val order = mutableListOf<String>()
        fixture.registry.register(method, HookRegistry.HookPhase.AFTER, { inv ->
            order += "after1"
            assertEquals("original-value", inv.result)
            inv.result = "overridden"
        }, "a1", "s1")
        fixture.registry.register(method, HookRegistry.HookPhase.AFTER, { inv ->
            order += "after2"
            assertEquals("overridden", inv.result)
        }, "a2", "s1")

        val result = fixture.invoke(method)

        assertEquals(listOf("after1", "after2"), order)
        assertEquals("overridden", result)
    }

    @Test
    fun skip_in_before_stops_original_and_runs_after() {
        val fixture = Fixture()
        val method = methodOf()
        val afterRan = mutableListOf<String>()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, { inv ->
            inv.skipWith("skipped")
        }, "b1", "s1")
        fixture.registry.register(method, HookRegistry.HookPhase.AFTER, {
            afterRan += "after"
        }, "a1", "s1")

        val result = fixture.invoke(method)

        assertEquals("skipped", result)
        assertEquals(listOf("after"), afterRan)
    }

    @Test
    fun callback_exception_is_reported_and_does_not_crash_target() {
        val fixture = Fixture()
        val method = methodOf()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, { inv ->
            throw IllegalStateException("boom")
        }, "b1", "s1")

        val result = fixture.invoke(method)

        assertEquals("original-value", result)
        assertTrue(fixture.events.any { it.startsWith("error:s1:b1:boom") })
    }

    @Test
    fun circuit_breaker_opens_after_repeated_failures_and_pauses_callbacks() {
        val fixture = Fixture()
        val method = methodOf()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, {
            throw IllegalStateException("boom")
        }, "b1", "s1")

        // HookRegistry's internal breaker opens after 10 consecutive failures.
        repeat(10) { fixture.invoke(method) }
        assertTrue(fixture.events.any { it.startsWith("circuit:s1:10") })
        val eventsAfterTrip = fixture.events.size
        // Circuit open: callbacks are skipped, original still runs, no new events.
        val result = fixture.invoke(method)
        assertEquals("original-value", result)
        assertEquals(eventsAfterTrip, fixture.events.size)
    }

    // --- teardown / leak guarantees ------------------------------------------

    @Test
    fun unhook_last_entry_tears_down_the_installed_hook() {
        val fixture = Fixture()
        val method = methodOf()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, {}, "h1", "s1")
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, {}, "h2", "s1")

        fixture.registry.unregister("h1")
        assertEquals(1, fixture.installedCount)
        assertEquals(0, fixture.uninstalledCount)

        fixture.registry.unregister("h2")
        assertEquals(1, fixture.installedCount)
        assertEquals(1, fixture.uninstalledCount)
        assertFalse(fixture.dispatcherByMethod.containsKey(method))
    }

    @Test
    fun shutdown_unhooks_every_installed_handle_exactly_once() {
        val fixture = Fixture()
        val first = methodOf("concat", 1)
        val second = methodOf("length", 0)
        fixture.registry.register(first, HookRegistry.HookPhase.BEFORE, {}, "a", "s1")
        fixture.registry.register(first, HookRegistry.HookPhase.BEFORE, {}, "b", "s1")
        fixture.registry.register(second, HookRegistry.HookPhase.AFTER, {}, "c", "s1")

        fixture.registry.shutdown()

        assertEquals(2, fixture.installedCount)
        assertEquals(2, fixture.uninstalledCount)
        assertEquals(0, fixture.registry.activeGroups())
        assertEquals(0, fixture.registry.activeHooks())
    }

    @Test
    fun unregister_script_removes_only_that_scripts_hooks() {
        val fixture = Fixture()
        val method = methodOf()
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, {}, "s1h", "script-1")
        fixture.registry.register(method, HookRegistry.HookPhase.BEFORE, {}, "s2h", "script-2")

        val removed = fixture.registry.unregisterScript("script-1")

        assertEquals(1, removed)
        assertEquals(1, fixture.registry.activeHooks())
        // Script 2's hook still dispatches.
        assertEquals("original-value", fixture.invoke(method))
    }

    private class TrackedChain(
        override val executable: java.lang.reflect.Executable,
    ) : HookRegistry.HookChain {
        var originalCalls = 0
        var lastResult: Any? = null
        override val receiver: Any? = null
        override val args: MutableList<Any?> = mutableListOf("a", 1)

        override fun proceed(): Any? {
            originalCalls++
            lastResult = "original"
            return lastResult
        }

        override fun proceedWith(args: List<Any?>): Any? {
            originalCalls++
            lastResult = "original"
            return lastResult
        }
    }
}
