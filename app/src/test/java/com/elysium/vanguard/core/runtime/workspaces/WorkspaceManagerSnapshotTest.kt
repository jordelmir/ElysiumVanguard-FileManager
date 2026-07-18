package com.elysium.vanguard.core.runtime.workspaces

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.snapshots.CopyStrategy
import com.elysium.vanguard.core.runtime.snapshots.MountPlan
import com.elysium.vanguard.core.runtime.snapshots.RollbackResult
import com.elysium.vanguard.core.runtime.snapshots.SnapshotEngine
import com.elysium.vanguard.core.runtime.snapshots.SnapshotError
import com.elysium.vanguard.core.runtime.snapshots.SnapshotResult
import com.elysium.vanguard.core.runtime.snapshots.WorkspaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 49 — tests for [WorkspaceManager]'s
 * snapshot / rollback integration.
 *
 * The manager wraps a [SnapshotEngine] and is
 * responsible for:
 *
 *   - delegating to the engine,
 *   - publishing the appropriate
 *     [RuntimeEvent.SnapshotCreatedEvent] /
 *     [RuntimeEvent.SnapshotRestoredEvent] /
 *     [RuntimeEvent.SnapshotDeletedEvent] on
 *     the bus on success,
 *   - returning typed
 *     [SnapshotError] / [WorkspaceError] on
 *     failure,
 *   - refusing to operate on an unknown
 *     workspace id,
 *   - refusing snapshot operations when no
 *     engine is configured.
 *
 * The tests use a hand-rolled [FakeSnapshotEngine]
 * that records every call in a thread-safe list.
 * The real [FilesystemSnapshotEngine] is exercised
 * in [com.elysium.vanguard.core.runtime.snapshots.FilesystemSnapshotEngineTest].
 */
class WorkspaceManagerSnapshotTest {

    private val store = InMemoryWorkspaceStore()
    private val eventBus = com.elysium.vanguard.core.runtime.observability.RecordingEventBus()
    private val fakeEngine = FakeSnapshotEngine()
    private val manager = WorkspaceManager(
        store = store,
        eventBus = eventBus,
        snapshotEngine = fakeEngine
    )

    // --- snapshot ---

    @Test
    fun `snapshotWorkspace captures the rootfs and publishes SnapshotCreatedEvent`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val fakeSourceRootfs = "/fake/source/rootfs"
        val fakePlan = MountPlan.EMPTY

        val result = manager.snapshotWorkspace(
            workspaceId = ws.id,
            sourceRootfsPath = fakeSourceRootfs,
            mountPlan = fakePlan,
            label = "manual-snap"
        )
        assertTrue(result.isSuccess)
        val snapshot = result.getOrThrow()
        assertEquals("manual-snap", snapshot.label)
        assertEquals(ws.id, snapshot.workspaceId)

        val events = eventBus.events.filterIsInstance<RuntimeEvent.SnapshotCreatedEvent>()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(ws.id, event.workspaceId)
        assertEquals(snapshot.id, event.snapshotId)
        assertEquals("manual-snap", event.label)
        assertEquals("HARDLINK", event.copyStrategy)

        // The engine was called with the right
        // arguments.
        val call = fakeEngine.snapshotCalls.single()
        assertEquals(ws.id, call.workspaceId)
        assertEquals(fakeSourceRootfs, call.sourceRootfsPath)
        assertEquals(fakePlan, call.mountPlan)
        assertEquals("manual-snap", call.label)
    }

    @Test
    fun `snapshotWorkspace returns SnapshotEngineNotConfigured when the manager has no engine`() {
        val bareManager = WorkspaceManager(store, eventBus)
        val ws = bareManager.createWorkspace("Work").getOrThrow()
        val result = bareManager.snapshotWorkspace(
            workspaceId = ws.id,
            sourceRootfsPath = "/fake",
            mountPlan = MountPlan.EMPTY,
            label = "no-engine"
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is WorkspaceError.SnapshotEngineNotConfigured)
    }

    @Test
    fun `snapshotWorkspace returns NotFound for an unknown workspace id`() {
        val result = manager.snapshotWorkspace(
            workspaceId = "ws-does-not-exist",
            sourceRootfsPath = "/fake",
            mountPlan = MountPlan.EMPTY,
            label = "no-workspace"
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is WorkspaceError.NotFound)
    }

    @Test
    fun `snapshotWorkspace does not publish an event when the engine fails`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        fakeEngine.nextSnapshotFailure = SnapshotError.SourceNotFound("/missing")

        val result = manager.snapshotWorkspace(
            workspaceId = ws.id,
            sourceRootfsPath = "/missing",
            mountPlan = MountPlan.EMPTY,
            label = "will-fail"
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SnapshotError.SourceNotFound)
        // No SnapshotCreatedEvent was published.
        assertTrue(
            eventBus.events.none { it is RuntimeEvent.SnapshotCreatedEvent }
        )
    }

    // --- rollback ---

    @Test
    fun `rollbackWorkspace restores and publishes SnapshotRestoredEvent`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val snapshot = makeSnapshot(ws.id, "restore-me")
        fakeEngine.snapshots[ws.id] = listOf(snapshot)

        val result = manager.rollbackWorkspace(
            workspaceId = ws.id,
            snapshotId = snapshot.id,
            liveRootfsPath = "/fake/live"
        )
        assertTrue(result.isSuccess)
        val restored = result.getOrThrow()
        assertEquals(snapshot.id, restored.id)

        val events = eventBus.events.filterIsInstance<RuntimeEvent.SnapshotRestoredEvent>()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(ws.id, event.workspaceId)
        assertEquals(snapshot.id, event.snapshotId)
    }

    @Test
    fun `rollbackWorkspace returns SnapshotNotFound when the engine has no such snapshot`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val result = manager.rollbackWorkspace(
            workspaceId = ws.id,
            snapshotId = "snap-no-such",
            liveRootfsPath = "/fake/live"
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is SnapshotError.SnapshotNotFound)
    }

    // --- list ---

    @Test
    fun `listSnapshots returns the engine's list for the workspace`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val s1 = makeSnapshot(ws.id, "first")
        val s2 = makeSnapshot(ws.id, "second")
        fakeEngine.snapshots[ws.id] = listOf(s1, s2)

        val list = manager.listSnapshots(ws.id)
        assertEquals(listOf(s1, s2), list)
    }

    @Test
    fun `listSnapshots returns empty when the manager has no engine`() {
        val bareManager = WorkspaceManager(store, eventBus)
        val ws = bareManager.createWorkspace("Work").getOrThrow()
        assertTrue(bareManager.listSnapshots(ws.id).isEmpty())
    }

    // --- delete ---

    @Test
    fun `deleteSnapshot publishes SnapshotDeletedEvent on success`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val snapshot = makeSnapshot(ws.id, "to-delete")
        fakeEngine.snapshots[ws.id] = listOf(snapshot)
        fakeEngine.deletedIds[snapshot.id] = true

        val result = manager.deleteSnapshot(ws.id, snapshot.id)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())

        val events = eventBus.events.filterIsInstance<RuntimeEvent.SnapshotDeletedEvent>()
        assertEquals(1, events.size)
        assertEquals(snapshot.id, events[0].snapshotId)
    }

    @Test
    fun `deleteSnapshot does not publish an event when the snapshot did not exist`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        fakeEngine.deletedIds["snap-missing"] = false

        val result = manager.deleteSnapshot(ws.id, "snap-missing")
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
        assertTrue(
            eventBus.events.none { it is RuntimeEvent.SnapshotDeletedEvent }
        )
    }

    // --- helpers ---

    private fun makeSnapshot(workspaceId: String, label: String): WorkspaceSnapshot {
        val counter = AtomicInteger()
        return WorkspaceSnapshot(
            id = "snap-${label}-${counter.incrementAndGet()}",
            workspaceId = workspaceId,
            label = label,
            createdAtMs = 1_700_000_000_000L,
            rootfsPath = "/fake/snapshots/${label}/rootfs",
            mountPlan = MountPlan.EMPTY,
            sizeBytes = 1024L,
            copyStrategy = CopyStrategy.HARDLINK
        )
    }
}

/**
 * Hand-rolled [SnapshotEngine] for unit tests.
 * Records every call in a thread-safe list and
 * returns results from a per-workspace list of
 * snapshots. Tests can pre-load
 * [snapshots] / [deletedIds] and set
 * [nextSnapshotFailure] to simulate engine
 * failures.
 */
internal class FakeSnapshotEngine : SnapshotEngine {
    data class SnapshotCall(
        val workspaceId: String,
        val sourceRootfsPath: String,
        val mountPlan: MountPlan,
        val label: String
    )
    data class RollbackCall(
        val workspaceId: String,
        val snapshotId: String,
        val liveRootfsPath: String
    )

    val snapshotCalls = java.util.Collections.synchronizedList(mutableListOf<SnapshotCall>())
    val rollbackCalls = java.util.Collections.synchronizedList(mutableListOf<RollbackCall>())
    val snapshots = java.util.concurrent.ConcurrentHashMap<String, List<WorkspaceSnapshot>>()
    val deletedIds = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    var nextSnapshotFailure: SnapshotError? = null
    private val counter = AtomicInteger(0)

    override fun snapshot(
        workspaceId: String,
        sourceRootfsPath: String,
        mountPlan: MountPlan,
        label: String,
        nowMs: Long?
    ): SnapshotResult {
        val effectiveNowMs = nowMs ?: System.currentTimeMillis()
        snapshotCalls += SnapshotCall(workspaceId, sourceRootfsPath, mountPlan, label)
        nextSnapshotFailure?.let {
            nextSnapshotFailure = null
            return SnapshotResult.Failure(it)
        }
        val id = "snap-fake-${counter.incrementAndGet()}"
        val snap = WorkspaceSnapshot(
            id = id,
            workspaceId = workspaceId,
            label = label,
            createdAtMs = effectiveNowMs,
            rootfsPath = "$sourceRootfsPath/snapshots/$id",
            mountPlan = mountPlan,
            sizeBytes = 0L,
            copyStrategy = CopyStrategy.HARDLINK
        )
        snapshots[workspaceId] = (snapshots[workspaceId] ?: emptyList()) + snap
        return SnapshotResult.Success(snap)
    }

    override fun rollback(
        workspaceId: String,
        snapshotId: String,
        liveRootfsPath: String
    ): RollbackResult {
        rollbackCalls += RollbackCall(workspaceId, snapshotId, liveRootfsPath)
        val snap = snapshots[workspaceId].orEmpty().firstOrNull { it.id == snapshotId }
            ?: return RollbackResult.Failure(SnapshotError.SnapshotNotFound(snapshotId))
        return RollbackResult.Success(snap)
    }

    override fun list(workspaceId: String): List<WorkspaceSnapshot> =
        snapshots[workspaceId].orEmpty().sortedBy { it.createdAtMs }

    override fun delete(snapshotId: String): Boolean =
        deletedIds[snapshotId] ?: false
}
