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
 * PHASE 10.4 — Tests for the Direct-Exec launcher.
 *
 * These tests pin down the contract: shell discovery, command shape,
 * environment overrides, probe command shape, and the "no shell
 * present" path that lets the resolver fall back to JailedDistroLauncher.
 *
 * All tests run on the JVM with a synthetic rootfs directory tree
 * created in `Files.createTempDirectory`. No Android device required.
 */

/**
 * PHASE 10.4 — Write [content] into [root] at the relative path
 * [shellRel] (which is always given as absolute-from-rootfs, e.g.
 * `/bin/bash`). We strip the leading `/` because `java.io.File`
 * treats absolute children as absolute paths on the host, which
 * would put the file at the JVM's actual `/bin/bash` and bypass
 * the temp dir. The leading-`/` semantic is the rootfs-relative
 * view, not the host view.
 *
 * Lives at file scope (not inside a class) so both `DirectExecDistroLauncherTest`
 * and `TerminalSessionForDistroTest` can call it.
 */
internal fun writeShell(root: File, shellRel: String, content: String) {
    val relative = shellRel.removePrefix("/")
    val shell = File(root, relative)
    shell.parentFile?.mkdirs()
    shell.writeText(content)
    if (content.isNotEmpty()) shell.setExecutable(true)
}
class DirectExecDistroLauncherTest {

    /**
     * Build a temp rootfs with one shell file inside it.
     */
    private fun newRootfsWithShell(shellRel: String, content: ByteArray = "#!/bin/sh\necho ok\n".toByteArray()): Pair<File, String> {
        val root = Files.createTempDirectory("elysium-direct-test").toFile()
        writeShell(root, shellRel, String(content))
        return root to shellRel
    }

    @Test
    fun `findShell returns the first matching candidate`() {
        val (root, _) = newRootfsWithShell("/bin/bash")
        try {
            val launcher = DirectExecDistroLauncher()
            val found = launcher.findShell(root)
            assertEquals("/bin/bash", found)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `findShell prefers bash over sh over ash over busybox`() {
        // Plant only the less-preferred shells; the launcher should
        // find the highest-priority one that exists.
        val (root, _) = newRootfsWithShell("/bin/sh")
        try {
            writeShell(root, "/bin/ash", "#!/bin/sh\n")
            writeShell(root, "/bin/busybox", "#!/bin/sh\n")
            val launcher = DirectExecDistroLauncher()
            assertEquals("/bin/sh", launcher.findShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `findShell returns null when no shell exists`() {
        val root = Files.createTempDirectory("elysium-direct-test").toFile()
        try {
            val launcher = DirectExecDistroLauncher()
            assertNull(launcher.findShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `findShell ignores empty files`() {
        val root = Files.createTempDirectory("elysium-direct-test").toFile()
        try {
            writeShell(root, "/bin/bash", "")
            val launcher = DirectExecDistroLauncher()
            // Empty file → not a real shell, no other candidates.
            assertNull(launcher.findShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `findShell honors forcedShell override`() {
        val (root, _) = newRootfsWithShell("/bin/bash")
        try {
            val launcher = DirectExecDistroLauncher(forcedShell = "/bin/zsh")
            assertEquals("/bin/zsh", launcher.findShell(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `isAvailable mirrors findShell`() {
        val (root, _) = newRootfsWithShell("/bin/bash")
        try {
            val launcher = DirectExecDistroLauncher()
            assertTrue(launcher.isAvailable(root))
        } finally {
            root.deleteRecursively()
        }
        val empty = Files.createTempDirectory("elysium-direct-test").toFile()
        try {
            val launcher = DirectExecDistroLauncher()
            assertFalse(launcher.isAvailable(empty))
        } finally {
            empty.deleteRecursively()
        }
    }

    @Test
    fun `isAvailable returns false for a missing rootfs`() {
        val launcher = DirectExecDistroLauncher()
        assertFalse(launcher.isAvailable(File("/no/such/elysium/path")))
    }

    @Test
    fun `buildShellCommand with empty script yields an interactive shell`() {
        val (root, _) = newRootfsWithShell("/bin/bash")
        try {
            val launcher = DirectExecDistroLauncher()
            val cmd = launcher.buildShellCommand(root, script = "")
            // The first argv entry must be the absolute path to the shell.
            assertEquals(File(root, "/bin/bash").absolutePath, cmd[0])
            // The launcher invokes `bash -i` for interactive mode.
            assertEquals("-i", cmd[1])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand with a script wraps it in a -c invocation`() {
        val (root, _) = newRootfsWithShell("/bin/sh")
        try {
            val launcher = DirectExecDistroLauncher()
            val cmd = launcher.buildShellCommand(root, script = "echo hi")
            assertEquals(File(root, "/bin/sh").absolutePath, cmd[0])
            assertEquals("-c", cmd[1])
            assertTrue(cmd[2].contains("echo hi"))
            // The header sets HOME, TMPDIR, PATH, LD_LIBRARY_PATH.
            assertTrue(cmd[2].contains("HOME="))
            assertTrue(cmd[2].contains("TMPDIR="))
            assertTrue(cmd[2].contains("PATH="))
            assertTrue(cmd[2].contains("LD_LIBRARY_PATH="))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `buildShellCommand throws when no shell is present`() {
        val root = Files.createTempDirectory("elysium-direct-test").toFile()
        try {
            val launcher = DirectExecDistroLauncher()
            try {
                launcher.buildShellCommand(root, script = "")
                org.junit.Assert.fail("expected IllegalStateException")
            } catch (_: IllegalStateException) {
                // expected
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `buildProbeCommand emits cd plus the env setup plus the args`() {
        val (root, _) = newRootfsWithShell("/bin/sh")
        try {
            val launcher = DirectExecDistroLauncher()
            val cmd = launcher.buildProbeCommand(root, listOf("cat", "/etc/os-release"))
            assertEquals(File(root, "/bin/sh").absolutePath, cmd[0])
            assertEquals("-c", cmd[1])
            val script = cmd[2]
            assertTrue(script.contains("cd"))
            assertTrue(script.contains("cat"))
            assertTrue(script.contains("/etc/os-release"))
            assertTrue(script.contains("PATH="))
            assertTrue(script.contains("LD_LIBRARY_PATH="))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `defaultEnvironment points PATH and LD_LIBRARY_PATH at the rootfs`() {
        val (root, _) = newRootfsWithShell("/bin/bash")
        try {
            val launcher = DirectExecDistroLauncher()
            val env = launcher.defaultEnvironment(root).associate { it.first to it.second }
            assertNotNull(env["PATH"])
            assertNotNull(env["LD_LIBRARY_PATH"])
            assertNotNull(env["HOME"])
            assertNotNull(env["TMPDIR"])
            val rootfs = root.absolutePath
            assertTrue(env["PATH"]!!.contains("$rootfs/bin"))
            assertTrue(env["PATH"]!!.contains("$rootfs/usr/bin"))
            assertTrue(env["LD_LIBRARY_PATH"]!!.contains("$rootfs/lib"))
            assertTrue(env["LD_LIBRARY_PATH"]!!.contains("$rootfs/usr/lib"))
            assertEquals("$rootfs/root", env["HOME"])
            assertEquals("$rootfs/tmp", env["TMPDIR"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `kind and capabilities reflect direct-exec flavor`() {
        val launcher = DirectExecDistroLauncher()
        assertEquals(LauncherKind.DIRECT_EXEC, launcher.kind)
        assertTrue(launcher.capabilities.canRunElfBinaries)
        assertTrue(launcher.capabilities.exposesPty)
        assertFalse(launcher.capabilities.supportsBindMounts)
        assertFalse(launcher.capabilities.requiresRoot)
    }

    @Test
    fun `production registry now prefers Direct-Exec over Jailed`() {
        // Phase 10.4: production puts Direct-Exec before Jailed in the
        // candidate list. Resolution visits candidates in order, so a
        // rootfs containing /bin/bash lands on Direct-Exec.
        val (root, _) = newRootfsWithShell("/bin/bash")
        try {
            val reg = DistroLauncherRegistry.production(supportedAbis = setOf("arm64-v8a"))
            val pick = LauncherResolution.resolve(root, reg)
            assertEquals(LauncherKind.DIRECT_EXEC, pick.launcher.kind)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `production registry falls back to Jailed when rootfs has no shell`() {
        val root = Files.createTempDirectory("elysium-direct-test").toFile()
        try {
            val reg = DistroLauncherRegistry.production(supportedAbis = setOf("arm64-v8a"))
            val pick = LauncherResolution.resolve(root, reg)
            // Direct-Exec and Native-Proot both fail → Jailed wins.
            assertEquals(LauncherKind.JAILED_SHELL, pick.launcher.kind)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shellAbsolutePath returns the canonical absolute path`() {
        val (root, _) = newRootfsWithShell("/usr/bin/bash")
        try {
            val launcher = DirectExecDistroLauncher()
            val abs = launcher.shellAbsolutePath(root)
            assertEquals(File(root, "/usr/bin/bash").absolutePath, abs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `shellAbsolutePath returns null when no shell exists`() {
        val root = Files.createTempDirectory("elysium-direct-test").toFile()
        try {
            val launcher = DirectExecDistroLauncher()
            assertNull(launcher.shellAbsolutePath(root))
        } finally {
            root.deleteRecursively()
        }
    }
}

/**
 * PHASE 10.4 — Tests for TerminalSession.forDistro's new behavior.
 *
 * The session is hard to spin up under JUnit (it owns a real `Process`
 * and a coroutine scope), so we exercise the static helper's contract
 * by reflecting on what it produces. The tests verify the command
 * shape and the env-var passthrough for the Direct-Exec case; Jailed
 * is left to the older JailedDistroLauncherTest.
 */
class TerminalSessionForDistroTest {

    @Test
    fun `forDistro with Direct-Exec uses an interactive bash -i command`() {
        val root = java.nio.file.Files.createTempDirectory("elysium-tty-test").toFile()
        try {
            writeShell(root, "/bin/bash", "#!/bin/sh\n")
            val launcher = DirectExecDistroLauncher()
            val pick = LauncherPick(launcher, "test direct-exec")
            val session = com.elysium.vanguard.core.runtime.terminal.session.TerminalSession.forDistro(
                root, pick
            )
            // First two argv entries must be the absolute shell path and `-i`.
            assertEquals(File(root, "bin/bash").absolutePath, session.config.command[0])
            assertEquals("-i", session.config.command[1])
            // Environment overrides must be threaded through to the Config.
            val envMap = session.config.environmentVariables.toMap()
            assertNotNull(envMap["PATH"])
            assertNotNull(envMap["LD_LIBRARY_PATH"])
            assertNotNull(envMap["HOME"])
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `forDistro with Jailed injects a probe and skips env overrides`() {
        val root = java.nio.file.Files.createTempDirectory("elysium-tty-test").toFile()
        try {
            val launcher = JailedDistroLauncher()
            val pick = LauncherPick(launcher, "test jailed")
            val session = com.elysium.vanguard.core.runtime.terminal.session.TerminalSession.forDistro(
                root, pick
            )
            // JailedDistroLauncher always runs /system/bin/sh -c <probe>.
            assertEquals("/system/bin/sh", session.config.command[0])
            assertEquals("-c", session.config.command[1])
            // The probe should mention the launcher kind and a /etc/os-release lookup.
            val script = session.config.command[2]
            assertTrue(script.contains("JAILED"))
            assertTrue(script.contains("/etc/os-release"))
            // No Direct-Exec env overrides for the jailed path.
            val envMap = session.config.environmentVariables.toMap()
            assertNull(envMap["LD_LIBRARY_PATH"])
        } finally {
            root.deleteRecursively()
        }
    }
}
