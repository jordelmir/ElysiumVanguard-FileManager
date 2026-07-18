package com.elysium.vanguard.core.runtime.snapshots

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 49 — tests for the filesystem snapshot
 * engine.
 *
 * The engine is the runtime's persistence seam for
 * workspace snapshots. The tests pin:
 *
 *   - Snapshot capture creates the snapshot
 *     directory + manifest + rootfs copy.
 *   - The captured rootfs contains the source's
 *     files (same bytes).
 *   - The chosen copy strategy is one of
 *     [CopyStrategy.HARDLINK] /
 *     [CopyStrategy.FULL_COPY].
 *   - Rollback restores the live rootfs to the
 *     snapshot's state (destructive — the
 *     previous live rootfs is NOT preserved).
 *   - List returns snapshots in chronological
 *     order.
 *   - Delete removes the snapshot directory.
 *   - Edge cases: missing source, missing live
 *     rootfs, blank label, missing snapshot id.
 */
class FilesystemSnapshotEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var sourceRootfs: File
    private lateinit var liveRootfs: File
    private lateinit var engine: FilesystemSnapshotEngine
    private var nextId: Int = 0
    private var nextClock: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("workspaces")
        sourceRootfs = File(baseDir, "ws-1/rootfs").apply {
            mkdirs()
            File(this, "etc").mkdirs()
            File(this, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/sh\n")
            File(this, "etc/hostname").writeText("snapshotted-host\n")
        }
        liveRootfs = File(baseDir, "ws-1/rootfs-live").apply {
            mkdirs()
            File(this, "etc").mkdirs()
            File(this, "etc/passwd").writeText("root:x:0:0:root:/root:/bin/bash\n")
        }
        // Counter-based id generator for
        // determinism (the default uses
        // currentTimeMillis + an AtomicInteger,
        // which is fine for production but
        // produces non-predictable ids in tests).
        nextId = 0
        nextClock = 1_700_000_000_000L
        engine = FilesystemSnapshotEngine(
            baseDir = baseDir,
            clock = { nextClock++ },
            idGenerator = { "snap-test-${++nextId}" }
        )
    }

    private fun makeMountPlan(): MountPlan = MountPlan(
        mounts = listOf(
            MountEntry(
                hostPath = "/sdcard",
                guestPath = "/sdcard",
                readOnly = true,
                label = "android sdcard"
            )
        ),
        env = mapOf("LANG" to "C.UTF-8")
    )

    // --- snapshot ---

    @Test
    fun `snapshot captures the source rootfs and writes a manifest`() {
        val result = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = makeMountPlan(),
            label = "before-config-tweak"
        )
        assertTrue(result is SnapshotResult.Success)
        val snapshot = (result as SnapshotResult.Success).snapshot
        assertEquals("snap-test-1", snapshot.id)
        assertEquals("ws-1", snapshot.workspaceId)
        assertEquals("before-config-tweak", snapshot.label)
        assertEquals(1_700_000_000_000L, snapshot.createdAtMs)
        assertTrue("snapshot rootfs should exist", File(snapshot.rootfsPath).exists())
        assertTrue(
            "manifest should exist",
            File(baseDir, "ws-1/snapshots/snap-test-1/manifest.json").exists()
        )
        // The captured rootfs contains the source's
        // files with the same content.
        val capturedPasswd = File(snapshot.rootfsPath, "etc/passwd")
        assertTrue(capturedPasswd.exists())
        assertEquals("root:x:0:0:root:/root:/bin/sh\n", capturedPasswd.readText())
    }

    @Test
    fun `snapshot records the chosen copy strategy`() {
        val result = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = makeMountPlan(),
            label = "strategy-test"
        )
        val snapshot = (result as SnapshotResult.Success).snapshot
        // The strategy is either HARDLINK (cp -al
        // succeeded) or FULL_COPY (cp -al failed
        // and JVM fallback succeeded). Both are
        // valid outcomes depending on the host.
        assertTrue(
            "strategy must be HARDLINK or FULL_COPY, was ${snapshot.copyStrategy}",
            snapshot.copyStrategy == CopyStrategy.HARDLINK ||
                snapshot.copyStrategy == CopyStrategy.FULL_COPY
        )
    }

    @Test
    fun `snapshot rejects a blank label`() {
        val result = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = MountPlan.EMPTY,
            label = "   "
        )
        assertTrue(result is SnapshotResult.Failure)
        val error = (result as SnapshotResult.Failure).error
        assertTrue(error is SnapshotError.InvalidLabel)
    }

    @Test
    fun `snapshot rejects a missing source rootfs`() {
        val result = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = "/nonexistent/path",
            mountPlan = MountPlan.EMPTY,
            label = "missing-source"
        )
        assertTrue(result is SnapshotResult.Failure)
        val error = (result as SnapshotResult.Failure).error
        assertTrue(error is SnapshotError.SourceNotFound)
    }

    @Test
    fun `snapshot persists the mount plan in the manifest`() {
        val plan = makeMountPlan()
        val result = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = plan,
            label = "with-mounts"
        )
        val snapshot = (result as SnapshotResult.Success).snapshot
        assertEquals(1, snapshot.mountPlan.mounts.size)
        assertEquals("/sdcard", snapshot.mountPlan.mounts[0].hostPath)
        assertEquals("C.UTF-8", snapshot.mountPlan.env["LANG"])
    }

    // --- rollback ---

    @Test
    fun `rollback restores the live rootfs to the snapshot's state`() {
        // Capture the source rootfs state (it has
        // /bin/sh in passwd).
        val snapResult = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = MountPlan.EMPTY,
            label = "rollback-test"
        )
        val snapshot = (snapResult as SnapshotResult.Success).snapshot

        // Modify the live rootfs to a different
        // state (it has /bin/bash in passwd).
        File(liveRootfs, "etc/hostname").writeText("modified-host\n")
        File(liveRootfs, "new-file.txt").writeText("created after snapshot\n")
        assertEquals(
            "modified-host\n",
            File(liveRootfs, "etc/hostname").readText()
        )

        // Rollback.
        val rollbackResult = engine.rollback(
            workspaceId = "ws-1",
            snapshotId = snapshot.id,
            liveRootfsPath = liveRootfs.absolutePath
        )
        assertTrue(rollbackResult is RollbackResult.Success)
        val restored = (rollbackResult as RollbackResult.Success).restoredFrom
        assertEquals(snapshot.id, restored.id)

        // The live rootfs is now in the snapshotted
        // state — NOT the modified state.
        assertEquals("snapshotted-host\n", File(liveRootfs, "etc/hostname").readText())
        assertFalse(
            "files created after the snapshot should be gone after rollback",
            File(liveRootfs, "new-file.txt").exists()
        )
    }

    @Test
    fun `rollback rejects an unknown snapshot id`() {
        val result = engine.rollback(
            workspaceId = "ws-1",
            snapshotId = "snap-no-such",
            liveRootfsPath = liveRootfs.absolutePath
        )
        assertTrue(result is RollbackResult.Failure)
        val error = (result as RollbackResult.Failure).error
        assertTrue(error is SnapshotError.SnapshotNotFound)
    }

    @Test
    fun `rollback rejects a missing live rootfs`() {
        // First, take a snapshot of the source.
        val snapResult = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = MountPlan.EMPTY,
            label = "live-missing"
        )
        val snapshot = (snapResult as SnapshotResult.Success).snapshot

        val result = engine.rollback(
            workspaceId = "ws-1",
            snapshotId = snapshot.id,
            liveRootfsPath = "/nonexistent/live"
        )
        assertTrue(result is RollbackResult.Failure)
        val error = (result as RollbackResult.Failure).error
        assertTrue(error is SnapshotError.LiveRootfsNotFound)
    }

    // --- list ---

    @Test
    fun `list returns snapshots in chronological order`() {
        // Take three snapshots in order.
        engine.snapshot("ws-1", sourceRootfs.absolutePath, MountPlan.EMPTY, "first")
        engine.snapshot("ws-1", sourceRootfs.absolutePath, MountPlan.EMPTY, "second")
        engine.snapshot("ws-1", sourceRootfs.absolutePath, MountPlan.EMPTY, "third")

        val snapshots = engine.list("ws-1")
        assertEquals(3, snapshots.size)
        assertEquals("first", snapshots[0].label)
        assertEquals("second", snapshots[1].label)
        assertEquals("third", snapshots[2].label)
    }

    @Test
    fun `list returns empty when no snapshots exist`() {
        val snapshots = engine.list("ws-no-such")
        assertTrue(snapshots.isEmpty())
    }

    // --- delete ---

    @Test
    fun `delete removes the snapshot directory`() {
        val snapResult = engine.snapshot(
            workspaceId = "ws-1",
            sourceRootfsPath = sourceRootfs.absolutePath,
            mountPlan = MountPlan.EMPTY,
            label = "to-delete"
        )
        val snapshot = (snapResult as SnapshotResult.Success).snapshot

        assertEquals(1, engine.list("ws-1").size)
        assertTrue(engine.delete(snapshot.id))
        assertTrue(engine.list("ws-1").isEmpty())
    }

    @Test
    fun `delete returns false for an unknown snapshot id`() {
        assertFalse(engine.delete("snap-no-such"))
    }

    // --- workspace isolation ---

    @Test
    fun `list returns only the requested workspace's snapshots`() {
        // Create a second workspace with its own
        // source rootfs.
        val otherSource = File(baseDir, "ws-2/rootfs").apply {
            mkdirs()
            File(this, "etc").mkdirs()
            File(this, "etc/passwd").writeText("other:x:1:1:other:/:/bin/sh\n")
        }
        engine.snapshot("ws-1", sourceRootfs.absolutePath, MountPlan.EMPTY, "ws-1-snap")
        engine.snapshot("ws-2", otherSource.absolutePath, MountPlan.EMPTY, "ws-2-snap")

        val ws1List = engine.list("ws-1")
        val ws2List = engine.list("ws-2")
        assertEquals(1, ws1List.size)
        assertEquals("ws-1-snap", ws1List[0].label)
        assertEquals(1, ws2List.size)
        assertEquals("ws-2-snap", ws2List[0].label)
    }
}
