package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.server.Json
import java.io.File

/**
 * PHASE 9.18 — Folder-level CRDT sync manifest.
 *
 * Drop an `.elysium-sync-folder.json` file into a directory to
 * turn it into a "sync folder": every `.elysium.*` file in that
 * folder (matching the include patterns) syncs to each peer
 * listed in the manifest. The manifest is hand-written-friendly
 * JSON so the user can edit it in any editor.
 *
 * Manifest format:
 *
 *     {
 *       "patterns": ["*.elysium.word", "*.elysium.sheet"],
 *       "peers": [
 *         { "name": "Laptop", "baseUrl": "http://192.168.1.20:8765",
 *           "authToken": "abc123" }
 *       ],
 *       "lastUpdated": "2026-07-09T23:34:56Z"
 *     }
 *
 * Sync is peer-driven: `ElysiumSyncFolder.syncAll(...)` walks
 * the directory, opens a [CrdtDocumentSession] for each match,
 * and runs the configured [CrdtSyncAdapter] against each peer.
 * Idempotent — safe to invoke repeatedly.
 *
 * Phase 9.18 — first build; intentionally minimal.
 */
data class ElysiumSyncFolder(
    val directory: File,
    val manifestFile: File,
    val patterns: List<String>,
    val peers: List<PeerSpec>,
    val lastUpdated: String? = null
) {
    /** A peer this folder syncs against. */
    data class PeerSpec(
        val name: String,
        val baseUrl: String,
        val authToken: String
    )

    /**
     * List every `.elysium.*` file under [directory] that
     * matches any of the include [patterns]. We deliberately use
     * pure JVM globs (`*`, `?`) so the manifest stays portable;
     * a full regex matcher can come later if users ask.
     */
    fun listDocuments(): List<File> {
        val files = directory.listFiles()?.filter { it.isFile } ?: return emptyList()
        return files.filter { f -> patterns.any { p -> matchGlob(f.name, p) } }
    }

    /**
     * Open each document listed by [listDocuments] via the
     * supplied [sessionFactory], run a sync round against every
     * peer in [peers], and save the doc back. Total ops
     * absorbed across all docs is returned.
     *
     * [transportBuilder] lets callers customize the
     * [HttpSyncTransport] (defaults to [JdkHttpSyncTransport])
     * and [documentPathFor] lets them tell the peer which
     * relative path the doc lives at server-side.
     */
    fun syncAll(
        sessionFactory: (File) -> CrdtDocumentSession,
        transportBuilder: () -> HttpSyncTransport = { JdkHttpSyncTransport() },
        documentPathFor: (File) -> String = { f -> f.name }
    ): Int {
        var totalAbsorbed = 0
        for (doc in listDocuments()) {
            val session = runCatching { sessionFactory(doc) }.getOrNull() ?: continue
            for (peer in peers) {
                val adapter = LocalServerSyncAdapter(
                    baseUrl = peer.baseUrl,
                    authToken = peer.authToken,
                    relativePath = documentPathFor(doc),
                    transport = transportBuilder()
                )
                runCatching { adapter.syncWith(session) }
                    .onSuccess { totalAbsorbed += it }
            }
            runCatching { session.save() }
        }
        return totalAbsorbed
    }

    /**
     * Serialize the manifest (without [directory] / [manifestFile])
     * to the JSON format documented in the class header.
     */
    fun toJson(): String = Json.encode(
        mapOf(
            "patterns" to patterns,
            "peers" to peers.map {
                mapOf(
                    "name" to it.name,
                    "baseUrl" to it.baseUrl,
                    "authToken" to it.authToken
                )
            },
            "lastUpdated" to (lastUpdated ?: "")
        )
    )

    companion object {
        const val MANIFEST_FILENAME = ".elysium-sync-folder.json"

        /**
         * Look up the manifest inside [directory]. Returns null
         * if no manifest exists.
         */
        fun lookup(directory: File): ElysiumSyncFolder? {
            val manifestFile = File(directory, MANIFEST_FILENAME)
            if (!manifestFile.isFile) return null
            return fromManifestFile(directory, manifestFile)
        }

        /**
         * Build a fresh manifest for [directory] and write it to
         * disk. Returns the parsed result so callers don't need
         * a second roundtrip.
         */
        fun create(
            directory: File,
            patterns: List<String>,
            peers: List<PeerSpec>,
            lastUpdated: String? = null
        ): ElysiumSyncFolder {
            require(directory.isDirectory) { "not a directory: $directory" }
            val folder = ElysiumSyncFolder(
                directory = directory,
                manifestFile = File(directory, MANIFEST_FILENAME),
                patterns = patterns.ifEmpty { listOf("*.elysium.word") },
                peers = peers,
                lastUpdated = lastUpdated
            )
            folder.manifestFile.writeText(folder.toJson())
            return folder
        }

        /**
         * Read a manifest file and parse it. Public so tests can
         * construct fixtures directly.
         */
        fun fromManifestFile(directory: File, manifestFile: File): ElysiumSyncFolder? {
            val text = runCatching { manifestFile.readText() }.getOrNull() ?: return null
            return fromJsonText(directory, manifestFile, text)
        }

        fun fromJsonText(
            directory: File,
            manifestFile: File,
            text: String
        ): ElysiumSyncFolder? {
            val parsed = runCatching { Json.decode(text) }.getOrNull() as? Map<*, *> ?: return null
            val patterns = (parsed["patterns"] as? Iterable<*>)
                ?.mapNotNull { it?.toString() }
                ?: return null
            @Suppress("UNCHECKED_CAST")
            val peers = (parsed["peers"] as? Iterable<*>)
                ?.mapNotNull { entry ->
                    val m = entry as? Map<*, *> ?: return@mapNotNull null
                    val name = m["name"]?.toString() ?: return@mapNotNull null
                    val baseUrl = m["baseUrl"]?.toString() ?: return@mapNotNull null
                    val authToken = m["authToken"]?.toString() ?: return@mapNotNull null
                    PeerSpec(name, baseUrl, authToken)
                }
                ?: return null
            val lastUpdated = parsed["lastUpdated"]?.toString()
            return ElysiumSyncFolder(
                directory = directory,
                manifestFile = manifestFile,
                patterns = patterns,
                peers = peers,
                lastUpdated = if (lastUpdated.isNullOrEmpty()) null else lastUpdated
            )
        }

        /**
         * Glob matcher that supports `*` (any chars in a segment)
         * and `?` (single char). Case-sensitive by default.
         */
        internal fun matchGlob(name: String, pattern: String): Boolean {
            val regex = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> regex.append(".*")
                    '?' -> regex.append(".")
                    '.', '(', ')', '+', '|', '^', '$', '\\', '[', ']', '{', '}' ->
                        regex.append('\\').append(c)
                    else -> regex.append(c)
                }
            }
            regex.append("$")
            return Regex(regex.toString()).matches(name)
        }
    }
}
