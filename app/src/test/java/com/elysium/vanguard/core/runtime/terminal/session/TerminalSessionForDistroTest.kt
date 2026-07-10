package com.elysium.vanguard.core.runtime.terminal.session

import com.elysium.vanguard.core.runtime.distros.launcher.JailedDistroLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.3 — Tests for [TerminalSession.forDistro].
 *
 * These verify the WIRE between the launcher and the session
 * constructor: the command list should reflect whatever the launcher
 * built, and the working directory should be the rootfs. We do not
 * actually spawn a process; that requires a real Android `/system/bin/sh`
 * which is not available on the JVM unit-test classpath.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
class TerminalSessionForDistroTest {

    @Test
    fun `forDistro produces a session whose Config matches the launcher output`() {
        val rootfs = Files.createTempDirectory("elysium-term-for-distro").toFile()
        try {
            val launcher = JailedDistroLauncher()
            val pick = LauncherPick(launcher, "test")
            val session = TerminalSession.forDistro(rootfs, pick)
            assertEquals(
                launcher.buildShellCommand(rootfs, "anything").first(),
                session.config.command.first()
            )
            assertEquals(rootfs, session.config.workingDirectory)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `forDistro defaults to 80x24 and xterm-256color`() {
        val rootfs = Files.createTempDirectory("elysium-term-for-distro").toFile()
        try {
            val launcher = JailedDistroLauncher()
            val pick = LauncherPick(launcher, "test")
            val session = TerminalSession.forDistro(rootfs, pick)
            assertEquals(80, session.config.cols)
            assertEquals(24, session.config.rows)
            assertEquals("xterm-256color", session.config.termName)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `forDistro honors overrides for cols, rows, termName`() {
        val rootfs = Files.createTempDirectory("elysium-term-for-distro").toFile()
        try {
            val launcher = JailedDistroLauncher()
            val pick = LauncherPick(launcher, "test")
            val session = TerminalSession.forDistro(
                rootfsDir = rootfs,
                pick = pick,
                cols = 120,
                rows = 40,
                termName = "vt100"
            )
            assertEquals(120, session.config.cols)
            assertEquals(40, session.config.rows)
            assertEquals("vt100", session.config.termName)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `forDistro's command switches to the proot launcher output when supplied`() {
        // Future-proofing: when 9.6.3.1 lands and a stub proot launcher
        // is wired, this verifies the wire still adapts. For 9.6.3 we
        // emulate the contract by passing a fake launcher that returns
        // a recognizable marker.
        val rootfs = Files.createTempDirectory("elysium-term-for-distro").toFile()
        try {
            val fakeProot = object : com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher {
                override val kind = com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind.NATIVE_PROOT
                override val capabilities = com.elysium.vanguard.core.runtime.distros.launcher.LauncherCapabilities.JAILED_BASELINE
                override fun buildShellCommand(r: File, script: String) =
                    listOf("FAKE-PROOT", "-r", r.absolutePath, "/bin/sh", "-c", script)
                override fun buildProbeCommand(r: File, args: List<String>) =
                    listOf("FAKE-PROOT", "-r", r.absolutePath, "/bin/sh", "-c", args.joinToString(" "))
                override fun isAvailable(r: File) = true
            }
            val session = TerminalSession.forDistro(rootfs, LauncherPick(fakeProot, "fake"))
            assertEquals("FAKE-PROOT", session.config.command.first())
            assertTrue(session.config.command.contains(rootfs.absolutePath))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forDistro rejects a missing rootfs`() {
        val launcher = JailedDistroLauncher()
        TerminalSession.forDistro(
            rootfsDir = File("/no/such/path"),
            pick = LauncherPick(launcher, "test")
        )
    }
}
