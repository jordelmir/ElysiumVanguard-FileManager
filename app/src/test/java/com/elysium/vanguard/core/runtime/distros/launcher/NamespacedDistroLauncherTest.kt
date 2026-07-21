package com.elysium.vanguard.core.runtime.distros.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 102 — JVM tests for [NamespacedDistroLauncher].
 *
 * Uses a [FakeRootedModeProbe] that records its calls so we
 * can assert on the probe-driven behavior of the launcher
 * without spawning real `su`.
 */
class NamespacedDistroLauncherTest {

    @Test
    fun `kind is NAMESPACE_UNSHARE and capabilities require root`() {
        val launcher = NamespacedDistroLauncher(FakeRootedModeProbe.fullyRooted())
        assertEquals(LauncherKind.NAMESPACE_UNSHARE, launcher.kind)
        assertTrue(launcher.capabilities.requiresRoot)
        assertTrue(launcher.capabilities.canRunElfBinaries)
        assertTrue(launcher.capabilities.exposesPty)
        assertTrue(launcher.capabilities.supportsBindMounts)
    }

    @Test
    fun `buildShellCommand returns the missing sentinel when probe says not rooted`() {
        val launcher = NamespacedDistroLauncher(FakeRootedModeProbe.notRooted())
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "echo hi")
            assertEquals(listOf(UnshareCommandBuilder.MISSING_SENTINEL), cmd)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand returns the missing sentinel when rootfs is not a directory`() {
        val probe = FakeRootedModeProbe.fullyRooted()
        val launcher = NamespacedDistroLauncher(probe)
        val notADirectory = File("/this/path/does/not/exist/at/all")
        val cmd = launcher.buildShellCommand(notADirectory, "echo hi")
        assertEquals(listOf(UnshareCommandBuilder.MISSING_SENTINEL), cmd)
        // We should NOT have called probe in this case — the
        // directory existence check is the cheap gate.
        assertEquals(0, probe.callCount)
    }

    @Test
    fun `buildShellCommand returns the missing sentinel when unshare is missing`() {
        val probe = FakeRootedModeProbe.fullyRooted().copy(unshareAvailable = false)
        val launcher = NamespacedDistroLauncher(probe)
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "echo hi")
            assertEquals(listOf(UnshareCommandBuilder.MISSING_SENTINEL), cmd)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand returns the missing sentinel when CgroupSpec is non-empty but cgroup v1`() {
        val probe = FakeRootedModeProbe.fullyRooted().copy(cgroupVersion = 1)
        val launcher = NamespacedDistroLauncher(
            probe = probe,
            cgroupSpec = CgroupSpec(cpuWeight = 100),
        )
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "echo hi")
            assertEquals(listOf(UnshareCommandBuilder.MISSING_SENTINEL), cmd)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand returns the missing sentinel when CgroupSpec is non-empty but cgexec missing`() {
        val probe = FakeRootedModeProbe.fullyRooted().copy(cgexecAvailable = false)
        val launcher = NamespacedDistroLauncher(
            probe = probe,
            cgroupSpec = CgroupSpec(cpuWeight = 100),
        )
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "echo hi")
            assertEquals(listOf(UnshareCommandBuilder.MISSING_SENTINEL), cmd)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand returns a real command when probe is fully rooted`() {
        val launcher = NamespacedDistroLauncher(FakeRootedModeProbe.fullyRooted())
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "ls /")
            assertEquals(3, cmd.size)
            assertEquals("su", cmd[0])
            assertEquals("-c", cmd[1])
            val inner = cmd[2]
            assertTrue("must contain unshare: $inner", inner.startsWith("unshare "))
            assertTrue("must contain chroot: $inner", inner.contains(" chroot "))
            assertTrue("must contain rootfs: $inner", inner.contains(rootfs.absolutePath))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `user namespace is dropped when kernel does not support it`() {
        val probe = FakeRootedModeProbe.fullyRooted().copy(unprivilegedUserNsClone = false)
        val launcher = NamespacedDistroLauncher(
            probe = probe,
            namespaceSpec = NamespaceSpec.FULL_SANDBOX.copy(user = true),
        )
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "true")
            val inner = cmd[2]
            assertFalse("user namespace flag must be dropped: $inner",
                inner.contains("--user"))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `user namespace is kept when kernel supports it`() {
        val probe = FakeRootedModeProbe.fullyRooted()  // unprivilegedUserNsClone = true
        val launcher = NamespacedDistroLauncher(
            probe = probe,
            namespaceSpec = NamespaceSpec.FULL_SANDBOX.copy(user = true),
        )
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "true")
            val inner = cmd[2]
            assertTrue("user namespace flag must be present: $inner",
                inner.contains("--user"))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `requireUserNamespace flag fails availability when kernel does not support it`() {
        val probe = FakeRootedModeProbe.fullyRooted().copy(unprivilegedUserNsClone = false)
        val launcher = NamespacedDistroLauncher(
            probe = probe,
            requireUserNamespace = true,
            namespaceSpec = NamespaceSpec.FULL_SANDBOX.copy(user = true),
        )
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            assertFalse(launcher.isAvailable(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `environmentVariables returns empty for the rooted launcher`() {
        val launcher = NamespacedDistroLauncher(FakeRootedModeProbe.fullyRooted())
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            assertTrue(launcher.environmentVariables(rootfs).isEmpty())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `cgroup v1 with empty cgroup spec is still launchable (cgroup layer not needed)`() {
        // The probe reports cgroup v1 (tmpfs) but the spec
        // is empty, so the launcher can proceed. cgexec
        // is missing, but with no cgroup spec we don't need it.
        val probe = FakeRootedModeProbe.fullyRooted().copy(
            cgroupVersion = 1,
            cgexecAvailable = false,
        )
        val launcher = NamespacedDistroLauncher(probe)
        val rootfs = Files.createTempDirectory("elysium-namespace-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "true")
            assertEquals(3, cmd.size)
            // No MISSING_SENTINEL because we don't need cgexec
            assertFalse(cmd.contains(UnshareCommandBuilder.MISSING_SENTINEL))
            // The inner string must not contain cgexec
            assertFalse("inner must not contain cgexec: ${cmd[2]}",
                cmd[2].contains("cgexec"))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}

/**
 * In-memory [RootedModeProbe] for tests. Holds the desired
 * [RootStatus] in a single field; the `probe()` method clones
 * it (so the caller can mutate without affecting the fake)
 * and bumps a call counter. `copy(...)` on the data class
 * lets individual fields be overridden per-test.
 */
class FakeRootedModeProbe(
    private val status: RootStatus = FULLY_ROOTED,
) : RootedModeProbe {
    var callCount: Int = 0
        private set

    override fun probe(): RootStatus {
        callCount++
        return status
    }

    /**
     * Returns a NEW fake whose [RootStatus] has the named
     * fields overridden. Keeps the test code idiomatic
     * (`FakeRootedModeProbe.fullyRooted().copy(unshareAvailable = false)`).
     */
    fun copy(
        isRooted: Boolean = status.isRooted,
        provider: RootProvider = status.provider,
        unshareAvailable: Boolean = status.unshareAvailable,
        cgexecAvailable: Boolean = status.cgexecAvailable,
        unprivilegedUserNsClone: Boolean? = status.unprivilegedUserNsClone,
        cgroupVersion: Int? = status.cgroupVersion,
        diagnostics: String = status.diagnostics,
    ): FakeRootedModeProbe = FakeRootedModeProbe(
        status = status.copy(
            isRooted = isRooted,
            provider = provider,
            unshareAvailable = unshareAvailable,
            cgexecAvailable = cgexecAvailable,
            unprivilegedUserNsClone = unprivilegedUserNsClone,
            cgroupVersion = cgroupVersion,
            diagnostics = diagnostics,
        )
    )

    companion object {
        private val FULLY_ROOTED = RootStatus(
            isRooted = true,
            provider = RootProvider.MAGISK,
            unshareAvailable = true,
            cgexecAvailable = true,
            unprivilegedUserNsClone = true,
            cgroupVersion = 2,
            diagnostics = "fake probe (fully rooted)",
        )

        private val NOT_ROOTED = RootStatus(
            isRooted = false,
            provider = RootProvider.NONE,
            unshareAvailable = false,
            cgexecAvailable = false,
            unprivilegedUserNsClone = null,
            cgroupVersion = null,
            diagnostics = "not rooted (test)",
        )

        fun fullyRooted(): FakeRootedModeProbe = FakeRootedModeProbe(FULLY_ROOTED)
        fun notRooted(): FakeRootedModeProbe = FakeRootedModeProbe(NOT_ROOTED)
    }
}
