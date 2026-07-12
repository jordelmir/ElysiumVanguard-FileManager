package com.elysium.vanguard.core.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * End-to-end tests for [LocalFileServer] using raw sockets (not HttpURLConnection).
 *
 * Why raw sockets:
 *   - HttpURLConnection caches connections, retries on idempotent verbs, and pools
 *     keep-alive sockets — behavior that complicates assertions about single-shot
 *     behavior. Raw sockets give us byte-level control over what's sent and received.
 *   - HttpURLConnection also uses internal DNS resolvers and proxy detection that
 *     can mask real problems when the test machine is on a corporate network.
 *
 * We bind to an ephemeral port (0 → OS assigns), register handlers, and drive the
 * server via plain HTTP/1.1 over a Socket. The auth token is a fixed string so the
 * assertions are deterministic.
 */
class LocalFileServerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: LocalFileServer
    private val token = "test-token-1234567890"

    @Test fun `network binding is loopback unless LAN exposure is explicit`() {
        assertEquals("127.0.0.1", LocalFileServer.DEFAULT_BIND_ADDRESS)
        assertEquals("0.0.0.0", LocalFileServer.LAN_BIND_ADDRESS)
    }

    @Before fun setUp() {
        server = LocalFileServer(
            port = 0,
            authTokenSupplier = { token },
            rootDir = { tmp.root.absolutePath },
            requestTimeoutMillis = 2_000
        )
        server.start()
    }

    @After fun tearDown() {
        server.stop()
    }

    private fun port(): Int = server.currentStatus().port

    /** Minimal HTTP/1.1 client over raw socket. Closes connection after writing
     *  the request so we get a clean single-shot round trip. */
    private fun roundTrip(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0)
    ): Pair<Int, String> {
        val socket = Socket("127.0.0.1", port())
        socket.soTimeout = 5_000
        try {
            val out = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII)
            out.write("$method $path HTTP/1.1\r\n")
            out.write("Host: 127.0.0.1\r\n")
            for ((k, v) in headers) out.write("$k: $v\r\n")
            if (body.isNotEmpty()) out.write("Content-Length: ${body.size}\r\n")
            out.write("Connection: close\r\n")
            out.write("\r\n")
            out.flush()
            if (body.isNotEmpty()) socket.getOutputStream().write(body)

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            // Read status line.
            val statusLine = reader.readLine() ?: error("No status line")
            // Skip headers.
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            // Read body (best effort; non-text responses short-circuit).
            val bodyBuilder = StringBuilder()
            val toRead = contentLength.coerceAtMost(8192)
            if (toRead > 0) {
                val buf = CharArray(toRead)
                var total = 0
                while (total < toRead) {
                    val n = reader.read(buf, total, toRead - total)
                    if (n < 0) break
                    total += n
                }
                bodyBuilder.append(buf, 0, total)
            }
            // Parse status code.
            val parts = statusLine.split(' ', limit = 3)
            return parts[1].toInt() to bodyBuilder.toString()
        } finally {
            socket.close()
        }
    }

    @Test fun `responds 401 without bearer token on protected routes`() {
        server.registerRoute("GET", "/api/protected") {
            HttpResponse.ok("secret")
        }
        val (code, _) = roundTrip("GET", "/api/protected")
        assertEquals(401, code)
    }

    @Test fun `responds 200 with bearer token on protected routes`() {
        server.registerRoute("GET", "/api/protected") {
            HttpResponse.ok("secret")
        }
        val (code, body) = roundTrip("GET", "/api/protected",
            headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals(200, code)
        assertEquals("secret", body)
    }

    @Test fun `responds 404 on unknown route`() {
        val (code, _) = roundTrip("GET", "/api/missing",
            headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals(404, code)
    }

    @Test fun `handles multiple sequential connections`() {
        server.registerRoute("GET", "/api/ping") {
            HttpResponse.json(Json.encode(mapOf("pong" to true)))
        }
        for (i in 1..5) {
            val (code, body) = roundTrip("GET", "/api/ping",
                headers = mapOf("Authorization" to "Bearer $token"))
            assertEquals("iteration $i", 200, code)
            assertTrue("iteration $i body=$body", body.contains("\"pong\":true"))
        }
        assertTrue(server.currentStatus().totalRequests >= 5)
    }

    @Test fun `parses query string from incoming request`() {
        var captured: String? = null
        server.registerRoute("GET", "/api/echo") { req ->
            captured = req.query["name"]
            HttpResponse.ok("ok")
        }
        roundTrip("GET", "/api/echo?name=hello%20world",
            headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals("hello world", captured)
    }

    @Test fun `route registration order matters — first match wins`() {
        server.registerRoute("GET", "/api/multi") { HttpResponse.ok("first") }
        server.registerRoute("GET", "/api/multi") { HttpResponse.ok("second") }
        val (_, body) = roundTrip("GET", "/api/multi",
            headers = mapOf("Authorization" to "Bearer $token"))
        assertEquals("first", body)
    }

    @Test fun `stop then start on same orchestrator works`() {
        val originalPort = port()
        assertTrue(originalPort > 0)
        server.stop()

        val restarted = LocalFileServer(
            port = 0,
            authTokenSupplier = { token },
            rootDir = { tmp.root.absolutePath },
            requestTimeoutMillis = 2_000
        )
        restarted.start()
        try {
            assertNotNull(restarted.currentStatus().port)
            // Register a route on the new instance to confirm it actually serves.
            restarted.registerRoute("GET", "/api/restarted") {
                HttpResponse.ok("ok")
            }
            val (code, _) = roundTripOn(restarted.currentStatus().port, "GET", "/api/restarted",
                headers = mapOf("Authorization" to "Bearer $token"))
            assertEquals(200, code)
        } finally {
            restarted.stop()
        }
    }

    private fun roundTripOn(
        port: Int, method: String, path: String,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5_000
        try {
            val out = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII)
            out.write("$method $path HTTP/1.1\r\n")
            out.write("Host: 127.0.0.1\r\n")
            for ((k, v) in headers) out.write("$k: $v\r\n")
            out.write("Connection: close\r\n\r\n")
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
            val statusLine = reader.readLine() ?: error("no status")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            return statusLine.split(' ')[1].toInt() to ""
        } finally {
            socket.close()
        }
    }
}
