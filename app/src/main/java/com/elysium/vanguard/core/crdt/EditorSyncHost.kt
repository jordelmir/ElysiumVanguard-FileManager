package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.features.crdteditor.CrdtDocumentEditorEngine
import java.io.File

/**
 * PHASE 10.1 — Bridges the editor engine to whatever is
 * currently serving CRDT sync (today: the local
 * `LocalServerOrchestrator`; tomorrow: a remote peer, SFTP
 * target, etc.).
 *
 * The editor's `CrdtDocumentEditorEngine` only knows the
 * [CrdtDocumentEditorEngine.SyncHost] interface — it does not
 * know about HTTP, auth tokens, or the running server. This
 * class is the glue: at sync time the editor calls
 * [adapterFor] with the absolute path of the document the user
 * has open. We:
 *
 *   1. Check the [Source] reports the server as running.
 *   2. Resolve the document path relative to the
 *      orchestrator's `fsRoot` (its sandbox).
 *   3. Build a [LocalServerSyncAdapter] pointing at the
 *      orchestrator's loopback URL with its auth token.
 *
 * If the source is stopped, the document lives outside the
 * sandbox, or the loopback URL is not resolvable, [adapterFor]
 * returns `null` and the engine reports
 * `EditorResult.SyncNoPeer` (existing behavior).
 *
 * Pure JVM; tests can drive it with a hand-rolled [Source] and
 * a real [com.elysium.vanguard.core.server.LocalFileServer]
 * bound to an ephemeral port.
 */
class EditorSyncHost(
    private val source: Source,
    private val fsRoot: () -> File?,
    private val transportBuilder: () -> HttpSyncTransport = { JdkHttpSyncTransport() }
) {

    /**
     * Tiny interface that lets [EditorSyncHost] talk to any
     * "thing that can answer a CRDT sync request" — in
     * production this is the `LocalServerOrchestrator`, in
     * tests it's a hand-rolled stub. The interface is the
     * smallest possible surface (`isRunning`, `serviceBaseUrl`,
     * `authToken`); everything else stays inside the host.
     */
    interface Source {
        fun isRunning(): Boolean
        fun serviceBaseUrl(): String?
        fun authToken(): String
    }

    /**
     * Whether the editor should expect a peer on the next sync.
     * Mirrors [Source.isRunning].
     */
    fun isAvailable(): Boolean = source.isRunning()

    /**
     * Build a per-file [CrdtDocumentEditorEngine.SyncHost] that
     * ships the document's companion file to the running
     * server.
     *
     * Returns `null` if:
     *   - the source is not running,
     *   - the loopback URL is not resolvable,
     *   - the document path does not live under the sandbox.
     */
    fun adapterFor(documentFile: File): CrdtDocumentEditorEngine.SyncHost? {
        if (!isAvailable()) return null
        val root = fsRoot() ?: return null
        val relativePath = relativePathFor(root, documentFile) ?: return null
        val baseUrl = source.serviceBaseUrl() ?: return null
        val adapter = LocalServerSyncAdapter(
            baseUrl = baseUrl,
            authToken = source.authToken(),
            relativePath = relativePath,
            transport = transportBuilder()
        )
        return object : CrdtDocumentEditorEngine.SyncHost {
            override fun syncWith(session: CrdtDocumentSession): Int = adapter.syncWith(session)
        }
    }

    companion object {
        /**
         * Compute the path the server expects, relative to its
         * sandbox root. Returns `null` if [file] does not live
         * under [root].
         *
         * Public so tests can pin the contract without going
         * through the [Source].
         */
        fun relativePathFor(root: File, file: File): String? {
            val rootAbs = runCatching { root.canonicalFile.absolutePath }.getOrNull() ?: return null
            val fileAbs = runCatching { file.canonicalFile.absolutePath }.getOrNull() ?: return null
            if (!fileAbs.startsWith(rootAbs + File.separator) && fileAbs != rootAbs) {
                return null
            }
            return fileAbs.removePrefix(rootAbs).trimStart(File.separatorChar)
        }
    }
}
