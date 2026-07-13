package com.modspec.agent.rpc

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * WebSocket JSON-RPC on [port] — text frame in, text frame out.
 */
class LocalWsServer(
    private val rpcHandler: RpcHandler,
    private val port: Int,
) {
    private var server: InternalServer? = null

    fun start() {
        if (server != null) return
        server = InternalServer(InetSocketAddress(port)).also { it.start() }
    }

    fun stop() {
        server?.stop(500)
        server = null
    }

    private inner class InternalServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            // Accept all paths; dedicated port 8765
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val response = rpcHandler.handleRequest(message)
            conn.send(response)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

        override fun onError(conn: WebSocket?, ex: Exception) = Unit

        override fun onStart() = Unit
    }
}
