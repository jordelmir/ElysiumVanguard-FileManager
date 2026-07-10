package com.elysium.vanguard.core.runtime.distros.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.3.1 — Tests for [RootfsSnapshot].
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
class RootfsSnapshotTest {

    private fun buildFakeRootfs(): Pair<File, File> {
        val base = Files.createTempDirectory("elysium-snap-base").toFile()
        val sourceId = "fake-distro"
        val sourceDir = File(File(base, sourceId), "rootfs").apply {
            mkdirs()
            child("etc").mkdirs()
            child("etc/os-release").writeText("NAME=fake")
            child("bin").mkdirs()
            child("bin/sh").writeText("#!/bin/sh")
            // Add a few files for byte counting.
            child("usr/bin").mkdirs()
            child("usr/bin/ls").writeText("ls fake")
            child("usr/bin/cat").writeText("cat fake")
        }
        return base to sourceDir
    }

    private fun File.child(child: String): File = File(this, child)

    @Test
    fun `SnapshotIds produces sortable ids`() {
        val id1 = SnapshotIds.next("alpine-latest", epochMs = 1_700_000_000_000L)
        val id2 = SnapshotIds.next("alpine-latest", epochMs = 1_800_000_000_000L)
        // Lexicographic order matches epoch-ms order.
        assertTrue(id1 < id2)
        assertTrue(id1.startsWith("alpine-latest@"))
    }

    @Test
    fun `capture clones the rootfs under a snapshot directory`() {
        val (base, _) = buildFakeRootfs()
        try {
            val snap = RootfsSnapshot(base)
            val result = snap.capture("fake-distro")
            assertTrue(result.isComplete)
            assertEquals("fake-distro", result.sourceId)
            assertTrue(result.id.startsWith("fake-distro@"))
            val srcDir = File(File(base, "fake-distro"), "rootfs")
            val dstDir = result.rootfsDir
            // Symlinks may or may not survive; check regular files.
            assertTrue(File(dstDir, "etc/os-release").isFile)
            assertTrue(File(dstDir, "bin/sh").isFile)
            assertEquals("ls fake", File(dstDir, "usr/bin/ls").readText())
            assertTrue(result.bytesCopied > 0)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `capture fails cleanly when source rootfs is missing`() {
        val base = Files.createTempDirectory("elysium-snap-empty").toFile()
        try {
            val snap = RootfsSnapshot(base)
            try {
                snap.capture("nonexistent")
                throw AssertionError("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                // No phantom snapshot directories left behind.
                assertEquals(0, base.listFiles()?.size ?: 0)
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `remove deletes the snapshot directory`() {
        val (base, _) = buildFakeRootfs()
        try {
            val snap = RootfsSnapshot(base)
            val result = snap.capture("fake-distro")
            assertTrue(result.isComplete)
            assertTrue(snap.remove(result.id))
            assertFalse(File(base, result.id).exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `list returns snapshots sorted newest first`() {
        val (base, _) = buildFakeRootfs()
        try {
            val snap = RootfsSnapshot(base)
            // Capture three snapshots with explicit, monotonic timestamps.
            val ids = (0 until 3).map { i ->
                val pickedEpoch = 1_700_000_000_000L + i * 60_000L
                val id = SnapshotIds.next("fake-distro", epochMs = pickedEpoch)
                // Manually craft a snapshot by copying — we don't want
                // timing flakes, so we just create directories of the
                // right shape and let `list()` discover them.
                File(File(base, id), "rootfs").mkdirs()
                File(File(base, id), "rootfs/marker").writeText("x")
                id
            }
            val listed = snap.list()
            // Sorted descending: highest id first.
            assertEquals(ids.reversed(), listed.map { it.id })
        } finally {
            base.deleteRecursively()
        }
    }
}
