package com.modspec.agent.rpc

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Local HTTP server — health, pairing, JSON-RPC.
 */
class LocalHttpServer(
    private val rpcHandler: RpcHandler,
    port: Int,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/health" && session.method == Method.GET -> health()
            uri == "/pair" && session.method == Method.POST -> pair(session)
            uri == "/rpc" && session.method == Method.POST -> rpc(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
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
