package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.server.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PHASE 9.17 — HTTP client transport for CRDT sync.
 *
 * Wraps the network call so [LocalServerSyncAdapter] stays
 * transport-agnostic. We default to [JdkHttpSyncTransport]
 * (HttpURLConnection-based, no extra dependency) but the
 * interface allows tests to plug in a fake.
 *
 * Phase 9.17 — first build; intentionally minimal.
 */
interface HttpSyncTransport {
    /**
     * Perform a POST to [url] with [body] and the given [headers].
     * Returns the HTTP status + body. Throws on network error so
     * the adapter can convert to "0 ops absorbed".
     */
    fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpSyncTransport.Response

    data class Response(
        val status: Int,
        val body: ByteArray
    ) {
        val ok: Boolean get() = status in 200..299
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Response) return false
            return status == other.status && body.contentEquals(other.body)
        }
        override fun hashCode(): Int {
            var result = status
            result = 31 * result + body.contentHashCode()
            return result
        }
    }
}

/**
 * JDK HttpURLConnection backed transport. Adds the
 * `Authorization: Bearer …` header from the [headers] map.
 *
 * Pure JVM; no Android dependencies. OkHttp would be a fine
 * swap-in if we want timeouts, retries, or HTTP/2 later.
 */
class JdkHttpSyncTransport : HttpSyncTransport {
    override fun post(
        url: String,
        body: ByteArray,
        headers: Map<String, String>
    ): HttpSyncTransport.Response {
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.doInput = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.readBytes() ?: ByteArray(0)
        conn.disconnect()
        return HttpSyncTransport.Response(status, bytes)
    }
}

/**
 * PHASE 9.17 — HTTP-backed adapter that talks to a
 * [CrdtSyncRouteRegistrar] exposed by another device's
 * [com.elysium.vanguard.core.server.LocalServer].
 *
 * Contract:
 *   - `syncWith(session)`: ships the session's companion file to
 *     the server, parses the merged companion back, and applies
 *     it via [CrdtDocumentSession.absorbRemote]. Returns the
 *     number of remote ops absorbed.
 *   - Failures (network error, non-2xx, malformed envelope)
 *     surface as `0` and set [lastError] for the screen to
 *     render.
 *
 * Phase 9.17 — first build; intentionally minimal.
 */
class LocalServerSyncAdapter(
    private val baseUrl: String,
    private val authToken: String,
    private val relativePath: String,
    private val transport: HttpSyncTransport = JdkHttpSyncTransport()
) : CrdtSyncAdapter {

    @Volatile
    var lastError: String? = null
        private set

    override fun syncWith(session: CrdtDocumentSession): Int {
        lastError = null
        val sentText = session.syncFile.serialize()
        val url = buildUrl(baseUrl, relativePath)
        val response = try {
            transport.post(
                url = url,
                body = sentText.toByteArray(Charsets.UTF_8),
                headers = mapOf(
                    "Authorization" to "Bearer $authToken",
                    "Content-Type" to "text/plain; charset=utf-8"
                )
            )
        } catch (t: Throwable) {
            lastError = "transport error: ${t.message}"
            return 0
        }
        if (!response.ok) {
            lastError = "server returned ${response.status}"
            return 0
        }
        val envelopeBody = String(response.body, Charsets.UTF_8)
        return parseAndAbsorb(envelopeBody, session)
    }

    /**
     * Parse the JSON envelope returned by the server, build an
     * [ElysiumSyncFile] bound to the local document file, and
     * apply it. Returns the count of newly-absorbed ops.
     *
     * Public for tests so they can drive the parsing pipeline
     * without spinning up an HTTP server.
     */
    fun parseAndAbsorb(envelopeJson: String, session: CrdtDocumentSession): Int {
        val parsed = try {
            Json.decode(envelopeJson)
        } catch (t: Throwable) {
            lastError = "malformed envelope: ${t.message}"
            return 0
        }
        if (parsed !is Map<*, *>) {
            lastError = "envelope is not an object"
            return 0
        }
        val nodeId = (parsed["nodeId"] as? String) ?: "server"
        val lastSeen = (parsed["lastSeen"] as? String)?.let {
            if (it == "null") null else HybridLogicalClock.parse(it)
        }
        val logText = (parsed["log"] as? String) ?: ""
        // Reconstruct the in-memory companion.
        val parsedLog = CrdtOpLog().parse(logText) ?: CrdtOpLog()
        val companion = ElysiumSyncFile(
            documentFile = session.file,
            log = parsedLog,
            lastSeen = lastSeen,
            nodeId = nodeId
        )
        return session.absorbRemote(companion)
    }

    /**
     * Build the full URL the transport will POST to. Public so
     * tests can verify URL encoding.
     */
    fun buildUrl(baseUrl: String, path: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(path, "UTF-8")
        return "$cleanBase${CrdtSyncRouteRegistrar.ROUTE_PATH}?path=$encoded"
    }
}
