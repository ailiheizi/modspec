package com.modspec.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * JNI bridge to the native PLT hook library (xhook). Hook events arrive on
 * arbitrary native threads; the [eventSink] registered by the script host
 * turns them into structured logcat events. Returns the (possibly overridden)
 * result to the trampoline — default observe mode keeps original behavior.
 */
object NativeHookBridge {

    @Volatile
    var eventSink: ((hookId: Long, args: LongArray, result: Long) -> Long)? = null

    val nextHookId: AtomicLong = AtomicLong(0)

    @JvmStatic
    external fun register(libRegex: String, symbol: String, hookId: Long): Boolean

    @JvmStatic
    external fun refresh(rebuild: Boolean)

    @JvmStatic
    external fun clear()

    /** Native → Java callback; must return the (possibly overridden) result. */
    @JvmStatic
    fun onHook(hookId: Long, args: LongArray, result: Long): Long =
        eventSink?.invoke(hookId, args, result) ?: result
}
