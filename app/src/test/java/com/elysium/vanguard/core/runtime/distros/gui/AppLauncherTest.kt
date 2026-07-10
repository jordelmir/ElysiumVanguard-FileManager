package com.elysium.vanguard.core.runtime.distros.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.9 — Tests for the AppLauncher.
 */
class AppLauncherTest {

    private fun fakeApp(): LinuxAppEntry {
        val root = Files.createTempDirectory("elysium-launcher-app").toFile()
        val src = File(File(root, "usr/share/applications"), "firefox.desktop")
        src.parentFile.mkdirs()
        src.writeText("[Desktop Entry]\nType=Application\nName=Firefox\nExec=firefox %U\n")
        return LinuxAppEntry(
            id = "firefox",
            name = "Firefox",
            comment = null,
            exec = "firefox %U",
            sourceFile = src
        )
    }

    @Test
    fun `build produces a session id and a non-empty command`() {
        val launcher = AppLauncher(
            distroLauncherBuilder = { _ -> listOf("stub-launcher") }
        )
        val app = fakeApp()
        val launch = launcher.build("alpine-latest", app)
        assertNotNull(launch.sessionId)
        assertEquals("alpine-latest", launch.distroId)
        assertEquals(app.id, launch.app.id)
        assertTrue(launch.command.isNotEmpty())
    }

    @Test
    fun `build preserves the executed body in the command`() {
        val launcher = AppLauncher(
            distroLauncherBuilder = { _ -> listOf("stub-launcher") }
        )
        val launch = launcher.build("alpine-latest", fakeApp())
        // The command must reference firefox (joined as one string)
        val joined = launch.command.joinToString(" ")
        assertTrue("expected command to mention 'firefox'; got: $joined",
            joined.contains("firefox"))
    }

    @Test
    fun `log records and lists launched apps`() {
        val log = AppLaunchLog()
        val launcher = AppLauncher(distroLauncherBuilder = { _ -> emptyList() })
        val app = fakeApp()
        log.record(launcher.build("alpine-latest", app, sessionId = "s1"))
        log.record(launcher.build("debian", app, sessionId = "s2"))
        assertEquals(2, log.list().size)
        log.clear()
        assertEquals(0, log.list().size)
    }

    @Test
    fun `every launcher produces a different session id`() {
        val launcher = AppLauncher(distroLauncherBuilder = { _ -> emptyList() })
        val app = fakeApp()
        val a = launcher.build("a", app).sessionId
        val b = launcher.build("a", app).sessionId
        assertTrue(a != b)
    }

    @Test
    fun `default launcher prefix is non-empty`() {
        val launcher = AppLauncher()
        val launch = launcher.build("alpine-latest", fakeApp())
        // The default prefix has proot + 3 args.
        assertTrue(launch.command.size >= 4)
        assertEquals("proot", launch.command.first())
    }
}
