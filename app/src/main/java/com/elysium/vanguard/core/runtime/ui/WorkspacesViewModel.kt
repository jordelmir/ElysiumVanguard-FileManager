package com.elysium.vanguard.core.runtime.ui

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.runner.SessionRunner
import com.elysium.vanguard.core.runtime.runner.SessionState
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceError
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceState
import com.elysium.vanguard.core.runtime.WallClock
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 29 — the action layer for the workspace UI.
 *
 * The [WorkspacesViewModel] is the JVM-testable seam the
 * Compose UI calls when the user taps "create
 * workspace", "pause", "activate", "close", "add
 * session", "remove session", "start session", or
 * "stop session". The ViewModel:
 *
 *   - delegates every action to the [WorkspaceManager]
 *     (Phase 24) — the same manager the data layer
 *     uses; no second source of truth,
 *   - delegates session start / stop to the
 *     [SessionRunner] (Phase 32 registry) — the
 *     runner dispatches by [WorkspaceSession.kind]
 *     and publishes its own runtime events,
 *   - publishes a [RuntimeEvent] to the [RuntimeEventBus]
 *     (Phase 25) when a workspace action succeeds —
 *     the observability bus + the
 *     [MainScreenViewModel] pick the event up,
 *   - exposes a [StateFlow] of the current workspace
 *     list, the live session-state map, and the last
 *     action's typed result.
 *
 * The ViewModel is `AndroidViewModel`-free. A future
 * Compose-side adapter wires the `StateFlow` to the
 * Compose state and the action methods to the UI's
 * click handlers.
 *
 * Phase 33 — added `startSession` / `stopSession`
 * methods that delegate to the [SessionRunner]; the
 * [WorkspacesState] gained a `sessionStates` map the
 * UI uses to render the start / stop / running badge
 * per session.
 */
@HiltViewModel
class WorkspacesViewModel @Inject constructor(
    private val workspaceManager: WorkspaceManager,
    private val eventBus: RuntimeEventBus,
    private val sessionRunner: SessionRunner,
    @WallClock private val clock: () -> Long
) : ViewModel(), AutoCloseable {

    private val _state = MutableStateFlow(WorkspacesState.EMPTY)
    val state: StateFlow<WorkspacesState> = _state.asStateFlow()

    private val subscription: AutoCloseable = eventBus.subscribe { event ->
        if (event is RuntimeEvent.WorkspaceStateChangedEvent ||
            event is RuntimeEvent.SessionAddedEvent ||
            event is RuntimeEvent.SessionRemovedEvent) {
            refresh()
        } else if (event is RuntimeEvent.SessionStartedEvent ||
            event is RuntimeEvent.SessionStoppedEvent ||
            event is RuntimeEvent.SessionStartFailedEvent) {
            refreshSessionStates()
        }
    }

    init {
        refresh()
        refreshSessionStates()
    }

    // --- read ---

    fun refresh() {
        _state.value = _state.value.copy(
            workspaces = workspaceManager.listWorkspaces(),
            lastActionResult = null
        )
    }

    /**
     * Re-read the live session states from the
     * runner. The runner is the source of truth for
     * "is this session running?"; the ViewModel
     * projects the runner's `listActive()` onto a
     * per-`(workspaceId, sessionId)` map the UI
     * can render.
     */
    fun refreshSessionStates() {
        val states = sessionRunner.listActive().associate { active ->
            SessionKey(active.workspaceId, active.sessionId) to active.state
        }
        _state.value = _state.value.copy(sessionStates = states)
    }

    // --- create ---

    /**
     * Create a new workspace.
     *
     * Phase 39 — the [WorkspaceManager] publishes
     * its own [RuntimeEvent.WorkspaceStateChangedEvent]
     * on success; the bus subscriber in this ViewModel
     * re-reads state via [refresh]. The ViewModel no
     * longer publishes (that was the architectural
     * drift Phase 39 closed). On failure, the
     * ViewModel still records the result on
     * [WorkspacesState.lastActionResult] so the UI
     * can show a snackbar.
     */
    fun createWorkspace(name: String, sessions: List<WorkspaceSession> = emptyList()): Result<Workspace> {
        val result = workspaceManager.createWorkspace(name = name, sessions = sessions)
        if (result.isFailure) {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    // --- state transitions ---

    fun pauseWorkspace(workspaceId: String): Result<Workspace> =
        transitionAndRecord("Active", "Paused") {
            workspaceManager.pauseWorkspace(workspaceId)
        }

    fun activateWorkspace(workspaceId: String): Result<Workspace> =
        transitionAndRecord("Paused", "Active") {
            workspaceManager.activateWorkspace(workspaceId)
        }

    fun closeWorkspace(workspaceId: String): Result<Workspace> =
        transitionAndRecord("Active", "Closed") {
            workspaceManager.closeWorkspace(workspaceId)
        }

    // --- session management ---

    /**
     * Phase 39 — the manager publishes its own
     * [RuntimeEvent.SessionAddedEvent] on success; the
     * bus subscriber in this ViewModel re-reads state.
     * The ViewModel only records the failure on
     * [WorkspacesState.lastActionResult].
     */
    fun addSession(workspaceId: String, session: WorkspaceSession): Result<Workspace> {
        val result = workspaceManager.addSession(workspaceId, session)
        if (result.isFailure) {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    /**
     * Phase 39 — see [addSession]. The manager
     * publishes the event; the ViewModel just
     * records failures.
     */
    fun removeSession(workspaceId: String, sessionId: String): Result<Workspace> {
        val result = workspaceManager.removeSession(workspaceId, sessionId)
        if (result.isFailure) {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    // --- session runner (Phase 33) ---

    /**
     * Start a session via the [SessionRunner].
     * Delegates to the runner, which dispatches by
     * [WorkspaceSession.kind] (Phase 32). The
     * runner's own [RuntimeEvent.SessionStartedEvent] /
     * `SessionStartFailedEvent` flows back through
     * the bus; the subscriber re-reads the runner's
     * state via [refreshSessionStates].
     */
    fun startSession(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
        val result = sessionRunner.start(workspace, session)
        if (result.isFailure) {
            // Record the failure on the workspace-
            // level lastActionResult so the UI can
            // show a snackbar.
            @Suppress("UNCHECKED_CAST")
            val wrapped: Result<Workspace> = Result.failure(
                result.exceptionOrNull() ?: IllegalStateException("session start failed")
            )
            _state.value = _state.value.copy(lastActionResult = wrapped)
        } else {
            refreshSessionStates()
        }
        return result
    }

    /**
     * Stop a session via the [SessionRunner]. The
     * runner's [RuntimeEvent.SessionStoppedEvent]
     * flows back through the bus; the subscriber
     * re-reads the runner's state via
     * [refreshSessionStates].
     */
    fun stopSession(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
        val result = sessionRunner.stop(workspace, session)
        if (result.isFailure) {
            @Suppress("UNCHECKED_CAST")
            val wrapped: Result<Workspace> = Result.failure(
                result.exceptionOrNull() ?: IllegalStateException("session stop failed")
            )
            _state.value = _state.value.copy(lastActionResult = wrapped)
        } else {
            refreshSessionStates()
        }
        return result
    }

    // --- internals ---

    /**
     * Phase 39 — `transitionAndPublish` is now just
     * `transitionAndRecord`. The [WorkspaceManager]
     * publishes its own [RuntimeEvent.WorkspaceStateChangedEvent];
     * the bus subscriber in this ViewModel re-reads
     * state. The `fromState` / `toState` parameters
     * are kept for documentation (they describe the
     * intended transition) but are no longer used
     * to construct the event.
     */
    private inline fun transitionAndRecord(
        @Suppress("UNUSED_PARAMETER") fromState: String,
        @Suppress("UNUSED_PARAMETER") toState: String,
        block: () -> Result<Workspace>
    ): Result<Workspace> {
        val result = block()
        if (result.isFailure) {
            _state.value = _state.value.copy(lastActionResult = result)
        }
        return result
    }

    /**
     * Phase 36 — the ViewModel's cleanup hook.
     *
     * In production, the [ViewModel] base class calls
     * [onCleared] when the host Activity / Fragment is
     * destroyed. The override delegates to [close] so
     * the production and test paths share one
     * implementation.
     *
     * Tests use [close] directly (the test fixture
     * is `AutoCloseable` so `vm.use { ... }` works
     * unchanged from Phase 29).
     */
    override fun onCleared() {
        close()
    }

    /**
     * Phase 36 — the shared cleanup logic. Unsubscribes
     * from the bus. Idempotent: a second call is a
     * no-op (the [AutoCloseable.close] contract).
     */
    override fun close() {
        subscription.close()
    }

    /**
     * The (workspaceId, sessionId) pair used as a
     * map key for the live session states. Lives
     * here so the test can construct one without
     * depending on the runner's internal key type.
     */
    data class SessionKey(val workspaceId: String, val sessionId: String)
}

/**
 * The state object the Compose UI consumes. The
 * [workspaces] list is the source of truth (hydrated
 * from the manager on every refresh); [sessionStates]
 * is the live per-session state map (hydrated from
 * the runner on every refresh); [lastActionResult]
 * is the result of the most recent action, so the UI
 * can show a snackbar / error banner without polling
 * the manager.
 */
data class WorkspacesState(
    val workspaces: List<Workspace> = emptyList(),
    val sessionStates: Map<WorkspacesViewModel.SessionKey, SessionState> = emptyMap(),
    val lastActionResult: Result<Workspace>? = null
) {
    companion object {
        val EMPTY = WorkspacesState()
    }
}
