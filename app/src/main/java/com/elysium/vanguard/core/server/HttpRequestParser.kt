package com.elysium.vanguard.core.server

/**
 * PHASE 2.3 — HTTP/1.1 request parser.
 *
 * Parses a single HTTP request from raw bytes. We support:
 *   - Method, path, query
 *   - Headers (case-insensitive lookup)
 *   - Body up to [MAX_BODY_BYTES]
 *
 * Out of scope (intentionally): chunked transfer encoding, HTTP/2, keep-alive pipelining.
 * Keep-alive is implemented in the server with explicit connection: close to keep the
 * parser deterministic.
 */
object HttpRequestParser {

    /** Hard cap on incoming body. The local server is for files, but uploads are POST'd
     *  as multipart; 100 MB is enough for reasonable single-file transfers. Larger files
     *  go through the streaming endpoint instead, which doesn't buffer the body. */
    const val MAX_BODY_BYTES = 100L * 1024 * 1024

    /** Reads exactly one HTTP request from the input stream. Returns null if the stream
     *  closes before any data is read (caller treats that as a graceful disconnect).
     *  Throws [HttpParseException] on malformed requests. */
    fun readRequest(input: java.io.InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 3) throw HttpParseException("Malformed request line: $requestLine")
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]

        if (!version.startsWith("HTTP/")) {
            throw HttpParseException("Unsupported protocol version: $version")
        }

        // Parse target into path + query.
        // Parse target into path + query.
        val (path, query) = parseTarget(target)

        // Parse target into path + query.

        // Read headers.
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw HttpParseException("Unexpected EOF in headers")
            if (line.isEmpty()) break  // end of headers
            val colon = line.indexOf(':')
            if (colon < 0) throw HttpParseException("Malformed header line: $line")
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }

        // Body (Content-Length aware; chunked not supported).
        val contentLength = headers["Content-Length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_BODY_BYTES) {
            throw HttpParseException("Body too large: $contentLength > $MAX_BODY_BYTES")
        }
        val body = if (contentLength > 0) {
            ByteArray(contentLength.toInt()).also { buf ->
                var offset = 0
                while (offset < buf.size) {
                    val read = input.read(buf, offset, buf.size - offset)
                    if (read < 0) throw HttpParseException("Unexpected EOF in body")
                    offset += read
                }
            }
        } else ByteArray(0)

        return HttpRequest(method, path, query, headers, body)
    }

    /**
     * Split a request target like `/api/list?path=%2Ffoo&recursive=true` into
     * `(path, queryMap)`. Empty input → `("/", {})`.
     */
    private fun parseTarget(target: String): Pair<String, Map<String, String>> {
        val qIdx = target.indexOf('?')
        if (qIdx < 0) return target to emptyMap()
        val rawPath = target.substring(0, qIdx)
        val rawQuery = target.substring(qIdx + 1)
        val query = rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair to ""
                else {
                    // URLDecoder.decode(String) is the legacy overload available
                    // since API 1. The (String, Charset) overload added in API 33
                    // would be cleaner but our minSdk is 26.
                    val k = java.net.URLDecoder.decode(
                        pair.substring(0, eq).replace("+", "%2B")
                    )
                    val v = java.net.URLDecoder.decode(
                        pair.substring(eq + 1).replace("+", "%2B")
                    )
                    k to v
                }
            }.toMap()
        return rawPath to query
    }

    /** Read up to (but not including) the next CRLF or LF. Returns null on EOF. */
    private fun readLine(input: java.io.InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            when (b.toChar()) {
                '\r' -> {
                    // Look ahead for LF.
                    val next = input.read()
                    if (next >= 0 && next.toChar() != '\n') {
                        // Standalone CR — uncommon but handle gracefully.
                        buf.append('\r')
                        if (next.toChar() != '\n') {
                            buf.append(next.toChar())
                        }
                    }
                    return buf.toString()
                }
                '\n' -> return buf.toString()
                else -> buf.append(b.toChar())
            }
        }
    }
}

class HttpParseException(message: String) : RuntimeException(message)