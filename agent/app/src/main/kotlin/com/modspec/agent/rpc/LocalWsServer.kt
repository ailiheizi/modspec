package com.modspec.agent.rpc

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * WebSocket JSON-RPC on [port] — text frame in, text frame out.
 *
 * Implements [ManagedServer] so the [ServerSupervisor] can supervise it. The
 * server socket is bound in [start] (not the constructor), so a fresh instance
 * can be created for a restart without leaking a bound socket. Errors are
 * surfaced through [lastError] and the logger instead of being swallowed, and
 * message handling is wrapped so one bad frame cannot kill the accept loop.
 */
class LocalWsServer(
    private val rpcHandler: RpcHandler,
    private val port: Int,
    private val log: (String) -> Unit = { message -> android.util.Log.e("LocalWsServer", message) },
) : ManagedServer {

    override val name: String get() = "ws"

    private var server: InternalServer? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var lastError: String? = null

    override fun start() {
        if (isAlive()) return
        stop()
        val fresh = InternalServer(InetSocketAddress("127.0.0.1", port))
        server = fresh
        val thread = Thread(fresh, "modspec-ws-server")
        worker = thread
        thread.start()
        lastError = null
    }

    override fun stop() {
        val current = server
        server = null
        worker = null
        if (current != null) {
            runCatching { current.stop(500) }.onFailure { error ->
                log("ws stop failed: ${error.message}")
            }
        }
    }

    override fun isAlive(): Boolean {
        val current = worker
        return current != null && current.isAlive
    }

    fun lastError(): String? = lastError

    private inner class InternalServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val token = handshake.getFieldValue("Authorization")
                .removePrefix("Bearer ")
                .takeIf { it.isNotBlank() }
            if (!rpcHandler.isAuthorized(token)) {
                conn.close(1008, "pairing token required")
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val response = runCatching { rpcHandler.handleRequest(message) }
                .getOrElse { error ->
                    lastError = error.message ?: error.javaClass.simpleName
                    log("ws rpc failed: $lastError")
                    """{"jsonrpc":"2.0","error":{"code":-32603,"message":"internal error"}}"""
                }
            runCatching { conn.send(response) }.onFailure { error ->
                lastError = error.message ?: error.javaClass.simpleName
                log("ws send failed: $lastError")
            }
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

        override fun onError(conn: WebSocket?, ex: Exception) {
            lastError = ex.message ?: ex.javaClass.simpleName
            log("ws error: $lastError")
        }

        override fun onStart() = Unit
    }
}
