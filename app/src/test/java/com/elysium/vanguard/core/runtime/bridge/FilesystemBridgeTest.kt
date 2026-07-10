package com.elysium.vanguard.core.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.3 — Tests for the filesystem bridge.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class FilesystemBridgeTest {

    @Test
    fun `mountsFor returns empty list when all inputs are null`() {
        val namespaces = ElysiumNamespaces(
            sdcardPath = null,
            vaultPath = null,
            timeTravelPath = null,
            cloudPath = null
        )
        val mounts = FilesystemBridge.mountsFor(namespaces)
        assertTrue(mounts.isEmpty())
    }

    @Test
    fun `mountsFor exposes sdcard and vault with the correct guest paths`() {
        val sdcard = Files.createTempDirectory("elysium-bridge-sdcard").toFile()
        val vault = Files.createTempDirectory("elysium-bridge-vault").toFile()
        try {
            val namespaces = ElysiumNamespaces(
                sdcardPath = sdcard,
                vaultPath = vault,
                timeTravelPath = null,
                cloudPath = null
            )
            val mounts = FilesystemBridge.mountsFor(namespaces)
            assertEquals(2, mounts.size)
            assertEquals("/sdcard", mounts[0].guestPath)
            assertEquals(sdcard.absolutePath, mounts[0].hostPath)
            assertTrue(mounts[0].readOnly) // sdcard is read-only by policy
            assertEquals("/elysium/vault", mounts[1].guestPath)
            assertEquals(vault.absolutePath, mounts[1].hostPath)
            assertEquals(false, mounts[1].readOnly) // vault must accept writes
        } finally {
            sdcard.deleteRecursively()
            vault.deleteRecursively()
        }
    }

    @Test
    fun `mountsFor includes time-travel and cloud when configured`() {
        val sdcard = Files.createTempDirectory("elysium-bridge-sd").toFile()
        val vault = Files.createTempDirectory("elysium-bridge-va").toFile()
        val timeTravel = Files.createTempDirectory("elysium-bridge-tt").toFile()
        val cloud = Files.createTempDirectory("elysium-bridge-cl").toFile()
        try {
            val namespaces = ElysiumNamespaces(
                sdcardPath = sdcard,
                vaultPath = vault,
                timeTravelPath = timeTravel,
                cloudPath = cloud
            )
            val mounts = FilesystemBridge.mountsFor(namespaces)
            val guests = mounts.map { it.guestPath }
            assertTrue("/sdcard" in guests)
            assertTrue("/elysium/vault" in guests)
            assertTrue("/elysium/time-travel" in guests)
            assertTrue("/elysium/cloud" in guests)
        } finally {
            listOf(sdcard, vault, timeTravel, cloud).forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun `standardMounts is a shortcut for sdcard plus vault only`() {
        val sdcard = Files.createTempDirectory("elysium-bridge-sd").toFile()
        val vault = Files.createTempDirectory("elysium-bridge-va").toFile()
        try {
            val mounts = FilesystemBridge.standardMounts(sdcard, vault)
            assertEquals(2, mounts.size)
            assertEquals("/sdcard", mounts[0].guestPath)
            assertEquals("/elysium/vault", mounts[1].guestPath)
        } finally {
            sdcard.deleteRecursively()
            vault.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `MountEntry rejects a blank host path`() {
        MountEntry(hostPath = "", guestPath = "/sdcard")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `MountEntry rejects a blank guest path`() {
        MountEntry(hostPath = "/foo", guestPath = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `MountEntry rejects a relative guest path`() {
        MountEntry(hostPath = "/foo", guestPath = "vault")
    }

    @Test
    fun `MountEntry defaults readOnly to true`() {
        val entry = MountEntry(hostPath = "/foo", guestPath = "/bar")
        assertTrue(entry.readOnly)
        assertNull(entry.label)
    }
}
