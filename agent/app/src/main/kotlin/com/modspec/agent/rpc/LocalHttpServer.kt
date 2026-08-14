package com.modspec.agent.rpc

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Local HTTP server — health, pairing, JSON-RPC.
 *
 * Implements [ManagedServer] so the [ServerSupervisor] can detect a dead
 * accept loop and re-create the server without killing the app. [serve] never
 * lets a handler exception escape: an uncaught exception would kill the
 * NanoHTTPD accept thread, which on a real device looks like a port that still
 * LISTENs but hangs every request.
 */
class LocalHttpServer(
    private val rpcHandler: RpcHandler,
    port: Int,
    private val log: (String) -> Unit = { message -> android.util.Log.e("LocalHttpServer", message) },
) : NanoHTTPD("127.0.0.1", port), ManagedServer {

    override val name: String get() = "http"

    override fun start() {
        super.start()
    }

    override fun stop() {
        super.stop()
    }

    // `isAlive()` is final on NanoHTTPD and satisfies the ManagedServer contract.
    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/health" && session.method == Method.GET -> health()
                session.uri == "/pair" && session.method == Method.POST -> pair(session)
                session.uri == "/rpc" && session.method == Method.POST -> rpc(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "not found",
                )
            }
        } catch (error: Exception) {
            log("serve(${session.method} ${session.uri}) failed: ${error.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"jsonrpc":"2.0","error":{"code":-32603,"message":"internal error"}}""",
            )
        }
    }

    private fun health(): Response {
        val body = JSONObject()
            .put("ok", true)
            .put("agent", "modspec-agent")
            .put("http_port", RpcHandler.HTTP_PORT)
            .put("ws_port", RpcHandler.WS_PORT)
            .toString()
        return newFixedLengthResponse(Response.Status.OK, "application/json", body)
    }

    private fun pair(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"].orEmpty()
        if (body.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"empty body"}""",
            )
        }
        val response = rpcHandler.handlePair(body)
        val status = if (response.contains("\"error\"")) {
            Response.Status.UNAUTHORIZED
        } else {
            Response.Status.OK
        }
        return newFixedLengthResponse(status, "application/json", response)
    }

    private fun rpc(session: IHTTPSession): Response {
        val token = session.headers["authorization"]
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }
        if (!rpcHandler.isAuthorized(token)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                """{"error":"pairing token required"}""",
            )
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"].orEmpty()
        val response = if (body.isBlank()) {
            """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}"""
        } else {
            rpcHandler.handleRequest(body)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }
}
