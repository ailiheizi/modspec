package com.modspec.agent.rpc

/**
 * Lifecycle contract implemented by the local HTTP/WS RPC servers so the
 * [ServerSupervisor] can supervise them without depending on their concrete
 * types (which keeps the supervisor pure JVM-testable with fakes).
 */
interface ManagedServer {
    /** Stable identifier used in diagnostics and restart accounting. */
    val name: String

    /** Idempotent start; must never bind twice. Throws on failure so the caller can record it. */
    fun start()

    /** Idempotent stop; releases sockets and joins worker threads. */
    fun stop()

    /** Whether the accept loop is currently running. */
    fun isAlive(): Boolean
}

/** One server's health snapshot for RPC diagnostics. */
data class ServerHealth(
    val name: String,
    val alive: Boolean,
    val restarts: Int,
    val lastError: String?,
)
