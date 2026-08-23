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
        val body = readBodyUtf8(session)
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
        val body = readBodyUtf8(session)
        val response = if (body.isBlank()) {
            """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}"""
        } else {
            rpcHandler.handleRequest(body)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    /**
     * Read the raw request body and decode it as UTF-8.
     *
     * NanoHTTPD's `parseBody` decodes POST bodies with `ContentType.getEncoding()`,
     * which defaults to `US-ASCII` when the client omits `charset` from
     * `Content-Type` (our Rust client sends plain `application/json`). That mangles
     * any non-ASCII UTF-8 payload (e.g. `title = "5GHz 热点"` becomes `5GHz ?????`).
     * Bypass `parseBody` and read the body bytes directly from the session input
     * stream, then decode as UTF-8. The response path is unaffected: NanoHTTPD
     * falls back to UTF-8 automatically when ASCII cannot encode the payload.
     */
    private fun readBodyUtf8(session: IHTTPSession): String {
        val declared = session.headers["content-length"]?.toLongOrNull()
        val bytes = if (declared != null && declared in 0..MAX_BODY_BYTES) {
            val buffer = ByteArray(declared.toInt())
            var offset = 0
            while (offset < buffer.size) {
                val read = session.inputStream.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            buffer.copyOf(offset)
        } else {
            session.inputStream.readBytes()
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024L
    }
}
