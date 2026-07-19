package com.elysium.vanguard.core.runtime.cloud

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 77 — the test seam for
 * [HttpRemoteBuildClientImpl].
 *
 * The client separates the
 * "build the URL / serialize the
 * request / parse the response"
 * logic from the "open a TCP
 * connection / send bytes / read
 * bytes" logic. The former lives
 * in [HttpRemoteBuildClientImpl];
 * the latter lives in this
 * [RemoteBuildTransport].
 *
 * The split exists so the JVM test
 * suite can drive every (URL,
 * headers, body) combination
 * without standing up a real HTTP
 * server. Tests inject a
 * [FakeRemoteBuildTransport] that
 * records the call + returns a
 * pre-configured response.
 *
 * **Production wiring**: the
 * `CloudModule` provides the
 * [HttpRemoteBuildTransport] (a
 * thin wrapper around
 * [HttpURLConnection]); tests
 * provide a fake.
 */

/**
 * A single HTTP request/response
 * pair. The transport's `execute`
 * returns this from a configured
 * endpoint; the client reads
 * [statusCode] to branch on success
 * vs. error and [body] to parse
 * the JSON.
 */
data class RemoteBuildResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * The transport contract. The
 * [execute] method is the only
 * function the client needs.
 * Implementations:
 * - [HttpRemoteBuildTransport] —
 *   the production impl (uses
 *   [HttpURLConnection]).
 * - `FakeRemoteBuildTransport` —
 *   test-only; records the call
 *   + returns a pre-configured
 *   response.
 */
interface RemoteBuildTransport {
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteBuildResponse
}

/**
 * Phase 77 — the production
 * [RemoteBuildTransport]. Uses
 * [HttpURLConnection] (JDK-native;
 * no third-party HTTP client).
 *
 * The transport:
 * 1. Opens the URL.
 * 2. Sets the request method, the
 *    headers, and the timeouts.
 * 3. Writes the body to the output
 *    stream (POST/PUT only).
 * 4. Reads the response status +
 *    body. The body is read from
 *    the error stream for non-2xx
 *    responses (so the caller can
 *    surface the server's error
 *    message); from the input
 *    stream for 2xx.
 * 5. Returns a [RemoteBuildResponse]
 *    with the status code + body.
 *
 * IOExceptions are wrapped in
 * [RemoteBuildError.TransportFailure]
 * by the [HttpRemoteBuildClientImpl]
 * (the transport does not translate
 * the error — it propagates the
 * `IOException` to the caller).
 */
class HttpRemoteBuildTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : RemoteBuildTransport {

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String,
    ): RemoteBuildResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body.isNotEmpty() && (method == "POST" || method == "PUT")) {
                doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        return try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            RemoteBuildResponse(statusCode = status, body = responseBody)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /**
         * Translate an [IOException] (or
         * any [Throwable] from the
         * transport) into the
         * [RemoteBuildError.TransportFailure]
         * message the client returns.
         * Kept as a companion so the
         * client can use the same
         * formatting without re-reading
         * the transport's source.
         */
        fun describeFailure(failure: Throwable): String =
            failure.message ?: failure.javaClass.simpleName
    }
}
