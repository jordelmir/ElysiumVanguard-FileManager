package com.elysium.vanguard.core.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 2.3 — Local HTTP server for cross-device file transfer.
 *
 * Pure-Kotlin HTTP/1.1 server bound to a localhost port. Handlers register routes via
 * [registerRoute]; incoming connections are dispatched on a [Dispatchers.IO] scope and
 * each connection is served with `Connection: close` (no keep-alive) so the parser
 * stays simple and predictable.
 *
 * Why hand-rolled instead of NanoHTTPD:
 *   1. The NanoHTTPD API is Java-6 era and doesn't compose cleanly with coroutines.
 *   2. Total surface area we need is small (5–8 routes); < 500 lines of Kotlin.
 *   3. Zero new dependency = smaller APK and one less thing to audit.
 *
 * Auth: every route (except the well-known info endpoint) requires a Bearer token that
 * the [authTokenSupplier] returns. This is regenerated when the server starts so the
 * URL the user scans is the only way in.
 *
 * Threading: the [ServerSocket.accept] loop runs on a single coroutine on
 * [Dispatchers.IO]. Each accepted connection is dispatched as a child coroutine, so
 * concurrent requests are handled in parallel up to the thread pool size.
 */
class LocalFileServer(
    private val port: Int = DEFAULT_PORT,
    private val bindAddress: String = DEFAULT_BIND_ADDRESS,
    private val authTokenSupplier: () -> String,
    private val rootDir: () -> String,
    private val maxConcurrentConnections: Int = 8,
    private val requestTimeoutMillis: Int = 30_000
) {

    companion object {
        const val DEFAULT_PORT = 8765
        /** Safe default. A caller must opt into [LAN_BIND_ADDRESS] explicitly. */
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val LAN_BIND_ADDRESS = "0.0.0.0"
        private const val TAG = "LocalFileServer"

        /** Generate a 24-byte URL-safe auth token (≈ 32 base64url chars). */
        fun generateAuthToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    private val routes = mutableListOf<Route>()
    private val activeConnections = AtomicLong(0)
    private val totalRequests = AtomicLong(0)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Snapshot for status reporting. Immutable. */
    data class Status(
        val running: Boolean,
        val port: Int,
        val bindAddress: String,
        val authToken: String?,
        val rootDir: String,
        val activeConnections: Long,
        val totalRequests: Long,
        val registeredRoutes: Int
    )

    /** Public so the UI can show the token + URL. Returns null when stopped. */
    fun currentStatus(): Status = Status(
        running = serverSocket != null,
        port = serverSocket?.localPort ?: port,
        bindAddress = bindAddress,
        authToken = if (serverSocket != null) authTokenSupplier() else null,
        rootDir = rootDir(),
        activeConnections = activeConnections.get(),
        totalRequests = totalRequests.get(),
        registeredRoutes = routes.size
    )

    // ---- Route registration ----

    /**
     * Add a route. Routes are matched in registration order; first match wins.
     * Use [path] with a literal path (no regex/templating yet — we don't need it).
     * Path matching is case-sensitive on purpose; RFC 7230 says it should be but most
     * clients normalize before sending.
     */
    fun registerRoute(method: String, path: String, handler: suspend (HttpRequest) -> HttpResponse) {
        routes.add(Route(method.uppercase(), path, handler))
    }

    data class Route(
        val method: String,
        val path: String,
        val handler: suspend (HttpRequest) -> HttpResponse
    )

    // ---- Lifecycle ----

    /** Open the listener socket and start accepting connections. Idempotent. */
    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = try {
            ServerSocket(port, 50, java.net.InetAddress.getByName(bindAddress))
        } catch (e: IOException) {
            Log.w(TAG, "Could not bind to $bindAddress:$port — ${e.message}")
            return
        }
        socket.soTimeout = 500  // so accept() wakes periodically to check cancellation
        serverSocket = socket

        acceptJob = serverScope.launch {
            try {
                Log.i(TAG, "Listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")
                while (isActive && serverSocket != null) {
                    val client = try {
                        socket.accept()
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: IOException) {
                        if (isActive) Log.w(TAG, "accept() failed: ${e.message}")
                        break
                    }
                    handleConnection(client)
                }
            } finally {
                Log.i(TAG, "Accept loop exited")
            }
        }
    }

    /** Stop accepting new connections and let the active ones drain. Idempotent. */
    @Synchronized
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        try { socket.close() } catch (_: IOException) {}
        // Wait briefly for acceptJob to die so we don't double-close.
        runBlocking { withContext(Dispatchers.IO) { acceptJob?.cancel() } }
        Log.i(TAG, "Stopped")
    }

    /** For tests: block until N requests have been served (or timeout). */
    suspend fun awaitRequests(count: Long, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (totalRequests.get() < count && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(50)
        }
    }

    // ---- Internal: per-connection handler ----

    private fun handleConnection(socket: Socket) {
        val incoming = activeConnections.incrementAndGet()
        if (incoming > maxConcurrentConnections) {
            activeConnections.decrementAndGet()
            try { socket.close() } catch (_: IOException) {}
            return
        }

        serverScope.launch {
            try {
                socket.soTimeout = requestTimeoutMillis
                socket.tcpNoDelay = true

                while (!socket.isClosed && serverSocket != null) {
                    val request = try {
                        HttpRequestParser.readRequest(socket.getInputStream())
                    } catch (e: HttpParseException) {
                        Log.w(TAG, "Bad request: ${e.message}")
                        writeResponse(socket, HttpResponse.badRequest(e.message ?: "Bad request"))
                        break
                    } catch (e: SocketTimeoutException) {
                        // idle close
                        break
                    } catch (e: IOException) {
                        break
                    }
                    if (request == null) break

                    totalRequests.incrementAndGet()
                    val response = try {
                        dispatch(request)
                    } catch (e: Exception) {
                        Log.e(TAG, "Handler crashed for ${request.method} ${request.path}", e)
                        HttpResponse.serverError(e.message ?: "Internal error")
                    }

                    try {
                        writeResponse(socket, response)
                    } catch (e: IOException) {
                        Log.d(TAG, "Client went away mid-response: ${e.message}")
                        break
                    }

                    // Single-shot per connection — keep-alive would require Content-Length
                    // accounting and we don't need it for the local-transfer use case.
                    if (request.header("Connection")?.lowercase() == "close") break
                    break  // always single-shot for now
                }
            } finally {
                try { socket.close() } catch (_: IOException) {}
                activeConnections.decrementAndGet()
            }
        }
    }

    private fun dispatch(request: HttpRequest): HttpResponse {
        // Auth gate: any path NOT in the public allow-list must carry a valid token.
        if (!isPublicRoute(request.path) && request.bearerToken != authTokenSupplier()) {
            return HttpResponse.unauthorized()
        }
        // Path-not-found gate: prevents /api/list leaking when the server is running.
        val match = routes.firstOrNull {
            it.method == request.method && it.path == request.path
        } ?: return HttpResponse.notFound()
        return runBlocking { match.handler(request) }
    }

    /**
     * Public routes don't need auth. Right now just the bare root and the info endpoint,
     * so a browser visiting the URL gets a friendly landing page even before auth.
     */
    private fun isPublicRoute(path: String): Boolean = when (path) {
        "/" -> true
        "/info" -> true
        else -> false
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val reason = statusReason(response.status)
        out.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(Charsets.US_ASCII))

        // Default headers; user-supplied headers win on conflict.
        val merged = LinkedHashMap<String, String>()
        merged["Server"] = "ElysiumVanguard/1.0"
        merged["Connection"] = "close"
        merged["Date"] = httpDate(System.currentTimeMillis())
        merged.putAll(response.headers)

        when (response) {
            is HttpResponse.InMemory -> {
                merged["Content-Length"] = response.body.size.toString()
                for ((k, v) in merged) {
                    out.write("$k: $v\r\n".toByteArray(Charsets.US_ASCII))
                }
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.write(response.body)
            }
            is HttpResponse.Stream -> {
                // We don't know the length up front for streaming. Use chunked transfer.
                merged.remove("Content-Length")
                merged["Transfer-Encoding"] = "chunked"
                for ((k, v) in merged) {
                    out.write("$k: $v\r\n".toByteArray(Charsets.US_ASCII))
                }
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
                val chunked = java.util.zip.CheckedOutputStream(out, java.util.zip.CRC32()) // any wrap is fine
                // Wrap so the inner write can be used as OutputStream.
                response.streamBody(object : java.io.OutputStream() {
                    private val chunkBuf = ByteArray(8 * 1024)
                    override fun write(b: Int) {
                        val one = byteArrayOf(b.toByte())
                        writeChunk(one, 0, 1)
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        var pos = off
                        var remaining = len
                        while (remaining > 0) {
                            val n = minOf(chunkBuf.size, remaining)
                            System.arraycopy(b, pos, chunkBuf, 0, n)
                            writeChunk(chunkBuf, 0, n)
                            pos += n
                            remaining -= n
                        }
                    }
                    private fun writeChunk(buf: ByteArray, off: Int, len: Int) {
                        chunked.write(Integer.toHexString(len).uppercase().toByteArray())
                        chunked.write("\r\n".toByteArray())
                        chunked.write(buf, off, len)
                        chunked.write("\r\n".toByteArray())
                        chunked.flush()
                    }
                })
                // Final chunk (zero-length).
                out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            }
        }
        out.flush()
    }

    private fun statusReason(code: Int): String = when (code) {
        200 -> "OK"
        302 -> "Found"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private fun httpDate(epochMillis: Long): String =
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date(epochMillis))
}
