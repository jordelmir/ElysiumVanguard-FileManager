package com.elysium.vanguard.core.runtime.snapshots

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 49 — value-type invariant tests for
 * [WorkspaceSnapshot] + [CopyStrategy] +
 * [MountPlan].
 *
 * These are pure JVM tests; no filesystem, no
 * Android. The data classes are the contract the
 * engine + manager use, and the init blocks are
 * the only enforcement of the contract. A test
 * caught a real bug in the past (a duplicate
 * guest path slipped through once), so the
 * invariant coverage is intentional.
 */
class WorkspaceSnapshotTest {

    // --- WorkspaceSnapshot invariants ---

    @Test
    fun `WorkspaceSnapshot rejects a blank id`() {
        try {
            makeSnapshot(id = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WorkspaceSnapshot rejects a blank workspaceId`() {
        try {
            makeSnapshot(workspaceId = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WorkspaceSnapshot rejects a blank label`() {
        try {
            makeSnapshot(label = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WorkspaceSnapshot rejects a blank rootfsPath`() {
        try {
            makeSnapshot(rootfsPath = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WorkspaceSnapshot rejects a negative sizeBytes`() {
        try {
            makeSnapshot(sizeBytes = -1L)
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `WorkspaceSnapshot accepts a zero sizeBytes (unknown size)`() {
        val snap = makeSnapshot(sizeBytes = 0L)
        assertEquals(0L, snap.sizeBytes)
    }

    @Test
    fun `WorkspaceSnapshot toString returns a non-default JVM form`() {
        val snap = makeSnapshot()
        val rendered = snap.toString()
        // The default JVM toString leaks the FQN
        // (e.g. "WorkspaceSnapshot@1a2b3c4d"). The
        // override returns a structured form.
        assertTrue(
            "toString should not leak the FQN: $rendered",
            !rendered.contains("WorkspaceSnapshot@")
        )
        assertTrue(
            "toString should mention the label: $rendered",
            rendered.contains("before-config-tweak")
        )
    }

    // --- MountPlan invariants ---

    @Test
    fun `MountPlan rejects duplicate guest paths`() {
        try {
            MountPlan(
                mounts = listOf(
                    MountEntry(
                        hostPath = "/sdcard",
                        guestPath = "/sdcard",
                        readOnly = true
                    ),
                    MountEntry(
                        hostPath = "/other-sdcard",
                        guestPath = "/sdcard",
                        readOnly = true
                    )
                )
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `MountPlan EMPTY is an empty list and an empty env`() {
        val empty = MountPlan.EMPTY
        assertEquals(0, empty.mounts.size)
        assertEquals(0, empty.env.size)
    }

    // --- helpers ---

    private fun makeSnapshot(
        id: String = "snap-1",
        workspaceId: String = "ws-1",
        label: String = "before-config-tweak",
        rootfsPath: String = "/fake/snapshot/rootfs",
        sizeBytes: Long = 1024L,
        copyStrategy: CopyStrategy = CopyStrategy.HARDLINK
    ) = WorkspaceSnapshot(
        id = id,
        workspaceId = workspaceId,
        label = label,
        createdAtMs = 1_700_000_000_000L,
        rootfsPath = rootfsPath,
        mountPlan = MountPlan.EMPTY,
        sizeBytes = sizeBytes,
        copyStrategy = copyStrategy
    )
}
