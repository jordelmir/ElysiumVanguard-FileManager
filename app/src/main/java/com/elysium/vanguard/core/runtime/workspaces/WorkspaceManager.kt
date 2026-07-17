package com.elysium.vanguard.core.runtime.workspaces

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus

/**
 * Phase 24 — the user-facing orchestrator for workspaces.
 *
 * The manager is the runtime's single public surface for
 * the workspace path. It composes:
 *
 *   - a [WorkspaceStore] (the persistence seam),
 *   - a per-process workspace index ([byId]) that
 *     mirrors the store's view and survives across
 *     calls,
 *   - a [RuntimeEventBus] (the observability seam) the
 *     manager publishes every state transition to.
 *
 * The manager's job is translation: the UI calls
 * [createWorkspace] / [addSession] / [pauseWorkspace] /
 * etc. The manager mutates the workspace's state, calls
 * the store to persist, publishes a [RuntimeEvent] on
 * the bus, and returns the updated workspace.
 *
 * Cross-workspace isolation: the manager refuses to
 * return a session that belongs to a different
 * workspace. The typed error is [WorkspaceError].
 *
 * Phase 39 — the manager now publishes every state
 * transition to the [RuntimeEventBus] itself. Before
 * Phase 39, the [com.elysium.vanguard.core.runtime.ui.WorkspacesViewModel]
 * was the one publishing on its own actions — which
 * meant a future service or background job that
 * mutated the manager directly would skip the
 * observability path. Phase 39 closes that gap: the
 * manager is the single source of truth for "what
 * just happened to a workspace", and the bus is the
 * single place that learns about it.
 */
class WorkspaceManager(
    private val store: WorkspaceStore,
    private val eventBus: RuntimeEventBus,
    clock: () -> Long = Companion::systemClock
) {
    private val clock: () -> Long = clock
    private val byId = java.util.concurrent.ConcurrentHashMap<String, Workspace>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(workspaceId: String): Any =
        locks.computeIfAbsent(workspaceId) { Any() }

    init {
        // Hydrate the in-memory index from the store.
        // A freshly-installed runtime sees the user's
        // existing workspaces on first launch.
        for (ws in store.list()) byId[ws.id] = ws
    }

    /**
     * Create a new workspace. The manager auto-generates
     * a unique id (the id is `ws-<systemTimeMs>-<counter>`);
     * callers do not need to provide one. The workspace
     * starts in [WorkspaceState.Active].
     *
     * Phase 39 — publishes a [RuntimeEvent.WorkspaceStateChangedEvent]
     * on the bus. The event's `fromState` is "(none)"
     * (a fresh workspace has no prior state); the
     * `toState` is "Active".
     */
    fun createWorkspace(
        name: String,
        sessions: List<WorkspaceSession> = emptyList(),
        nowMs: Long = clock()
    ): Result<Workspace> {
        if (name.isBlank()) {
            return Result.failure(WorkspaceError.InvalidName(name))
        }
        val id = "ws-$nowMs-${nextCounter.incrementAndGet()}"
        val workspace = Workspace(
            id = id,
            name = name,
            createdAtMs = nowMs,
            sessions = sessions
        )
        byId[workspace.id] = workspace
        store.save(workspace)
        eventBus.publish(
            RuntimeEvent.WorkspaceStateChangedEvent(
                atMs = nowMs,
                workspaceId = workspace.id,
                fromState = "(none)",
                toState = workspace.state.toString()
            )
        )
        return Result.success(workspace)
    }

    /** Look up a workspace by id. */
    fun getWorkspace(id: String): Workspace? = byId[id]

    /** List all workspaces, sorted by `createdAtMs`
     *  ascending. */
    fun listWorkspaces(): List<Workspace> = byId.values.sortedBy { it.createdAtMs }

    /**
     * Transition a workspace to [WorkspaceState.Paused].
     *
     * Phase 39 — publishes a
     * [RuntimeEvent.WorkspaceStateChangedEvent] on the
     * bus with the prior state as `fromState`.
     */
    fun pauseWorkspace(id: String): Result<Workspace> {
        val workspace = byId[id] ?: return Result.failure(WorkspaceError.NotFound(id))
        val nowMs = clock()
        val paused = workspace.copy(state = WorkspaceState.Paused)
        byId[id] = paused
        store.save(paused)
        eventBus.publish(
            RuntimeEvent.WorkspaceStateChangedEvent(
                atMs = nowMs,
                workspaceId = id,
                fromState = workspace.state.toString(),
                toState = paused.state.toString()
            )
        )
        return Result.success(paused)
    }

    /**
     * Transition a workspace to [WorkspaceState.Active]
     * from any other state.
     *
     * Phase 39 — publishes a
     * [RuntimeEvent.WorkspaceStateChangedEvent] on the
     * bus.
     */
    fun activateWorkspace(id: String): Result<Workspace> {
        val workspace = byId[id] ?: return Result.failure(WorkspaceError.NotFound(id))
        val nowMs = clock()
        val active = workspace.copy(state = WorkspaceState.Active)
        byId[id] = active
        store.save(active)
        eventBus.publish(
            RuntimeEvent.WorkspaceStateChangedEvent(
                atMs = nowMs,
                workspaceId = id,
                fromState = workspace.state.toString(),
                toState = active.state.toString()
            )
        )
        return Result.success(active)
    }

    /**
     * Close a workspace. A closed workspace can be
     * re-opened via [activateWorkspace] (the runtime
     * preserves the sessions; closing is a logical
     * state, not a delete).
     *
     * Phase 39 — publishes a
     * [RuntimeEvent.WorkspaceStateChangedEvent] on the
     * bus.
     */
    fun closeWorkspace(id: String): Result<Workspace> {
        val workspace = byId[id] ?: return Result.failure(WorkspaceError.NotFound(id))
        if (workspace.sessions.isEmpty()) {
            // A closed workspace must contain at least
            // one session per the [Workspace] init.
            return Result.failure(WorkspaceError.CannotCloseEmpty(id))
        }
        val nowMs = clock()
        val closed = workspace.copy(state = WorkspaceState.Closed)
        byId[id] = closed
        store.save(closed)
        eventBus.publish(
            RuntimeEvent.WorkspaceStateChangedEvent(
                atMs = nowMs,
                workspaceId = id,
                fromState = workspace.state.toString(),
                toState = closed.state.toString()
            )
        )
        return Result.success(closed)
    }

    /**
     * Add a session to a workspace. Refuses to add a
     * session whose id already exists in the workspace
     * or whose id exists in a *different* workspace
     * (cross-workspace isolation).
     *
     * The check-then-act is atomic: a per-workspace lock
     *  serialises concurrent `addSession` calls so two
     *  threads adding different sessions see the
     *  latest workspace state. Without the lock, a race
     *  could cause a write to be lost.
     *
     * Phase 39 — publishes a
     * [RuntimeEvent.SessionAddedEvent] on the bus on
     * success.
     */
    fun addSession(workspaceId: String, session: WorkspaceSession): Result<Workspace> {
        synchronized(lockFor(workspaceId)) {
            val workspace = byId[workspaceId] ?: return Result.failure(WorkspaceError.NotFound(workspaceId))
            if (session.id in workspace.sessions.map { it.id }) {
                return Result.failure(WorkspaceError.DuplicateSessionId(session.id, workspaceId))
            }
            // Cross-workspace isolation: a session id used
            // by another workspace is a hard error.
            val conflicting = byId.values.firstOrNull {
                it.id != workspaceId && session.id in it.sessions.map { s -> s.id }
            }
            if (conflicting != null) {
                return Result.failure(
                    WorkspaceError.SessionIdUsedElsewhere(
                        sessionId = session.id,
                        workspaceId = workspaceId,
                        otherWorkspaceId = conflicting.id
                    )
                )
            }
            val nowMs = clock()
            val updated = workspace.copy(sessions = workspace.sessions + session)
            byId[workspaceId] = updated
            store.save(updated)
            eventBus.publish(
                RuntimeEvent.SessionAddedEvent(
                    atMs = nowMs,
                    workspaceId = workspaceId,
                    sessionId = session.id,
                    sessionKind = session.kind.name
                )
            )
            return Result.success(updated)
        }
    }

    /**
     * Remove a session from a workspace. The same
     * per-workspace lock as [addSession] serialises
     * the read-modify-write.
     *
     * Phase 39 — publishes a
     * [RuntimeEvent.SessionRemovedEvent] on the bus on
     * success.
     */
    fun removeSession(workspaceId: String, sessionId: String): Result<Workspace> {
        synchronized(lockFor(workspaceId)) {
            val workspace = byId[workspaceId] ?: return Result.failure(WorkspaceError.NotFound(workspaceId))
            if (sessionId !in workspace.sessions.map { it.id }) {
                return Result.failure(WorkspaceError.SessionNotFound(sessionId, workspaceId))
            }
            val nowMs = clock()
            val updated = workspace.copy(sessions = workspace.sessions.filter { it.id != sessionId })
            byId[workspaceId] = updated
            store.save(updated)
            eventBus.publish(
                RuntimeEvent.SessionRemovedEvent(
                    atMs = nowMs,
                    workspaceId = workspaceId,
                    sessionId = sessionId
                )
            )
            return Result.success(updated)
        }
    }

    /** Look up a session by its id. The lookup is
     *  workspace-scoped: a session that exists in a
     *  different workspace is not visible. */
    fun findSession(workspaceId: String, sessionId: String): WorkspaceSession? {
        val workspace = byId[workspaceId] ?: return null
        return workspace.findSession(sessionId)
    }

    /** Persist every in-memory workspace to the store.
     *  The manager auto-saves on every state change; this
     *  is a belt-and-braces hook the runtime calls on
     *  shutdown. */
    fun flushAll() {
        for (workspace in byId.values) store.save(workspace)
    }

    private companion object {
        val nextCounter = java.util.concurrent.atomic.AtomicInteger(0)

        /** Default wall clock used when the caller
         *  does not pass an explicit `clock`. Pulled
         *  out of the constructor default because
         *  Kotlin reserves `::` in default-value
         *  position for future use; the named
         *  reference keeps the constructor callable
         *  with a single `WorkspaceManager(store, bus)`. */
        fun systemClock(): Long = System.currentTimeMillis()
    }
}

/** Typed errors the manager returns. The caller branches
 *  on the kind rather than parsing free-form strings. */
sealed class WorkspaceError(message: String) : RuntimeException(message) {
    data class NotFound(val workspaceId: String) : WorkspaceError("Workspace not found: $workspaceId")
    data class InvalidName(val name: String) : WorkspaceError("Invalid workspace name: '$name'")
    data class DuplicateSessionId(val sessionId: String, val workspaceId: String) :
        WorkspaceError("Session $sessionId already exists in workspace $workspaceId")
    data class SessionIdUsedElsewhere(
        val sessionId: String,
        val workspaceId: String,
        val otherWorkspaceId: String
    ) : WorkspaceError("Session $sessionId is used by another workspace ($otherWorkspaceId); cannot add to $workspaceId")
    data class SessionNotFound(val sessionId: String, val workspaceId: String) :
        WorkspaceError("Session $sessionId not found in workspace $workspaceId")
    data class CannotCloseEmpty(val workspaceId: String) :
        WorkspaceError("Cannot close workspace $workspaceId: it has no sessions")
}
