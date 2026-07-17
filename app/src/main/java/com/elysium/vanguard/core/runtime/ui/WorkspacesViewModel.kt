package com.elysium.vanguard.core.runtime.ui

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceError
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 29 — the action layer for the workspace UI.
 *
 * The [WorkspacesViewModel] is the JVM-testable seam the
 * Compose UI calls when the user taps "create
 * workspace", "pause", "activate", "close", "add
 * session", or "remove session". The ViewModel:
 *
 *   - delegates every action to the [WorkspaceManager]
 *     (Phase 24) — the same manager the data layer
 *     uses; no second source of truth,
 *   - publishes a [RuntimeEvent] to the [RuntimeEventBus]
 *     (Phase 25) when an action succeeds — the
 *     observability bus + the [MainScreenViewModel]
 *     pick the event up,
 *   - exposes a [StateFlow] of the current workspace
 *     list + the last action's typed result.
 *
 * The ViewModel is `AndroidViewModel`-free. A future
 * Compose-side adapter wires the `StateFlow` to the
 * Compose state and the action methods to the UI's
 * click handlers.
 */
class WorkspacesViewModel(
    private val workspaceManager: WorkspaceManager,
    private val eventBus: RuntimeEventBus,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {

    private val _state = MutableStateFlow(WorkspacesState.EMPTY)
    val state: StateFlow<WorkspacesState> = _state.asStateFlow()

    private val subscription: AutoCloseable = eventBus.subscribe { event ->
        if (event is RuntimeEvent.WorkspaceStateChangedEvent ||
            event is RuntimeEvent.SessionAddedEvent ||
            event is RuntimeEvent.SessionRemovedEvent) {
            refresh()
        }
    }

    init {
        refresh()
    }

    // --- read ---

    fun refresh() {
        _state.value = _state.value.copy(
            workspaces = workspaceManager.listWorkspaces(),
            lastActionResult = null
        )
    }

    // --- create ---

    /**
     * Create a new workspace. Emits a
     * [RuntimeEvent.WorkspaceStateChangedEvent] on
     * success so the bus subscribers (the audit log,
     * the main-screen ViewModel) re-read state.
     */
    fun createWorkspace(name: String, sessions: List<WorkspaceSession> = emptyList()): Result<Workspace> {
        val nowMs = clock()
        val result = workspaceManager.createWorkspace(name = name, sessions = sessions, nowMs = nowMs)
        if (result.isSuccess) {
            val ws = result.getOrThrow()
            eventBus.publish(
                RuntimeEvent.WorkspaceStateChangedEvent(
                    atMs = nowMs,
                    workspaceId = ws.id,
                    fromState = "(none)",
                    toState = ws.state.toString()
                )
            )
            refresh()
        } else {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    // --- state transitions ---

    fun pauseWorkspace(workspaceId: String): Result<Workspace> =
        transitionAndPublish(workspaceId, "Active", "Paused") {
            workspaceManager.pauseWorkspace(workspaceId)
        }

    fun activateWorkspace(workspaceId: String): Result<Workspace> =
        transitionAndPublish(workspaceId, "Paused", "Active") {
            workspaceManager.activateWorkspace(workspaceId)
        }

    fun closeWorkspace(workspaceId: String): Result<Workspace> =
        transitionAndPublish(workspaceId, "Active", "Closed") {
            workspaceManager.closeWorkspace(workspaceId)
        }

    // --- session management ---

    fun addSession(workspaceId: String, session: WorkspaceSession): Result<Workspace> {
        val nowMs = clock()
        val result = workspaceManager.addSession(workspaceId, session)
        if (result.isSuccess) {
            val ws = result.getOrThrow()
            eventBus.publish(
                RuntimeEvent.SessionAddedEvent(
                    atMs = nowMs,
                    workspaceId = ws.id,
                    sessionId = session.id,
                    sessionKind = session.kind.toString()
                )
            )
            refresh()
        } else {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    fun removeSession(workspaceId: String, sessionId: String): Result<Workspace> {
        val nowMs = clock()
        val result = workspaceManager.removeSession(workspaceId, sessionId)
        if (result.isSuccess) {
            val ws = result.getOrThrow()
            eventBus.publish(
                RuntimeEvent.SessionRemovedEvent(
                    atMs = nowMs,
                    workspaceId = ws.id,
                    sessionId = sessionId
                )
            )
            refresh()
        } else {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    // --- internals ---

    private inline fun transitionAndPublish(
        workspaceId: String,
        fromState: String,
        toState: String,
        block: () -> Result<Workspace>
    ): Result<Workspace> {
        val nowMs = clock()
        val result = block()
        if (result.isSuccess) {
            eventBus.publish(
                RuntimeEvent.WorkspaceStateChangedEvent(
                    atMs = nowMs,
                    workspaceId = workspaceId,
                    fromState = fromState,
                    toState = toState
                )
            )
            refresh()
        } else {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    override fun close() {
        subscription.close()
    }
}

/**
 * The state object the Compose UI consumes. The
 * [workspaces] list is the source of truth (hydrated
 * from the manager on every refresh); [lastActionResult]
 * is the result of the most recent action, so the UI
 * can show a snackbar / error banner without polling
 * the manager.
 */
data class WorkspacesState(
    val workspaces: List<Workspace> = emptyList(),
    val lastActionResult: Result<Workspace>? = null
) {
    companion object {
        val EMPTY = WorkspacesState()
    }
}
