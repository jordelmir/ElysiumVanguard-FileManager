package com.elysium.vanguard.core.runtime.distros.introspector

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * PHASE 9.6.3.1 — Tests for the [RootfsIntrospector]. Builds a fake
 * rootfs on disk and parses it without booting anything.
 *
 * Phase 9.6.3.1 — first build; intentionally minimal.
 */
class RootfsIntrospectorTest {

    private fun buildFakeAlpine(): File {
        val rootfs = Files.createTempDirectory("elysium-introspect").toFile()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText(
            """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.21.2
            PRETTY_NAME="Alpine Linux v3.21"
            HOME_URL="https://alpinelinux.org/"
            """.trimIndent()
        )
        File(rootfs, "lib/apk/db").mkdirs()
        File(rootfs, "lib/apk/db/installed").writeText(
            """
            P:musl
            V:1.2.4-r0
            c:the musl c library (libc) implementation

            P:apk-tools
            V:2.14.4-r1
            c:Alpine Package Keeper
            """.trimIndent()
        )
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/sh").writeText("#!/bin/sh\necho hi\n")
        return rootfs
    }

    private fun buildFakeDebian(): File {
        val rootfs = Files.createTempDirectory("elysium-introspect-debian").toFile()
        File(rootfs, "var/lib/dpkg").mkdirs()
        File(rootfs, "var/lib/dpkg/status").writeText(
            """
            Package: python3
            Version: 3.11.2-1
            Description: interactive high-level object-oriented language

            Package: bash
            Version: 5.2-5
            Description: GNU Bourne-Again SHell
            """.trimIndent()
        )
        return rootfs
    }

    private fun buildFakeArch(): File {
        val rootfs = Files.createTempDirectory("elysium-introspect-arch").toFile()
        val pkgDir = File(rootfs, "var/lib/pacman/local/python-3.11.5-1")
        pkgDir.mkdirs()
        File(pkgDir, "desc").writeText(
            """
            %NAME%
            python

            %VERSION%
            3.11.5-1

            %DESC%
            Next generation of the python language
            """.trimIndent()
        )
        return rootfs
    }

    @Test
    fun `entries returns top-level files and directories`() {
        val rootfs = buildFakeAlpine()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val entries = introspector.entries(maxDepth = 1)
            val paths = entries.map { it.relativePath }.toSet()
            assertTrue("etc" in paths)
            assertTrue("lib" in paths)
            assertTrue("bin" in paths)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `entries includes depth-N children when maxDepth greater than 1`() {
        val rootfs = buildFakeAlpine()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val entries = introspector.entries(maxDepth = 2)
            val paths = entries.map { it.relativePath }.toSet()
            // etc/os-release is at depth 2
            assertTrue("etc/os-release" in paths)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `osRelease parses Alpine pretty name`() {
        val rootfs = buildFakeAlpine()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val release = introspector.osRelease()
            assertEquals("Alpine Linux", release.name)
            assertEquals("alpine", release.id)
            assertEquals("3.21.2", release.versionId)
            assertEquals("Alpine Linux v3.21", release.prettyName)
            assertEquals("https://alpinelinux.org/", release.homeUrl)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `osRelease returns UNKNOWN when file missing`() {
        val rootfs = Files.createTempDirectory("elysium-introspect-empty").toFile()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val release = introspector.osRelease()
            assertEquals(OsRelease.UNKNOWN, release)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `osRelease tolerates malformed content`() {
        val rootfs = Files.createTempDirectory("elysium-introspect-malformed").toFile()
        try {
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/os-release").writeText("# weird content without = signs\nrandom junk")
            val introspector = RootfsIntrospector(rootfs)
            // No entries with `=` should produce a UNKNOWN-shaped result.
            val release = introspector.osRelease()
            assertNull(release.name)
            assertNull(release.id)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `installedPackages reads Alpine apk database`() {
        val rootfs = buildFakeAlpine()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val packages = introspector.installedPackages()
            val names = packages.map { it.name }.toSet()
            assertTrue("musl" in names)
            assertTrue("apk-tools" in names)
            val musl = packages.first { it.name == "musl" }
            assertEquals("1.2.4-r0", musl.version)
            val apkTools = packages.first { it.name == "apk-tools" }
            assertEquals("Alpine Package Keeper", apkTools.description)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `installedPackages reads Debian dpkg status`() {
        val rootfs = buildFakeDebian()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val packages = introspector.installedPackages()
            val names = packages.map { it.name }.toSet()
            assertTrue("python3" in names)
            assertTrue("bash" in names)
            assertEquals("3.11.2-1", packages.first { it.name == "python3" }.version)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `installedPackages reads Arch pacman local`() {
        val rootfs = buildFakeArch()
        try {
            val introspector = RootfsIntrospector(rootfs)
            val packages = introspector.installedPackages()
            val names = packages.map { it.name }.toSet()
            assertTrue("'python' should be in parsed package names; got: $names", "python" in names)
            val pkg = packages.first { it.name == "python" }
            assertEquals("3.11.5-1", pkg.version)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `installedPackages returns empty list when no package manager is detected`() {
        val rootfs = Files.createTempDirectory("elysium-introspect-no-pm").toFile()
        try {
            val introspector = RootfsIntrospector(rootfs)
            assertEquals(emptyList<InstalledPackage>(), introspector.installedPackages())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `entries handles symlinks without throwing`() {
        val rootfs = Files.createTempDirectory("elysium-introspect-sym").toFile()
        try {
            val real = File(rootfs, "real.txt").apply { writeText("hello") }
            val link = File(rootfs, "link.txt")
            try {
                Files.createSymbolicLink(link.toPath(), real.absoluteFile.toPath())
                val introspector = RootfsIntrospector(rootfs)
                val entries = introspector.entries(maxDepth = 1)
                val linkEntry = entries.first { it.relativePath == "link.txt" }
                assertTrue(linkEntry.isSymlink)
            } catch (_: UnsupportedOperationException) {
                // FS doesn't support symlinks; test passes by construction.
            } finally {
                rootfs.deleteRecursively()
            }
        } finally {
            // outer already removed
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `entries rejects missing rootfs`() {
        val introspector = RootfsIntrospector(File("/no/such"))
        introspector.entries()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `entries rejects maxDepth less than 1`() {
        val rootfs = Files.createTempDirectory("elysium-introspect-depth").toFile()
        try {
            val introspector = RootfsIntrospector(rootfs)
            introspector.entries(maxDepth = 0)
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
