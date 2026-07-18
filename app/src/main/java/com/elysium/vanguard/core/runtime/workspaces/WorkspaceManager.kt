package com.elysium.vanguard.core.runtime.workspaces

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.policy.MountAuditEntry
import com.elysium.vanguard.core.runtime.policy.MountAuditLog
import com.elysium.vanguard.core.runtime.policy.MountEnforcementResult
import com.elysium.vanguard.core.runtime.policy.MountPolicy
import com.elysium.vanguard.core.runtime.policy.MountPolicyEnforcer
import com.elysium.vanguard.core.runtime.snapshots.MountPlan
import com.elysium.vanguard.core.runtime.snapshots.RollbackResult
import com.elysium.vanguard.core.runtime.snapshots.SnapshotEngine
import com.elysium.vanguard.core.runtime.snapshots.SnapshotError
import com.elysium.vanguard.core.runtime.snapshots.SnapshotResult
import com.elysium.vanguard.core.runtime.snapshots.WorkspaceSnapshot

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
 *
 * Phase 49 — the manager is also the user-facing
 * surface for the [SnapshotEngine]. The engine is
 * injected; the manager wraps every engine call,
 * publishes the appropriate
 * [RuntimeEvent.SnapshotCreatedEvent] /
 * [RuntimeEvent.SnapshotRestoredEvent] /
 * [RuntimeEvent.SnapshotDeletedEvent], and returns
 * the result.
 */
class WorkspaceManager(
    private val store: WorkspaceStore,
    private val eventBus: RuntimeEventBus,
    private val snapshotEngine: SnapshotEngine? = null,
    private val mountPolicyEnforcer: MountPolicyEnforcer? = null,
    private val mountAuditLog: MountAuditLog? = null,
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

    // ---- Snapshot / rollback (Phase 49) ----

    /**
     * Capture a snapshot of the workspace's live
     * rootfs.
     *
     * The caller provides the live rootfs path
     * (the runtime knows where the distro's
     * rootfs lives; the manager does not). The
     * mount plan is recorded in the manifest for
     * future "rollback to mount plan" use.
     *
     * On success, the manager publishes
     * [RuntimeEvent.SnapshotCreatedEvent] on the
     * bus. The event carries the snapshot id,
     * label, and the copy strategy the engine
     * used.
     *
     * Errors:
     * - `WorkspaceError.SnapshotEngineNotConfigured`
     *   if the manager was constructed without a
     *   [SnapshotEngine].
     * - [SnapshotError] (the engine's typed
     *   errors) for snapshot capture failures.
     */
    fun snapshotWorkspace(
        workspaceId: String,
        sourceRootfsPath: String,
        mountPlan: MountPlan,
        label: String
    ): Result<WorkspaceSnapshot> {
        val engine = snapshotEngine
            ?: return Result.failure(WorkspaceError.SnapshotEngineNotConfigured("snapshot"))
        if (byId[workspaceId] == null) {
            return Result.failure(WorkspaceError.NotFound(workspaceId))
        }
        return when (val outcome = engine.snapshot(workspaceId, sourceRootfsPath, mountPlan, label)) {
            is SnapshotResult.Success -> {
                eventBus.publish(
                    RuntimeEvent.SnapshotCreatedEvent(
                        atMs = outcome.snapshot.createdAtMs,
                        workspaceId = workspaceId,
                        snapshotId = outcome.snapshot.id,
                        label = outcome.snapshot.label,
                        copyStrategy = outcome.snapshot.copyStrategy.name
                    )
                )
                Result.success(outcome.snapshot)
            }
            is SnapshotResult.Failure -> Result.failure(outcome.error)
        }
    }

    /**
     * Restore a workspace's live rootfs to a
     * previously captured snapshot.
     *
     * The caller provides the live rootfs path
     * (the manager does not know which distro's
     * rootfs backs the workspace). The previous
     * live rootfs is NOT preserved; the manager
     * is the orchestrator but the engine is the
     * destructive-IO layer.
     *
     * On success, publishes
     * [RuntimeEvent.SnapshotRestoredEvent] on
     * the bus.
     *
     * Errors:
     * - `WorkspaceError.SnapshotEngineNotConfigured`
     *   if the manager was constructed without a
     *   [SnapshotEngine].
     * - `WorkspaceError.NotFound` if the
     *   workspace id is unknown.
     * - [SnapshotError] for engine failures
     *   (snapshot not found, live rootfs
     *   missing, copy I/O error).
     */
    fun rollbackWorkspace(
        workspaceId: String,
        snapshotId: String,
        liveRootfsPath: String
    ): Result<WorkspaceSnapshot> {
        val engine = snapshotEngine
            ?: return Result.failure(WorkspaceError.SnapshotEngineNotConfigured("rollback"))
        if (byId[workspaceId] == null) {
            return Result.failure(WorkspaceError.NotFound(workspaceId))
        }
        return when (val outcome = engine.rollback(workspaceId, snapshotId, liveRootfsPath)) {
            is RollbackResult.Success -> {
                eventBus.publish(
                    RuntimeEvent.SnapshotRestoredEvent(
                        atMs = clock(),
                        workspaceId = workspaceId,
                        snapshotId = outcome.restoredFrom.id,
                        label = outcome.restoredFrom.label
                    )
                )
                Result.success(outcome.restoredFrom)
            }
            is RollbackResult.Failure -> Result.failure(outcome.error)
        }
    }

    /**
     * List every snapshot of [workspaceId],
     * sorted by `createdAtMs` ascending.
     * Returns an empty list if the workspace has
     * no snapshots or no engine is configured.
     */
    fun listSnapshots(workspaceId: String): List<WorkspaceSnapshot> {
        val engine = snapshotEngine ?: return emptyList()
        if (byId[workspaceId] == null) return emptyList()
        return engine.list(workspaceId)
    }

    /**
     * Delete a snapshot. On success, publishes
     * [RuntimeEvent.SnapshotDeletedEvent] on
     * the bus.
     *
     * Returns `true` if the snapshot was found
     * and deleted, `false` otherwise.
     */
    fun deleteSnapshot(workspaceId: String, snapshotId: String): Result<Boolean> {
        val engine = snapshotEngine
            ?: return Result.failure(WorkspaceError.SnapshotEngineNotConfigured("delete"))
        if (byId[workspaceId] == null) {
            return Result.failure(WorkspaceError.NotFound(workspaceId))
        }
        val deleted = engine.delete(snapshotId)
        if (deleted) {
            eventBus.publish(
                RuntimeEvent.SnapshotDeletedEvent(
                    atMs = clock(),
                    workspaceId = workspaceId,
                    snapshotId = snapshotId
                )
            )
        }
        return Result.success(deleted)
    }

    // ---- Mount policy (Phase 50) ----

    /**
     * Apply [policy] to the proposed [mounts] for
     * [sessionId] in [workspaceId]. The enforcer
     * returns either a filtered list (the
     * allowlist-intersected mounts) or a list of
     * violations.
     *
     * Every decision is:
     *
     * 1. Recorded in the [MountAuditLog] (if
     *    configured) — append-only NDJSON.
     * 2. Published on the [RuntimeEventBus] as a
     *    [RuntimeEvent.MountAllowedEvent] or
     *    [RuntimeEvent.MountPolicyViolationEvent].
     *
     * The runner (Phase 51+) consumes the
     * returned [MountEnforcementResult] to
     * produce its proot `-b` flag list.
     *
     * If the manager was constructed without an
     * enforcer (e.g. a unit test that does not
     * care about the policy), the method returns
     * `Allowed(proposed.toList())` and writes
     * nothing — the runtime is "permissive" for
     * backwards compatibility. Production
     * (Hilt) wires the enforcer; tests that
     * exercise the policy wire it explicitly.
     *
     * Errors:
     * - `WorkspaceError.NotFound` if the
     *   workspace id is unknown.
     */
    fun enforceMountPolicy(
        workspaceId: String,
        sessionId: String,
        policy: MountPolicy,
        mounts: List<MountEntry>
    ): Result<MountEnforcementResult> {
        if (byId[workspaceId] == null) {
            return Result.failure(WorkspaceError.NotFound(workspaceId))
        }
        val enforcer = mountPolicyEnforcer
            ?: return Result.success(
                MountEnforcementResult.Allowed(filteredMounts = mounts.toList())
            )
        val result = enforcer.enforce(policy, mounts)
        recordMountDecisions(workspaceId, sessionId, policy, result)
        return Result.success(result)
    }

    /**
     * Record every decision in [result] to the
     * audit log + the bus. One audit entry per
     * allowed / denied / tightened mount; one
     * event per decision.
     */
    private fun recordMountDecisions(
        workspaceId: String,
        sessionId: String,
        policy: MountPolicy,
        result: MountEnforcementResult
    ) {
        val nowMs = clock()
        when (result) {
            is MountEnforcementResult.Allowed -> {
                for (entry in result.filteredMounts) {
                    mountAuditLog?.append(
                        MountAuditEntry(
                            atMs = nowMs,
                            workspaceId = workspaceId,
                            sessionId = sessionId,
                            hostPath = entry.hostPath,
                            guestPath = entry.guestPath,
                            decision = MountAuditEntry.DECISION_ALLOWED,
                            reason = "mount allowed by policy"
                        )
                    )
                    eventBus.publish(
                        RuntimeEvent.MountAllowedEvent(
                            atMs = nowMs,
                            workspaceId = workspaceId,
                            sessionId = sessionId,
                            hostPath = entry.hostPath,
                            guestPath = entry.guestPath,
                            readOnly = entry.readOnly
                        )
                    )
                }
            }
            is MountEnforcementResult.Denied -> {
                // Record the allowed subset.
                for (entry in result.allowedMounts) {
                    mountAuditLog?.append(
                        MountAuditEntry(
                            atMs = nowMs,
                            workspaceId = workspaceId,
                            sessionId = sessionId,
                            hostPath = entry.hostPath,
                            guestPath = entry.guestPath,
                            decision = MountAuditEntry.DECISION_ALLOWED,
                            reason = "mount allowed (with denied siblings)"
                        )
                    )
                    eventBus.publish(
                        RuntimeEvent.MountAllowedEvent(
                            atMs = nowMs,
                            workspaceId = workspaceId,
                            sessionId = sessionId,
                            hostPath = entry.hostPath,
                            guestPath = entry.guestPath,
                            readOnly = entry.readOnly
                        )
                    )
                }
                // Record each violation.
                for (violation in result.violations) {
                    mountAuditLog?.append(
                        MountAuditEntry(
                            atMs = nowMs,
                            workspaceId = workspaceId,
                            sessionId = sessionId,
                            hostPath = violation.hostPath,
                            guestPath = violation.guestPath,
                            decision = MountAuditEntry.DECISION_DENIED,
                            reason = violation.reason
                        )
                    )
                    eventBus.publish(
                        RuntimeEvent.MountPolicyViolationEvent(
                            atMs = nowMs,
                            workspaceId = workspaceId,
                            sessionId = sessionId,
                            hostPath = violation.hostPath,
                            guestPath = violation.guestPath,
                            reason = violation.reason
                        )
                    )
                }
            }
        }
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
    data class SnapshotEngineNotConfigured(val operation: String) :
        WorkspaceError("Snapshot operation '$operation' requires a SnapshotEngine; manager was constructed without one")
}
