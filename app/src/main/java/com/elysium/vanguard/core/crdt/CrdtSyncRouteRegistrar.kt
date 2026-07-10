package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.server.HttpRequest
import com.elysium.vanguard.core.server.HttpResponse
import com.elysium.vanguard.core.server.Json
import com.elysium.vanguard.core.server.LocalFileServer
import java.io.File

/**
 * PHASE 9.17 — Server-side CRDT sync route registration.
 *
 * Wires `POST /api/crdt/sync` into a [LocalFileServer] so two
 * devices holding the same Elysium document can converge via the
 * existing transport. The route handler:
 *
 *  1. Reads the request body — the *peer* companion file in raw
 *     UTF-8 text form.
 *  2. Looks up the local companion file (server-side equivalent
 *     of `.elysium.sync`) for the same document path, opens it
 *     via [ElysiumSyncFile], and merges the peer's op log into
 *     it via [CrdtOpLog.merge].
 *  3. Persists the merged companion back to disk and returns
 *     the merged log (as a JSON envelope with `nodeId`,
 *     `lastSeen` and `log`) so the client can absorb the result.
 *
 * The companion file lives next to the document at
 * `<docPath>.server.elysium.sync`. Multiple devices can upload
 * their logs to the same file because merging is idempotent —
 * [CrdtOpLog.merge] deduplicates by `(hlc, kind)` and the merged
 * log always reflects every op the server has ever seen.
 *
 * Phase 9.17 — first build; intentionally minimal.
 */
object CrdtSyncRouteRegistrar {

    const val ROUTE_PATH = "/api/crdt/sync"

    private const val SERVER_NODE_ID = "server"
    private const val SERVER_COMPANION_SUFFIX = ".server.elysium.sync"

    /**
     * Register the CRDT sync endpoint on [srv]. [fsRoot] supplies
     * the directory the server treats as its sandbox; the
     * companion file lives next to the document identified by
     * the `path` query parameter inside that root.
     *
     * [authTokenSupplier] is required so the route enforces the
     * same Bearer-token auth as every other route on the
     * server.
     */
    fun register(
        srv: LocalFileServer,
        fsRoot: () -> File?,
        authTokenSupplier: () -> String
    ) {
        srv.registerRoute("POST", ROUTE_PATH) { req ->
            if (!isAuthorized(req, authTokenSupplier)) {
                HttpResponse.forbidden()
            } else {
                handle(req, fsRoot)
            }
        }
    }

    /**
     * The Bearer-token check shared with the rest of the server.
     * Public so tests can call it directly.
     */
    fun isAuthorized(req: HttpRequest, authTokenSupplier: () -> String): Boolean {
        val expected = authTokenSupplier()
        val auth = req.header("Authorization") ?: return false
        if (!auth.startsWith("Bearer ")) return false
        return auth.removePrefix("Bearer ") == expected
    }

    private fun handle(req: HttpRequest, fsRoot: () -> File?): HttpResponse {
        val path = req.query["path"]
            ?: return HttpResponse.badRequest("path required")
        val rootDir = fsRoot()
            ?: return HttpResponse.serverError("server fs root unavailable")
        val document = resolveDocument(rootDir, path)
            ?: return HttpResponse.badRequest("path escapes rootDir")
        val companion = File(document.parentFile, "${document.name}$SERVER_COMPANION_SUFFIX")

        // Parse the peer's log from the request body.
        val peerText = String(req.body, Charsets.UTF_8)
        val peerLog: CrdtOpLog = parseLog(peerText) ?: CrdtOpLog()
        // Read the server's existing companion (if any).
        val serverLog: CrdtOpLog =
            if (companion.isFile) parseLog(companion.readText()) ?: CrdtOpLog()
            else CrdtOpLog()
        // Merge the peer's ops into the server's log.
        for (entry in peerLog.rawEntries()) {
            if (!serverLog.rawEntries().any { sameKind(it, entry) }) {
                serverLog.recordSameKind(entry)
            }
        }
        // Persist merged companion.
        val highest = highestHlc(serverLog.rawEntries())
        val serialized = ElysiumSyncFile(
            documentFile = document,
            log = serverLog,
            lastSeen = highest,
            nodeId = SERVER_NODE_ID
        ).serialize()
        companion.parentFile?.mkdirs()
        companion.writeText(serialized)
        // Return merged log as JSON envelope.
        val envelope = Json.encode(
            mapOf(
                "nodeId" to SERVER_NODE_ID,
                "lastSeen" to (highest?.serialize() ?: "null"),
                "log" to serverLog.serialize()
            )
        )
        return HttpResponse.json(envelope)
    }

    /**
     * Resolve a `path` query parameter (relative or absolute)
     * against the server's [rootDir]. Returns `null` if the
     * resulting path escapes [rootDir] (basic traversal guard).
     * Public so tests can use it.
     */
    fun resolveDocument(rootDir: File, rawPath: String): File? {
        val candidate = if (rawPath.startsWith("/")) {
            File(rootDir, rawPath.trimStart('/'))
        } else {
            File(rootDir, rawPath)
        }
        val canonical = candidate.canonicalFile
        val rootCanonical = rootDir.canonicalFile
        // Traversal check: ensure canonical lives under rootCanonical.
        if (!canonical.absolutePath.startsWith(rootCanonical.absolutePath + File.separator) &&
            canonical.absolutePath != rootCanonical.absolutePath
        ) return null
        return canonical
    }

    /**
     * Reuse [ElysiumSyncFile.parse] but tolerate malformed input
     * by returning `null` (used to fall back to an empty log).
     * Public so tests can mock it.
     */
    fun parseLog(text: String): CrdtOpLog? {
        if (text.isBlank()) return CrdtOpLog()
        val companion = ElysiumSyncFile.parse(text, File("dummy")) ?: return CrdtOpLog()
        return companion.log
    }

    private fun sameKind(a: CrdtOpLog.Entry, b: CrdtOpLog.Entry): Boolean {
        if (a.hlc != b.hlc) return false
        return when {
            a is CrdtOpLog.DocSet && b is CrdtOpLog.DocSet ->
                a.key == b.key && a.value == b.value
            a is CrdtOpLog.DocDel && b is CrdtOpLog.DocDel -> a.key == b.key
            a is CrdtOpLog.SeqIns && b is CrdtOpLog.SeqIns -> a.value == b.value
            a is CrdtOpLog.SeqDel && b is CrdtOpLog.SeqDel -> a.targetHlc == b.targetHlc
            else -> false
        }
    }

    private fun highestHlc(entries: List<CrdtOpLog.Entry>): HybridLogicalClock? {
        var max: HybridLogicalClock? = null
        for (e in entries) {
            if (max == null || e.hlc > max) max = e.hlc
        }
        return max
    }
}

/**
 * Helper: re-record an entry by reflecting its concrete subtype.
 * Local-only extension to keep the registrar self-contained.
 */
private fun CrdtOpLog.recordSameKind(entry: CrdtOpLog.Entry) {
    when (entry) {
        is CrdtOpLog.DocSet -> record(CrdtOp.SetProperty(entry.hlc, entry.key, entry.value))
        is CrdtOpLog.DocDel -> record(CrdtOp.DeleteProperty(entry.hlc, entry.key))
        is CrdtOpLog.SeqIns -> record(CrdtSeqOp.Insert(entry.hlc, entry.value))
        is CrdtOpLog.SeqDel -> record(CrdtSeqOp.Delete(entry.hlc, entry.targetHlc))
    }
}
