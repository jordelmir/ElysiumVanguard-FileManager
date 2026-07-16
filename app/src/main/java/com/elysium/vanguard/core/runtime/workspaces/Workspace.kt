package com.elysium.vanguard.core.runtime.workspaces

/**
 * Phase 24 — a workspace.
 *
 * A workspace is a logical container for one or more
 * sessions. The runtime's user model is "I have a
 * workspace called 'Work' with my Debian + my Windows
 * VM, and a separate workspace called 'Personal' with
 * my Arch". Workspaces are persisted via
 * [WorkspaceStore] and survive across process
 * restarts.
 *
 * The workspace is the *isolation boundary*. Two
 * workspaces cannot see each other's sessions, share
 * storage, or interfere with each other's state.
 * Cross-workspace access is a typed error (Phase 24
 * does not enforce this at the type level; the
 * [WorkspaceManager] is the runtime-side guard).
 *
 * The state machine is intentionally small:
 * [WorkspaceState.Active] / [WorkspaceState.Paused] /
 * [WorkspaceState.Closed]. A closed workspace can be
 * re-opened (the manager creates a new state) but the
 * closed state is the "logically deleted" marker for
 * the persistence layer.
 */
data class Workspace(
    val id: String,
    val name: String,
    val createdAtMs: Long,
    val sessions: List<WorkspaceSession>,
    val state: WorkspaceState = WorkspaceState.Active
) {
    init {
        require(id.isNotBlank()) { "workspace id must not be blank" }
        require(name.isNotBlank()) { "workspace name must not be blank" }
        require(sessions.isNotEmpty() || state != WorkspaceState.Closed) {
            "a closed workspace must contain at least one session"
        }
        // Each session id must be unique within the
        // workspace. Cross-workspace session id
        // uniqueness is the manager's job.
        val sessionIds = sessions.map { it.id }
        require(sessionIds.size == sessionIds.toSet().size) {
            "workspace has duplicate session ids: $sessionIds"
        }
    }

    /** Find a session by id. Returns null if not present. */
    fun findSession(sessionId: String): WorkspaceSession? =
        sessions.firstOrNull { it.id == sessionId }

    /** Count of sessions, useful for the UI. */
    fun sessionCount(): Int = sessions.size

    /** Count of sessions by kind, useful for the UI. */
    fun sessionCountByKind(): Map<WorkspaceSession.SessionKind, Int> =
        sessions.groupingBy { it.kind }.eachCount()
}

sealed class WorkspaceState {
    /** The workspace is open; sessions can be launched. */
    object Active : WorkspaceState()

    /** The workspace is suspended; sessions are paused but
     *  state is preserved. A follow-up Active restores. */
    object Paused : WorkspaceState()

    /** The workspace is closed; the user explicitly retired
     *  it. A follow-up Active re-opens with a fresh
     *  state. */
    object Closed : WorkspaceState()
}
