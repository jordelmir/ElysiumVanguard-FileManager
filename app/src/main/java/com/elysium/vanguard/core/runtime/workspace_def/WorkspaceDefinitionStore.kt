package com.elysium.vanguard.core.runtime.workspace_def

import java.io.File

/**
 * Phase 66 — the persistence seam for [WorkspaceDefinition].
 *
 * The store is keyed by workspace id. Each workspace is
 * stored as a single JSON file at
 * `<baseDir>/workspaces/<id>.json`. The on-disk format
 * is the same JSON the codec produces (the codec is the
 * source of truth for the format; the store is the
 * I/O wrapper).
 *
 * The store is **thread-safe**: every operation is
 * guarded by a lock. Concurrent writers serialize; the
 * last writer wins. The atomic write (temp file + rename)
 * prevents torn writes on crash.
 *
 * The store is **JVM-testable**: the [InMemoryWorkspaceDefinitionStore]
 * is the test impl. The production file-backed store
 * is the default.
 */
interface WorkspaceDefinitionStore {
    /** Save [definition], overwriting any existing entry. */
    fun save(definition: WorkspaceDefinition)

    /** Load the workspace with [id], or null if not present. */
    fun load(id: String): WorkspaceDefinition?

    /** List all stored workspace definitions. The order is
     *  implementation-defined; the manager sorts by
     *  `createdAtMs` for display. */
    fun list(): List<WorkspaceDefinition>

    /** Delete the workspace with [id]. Returns true if a
     *  workspace was deleted, false if no such id. */
    fun delete(id: String): Boolean
}

/**
 * The file-backed store. The store writes JSON files to
 * `<baseDir>/workspaces/<id>.json`. The base directory is
 * created lazily on the first `save()`.
 */
class FileWorkspaceDefinitionStore(
    private val baseDir: File,
) : WorkspaceDefinitionStore {

    private val lock = Any()
    private val workspacesDir: File = File(baseDir, "workspaces")

    init {
        require(baseDir.isDirectory || baseDir.mkdirs() || baseDir.isDirectory) {
            "baseDir must be an existing directory or be creatable: ${baseDir.absolutePath}"
        }
    }

    override fun save(definition: WorkspaceDefinition) = synchronized(lock) {
        if (!workspacesDir.exists()) {
            workspacesDir.mkdirs()
        }
        val file = File(workspacesDir, "${definition.id}.json")
        WorkspaceDefinitionCodec.encodeToFile(definition, file)
    }

    override fun load(id: String): WorkspaceDefinition? = synchronized(lock) {
        val file = File(workspacesDir, "$id.json")
        if (!file.exists()) null
        else try {
            WorkspaceDefinitionCodec.decodeFromFile(file)
        } catch (e: WorkspaceDefinitionCodecException) {
            // A malformed file is "not present" — the
            // consumer can re-create it.
            null
        }
    }

    override fun list(): List<WorkspaceDefinition> = synchronized(lock) {
        if (!workspacesDir.exists()) return@synchronized emptyList()
        workspacesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    WorkspaceDefinitionCodec.decodeFromFile(file)
                } catch (e: WorkspaceDefinitionCodecException) {
                    null
                }
            }
            ?: emptyList()
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        val file = File(workspacesDir, "$id.json")
        if (file.exists()) file.delete() else false
    }
}

/**
 * 5-line hand-rolled store for tests. Thread-safe via a
 * `synchronized` map. The map is the source of truth; no
 * I/O is involved.
 */
class InMemoryWorkspaceDefinitionStore : WorkspaceDefinitionStore {
    private val lock = Any()
    private val byId = mutableMapOf<String, WorkspaceDefinition>()

    override fun save(definition: WorkspaceDefinition) = synchronized(lock) {
        byId[definition.id] = definition
    }

    override fun load(id: String): WorkspaceDefinition? = synchronized(lock) {
        byId[id]
    }

    override fun list(): List<WorkspaceDefinition> = synchronized(lock) {
        byId.values.toList()
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        byId.remove(id) != null
    }

    fun clear() = synchronized(lock) { byId.clear() }
    fun size(): Int = synchronized(lock) { byId.size }
}
