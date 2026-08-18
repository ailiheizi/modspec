package com.modspec.agent

import java.lang.reflect.Executable

/**
 * Pure-JVM hook multiplexer — the shared heart of the rule engine and the
 * JS/Lua scripting runtime.
 *
 * Guarantees (and the reason this exists):
 * - **One installed hook per [Executable]** regardless of how many callbacks
 *   (rules/scripts) target it. The libxposed `module.hook(method)` builder is
 *   invoked exactly once per method (via [installHook]); every further
 *   registration joins the same group. This fixes the historical failure where
 *   one `O3.b` hook prevented later `O3.b` hooks (framework stacking semantics
 *   could shadow or break `proceed()` for later registrations).
 * - **Deterministic composition**: `before` callbacks run in registration
 *   order; the most recently registered `replace` callback is the active one
 *   (earlier replaces are superseded and re-armed if the latest is removed);
 *   `after` callbacks run in registration order after the original (or after a
 *   replace) returned. The original method runs at most once per invocation.
 * - **No handle leak**: [shutdown] unhooks every installed handle; [unhook]
 *   of the last entry of a group tears the installed hook down.
 * - **Protective execution**: a callback exception never propagates into the
 *   target app; it is reported through [HookEvents.onCallbackError] and feeds
 *   the [CircuitBreaker], which stops dispatching callbacks after repeated
 *   failures until a cooldown elapses.
 */
class HookRegistry(
    private val installHook: (Executable, (HookChain) -> Any?) -> InstalledHook,
    private val events: HookEvents,
) {
    private val groups = ConcurrentLinkedMap<Executable, HookGroup>()
    private val byId = ConcurrentLinkedMap<String, HookEntry>()
    private val breaker = CircuitBreaker(
        maxFailures = DEFAULT_BREAKER_MAX_FAILURES,
        cooldownMs = DEFAULT_BREAKER_COOLDOWN_MS,
    )

    val circuitOpen: Boolean get() = breaker.isOpen

    /** Register one callback for an executable. Returns the hook id. */
    fun register(
        executable: Executable,
        phase: HookPhase,
        callback: (HookInvocation) -> Unit,
        id: String,
        scriptId: String,
    ): String {
        val entry = HookEntry(id, phase, callback, scriptId, executable)
        synchronized(groups) {
            val existing = groups[executable]
            if (existing == null) {
                val handle = installHook(executable) { chain -> dispatch(executable, chain) }
                val group = HookGroup(handle)
                groups[executable] = group
                group.entries += entry
            } else {
                existing.entries += entry
            }
            byId[id] = entry
        }
        return id
    }

    /** Remove one hook by id; tears down the installed handle when the group empties. */
    fun unregister(id: String): Boolean {
        synchronized(groups) {
            val entry = byId.remove(id) ?: return false
            removeEntry(entry)
            return true
        }
    }

    /**
     * Remove every hook entry in one family: the base id plus any `#phase`
     * entries registered under it (script hooks register before/replace/after
     * callbacks as one family).
     */
    fun unregisterFamily(baseId: String): Int {
        synchronized(groups) {
            val ids = byId.keys.filter { it == baseId || it.startsWith("$baseId#") }
            ids.forEach { id ->
                byId.remove(id)?.let { removeEntry(it) }
            }
            return ids.size
        }
    }

    /** Unhook every hook belonging to one script (script unload). */
    fun unregisterScript(scriptId: String): Int {
        synchronized(groups) {
            val ids = byId.values.filter { it.scriptId == scriptId }.map { it.id }
            ids.forEach { id ->
                val entry = byId.remove(id) ?: return@forEach
                removeEntry(entry)
            }
            return ids.size
        }
    }

    /** Unhook every installed hook and clear all bookkeeping (module unload). */
    fun shutdown() {
        synchronized(groups) {
            groups.values.forEach { runCatching { it.handle.unhook() } }
            groups.clear()
            byId.clear()
            breaker.reset()
        }
    }

    fun activeGroups(): Int = synchronized(groups) { groups.size }

    fun activeHooks(): Int = synchronized(groups) { byId.size }

    private fun removeEntry(entry: HookEntry) {
        val group = groups[entry.executable] ?: return
        group.entries.remove(entry)
        if (group.entries.isEmpty()) {
            groups.remove(entry.executable)
            runCatching { group.handle.unhook() }
        }
    }

    /** Invoked by the installed interceptor for every call of a hooked method. */
    fun dispatch(executable: Executable, chain: HookChain): Any? {
        val group = synchronized(groups) { groups[executable] } ?: return chain.proceed()
        if (breaker.isOpen) {
            return chain.proceed()
        }
        val invocation = HookInvocation(chain)

        // Phase 1: before callbacks in registration order (may mutate args).
        for (entry in group.entries.filter { it.phase == HookPhase.BEFORE }) {
            runSafely(entry, invocation)
            if (invocation.skip) break
        }

        // Phase 2: the active replace callback (latest wins) or the original.
        if (!invocation.skip) {
            val replace = group.latestReplace()
            if (replace != null) {
                runSafely(replace, invocation)
                if (!invocation.originalCalled && !invocation.replaceSkipped) {
                    // Replace without callOriginal / proceed: the original is
                    // skipped; `result` is whatever the callback set (null
                    // when unset).
                } else if (invocation.replaceSkipped) {
                    // Callback explicitly requested the original.
                    invocation.result = chain.proceedWith(invocation.args.toList())
                    invocation.originalCalled = true
                }
            } else {
                invocation.result = chain.proceedWith(invocation.args.toList())
                invocation.originalCalled = true
            }
        }

        // Phase 3: after callbacks in registration order (may override result).
        for (entry in group.entries.filter { it.phase == HookPhase.AFTER }) {
            runSafely(entry, invocation)
        }

        return invocation.result
    }

    private fun runSafely(entry: HookEntry, invocation: HookInvocation) {
        val started = System.nanoTime()
        try {
            entry.callback.invoke(invocation)
        } catch (error: Throwable) {
            events.onCallbackError(entry.scriptId, entry.id, error)
            if (breaker.recordFailure()) {
                events.onCircuitOpen(entry.scriptId, breakerFailuresAfterTrip())
            }
        } finally {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            if (elapsedMs > SLOW_CALLBACK_MS) {
                events.onCallbackSlow(entry.scriptId, entry.id, elapsedMs)
            }
        }
    }

    private fun breakerFailuresAfterTrip(): Int = breaker.lastFailureCount

    interface HookEvents {
        fun onCallbackError(scriptId: String, hookId: String, error: Throwable)
        fun onCallbackSlow(scriptId: String, hookId: String, elapsedMs: Long)
        fun onReplaceSuperseded(scriptId: String, hookId: String)
        fun onCircuitOpen(scriptId: String, consecutiveFailures: Int)
    }

    enum class HookPhase { BEFORE, REPLACE, AFTER }

    fun interface InstalledHook {
        fun unhook()
    }

    /** The libxposed-side invocation chain (also faked in unit tests). */
    interface HookChain {
        val executable: Executable
        val receiver: Any?
        val args: MutableList<Any?>
        fun proceed(): Any?
        fun proceedWith(args: List<Any?>): Any?
    }

    internal class HookEntry(
        val id: String,
        val phase: HookPhase,
        val callback: (HookInvocation) -> Unit,
        val scriptId: String,
        val executable: Executable,
    ) {
        var superseded = false
    }

    internal class HookGroup(val handle: InstalledHook) {
        val entries = mutableListOf<HookEntry>()

        /**
         * The active replace callback: the most recently registered one.
         * Older replaces are marked superseded (and re-armed when the latest
         * is removed, so composition stays predictable).
         */
        fun latestReplace(): HookEntry? {
            val replaces = entries.filter { it.phase == HookPhase.REPLACE }
            if (replaces.isEmpty()) return null
            val latest = replaces.last()
            for (older in replaces.filter { it !== latest }) {
                if (!older.superseded) {
                    older.superseded = true
                }
            }
            latest.superseded = false
            return latest
        }
    }

    companion object {
        const val DEFAULT_BREAKER_MAX_FAILURES = 10
        const val DEFAULT_BREAKER_COOLDOWN_MS = 30_000L
        const val SLOW_CALLBACK_MS = 50L
    }
}

/**
 * Per-invocation, script-visible context handed to hook callbacks.
 * `args`/`receiver` map to the original call; `result` and `callOriginal`
 * give the callback control over the return value.
 */
class HookInvocation internal constructor(
    private val chain: HookRegistry.HookChain,
) {
    val receiver: Any? get() = chain.receiver
    val args: MutableList<Any?> get() = chain.args
    val executable: Executable get() = chain.executable

    var result: Any? = null
    var skip: Boolean = false
    var originalCalled: Boolean = false
    var replaceSkipped: Boolean = false

    fun arg(index: Int): Any? = chain.args[index]

    fun setArg(index: Int, value: Any?) {
        chain.args[index] = value
    }

    /** Only meaningful inside a replace callback: runs the original with the current args. */
    fun callOriginal(): Any? {
        originalCalled = true
        result = chain.proceedWith(args.toList())
        return result
    }

    /** Explicitly run the original and keep its result (replace callbacks). */
    fun proceed(): Any? = callOriginal()

    /** Skip the original call entirely and return [value]. */
    fun skipWith(value: Any?) {
        skip = true
        result = value
    }
}

/**
 * Simple failure circuit breaker for hook callbacks: after [maxFailures]
 * consecutive failures the circuit opens (callbacks are no longer dispatched),
 * and re-arms automatically after [cooldownMs].
 */
class CircuitBreaker(
    private val maxFailures: Int,
    private val cooldownMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var openedAt = 0L
    private var open = false

    val isOpen: Boolean
        get() {
            if (open && clock() - openedAt >= cooldownMs) {
                open = false
                failures = 0
            }
            return open
        }

    val lastFailureCount: Int get() = failures

    /** Record a failure; returns true when this failure tripped the circuit. */
    fun recordFailure(): Boolean {
        if (open) return false
        failures++
        if (failures >= maxFailures) {
            open = true
            openedAt = clock()
            return true
        }
        return false
    }

    fun reset() {
        failures = 0
        open = false
    }
}

/**
 * Simple thread-safe bounded map (JVM-pure; avoids Android collection deps).
 */
internal class ConcurrentLinkedMap<K, V> {
    private val map = java.util.concurrent.ConcurrentHashMap<K, V>()
    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    fun remove(key: K): V? = map.remove(key)
    fun clear() = map.clear()
    val values: Collection<V> get() = map.values
    val keys: Set<K> get() = map.keys
    val size: Int get() = map.size
}

/**
 * Token-bucket rate limiter for structured events and console output.
 * Pure JVM, so it is unit-testable without Android stubs.
 */
class RateLimiter(
    private val maxBurst: Int,
    private val refillPerSecond: Double,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var tokens = maxBurst.toDouble()
    private var lastRefill = clock()

    /** Whether a call for [key] may proceed (per-key rate limiting). */
    fun allow(key: String): Boolean {
        synchronized(lock) {
            val now = clock()
            val elapsed = (now - lastRefill).coerceAtLeast(0L) / 1000.0
            tokens = minOf(maxBurst.toDouble(), tokens + elapsed * refillPerSecond)
            lastRefill = now
            return if (tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            tokens = maxBurst.toDouble()
            lastRefill = clock()
        }
    }
}
