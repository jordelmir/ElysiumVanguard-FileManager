package com.elysium.vanguard.core.runtime.distros.launcher

import com.elysium.vanguard.core.runtime.network.GuestDnsConfig
import com.elysium.vanguard.core.runtime.network.InMemoryGuestDnsObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 11.x — Native PRoot launcher DNS refresh behavior.
 *
 * Master order §10.1 demands that the guest's resolver follow the device's
 * active network across Wi-Fi / data / VPN transitions. The launcher must
 * therefore expose a way to re-derive the bind-mounted resolv.conf without
 * rebuilding the entire proot command.
 *
 * [NativeProotLauncher.refreshDnsForRootfs] is the seam. These tests pin
 * its contract.
 */
class NativeProotLauncherDnsRefreshTest {

    @Test
    fun `refresh writes the latest snapshot to the bind-mounted resolv-conf`() {
        val runtimeDir = Files.createTempDirectory("elysium-proot-refresh").toFile()
        val rootfs = File(runtimeDir, "rootfs").apply { mkdirs() }
        try {
            File(runtimeDir, "libproot.so").writeText("proot")
            File(runtimeDir, "libproot_loader.so").writeText("loader")
            val library = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                nativeLibraryDir = runtimeDir,
                userProotDir = null,
                termuxProotCandidates = emptyList()
            )
            val config = ArrayConfig().apply {
                current = GuestDnsConfig(nameservers = listOf("192.0.2.1"))
            }
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val launcher = NativeProotLauncher(
                bundledAbis = setOf("arm64-v8a"),
                nativeLibrary = library,
                runtimeTmpDir = File(runtimeDir, "tmp"),
                guestDnsConfigProvider = observer
            )

            // First refresh uses the Wi-Fi DNS.
            val file = launcher.refreshDnsForRootfs(rootfs)
            assertNotNull("refresh must return a file when nameservers are present", file)
            assertTrue(file!!.readText().contains("nameserver 192.0.2.1"))

            // Simulate a Wi-Fi → data flip.
            config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
            val second = launcher.refreshDnsForRootfs(rootfs)
            assertNotNull(second)
            assertEquals(file.absolutePath, second!!.absolutePath)
            assertTrue(second.readText().contains("nameserver 198.51.100.7"))
            assertFalse(
                "the stale Wi-Fi server must not survive the refresh",
                second.readText().contains("192.0.2.1")
            )
        } finally {
            runtimeDir.deleteRecursively()
        }
    }

    @Test
    fun `refresh returns null when there is no active network`() {
        val runtimeDir = Files.createTempDirectory("elysium-proot-refresh-empty").toFile()
        val rootfs = File(runtimeDir, "rootfs").apply { mkdirs() }
        try {
            File(runtimeDir, "libproot.so").writeText("proot")
            File(runtimeDir, "libproot_loader.so").writeText("loader")
            val library = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                nativeLibraryDir = runtimeDir,
                userProotDir = null,
                termuxProotCandidates = emptyList()
            )
            val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
            val launcher = NativeProotLauncher(
                bundledAbis = setOf("arm64-v8a"),
                nativeLibrary = library,
                runtimeTmpDir = File(runtimeDir, "tmp"),
                guestDnsConfigProvider = observer
            )

            assertNull(launcher.refreshDnsForRootfs(rootfs))
        } finally {
            runtimeDir.deleteRecursively()
        }
    }

    @Test
    fun `refresh tolerates repeated identical snapshots`() {
        val runtimeDir = Files.createTempDirectory("elysium-proot-refresh-stable").toFile()
        val rootfs = File(runtimeDir, "rootfs").apply { mkdirs() }
        try {
            File(runtimeDir, "libproot.so").writeText("proot")
            File(runtimeDir, "libproot_loader.so").writeText("loader")
            val library = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                nativeLibraryDir = runtimeDir,
                userProotDir = null,
                termuxProotCandidates = emptyList()
            )
            val config = ArrayConfig().apply {
                current = GuestDnsConfig(nameservers = listOf("192.0.2.99"))
            }
            val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
            val launcher = NativeProotLauncher(
                bundledAbis = setOf("arm64-v8a"),
                nativeLibrary = library,
                runtimeTmpDir = File(runtimeDir, "tmp"),
                guestDnsConfigProvider = observer
            )

            val first = launcher.refreshDnsForRootfs(rootfs)
            val second = launcher.refreshDnsForRootfs(rootfs)
            assertNotNull(first)
            assertNotNull(second)
            assertEquals(first!!.absolutePath, second!!.absolutePath)
            assertTrue(second.readText().contains("nameserver 192.0.2.99"))
        } finally {
            runtimeDir.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refresh rejects a missing rootfs directory`() {
        val runtimeDir = Files.createTempDirectory("elysium-proot-refresh-bad").toFile()
        try {
            File(runtimeDir, "libproot.so").writeText("proot")
            File(runtimeDir, "libproot_loader.so").writeText("loader")
            val library = ProotNativeLibrary.default(
                abis = setOf("arm64-v8a"),
                nativeLibraryDir = runtimeDir,
                userProotDir = null,
                termuxProotCandidates = emptyList()
            )
            val observer = InMemoryGuestDnsObserver(snapshot = {
                GuestDnsConfig(nameservers = listOf("192.0.2.1"))
            })
            val launcher = NativeProotLauncher(
                bundledAbis = setOf("arm64-v8a"),
                nativeLibrary = library,
                runtimeTmpDir = File(runtimeDir, "tmp"),
                guestDnsConfigProvider = observer
            )

            launcher.refreshDnsForRootfs(File("/no/such/elysium/rootfs"))
        } finally {
            runtimeDir.deleteRecursively()
        }
    }

    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
