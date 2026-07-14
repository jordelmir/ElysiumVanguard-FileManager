package com.elysium.vanguard.core.runtime.network

import com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 11.1 — End-to-end pipeline test.
 *
 * Wires the full chain on the JVM:
 *
 *   InMemoryGuestDnsObserver
 *       ↓ signalChange(wifi → data)
 *   GuestDnsSessionTracker
 *       ↓ refreshAll
 *   ActiveRootfsRegistry
 *       ↓ invokes the registered closure
 *   NativeProotLauncher.refreshDnsForRootfs
 *       ↓ writes resolv.conf atomically
 *   bind-mounted file
 *
 * The test asserts the *actual* file content changes between the two
 * emissions. If any layer of the chain drops the signal, the assertion
 * fails.
 *
 * `UnconfinedTestDispatcher` + `advanceUntilIdle` keeps the flow
 * collector and the test on the same logical thread, so the assertions
 * are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsRefreshIntegrationTest {

    private lateinit var runtimeDir: File
    private lateinit var rootfs: File
    private lateinit var dnsFile: File

    @Before
    fun setUp() {
        runtimeDir = Files.createTempDirectory("elysium-dns-integration").toFile()
        rootfs = File(runtimeDir, "rootfs").apply { mkdirs() }
        File(runtimeDir, "libproot.so").writeText("proot")
        File(runtimeDir, "libproot_loader.so").writeText("loader")
        // The launcher keys the resolv.conf on `rootfsDir.parentFile.name`
        // (a per-distro unique key derived from the install dir). Mirror
        // that here so we read the same file the launcher writes.
        dnsFile = File(File(runtimeDir, "tmp/dns"), "${runtimeDir.name}.resolv.conf")
    }

    @After
    fun tearDown() {
        runtimeDir.deleteRecursively()
    }

    @Test
    fun `wifi to data flip rewrites the bind-mounted resolv-conf`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val config = ArrayConfig().apply {
            current = GuestDnsConfig(nameservers = listOf("192.0.2.1"))
        }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val library = ProotNativeLibrary.default(
            abis = setOf("arm64-v8a"),
            nativeLibraryDir = runtimeDir,
            userProotDir = null,
            termuxProotCandidates = emptyList()
        )
        val launcher = NativeProotLauncher(
            bundledAbis = setOf("arm64-v8a"),
            nativeLibrary = library,
            runtimeTmpDir = File(runtimeDir, "tmp"),
            guestDnsConfigProvider = observer
        )

        // The launcher writes the initial resolv.conf on first call, so
        // the file is on disk before the tracker ever sees a change.
        launcher.refreshDnsForRootfs(rootfs)
        registry.register(rootfs) { launcher.refreshDnsForRootfs(rootfs) }
        assertTrue(dnsFile.isFile)
        assertTrue(dnsFile.readText().contains("nameserver 192.0.2.1"))

        // Boot the tracker and flip the network. The mobile-network DNS
        // must show up in the bind-mounted file.
        tracker.start()
        advanceUntilIdle()
        config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
        observer.signalChange()
        advanceUntilIdle()

        val updated = dnsFile.readText()
        assertTrue("mobile nameserver must reach the file", updated.contains("nameserver 198.51.100.7"))
        assertFalse("stale Wi-Fi nameserver must be gone", updated.contains("192.0.2.1"))

        tracker.stop()
    }

    @Test
    fun `private DNS change updates search domains without changing nameserver`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val config = ArrayConfig().apply {
            current = GuestDnsConfig(
                nameservers = listOf("9.9.9.9"),
                searchDomains = listOf("home.example")
            )
        }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val library = ProotNativeLibrary.default(
            abis = setOf("arm64-v8a"),
            nativeLibraryDir = runtimeDir,
            userProotDir = null,
            termuxProotCandidates = emptyList()
        )
        val launcher = NativeProotLauncher(
            bundledAbis = setOf("arm64-v8a"),
            nativeLibrary = library,
            runtimeTmpDir = File(runtimeDir, "tmp"),
            guestDnsConfigProvider = observer
        )

        launcher.refreshDnsForRootfs(rootfs)
        registry.register(rootfs) { launcher.refreshDnsForRootfs(rootfs) }
        val initial = dnsFile.readText()
        assertTrue(initial.contains("search home.example"))
        assertTrue(initial.contains("nameserver 9.9.9.9"))

        // User reconfigures Android's private DNS to point at a different
        // search domain; the nameserver stays the same. The tracker
        // must still trigger a refresh because [GuestDnsConfig] changed.
        tracker.start()
        advanceUntilIdle()
        config.current = GuestDnsConfig(
            nameservers = listOf("9.9.9.9"),
            searchDomains = listOf("vpn.example")
        )
        observer.signalChange()
        advanceUntilIdle()

        val updated = dnsFile.readText()
        assertTrue(updated.contains("search vpn.example"))
        assertFalse("old search domain must be gone", updated.contains("home.example"))
        assertTrue(updated.contains("nameserver 9.9.9.9"))

        tracker.stop()
    }

    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
