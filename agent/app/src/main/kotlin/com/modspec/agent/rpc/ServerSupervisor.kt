package com.modspec.agent.rpc

import java.util.concurrent.ConcurrentHashMap

/**
 * Idempotent server lifecycle + watchdog for the Agent's local HTTP (8764) and
 * WebSocket (8765) RPC servers.
 *
 * `AgentService` is START_STICKY: the process and the foreground service can
 * stay alive while a server's accept loop has died (an unhandled handler
 * exception, a failed handler parse, a transient IO failure). Restarting the
 * service does NOT recreate the servers because `onCreate` is not re-run —
 * that is exactly the "forward still LISTENs but every request hangs" failure
 * observed on real devices. The supervisor fixes it in two complementary ways:
 *
 *  - [poke] is called on every `onStartCommand` (including the no-op restart
 *    `MainActivity.start()` triggers while the service is already alive), so a
 *    dead server is replaced immediately;
 *  - a low-frequency watchdog thread re-checks liveness and replaces dead
 *    servers on its own, so a server that dies while idle self-heals.
 *
 * Restarts never kill the app: only the failed server instance is stopped
 * (which also releases its stale socket) and re-created. Restart counts and
 * last errors are surfaced through [snapshot] for RPC diagnostics.
 */
class ServerSupervisor(
    private val serverFactory: (String) -> ManagedServer,
    private val log: (String) -> Unit = { message -> android.util.Log.w(TAG, message) },
    private val watchdogIntervalMs: Long = WATCHDOG_INTERVAL_MS,
    private val serverNames: List<String> = DEFAULT_SERVER_NAMES,
) {

    @Volatile
    private var running = false

    private val lock = Any()
    private var servers: Map<String, ManagedServer> = emptyMap()
    private var watchdog: Thread? = null
    private val restarts = ConcurrentHashMap<String, Int>()
    private val lastErrors = ConcurrentHashMap<String, String>()

    /** Start supervision and bring all servers up. Idempotent. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            ensureStartedLocked()
            watchdog = Thread({ loop() }, "modspec-server-watchdog").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Stop the watchdog and all servers, releasing sockets/threads. Idempotent. */
    fun stop() {
        synchronized(lock) {
            running = false
            val worker = watchdog
            watchdog = null
            if (worker != null && worker !== Thread.currentThread()) {
                runCatching { worker.interrupt() }
                runCatching { worker.join(1000L) }
            }
            for (server in servers.values) {
                runCatching { server.stop() }.onFailure { error ->
                    log("stop ${server.name} failed: ${error.message}")
                }
            }
            servers = emptyMap()
        }
    }

    /**
     * Immediate liveness check (every AgentService start command and RPC path).
     * Replaces any server whose accept loop is no longer running.
     */
    fun poke() {
        synchronized(lock) {
            if (!running) return
            ensureStartedLocked()
        }
    }

    fun isAlive(name: String): Boolean = synchronized(lock) {
        servers[name]?.isAlive() == true
    }

    fun restartCount(name: String): Int = restarts[name] ?: 0

    fun lastError(name: String): String? = lastErrors[name]

    fun snapshot(): List<ServerHealth> = synchronized(lock) {
        serverNames.map { name ->
            ServerHealth(
                name = name,
                alive = servers[name]?.isAlive() == true,
                restarts = restartCount(name),
                lastError = lastErrors[name],
            )
        }
    }

    private fun loop() {
        while (running) {
            try {
                Thread.sleep(watchdogIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
            poke()
        }
    }

    private fun ensureStartedLocked() {
        for (name in serverNames) {
            val server = servers[name]
            if (server != null && server.isAlive()) continue
            if (server != null) {
                lastErrors[name] = "accept loop stopped; supervisor restarting"
                log("$name server died; restarting")
            }
            startServerLocked(name)
        }
    }

    private fun startServerLocked(name: String) {
        val previous = servers[name]
        runCatching { previous?.stop() }.onFailure { error ->
            log("stop stale $name server failed: ${error.message}")
        }
        // Only re-creations count as restarts (the initial start is not one).
        if (previous != null) {
            restarts.merge(name, 1, Int::plus)
        }
        val fresh = serverFactory(name)
        try {
            fresh.start()
            servers = servers + (name to fresh)
            lastErrors.remove(name)
        } catch (error: Exception) {
            lastErrors[name] = error.message ?: "start failed"
            log("$name server failed to start: ${lastErrors[name]}")
            servers = servers - name
        }
    }

    companion object {
        private const val TAG = "ModspecServerSupervisor"
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private val DEFAULT_SERVER_NAMES = listOf("http", "ws")
    }
}
