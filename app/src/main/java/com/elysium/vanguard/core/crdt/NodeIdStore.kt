package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.server.Json
import java.io.File
import java.util.UUID

/**
 * PHASE 9.19 — Persisted node identifier for CRDT docs.
 *
 * Each device / process needs a stable node id so companion
 * files (`foo.elysium.word.<node>.elysium.sync`) created on
 * launch keep the same name across process restarts. The
 * [CrdtDocumentEditorViewModel] uses this value as the
 * `nodeId` argument when opening a session; HLCs issued from
 * the corresponding [HlcClock] all carry the same string,
 * which is the tiebreaker that prevents collisions when two
 * events share `(ms, counter)`.
 *
 * The first version persists a single `nodeId` string in a
 * JSON file at a caller-supplied path (typically the app's
 * `filesDir` on Android, but any path works). The store is
 * thread-safe enough for the editor's single-threaded intent
 * dispatch and one-shot reads.
 *
 * Phase 9.19 — first build; intentionally minimal.
 */
class NodeIdStore(private val storeFile: File) {

    @Volatile
    private var cached: String? = null

    /**
     * Return the persisted node id, lazily generating a fresh
     * UUID and persisting it on first use. Thread-safe enough
     * for the editor's single-threaded intent dispatch.
     */
    @Synchronized
    fun getOrCreate(): String {
        cached?.let { return it }
        val existing = readFromDisk()
        if (existing != null) {
            cached = existing
            return existing
        }
        val fresh = "node-${UUID.randomUUID()}"
        writeToDisk(fresh)
        cached = fresh
        return fresh
    }

    /**
     * Force a specific value (used by tests and migration
     * paths). Persists immediately.
     */
    @Synchronized
    fun set(value: String) {
        require(value.isNotBlank()) { "nodeId must be non-blank" }
        writeToDisk(value)
        cached = value
    }

    /**
     * Drop the cached and persisted value; the next
     * [getOrCreate] will mint a fresh UUID. Tests use this to
     * simulate a clean device.
     */
    @Synchronized
    fun clear() {
        if (storeFile.isFile) storeFile.delete()
        cached = null
    }

    private fun readFromDisk(): String? {
        if (!storeFile.isFile) return null
        return runCatching {
            val parsed = Json.decode(storeFile.readText())
            (parsed as? Map<*, *>)?.get("nodeId")?.toString()
        }.getOrNull()
    }

    private fun writeToDisk(value: String) {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(Json.encode(mapOf("nodeId" to value)))
    }

    companion object {
        /**
         * Synthesize a unique store filename for the device.
         * Public so callers can adopt a default when wiring
         * NodeIdStore into Hilt without forcing them to make a
         * path decision at the call site.
         */
        fun defaultStoreFile(rootDir: File, deviceTag: String = "main"): File =
            File(rootDir, "node-id-${deviceTag}.json")
    }
}
