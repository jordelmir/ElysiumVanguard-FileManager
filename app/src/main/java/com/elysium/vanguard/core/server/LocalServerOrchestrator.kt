package com.elysium.vanguard.core.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * PHASE 2.3 — Orchestrates the [LocalFileServer] + [TransferService].
 *
 * Why a separate façade:
 *   - The server itself is stateless except for routes + token; binding it to a
 *     service gives us a place to own the auth token lifecycle and the working
 *     directory / SAF URI selection.
 *   - Tests can drive the routes without going through the network layer (use
 *     [handleRaw] from a unit test by pointing the dispatcher at a test socket).
 *
 * State machine:
 *   STOPPED → start() → STARTING → RUNNING → stop() → STOPPED
 *   STARTING can transition to RUNNING or back to STOPPED on bind failure.
 */
class LocalServerOrchestrator(
    private val context: Context,
    private val fsRootSupplier: () -> File?,
    private val port: Int = LocalFileServer.DEFAULT_PORT,
    private val onLanIpResolved: (String?) -> Unit = {}
) {

    private val authToken: String = LocalFileServer.generateAuthToken()
    private var safTreeUri: Uri? = null
    private val transferService = TransferService(
        context = context,
        safTreeUri = { safTreeUri },
        fsRoot = fsRootSupplier
    )

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val authTokenString: String get() = authToken
    val transfer: TransferService get() = transferService

    enum class State { STOPPED, STARTING, RUNNING, FAILED }

    data class Stats(
        val totalRequests: Long = 0,
        val activeConnections: Long = 0,
        val boundPort: Int = 0
    )

    private var server: LocalFileServer? = null

    init {
        onLanIpResolved.invoke(currentLanIp())
    }

    /** Start the server. Idempotent. Returns true if running (now or already). */
    fun start(): Boolean {
        if (_state.value == State.RUNNING || _state.value == State.STARTING) return true
        _state.value = State.STARTING
        _lastError.value = null

        val srv = LocalFileServer(
            port = port,
            // start() is only reached from the user's explicit Wi-Fi sharing flow.
            bindAddress = LocalFileServer.LAN_BIND_ADDRESS,
            authTokenSupplier = { authToken },
            rootDir = { fsRootSupplier()?.absolutePath ?: "" }
        )
        registerRoutes(srv)

        // Hook stat reporting via a tiny polling thread (cheap; one float read).
        val poller = Thread({
            try {
                while (!Thread.currentThread().isInterrupted && server != null) {
                    val s = server?.currentStatus() ?: break
                    _stats.value = Stats(
                        totalRequests = s.totalRequests,
                        activeConnections = s.activeConnections,
                        boundPort = s.port
                    )
                    Thread.sleep(500)
                }
            } catch (_: InterruptedException) { /* exit */ }
        }, "LocalServerStats")
        poller.isDaemon = true
        poller.start()

        srv.start()
        val running = srv.currentStatus().running
        if (!running) {
            _state.value = State.FAILED
            _lastError.value = "Could not bind port $port"
            server = null
            return false
        }
        server = srv
        _state.value = State.RUNNING
        _stats.value = Stats(boundPort = srv.currentStatus().port)
        return true
    }

    /** Stop the server. Idempotent. */
    fun stop() {
        server?.stop()
        server = null
        _state.value = State.STOPPED
        _stats.value = Stats()
    }

    /** Set the SAF tree URI (e.g. user picked a folder). Refreshes status. */
    fun setSafTreeUri(uri: Uri?) {
        safTreeUri = uri
        onLanIpResolved(currentLanIp())
    }

    /** Currently used SAF tree URI, or null if using filesystem mode. */
    fun safTreeUri(): Uri? = safTreeUri

    /** Inspect the granted SAF tree (best-effort). Returns null if no SAF URI is set. */
    fun describeSafTree(): String? {
        val uri = safTreeUri ?: return null
        return try {
            DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (_: Exception) { null }
    }

    /** Best-effort local LAN IP via network interface enumeration. Returns null when
     *  no non-loopback IPv4 is up (e.g. emulator without internet). */
    fun currentLanIp(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { nic ->
                nic.inetAddresses?.toList()?.filter {
                    !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress
                } ?: emptyList()
            }?.firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }

    /** The URL the user should scan / type. */
    fun landingUrl(): String? {
        if (_state.value != State.RUNNING) return null
        val ip = currentLanIp() ?: return null
        return "http://$ip:${_stats.value.boundPort}/?token=$authToken"
    }

    /**
     * The base URL for the running server (no path, no token).
     * `http://<lan-ip>:<bound-port>` — useful for in-process
     * clients like the CRDT editor's `EditorSyncHost` that want
     * to call the API without going through a human-typed URL.
     *
     * Returns null if the server is not running yet, or if no LAN
     * IPv4 is up (e.g. emulator without internet).
     */
    fun serviceBaseUrl(): String? {
        if (_state.value != State.RUNNING) return null
        val ip = currentLanIp() ?: return null
        val port = _stats.value.boundPort
        if (port <= 0) return null
        return "http://$ip:$port"
    }

    /**
     * The directory the server treats as its sandbox. The
     * `CrdtSyncRouteRegistrar` writes companion files inside
     * this root; clients that want to point the server at a
     * specific document need to express paths relative to it.
     *
     * Public so the editor can compute `relativePath` for the
     * `LocalServerSyncAdapter` without going through the
     * orchestrator's private state.
     */
    fun currentFsRoot(): File? = fsRootSupplier()

    // ---- Routes ----

    private fun registerRoutes(srv: LocalFileServer) {
        // Public landing page (token embedded in HTML for the JS to read).
        srv.registerRoute("GET", "/") { _ ->
            HttpResponse.html(WebUi.landingPageHtml(authToken))
        }
        srv.registerRoute("GET", "/info") {
            HttpResponse.json(Json.encode(mapOf(
                "name" to "Elysium Vanguard",
                "version" to "1.0.0-TITAN",
                "mode" to transferService.mode.name,
                "saf_tree" to (describeSafTree() ?: ""),
                "root" to (fsRootSupplier()?.absolutePath ?: ""),
                "auth_token_prefix" to authToken.take(6)
            )))
        }
        // JSON list endpoint.
        srv.registerRoute("GET", "/api/list") { req ->
            val path = req.query["path"]
            val entries = transferService.list(path)
            if (entries == null) {
                HttpResponse.forbidden()
            } else {
                HttpResponse.json(Json.encode(mapOf(
                    "path" to (path ?: "/"),
                    "entries" to entries.map { e ->
                        mapOf(
                            "name" to e.name,
                            "path" to e.path,
                            "size" to e.size,
                            "lastModified" to e.lastModified,
                            "isDirectory" to e.isDirectory,
                            "mimeType" to (e.mimeType ?: "")
                        )
                    }
                )))
            }
        }
        // CRDT sync endpoint (Phase 9.17) — used by
        // [com.elysium.vanguard.core.crdt.LocalServerSyncAdapter]
        // to ship companion files between devices.
        com.elysium.vanguard.core.crdt.CrdtSyncRouteRegistrar.register(
            srv = srv,
            fsRoot = { fsRootSupplier() },
            authTokenSupplier = { authToken }
        )
        // Download endpoint — streaming.
        srv.registerRoute("GET", "/api/file") { req ->
            val path = req.query["path"] ?: return@registerRoute HttpResponse.badRequest("path required")
            val entry = transferService.list(null)?.firstOrNull { it.path == path }
                ?: return@registerRoute HttpResponse.notFound()
            val mime = entry.mimeType ?: "application/octet-stream"
            HttpResponse.Stream(
                status = 200,
                headers = mapOf(
                    "Content-Type" to mime,
                    "Content-Disposition" to "attachment; filename=\"${entry.name.replace('"', '_')}\""
                ),
                streamBody = { out ->
                    transferService.streamDownload(path, out)
                }
            )
        }
        // Upload endpoint — multipart parsing is intentionally simple: we read the body
        // as raw bytes and rely on a Content-Disposition-style helper for the boundary.
        srv.registerRoute("POST", "/api/upload") { req ->
            val ct = req.header("Content-Type") ?: return@registerRoute HttpResponse.badRequest("missing Content-Type")
            if (!ct.lowercase().startsWith("multipart/form-data")) {
                return@registerRoute HttpResponse.badRequest("expected multipart/form-data")
            }
            val boundaryMarker = "boundary="
            val bIdx = ct.indexOf(boundaryMarker, ignoreCase = true)
            if (bIdx < 0) return@registerRoute HttpResponse.badRequest("missing boundary")
            val boundary = ct.substring(bIdx + boundaryMarker.length).trim().trim('"')
            val parts = parseMultipart(req.body, boundary)
            val parentPath = parts["path"] ?: return@registerRoute HttpResponse.badRequest("path missing")
            val fileName = parts["name"] ?: return@registerRoute HttpResponse.badRequest("name missing")
            val fileBytes = parts["fileBytes"]?.toByteArray(Charsets.ISO_8859_1)
                ?: return@registerRoute HttpResponse.badRequest("file missing")
            val written = transferService.writeBytes(parentPath, fileName, fileBytes)
            if (written == null) HttpResponse.serverError("write failed")
            else HttpResponse.json(Json.encode(mapOf("ok" to true, "path" to written)))
        }
    }

    /**
     * Minimal multipart/form-data parser. Returns a map of:
     *   - text fields ("path", "name") keyed by their `name` attribute
     *   - "fileBytes" → the raw bytes of the first uploaded file (we currently only
     *     support one per request; UI uploads one at a time anyway)
     *
     * Not a full RFC 7578 implementation — we don't handle nested parts, charset hints
     * on fields, or multi-file per request. If those come up, swap in Apache Commons
     * FileUpload. For our use case this is plenty.
     */
    private fun parseMultipart(body: ByteArray, boundary: String): Map<String, String> {
        val result = HashMap<String, String>()
        val delim = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val out = HashMap<String, ByteArray>()

        fun findDelim(start: Int): Int {
            var i = start
            while (i <= body.size - delim.size) {
                var j = 0
                while (j < delim.size && body[i + j] == delim[j]) j++
                if (j == delim.size) return i
                i++
            }
            return -1
        }

        var start = findDelim(0)
        while (start >= 0) {
            val next = findDelim(start + delim.size)
            if (next < 0) break
            // Slice between [start + delim.size + CRLF, next - CRLF - CRLF].
            val headerEnd = findCrlf(body, start + delim.size)
            if (headerEnd < 0) break
            // Skip the CRLF after the (potentially empty) part headers.
            val contentStart = headerEnd + 2
            val contentEnd = next - 2  // strip trailing CRLF before next boundary
            if (contentEnd < contentStart) { start = next; continue }
            // Parse headers (we already know we have at least the empty-line separator).
            val headerSection = body.copyOfRange(start + delim.size, headerEnd).toString(Charsets.ISO_8859_1)
            val nameMatch = NAME_REGEX.find(headerSection)
            val name = nameMatch?.groupValues?.get(1) ?: continue
            val data = body.copyOfRange(contentStart, contentEnd)
            out[name] = data
            start = next
        }

        for ((k, v) in out) {
            // Treat as text unless the part looks like a file (caller asks for "fileBytes").
            result[k] = v.toString(Charsets.UTF_8)
        }
        out["fileBytes"]?.let { result["fileBytes"] = it.toString(Charsets.ISO_8859_1) }
        return result
    }

    private fun findCrlf(buf: ByteArray, from: Int): Int {
        var i = from
        while (i < buf.size - 1) {
            if (buf[i] == '\r'.code.toByte() && buf[i + 1] == '\n'.code.toByte()) return i
            i++
        }
        return -1
    }

    companion object {
        private val NAME_REGEX = Regex("""name="([^"]+)"""", RegexOption.IGNORE_CASE)
    }
}
