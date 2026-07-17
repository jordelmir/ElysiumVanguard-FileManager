package com.elysium.vanguard.core.runtime.ui

import com.elysium.vanguard.core.runtime.distros.DistroInstallProgress
import com.elysium.vanguard.core.runtime.distros.DistroInstallStage
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.observability.RecordingEventBus
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.windows.WindowsVmManager
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 28 — tests for the [MainScreenViewModel].
 *
 * The ViewModel is the JVM-testable seam between the
 * runtime's collaborators and the Compose UI. The tests
 * pin:
 *
 *   - The ViewModel composes the four collaborators
 *     (WorkspaceManager + DistroManager +
 *     WindowsVmManager + RuntimeEventBus) into a single
 *     [MainScreenState].
 *   - `refresh()` rebuilds the state from the current
 *     collaborator state.
 *   - Bus events append to the recent-events buffer;
 *     the buffer is capped at the configured capacity.
 *   - The ViewModel is closeable; closing unsubscribes
 *     from the bus.
 *   - The ViewModel is thread-safe under concurrent
 *     bus emissions and refreshes.
 */
class MainScreenViewModelTest {

    private val workspaceStore = com.elysium.vanguard.core.runtime.workspaces.InMemoryWorkspaceStore()
    private val workspaceManager = WorkspaceManager(workspaceStore)
    private val distroManager = DistroManager(
        baseDir = java.nio.file.Files.createTempDirectory("elysium-ui").toFile(),
        downloader = com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader { _ ->
            error("downloader must not be called")
        }
    )
    private val windowsVmBackend =
        com.elysium.vanguard.core.runtime.windows.InMemoryWindowsVmBackend()
    private val windowsVmManager = WindowsVmManager(
        baseDir = java.nio.file.Files.createTempDirectory("elysium-ui-wvm").toFile(),
        backend = windowsVmBackend
    )
    private val eventBus: RuntimeEventBus = RecordingEventBus()

    // --- initial state ---

    @Test
    fun `MainScreenState EMPTY is the initial state`() {
        MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = eventBus,
            recentEventsCapacity = 5
        ).use { vm ->
            val state = vm.state.value
            assertEquals(emptyList<WorkspaceSummary>(), state.workspaces)
            assertEquals(0, state.linuxDistrosInstalled)
            assertEquals(0, state.windowsVmsRunning)
            assertEquals(emptyList<RuntimeEvent>(), state.recentEvents)
        }
    }

    // --- refresh ---

    @Test
    fun `refresh populates the workspaces list from the manager`() {
        workspaceManager.createWorkspace("Work", listOf(
            WorkspaceSession.LinuxProot("s-1", "Debian", "debian-latest", "balanced"),
            WorkspaceSession.LinuxProot("s-2", "Debian 2", "debian-latest", "balanced"),
            WorkspaceSession.WindowsVm("w-1", "Win11", "win11-pro-23h2")
        ))
        MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = eventBus,
            recentEventsCapacity = 5
        ).use { vm ->
            vm.refresh()
            val state = vm.state.value
            assertEquals(1, state.workspaces.size)
            val ws = state.workspaces.single()
            assertEquals("Work", ws.name)
            assertEquals(WorkspaceState.Active, ws.state)
            assertEquals(3, ws.sessionCount)
            assertEquals(2, ws.linuxProotCount)
            assertEquals(1, ws.windowsVmCount)
        }
    }

    @Test
    fun `refresh picks up new workspaces created after the ViewModel was built`() {
        MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = eventBus,
            recentEventsCapacity = 5
        ).use { vm ->
            assertEquals(0, vm.state.value.workspaces.size)
            workspaceManager.createWorkspace("Late", listOf(
                WorkspaceSession.LinuxProot("s-late", "Late", "alpine-latest", "lite")
            ))
            vm.refresh()
            assertEquals(1, vm.state.value.workspaces.size)
            assertEquals("Late", vm.state.value.workspaces.single().name)
        }
    }

    // --- bus events ---

    @Test
    fun `bus events append to the recent events buffer`() {
        MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = eventBus,
            recentEventsCapacity = 5
        ).use { vm ->
            val recorder = eventBus as RecordingEventBus
            repeat(3) { i ->
                recorder.publish(workspaceEvent(atMs = i.toLong()))
            }
            // The bus's subscriber is async; the JVM test
            // path is synchronous (CopyOnWriteArrayList).
            // The state is updated on the publisher's
            // thread, so by the time `publish` returns, the
            // state is current.
            val recent = vm.state.value.recentEvents
            assertEquals(3, recent.size)
            assertEquals(0L, recent[0].atMs)
            assertEquals(2L, recent[2].atMs)
        }
    }

    @Test
    fun `recent events buffer is capped at the configured capacity`() {
        MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = eventBus,
            recentEventsCapacity = 3
        ).use { vm ->
            val recorder = eventBus as RecordingEventBus
            repeat(10) { i ->
                recorder.publish(workspaceEvent(atMs = i.toLong()))
            }
            val recent = vm.state.value.recentEvents
            assertEquals("buffer must be capped at 3", 3, recent.size)
            // The most recent three are atMs 7, 8, 9.
            assertEquals(7L, recent[0].atMs)
            assertEquals(8L, recent[1].atMs)
            assertEquals(9L, recent[2].atMs)
        }
    }

    // --- close ---

    @Test
    fun `close unsubscribes the ViewModel from the bus`() {
        val recorder = eventBus as RecordingEventBus
        val vm = MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = recorder,
            recentEventsCapacity = 5
        )
        assertEquals(1, recorder.subscriberCount())
        vm.close()
        assertEquals("close must unsubscribe", 0, recorder.subscriberCount())
    }

    // --- thread safety ---

    @Test
    fun `ViewModel is thread-safe under concurrent bus publishes and refreshes`() {
        val recorder = eventBus as RecordingEventBus
        MainScreenViewModel(
            workspaceManager = workspaceManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = recorder,
            recentEventsCapacity = 50
        ).use { vm ->
            val start = CountDownLatch(1)
            val pubDone = CountDownLatch(4)
            val refDone = CountDownLatch(4)
            repeat(4) { i ->
                Thread {
                    start.await()
                    repeat(50) {
                        recorder.publish(workspaceEvent(atMs = (i * 50 + it).toLong()))
                    }
                    pubDone.countDown()
                }.start()
            }
            repeat(4) {
                Thread {
                    start.await()
                    repeat(20) { vm.refresh() }
                    refDone.countDown()
                }.start()
            }
            start.countDown()
            assertTrue(pubDone.await(15, TimeUnit.SECONDS))
            assertTrue(refDone.await(15, TimeUnit.SECONDS))
            // 4 threads × 50 events = 200 events. The
            // buffer is capped at 50.
            val recent = vm.state.value.recentEvents
            assertTrue("buffer must be capped at 50", recent.size <= 50)
        }
    }

    // --- WorkspaceSummary derived fields ---

    @Test
    fun `WorkspaceSummary session counts are correct across kinds`() {
        // We need a manager that has the workspace in its
        // in-memory `byId` map. The store is shared, so a
        // fresh manager hydrates from the saved workspace.
        val freshStore = com.elysium.vanguard.core.runtime.workspaces.InMemoryWorkspaceStore()
        freshStore.save(
            Workspace(
                id = "ws-1",
                name = "Mixed",
                createdAtMs = 1L,
                sessions = listOf(
                    WorkspaceSession.LinuxProot("s-1", "L1", "d", "balanced"),
                    WorkspaceSession.LinuxProot("s-2", "L2", "d", "balanced"),
                    WorkspaceSession.LinuxProot("s-3", "L3", "d", "balanced"),
                    WorkspaceSession.WindowsVm("w-1", "W1", "win11"),
                    WorkspaceSession.WindowsVm("w-2", "W2", "win10")
                )
            )
        )
        val freshManager = WorkspaceManager(freshStore)
        MainScreenViewModel(
            workspaceManager = freshManager,
            distroManager = distroManager,
            windowsVmManager = windowsVmManager,
            eventBus = eventBus,
            recentEventsCapacity = 5
        ).use { vm ->
            val summary = vm.state.value.workspaces.single()
            assertEquals(5, summary.sessionCount)
            assertEquals(3, summary.linuxProotCount)
            assertEquals(2, summary.windowsVmCount)
        }
    }

    // --- helpers ---

    private fun workspaceEvent(atMs: Long): RuntimeEvent =
        RuntimeEvent.WorkspaceStateChangedEvent(
            atMs = atMs,
            workspaceId = "ws-1",
            fromState = "Active",
            toState = "Paused"
        )
}
