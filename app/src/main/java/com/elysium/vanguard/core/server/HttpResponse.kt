package com.elysium.vanguard.core.server

/**
 * PHASE 2.3 — HTTP response builder.
 *
 * Keeps response construction dead simple: status, headers, body. Streaming responses
 * (e.g. file downloads) use [streamBody] which gives the writer a [java.io.OutputStream]
 * to write directly without buffering the whole thing in memory.
 *
 * Why a sealed type: the file-download path needed a streaming variant that doesn't fit
 * a plain `data class`. Sealing it makes both branches exhaustively checkable.
 */
sealed class HttpResponse {

    abstract val status: Int
    abstract val headers: Map<String, String>

    /** In-memory response (HTML pages, JSON, small payloads). */
    data class InMemory(
        override val status: Int,
        val body: ByteArray,
        override val headers: Map<String, String> = emptyMap()
    ) : HttpResponse()

    /** Streaming response (large file downloads). */
    class Stream(
        override val status: Int,
        val streamBody: suspend (java.io.OutputStream) -> Unit,
        override val headers: Map<String, String> = emptyMap()
    ) : HttpResponse()

    // ----- Convenience builders -----

    companion object {
        fun ok(body: String, contentType: String = "text/plain; charset=utf-8"): InMemory =
            InMemory(200, body.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to contentType))

        fun json(body: String): InMemory =
            ok(body, "application/json; charset=utf-8")

        fun html(body: String): InMemory =
            ok(body, "text/html; charset=utf-8")

        fun notFound(): InMemory = InMemory(
            404, "Not Found".toByteArray(),
            mapOf("Content-Type" to "text/plain; charset=utf-8")
        )

        fun unauthorized(): InMemory = InMemory(
            401, "Unauthorized".toByteArray(),
            mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "WWW-Authenticate" to "Bearer realm=\"Elysium Vanguard\""
            )
        )

        fun forbidden(): InMemory = InMemory(
            403, "Forbidden".toByteArray(),
            mapOf("Content-Type" to "text/plain; charset=utf-8")
        )

        fun badRequest(reason: String): InMemory = InMemory(
            400, reason.toByteArray(),
            mapOf("Content-Type" to "text/plain; charset=utf-8")
        )

        fun methodNotAllowed(): InMemory = InMemory(
            405, "Method Not Allowed".toByteArray(),
            mapOf("Content-Type" to "text/plain; charset=utf-8")
        )

        fun serverError(reason: String): InMemory = InMemory(
            500, reason.toByteArray(),
            mapOf("Content-Type" to "text/plain; charset=utf-8")
        )

        fun redirect(location: String): InMemory = InMemory(
            302, "Redirecting to $location".toByteArray(),
            mapOf(
                "Content-Type" to "text/plain; charset=utf-8",
                "Location" to location
            )
        )
    }
}