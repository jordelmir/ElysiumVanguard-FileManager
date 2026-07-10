package com.elysium.vanguard.core.runtime.distros.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.3 — Tests for the launcher interface and its concrete
 * implementations. These run under plain JUnit on the JVM; no Android
 * device is required.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class JailedDistroLauncherTest {

    @Test
    fun `buildShellCommand injects a probe when script is empty`() {
        val launcher = JailedDistroLauncher()
        val rootfs = Files.createTempDirectory("elysium-jailed-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "")
            assertEquals(listOf("/system/bin/sh", "-c"), cmd.take(2))
            assertTrue(cmd[2].contains("echo"))
            assertTrue(cmd[2].contains("pwd"))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand passes the user script through unchanged`() {
        val launcher = JailedDistroLauncher()
        val rootfs = Files.createTempDirectory("elysium-jailed-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "ls /; cat /etc/os-release")
            assertEquals(3, cmd.size)
            assertEquals("/system/bin/sh", cmd[0])
            assertEquals("-c", cmd[1])
            assertEquals("ls /; cat /etc/os-release", cmd[2])
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildProbeCommand changes directory before running args`() {
        val launcher = JailedDistroLauncher()
        val rootfs = Files.createTempDirectory("elysium-jailed-test").toFile()
        try {
            val cmd = launcher.buildProbeCommand(rootfs, listOf("cat", "etc/os-release"))
            // Expected: /system/bin/sh -c "cd <rootfs> && cat etc/os-release"
            assertEquals("/system/bin/sh", cmd[0])
            assertEquals("-c", cmd[1])
            assertTrue(cmd[2].startsWith("cd "))
            assertTrue(cmd[2].contains("cat"))
            assertTrue(cmd[2].contains("etc/os-release"))
            // Path is quoted, single quotes, rootfs absolute path inside.
            assertTrue(cmd[2].contains(rootfs.absolutePath))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `isAvailable returns true when rootfs is a directory`() {
        val launcher = JailedDistroLauncher()
        val rootfs = Files.createTempDirectory("elysium-jailed-test").toFile()
        try {
            assertTrue(launcher.isAvailable(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `isAvailable returns false when rootfs is missing`() {
        val launcher = JailedDistroLauncher()
        val missing = File("/no/such/elysium/path")
        assertFalse(launcher.isAvailable(missing))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildShellCommand rejects a missing rootfs`() {
        val launcher = JailedDistroLauncher()
        launcher.buildShellCommand(File("/no/such/elysium"), "ls")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildProbeCommand rejects a missing rootfs`() {
        val launcher = JailedDistroLauncher()
        launcher.buildProbeCommand(File("/no/such/elysium"), listOf("ls"))
    }

    @Test
    fun `capabilities expose JAILED baseline`() {
        val launcher = JailedDistroLauncher()
        val caps = launcher.capabilities
        assertFalse(caps.canRunElfBinaries)
        assertFalse(caps.exposesPty)
        assertFalse(caps.supportsBindMounts)
        assertFalse(caps.requiresRoot)
    }

    @Test
    fun `kind is JAILED_SHELL`() {
        val launcher = JailedDistroLauncher()
        assertEquals(LauncherKind.JAILED_SHELL, launcher.kind)
    }
}

/**
 * PHASE 9.6.3 — Proot launcher tests. Until the JNI binary ships in
 * 9.6.3.1, every concrete test verifies the SHAPE of the command that
 * WOULD be built and the unavailability of the launcher.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class NativeProotLauncherTest {

    @Test
    fun `kind and capabilities reflect proot flavor`() {
        val launcher = NativeProotLauncher(bundledAbis = setOf("arm64-v8a"))
        assertEquals(LauncherKind.NATIVE_PROOT, launcher.kind)
        assertTrue(launcher.capabilities.canRunElfBinaries)
        assertTrue(launcher.capabilities.supportsBindMounts)
        assertEquals(setOf("arm64-v8a"), launcher.capabilities.abiSupport)
    }

    @Test
    fun `capabilities report no ELF when no ABIs were bundled`() {
        val launcher = NativeProotLauncher(bundledAbis = emptySet())
        assertFalse(launcher.capabilities.canRunElfBinaries)
        assertFalse(launcher.capabilities.supportsBindMounts)
        assertTrue(launcher.capabilities.abiSupport.isEmpty())
    }

    @Test
    fun `isAvailable is false until the JNI binary ships`() {
        val launcher = NativeProotLauncher(bundledAbis = setOf("arm64-v8a"))
        val rootfs = Files.createTempDirectory("elysium-proot-test").toFile()
        try {
            // Even with a valid rootfs, the proot path is not yet wired,
            // so isAvailable must be false so resolution falls back.
            assertFalse(launcher.isAvailable(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand returns the proot-missing sentinel when not available`() {
        // Honest expectation: 9.6.3 ships without the JNI binary, so the
        // launcher reports unavailability and buildShellCommand falls
        // back to the "proot-missing" sentinel string. 9.6.3.1's tests
        // will verify the real proot flag shape once the binary exists.
        val launcher = NativeProotLauncher(bundledAbis = setOf("arm64-v8a"))
        val rootfs = Files.createTempDirectory("elysium-proot-test").toFile()
        try {
            val cmd = launcher.buildShellCommand(rootfs, "ls /")
            assertEquals(listOf("proot-missing"), cmd)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand produces a real proot flag shape when a fake launcher marks itself available`() {
        // We exercise the wire through a small inline subclass that
        // overrides isAvailable. This proves the launcher interface
        // produces the expected proot command shape when the binary
        // is real. The subclass is also useful as a reference for
        // 9.6.3.1 when the JNI launches for real.
        val rootfs = Files.createTempDirectory("elysium-proot-test").toFile()
        try {
            val launcher = object : NativeProotLauncher(bundledAbis = setOf("arm64-v8a")) {
                override fun isAvailable(r: java.io.File): Boolean = true
            }
            val cmd = launcher.buildShellCommand(rootfs, "ls /")
            assertEquals("proot", cmd[0])
            assertEquals("-0", cmd[1])
            assertTrue(cmd.contains("-r"))
            assertTrue(cmd.contains(rootfs.absolutePath))
            assertTrue(cmd.contains("/bin/sh"))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}

/**
 * PHASE 9.6.3 — Resolution always falls back to the jailed shell when
 * no launcher reports `isAvailable == true`.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class LauncherResolutionTest {

    @Test
    fun `empty registry resolves to jailed shell`() {
        val rootfs = Files.createTempDirectory("elysium-res-test").toFile()
        try {
            val res = LauncherResolution.resolve(rootfs, DistroLauncherRegistry.empty())
            assertNotNull(res)
            assertEquals(LauncherKind.JAILED_SHELL, res.launcher.kind)
            assertTrue(res.reason.contains("jailed", ignoreCase = true))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `forceJailed bypasses registry entirely`() {
        val res = LauncherResolution.forceJailed()
        assertEquals(LauncherKind.JAILED_SHELL, res.launcher.kind)
        assertTrue(res.reason.contains("forced", ignoreCase = true))
    }

    @Test
    fun `production registry also resolves to jailed shell in 9_6_3`() {
        val rootfs = Files.createTempDirectory("elysium-res-test").toFile()
        try {
            val reg = DistroLauncherRegistry.production(supportedAbis = setOf("arm64-v8a"))
            val res = LauncherResolution.resolve(rootfs, reg)
            assertEquals(LauncherKind.JAILED_SHELL, res.launcher.kind)
            // The reason still mentions fallback because proot is not yet
            // actually wiring the binary.
            assertNotNull(res.reason)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `LauncherPick carries launcher and reason together`() {
        val launcher = JailedDistroLauncher()
        val pick = LauncherPick(launcher, "test reason")
        assertEquals(launcher, pick.launcher)
        assertEquals("test reason", pick.reason)
    }

    @Test
    fun `LauncherResolver staticResolver returns a LauncherPick`() {
        val resolver = LauncherResolver.staticResolver
        val pick = resolver.resolve(File("/tmp"))
        assertNotNull(pick)
        assertEquals(LauncherKind.JAILED_SHELL, pick.launcher.kind)
    }

    @Test
    fun `staticResolver does not depend on filesystem`() {
        val res = LauncherResolver.staticResolver.resolve(File("/no/such"))
        assertEquals(LauncherKind.JAILED_SHELL, res.launcher.kind)
    }
}
