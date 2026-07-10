package com.elysium.vanguard.core.server

/**
 * PHASE 2.3 — HTTP request model.
 *
 * Parsed from a raw HTTP/1.1 request line + headers + (optional) body. We don't try to be
 * a general-purpose server: this is purpose-built for the local transfer use case, so
 * things like chunked transfer encoding and HTTP/2 are out of scope. Chunked would be
 * the next thing to add if we ever stream gigabyte-sized files.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    /** Authorization header without the "Bearer " prefix, or null if absent. */
    val bearerToken: String?
        get() = headers["Authorization"]?.removePrefix("Bearer ")?.trim()

    /** Look up a header value, case-insensitive. */
    fun header(name: String): String? {
        val direct = headers[name]
        if (direct != null) return direct
        // Headers are case-insensitive per RFC 7230 §3.2.
        val needle = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == needle }?.value
    }

    /**
     * Decode an `application/x-www-form-urlencoded` body into key=value pairs. Returns
     * an empty map if the body is missing or the content-type doesn't match. The `+`
     * → space conversion happens here per the form-urlencoded spec (not in the URL
     * decoder itself, because plain `URLDecoder.decode` is used for query strings too,
     * where `+` is a literal plus).
     */
    val formFields: Map<String, String>
        get() {
            val ct = header("Content-Type")?.lowercase() ?: return emptyMap()
            if (!ct.startsWith("application/x-www-form-urlencoded")) return emptyMap()
            return body.toString(Charsets.UTF_8)
                .split('&')
                .filter { it.isNotEmpty() }
                .mapNotNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq < 0) null
                    else {
                        val k = urlDecode(pair.substring(0, eq).replace("+", " "))
                        val v = urlDecode(pair.substring(eq + 1).replace("+", " "))
                        k to v
                    }
                }.toMap()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method &&
            path == other.path &&
            query == other.query &&
            headers == other.headers &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + query.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

private fun urlDecode(raw: String): String = java.net.URLDecoder.decode(
    raw.replace("+", "%2B"),
    "UTF-8"
)