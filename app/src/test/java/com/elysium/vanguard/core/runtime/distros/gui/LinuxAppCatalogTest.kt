package com.elysium.vanguard.core.runtime.distros.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.6.5 — Tests for the Linux app catalog (.desktop parser).
 *
 * Builds a fake rootfs with a few `.desktop` files and validates
 * the parsing rules.
 *
 * Phase 9.6.5 — first build; intentionally minimal.
 */
class LinuxAppCatalogTest {

    private fun writeDesktop(dir: File, name: String, body: String) {
        val file = File(File(dir, "usr/share/applications"), name)
        file.parentFile.mkdirs()
        file.writeText(body)
    }

    private fun buildFakeAlpine(): File {
        val rootfs = Files.createTempDirectory("elysium-apps").toFile()
        writeDesktop(rootfs, "firefox.desktop", """
            [Desktop Entry]
            Type=Application
            Name=Firefox Web Browser
            Comment=Browse the World Wide Web
            Exec=firefox %U
            """.trimIndent())
        writeDesktop(rootfs, "gimp.desktop", """
            [Desktop Entry]
            Type=Application
            Name=GIMP
            Comment=GNU Image Manipulation Program
            Exec=gimp-2.10 %F
            """.trimIndent())
        writeDesktop(rootfs, "broken-no-type.desktop", """
            [Desktop Entry]
            Name=NoType
            Exec=nothing
            """.trimIndent())
        writeDesktop(rootfs, "ignored-not-desktop", """
            [Desktop Entry]
            Type=Application
            Name=NotDesktop
            Exec=foo
            """.trimIndent())
        // Move the .desktop files we want to test into the right dir;
        // the last one above ("ignored-not-desktop") doesn't end with
        // .desktop so it's filtered out by our matcher.
        return rootfs
    }

    @Test
    fun `listApps finds every valid desktop entry sorted by name`() {
        val rootfs = buildFakeAlpine()
        try {
            val catalog = LinuxAppCatalog(rootfs)
            val apps = catalog.listApps()
            val names = apps.map { it.name }
            // firefox and gimp come through; broken-no-type filtered.
            assertTrue("Firefox Web Browser" in names)
            assertTrue("GIMP" in names)
            assertTrue("NoType" !in names)
            assertEquals(2, apps.size)
            // Sorted by name (case-insensitive)
            assertEquals("Firefox Web Browser", apps[0].name)
            assertEquals("GIMP", apps[1].name)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `parseDesktop returns null for non-application types`() {
        val rootfs = Files.createTempDirectory("elysium-apps").toFile()
        try {
            writeDesktop(rootfs, "dir.desktop", """
                [Desktop Entry]
                Type=Directory
                Name=A Folder
                Exec=nautilus
                """.trimIndent())
            val catalog = LinuxAppCatalog(rootfs)
            val entry = catalog.parseDesktop(File(File(rootfs, "usr/share/applications"), "dir.desktop"))
            assertNull(entry)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `parseDesktop returns null when Name or Exec is missing`() {
        val rootfs = Files.createTempDirectory("elysium-apps").toFile()
        try {
            writeDesktop(rootfs, "missing-name.desktop", """
                [Desktop Entry]
                Type=Application
                Exec=foo
                """.trimIndent())
            writeDesktop(rootfs, "missing-exec.desktop", """
                [Desktop Entry]
                Type=Application
                Name=NoExec
                """.trimIndent())
            val catalog = LinuxAppCatalog(rootfs)
            assertNull(catalog.parseDesktop(File(File(rootfs, "usr/share/applications"), "missing-name.desktop")))
            assertNull(catalog.parseDesktop(File(File(rootfs, "usr/share/applications"), "missing-exec.desktop")))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `Exec line strips FIELDS placeholders`() {
        val rootfs = Files.createTempDirectory("elysium-apps").toFile()
        try {
            writeDesktop(rootfs, "stuff.desktop", """
                [Desktop Entry]
                Type=Application
                Name=Stuff
                Exec=firefox %U --new-window %F
                """.trimIndent())
            val catalog = LinuxAppCatalog(rootfs)
            val apps = catalog.listApps()
            assertEquals(1, apps.size)
            assertTrue(apps[0].exec.contains("firefox"))
            assertTrue(!apps[0].exec.contains("%U"))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `parseDesktop returns null for non-desktop files`() {
        val rootfs = Files.createTempDirectory("elysium-apps").toFile()
        try {
            File(rootfs, "thing.txt").writeText("not a desktop")
            val catalog = LinuxAppCatalog(rootfs)
            val entry = catalog.parseDesktop(File(rootfs, "thing.txt"))
            assertNull(entry)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `listApps returns empty list when no applications dir`() {
        val rootfs = Files.createTempDirectory("elysium-noapps").toFile()
        try {
            val catalog = LinuxAppCatalog(rootfs)
            assertEquals(emptyList<LinuxAppEntry>(), catalog.listApps())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `parseDesktop preserves the source file for debugging`() {
        val rootfs = Files.createTempDirectory("elysium-apps-source").toFile()
        try {
            val body = """
                [Desktop Entry]
                Type=Application
                Name=Editor
                Exec=vim %F
            """.trimIndent()
            writeDesktop(rootfs, "vim.desktop", body)
            val catalog = LinuxAppCatalog(rootfs)
            val apps = catalog.listApps()
            assertEquals(1, apps.size)
            assertTrue(apps[0].sourceFile.exists())
            assertEquals("vim.desktop", apps[0].sourceFile.name)
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
