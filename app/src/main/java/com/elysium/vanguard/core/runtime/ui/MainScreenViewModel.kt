package com.elysium.vanguard.core.runtime.ui

import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.runner.SessionRunner
import com.elysium.vanguard.core.runtime.windows.WindowsVmManager
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceState
import com.elysium.vanguard.core.runtime.MainScreenRecentEventsCapacity
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 28 — the runtime's main-screen orchestrator.
 *
 * The [MainScreenViewModel] is the JVM-testable seam the
 * Compose UI consumes. It composes:
 *
 *   - [WorkspaceManager] — the source of workspace
 *     state (Phase 24).
 *   - [DistroManager] — the source of Linux install
 *     state (Phase 17).
 *   - [WindowsVmManager] — the source of Windows VM
 *     state (Phase 22).
 *   - [SessionRunner] — the source of the live-session
 *     count (Phase 32 registry).
 *   - [RuntimeEventBus] — the observability bus the
 *     ViewModel subscribes to (Phase 25).
 *
 * The ViewModel is the **testable** form of the UI's
 * business logic. The actual Compose UI is a thin
 * consumer of [state]; the ViewModel does the work of
 * translating the four collaborators' state into a
 * single `MainScreenState` snapshot the UI renders.
 *
 * The ViewModel is `AndroidViewModel`-free: it does
 * not depend on `Application`, `Context`, or any
 * `androidx.lifecycle` API. A future Compose-side
 * adapter wires the StateFlow to a
 * `viewModelScope.launch { state.collect { ... } }`.
 *
 * Phase 34 — added [SessionRunner] as a fifth
 * collaborator; [MainScreenState] gained a
 * [MainScreenState.runningSessionCount] field that
 * reflects the runner's `activeCount()` (live
 * Linux + Windows sessions, any kind).
 *
 * Phase 36 — annotated `@HiltViewModel` + `@Inject
 * constructor`. The five collaborators are now Hilt
 * singletons (see [com.elysium.vanguard.core.runtime.RuntimeModule]).
 * Tests construct the ViewModel directly with the
 * same collaborators (no Hilt).
 */
@HiltViewModel
class MainScreenViewModel @Inject constructor(
    private val workspaceManager: WorkspaceManager,
    private val distroManager: DistroManager,
    private val windowsVmManager: WindowsVmManager,
    private val sessionRunner: SessionRunner,
    private val eventBus: RuntimeEventBus,
    @MainScreenRecentEventsCapacity private val recentEventsCapacity: Int
) : ViewModel(), AutoCloseable {

    private val _state = MutableStateFlow(MainScreenState.EMPTY)
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    private val subscription: AutoCloseable = eventBus.subscribe { event ->
        handleEvent(event)
    }

    init {
        refresh()
    }

    /**
     * Re-read every collaborator's state and rebuild the
     * [MainScreenState]. Cheap enough to call on every
     * event bus emission in production; the test suite
     * exercises it directly.
     */
    fun refresh() {
        val current = _state.value
        _state.value = current.copy(
            workspaces = workspaceManager.listWorkspaces().map { it.toSummary() },
            linuxDistrosInstalled = distroManager.installed.value.size,
            linuxDistrosInstalling = distroManager.installing.value.size,
            windowsVmsRunning = windowsVmManager.listRunning().size,
            runningSessionCount = sessionRunner.activeCount()
        )
    }

    /**
     * Append [event] to the recent-events buffer; trim to
     * [recentEventsCapacity]. The buffer is the UI's
     * "what just happened" feed. A session lifecycle
     * event also triggers a full refresh so the
     * [MainScreenState.runningSessionCount] stays in
     * sync with the runner.
     */
    private fun handleEvent(event: RuntimeEvent) {
        val current = _state.value
        val updatedEvents = (current.recentEvents + event)
            .takeLast(recentEventsCapacity)
        val updated = current.copy(recentEvents = updatedEvents)
        when (event) {
            is RuntimeEvent.SessionStartedEvent,
            is RuntimeEvent.SessionStoppedEvent,
            is RuntimeEvent.SessionStartFailedEvent -> {
                // Re-read the runner's active count.
                _state.value = updated.copy(
                    runningSessionCount = sessionRunner.activeCount()
                )
            }
            else -> {
                _state.value = updated
            }
        }
    }

    /**
     * Force a refresh; the bus may have been emitting
     * while the ViewModel was paused (process death,
     * background). The UI calls this on `onResume`.
     */
    fun forceRefresh() = refresh()

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
     * unchanged from Phase 28).
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

    private fun Workspace.toSummary(): WorkspaceSummary {
        val linuxCount = sessions.count { it is WorkspaceSession.LinuxProot }
        val windowsCount = sessions.count { it is WorkspaceSession.WindowsVm }
        return WorkspaceSummary(
            id = id,
            name = name,
            state = state,
            createdAtMs = createdAtMs,
            sessionCount = sessions.size,
            linuxProotCount = linuxCount,
            windowsVmCount = windowsCount
        )
    }
}

/**
 * The single state object the Compose UI renders. Every
 * field is a value type (no `LiveData`, no `Context`);
 * the UI subscribes via [MainScreenViewModel.state] and
 * re-renders on each new value.
 *
 * Phase 34 — `runningSessionCount` is the runner's
 * `activeCount()` (live Linux + Windows sessions, any
 * kind). The UI status bar uses this to render "N
 * sessions running".
 */
data class MainScreenState(
    val workspaces: List<WorkspaceSummary> = emptyList(),
    val linuxDistrosInstalled: Int = 0,
    val linuxDistrosInstalling: Int = 0,
    val windowsVmsRunning: Int = 0,
    val runningSessionCount: Int = 0,
    val recentEvents: List<RuntimeEvent> = emptyList()
) {
    companion object {
        val EMPTY = MainScreenState()
    }
}

/**
 * A workspace's render-ready summary. The UI uses this
 * to populate the workspace list (name, state, session
 * counts).
 */
data class WorkspaceSummary(
    val id: String,
    val name: String,
    val state: WorkspaceState,
    val createdAtMs: Long,
    val sessionCount: Int,
    val linuxProotCount: Int,
    val windowsVmCount: Int
)
