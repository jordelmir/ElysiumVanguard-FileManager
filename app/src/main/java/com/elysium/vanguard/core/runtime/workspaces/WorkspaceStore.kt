package com.elysium.vanguard.core.runtime.workspaces

/**
 * Phase 24 — the persistence seam for workspaces.
 *
 * The [WorkspaceStore] is the interface the production
 * file-backed store satisfies. The store is keyed by
 * workspace id; each workspace is stored as a single
 * unit (the production impl serialises the [Workspace]
 * data class to JSON and writes it under
 * `<baseDir>/workspaces/<id>.json`).
 *
 * Splitting the store from the manager keeps the
 * persistence policy JVM-testable end-to-end. The
 * [InMemoryWorkspaceStore] is the test impl; the
 * production store is a follow-up phase.
 */
interface WorkspaceStore {
    /** Save [workspace], overwriting any existing entry. */
    fun save(workspace: Workspace)

    /** Load the workspace with [id], or null if not present. */
    fun load(id: String): Workspace?

    /** List all stored workspaces. The order is
     *  implementation-defined; the manager sorts by
     *  `createdAtMs` for display. */
    fun list(): List<Workspace>

    /** Delete the workspace with [id]. Returns true if a
     *  workspace was deleted, false if no such id. */
    fun delete(id: String): Boolean
}

/**
 * 5-line hand-rolled store for tests. Thread-safe via a
 * `synchronized` map. Tests instantiate one per test
 * and the manager composes it.
 */
class InMemoryWorkspaceStore : WorkspaceStore {
    private val lock = Any()
    private val byId = mutableMapOf<String, Workspace>()

    override fun save(workspace: Workspace) {
        synchronized(lock) { byId[workspace.id] = workspace }
    }

    override fun load(id: String): Workspace? = synchronized(lock) {
        byId[id]
    }

    override fun list(): List<Workspace> = synchronized(lock) {
        byId.values.toList()
    }

    override fun delete(id: String): Boolean = synchronized(lock) {
        byId.remove(id) != null
    }

    fun clear() = synchronized(lock) { byId.clear() }
    fun size(): Int = synchronized(lock) { byId.size }
}
