package com.elysium.vanguard.core.runtime.network

import com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 11.4 — Distro session registry unit tests.
 *
 * This is the bridge between "a session is alive on this rootfs" and
 * "the DNS refresh pipeline has a place to land". The tests pin the
 * two-end contract: the closure registered by [DistroSessionRegistry]
 * calls the launcher's [NativeProotLauncher.refreshDnsForRootfs] when
 * the registry refreshes, and the file content on disk reflects the
 * current Android network state.
 */
class DistroSessionRegistryTest {

    private lateinit var runtimeDir: File
    private lateinit var rootfs: File
    private lateinit var registry: ActiveRootfsRegistry
    private lateinit var launcher: NativeProotLauncher
    private lateinit var config: ArrayConfig
    private lateinit var observer: InMemoryGuestDnsObserver
    private lateinit var distroSessionRegistry: DistroSessionRegistry
    private lateinit var dnsFile: File

    @Before
    fun setUp() {
        runtimeDir = Files.createTempDirectory("elysium-distro-registry").toFile()
        rootfs = File(runtimeDir, "rootfs").apply { mkdirs() }
        File(runtimeDir, "libproot.so").writeText("proot")
        File(runtimeDir, "libproot_loader.so").writeText("loader")
        dnsFile = File(File(runtimeDir, "tmp/dns"), "${runtimeDir.name}.resolv.conf")

        config = ArrayConfig().apply {
            current = GuestDnsConfig(nameservers = listOf("192.0.2.1"))
        }
        observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        registry = ActiveRootfsRegistry()
        val library = ProotNativeLibrary.default(
            abis = setOf("arm64-v8a"),
            nativeLibraryDir = runtimeDir,
            userProotDir = null,
            termuxProotCandidates = emptyList()
        )
        launcher = NativeProotLauncher(
            bundledAbis = setOf("arm64-v8a"),
            nativeLibrary = library,
            runtimeTmpDir = File(runtimeDir, "tmp"),
            guestDnsConfigProvider = observer
        )
        distroSessionRegistry = DistroSessionRegistry(registry, launcher)
    }

    @Test
    fun `onSessionStarted makes the rootfs a refresh target`() {
        distroSessionRegistry.onSessionStarted(rootfs)

        assertTrue(
            "rootfs must appear in the registry after onSessionStarted",
            rootfs in registry.activeRootfses()
        )
    }

    @Test
    fun `onSessionStopped removes the rootfs from refresh targets`() {
        distroSessionRegistry.onSessionStarted(rootfs)
        distroSessionRegistry.onSessionStopped(rootfs)

        assertTrue(
            "rootfs must NOT appear in the registry after onSessionStopped",
            rootfs !in registry.activeRootfses()
        )
    }

    @Test
    fun `network change while a session is active refreshes the bind mount`() {
        // The launcher writes the initial resolv.conf as part of
        // refreshDnsForRootfs. Register the rootfs, then change the
        // DNS, then call refreshAll() to simulate the tracker firing.
        distroSessionRegistry.onSessionStarted(rootfs)
        // First call seeds the file.
        registry.refreshAll()
        assertTrue("seed file should exist", dnsFile.isFile)
        assertTrue(dnsFile.readText().contains("nameserver 192.0.2.1"))

        // Simulate a Wi-Fi -> data flip.
        config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
        // The tracker would call refreshAll; we mimic it here.
        val failures = registry.refreshAll()
        assertTrue("no failures expected", failures.isEmpty())
        assertTrue(
            "mobile nameserver must reach the file",
            dnsFile.readText().contains("nameserver 198.51.100.7")
        )
    }

    @Test
    fun `network change after onSessionStopped does not refresh the file`() {
        distroSessionRegistry.onSessionStarted(rootfs)
        registry.refreshAll()
        val sizeBefore = dnsFile.length()
        val contentBefore = dnsFile.readText()

        distroSessionRegistry.onSessionStopped(rootfs)
        assertTrue("rootfs removed", rootfs !in registry.activeRootfses())

        // Simulate a network change after the session ended.
        config.current = GuestDnsConfig(nameservers = listOf("203.0.113.4"))
        val failures = registry.refreshAll()
        assertTrue("no failures expected", failures.isEmpty())
        // File unchanged because no one called refreshDnsForRootfs.
        assertEquals(contentBefore, dnsFile.readText())
        assertEquals(sizeBefore, dnsFile.length())
    }

    @Test
    fun `onSessionStopped is a no-op when the rootfs was never registered`() {
        // No exception, no entry in the registry.
        distroSessionRegistry.onSessionStopped(rootfs)
        assertTrue(registry.activeRootfses().isEmpty())
    }

    @Test
    fun `re-registering the same rootfs replaces the previous closure`() {
        distroSessionRegistry.onSessionStarted(rootfs)
        // Second call is idempotent (same closure).
        distroSessionRegistry.onSessionStarted(rootfs)
        // Active set still has exactly one entry for the rootfs.
        assertEquals(setOf(rootfs), registry.activeRootfses())
    }

    @Test
    fun `end-to-end - register, network flip, unregister, network flip again`() {
        // 1. Register
        distroSessionRegistry.onSessionStarted(rootfs)
        // 2. Initial DNS lands
        registry.refreshAll()
        assertTrue(dnsFile.readText().contains("nameserver 192.0.2.1"))

        // 3. Wi-Fi -> data while session is alive
        config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
        registry.refreshAll()
        assertTrue(dnsFile.readText().contains("nameserver 198.51.100.7"))

        // 4. Session ends
        distroSessionRegistry.onSessionStopped(rootfs)

        // 5. Another network change while no session is alive
        config.current = GuestDnsConfig(nameservers = listOf("203.0.113.4"))
        registry.refreshAll()
        // The file still holds the last live value because nothing
        // called refreshDnsForRootfs after step 4.
        assertTrue(dnsFile.readText().contains("198.51.100.7"))
        assertTrue(
            "stale value must persist when no session is active",
            !dnsFile.readText().contains("203.0.113.4")
        )
    }

    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
