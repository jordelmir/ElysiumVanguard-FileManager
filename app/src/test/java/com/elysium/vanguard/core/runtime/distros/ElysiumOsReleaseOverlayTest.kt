package com.elysium.vanguard.core.runtime.distros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 12.1 — overlay installer tests.
 *
 * The overlay writes four files into a fresh rootfs. We verify:
 *
 *   - Each file lands at the canonical path.
 *   - The os-release.d snippet contains the ADR-003 markers
 *     (NAME, ID=elysium, ID_LIKE=debian, ELYSIUM_*).
 *   - Re-applying the overlay overwrites in place (idempotency).
 *   - [remove] cleans up the four files but does not touch
 *     pre-existing files in the rootfs.
 *   - A missing `/etc/os-release.d` directory is created on demand.
 *   - An upstream `/etc/os-release` file is NOT clobbered.
 */
class ElysiumOsReleaseOverlayTest {

    @Test
    fun `apply writes the four canonical files`() {
        val rootfs = newRootfs()
        try {
            val overlay = ElysiumOsReleaseOverlay(
                elysiumVersion = "1.0.0-TITAN+12.1",
                baseDistro = "debian-stable-13",
                channel = ElysiumOsReleaseOverlay.Channel.STABLE
            )

            val applied = overlay.apply(rootfs)

            assertTrue(applied.osRelease.isFile)
            assertTrue(applied.version.isFile)
            assertTrue(applied.baseDistro.isFile)
            assertTrue(applied.channel.isFile)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `os-release snippet contains the ADR-003 markers`() {
        val rootfs = newRootfs()
        try {
            val overlay = ElysiumOsReleaseOverlay(
                elysiumVersion = "1.0.0-TITAN+12.1",
                baseDistro = "debian-stable-13",
                channel = ElysiumOsReleaseOverlay.Channel.BETA
            )
            val applied = overlay.apply(rootfs)
            val text = applied.osRelease.readText()

            assertTrue("must declare NAME", text.contains("""NAME="Elysium Vanguard Linux""""))
            assertTrue("must declare ID=elysium", text.contains("ID=elysium"))
            assertTrue("must declare ID_LIKE=debian", text.contains("ID_LIKE=debian"))
            assertTrue("must declare PRETTY_NAME", text.contains("""PRETTY_NAME="Elysium Vanguard Linux""""))
            assertTrue("must declare VARIANT", text.contains("""VARIANT="Android Runtime Edition""""))
            assertTrue("must embed ELYSIUM_VERSION", text.contains("ELYSIUM_VERSION=1.0.0-TITAN+12.1"))
            assertTrue("must embed ELYSIUM_BASE", text.contains("ELYSIUM_BASE=debian-stable-13"))
            assertTrue("must embed ELYSIUM_CHANNEL=beta", text.contains("ELYSIUM_CHANNEL=beta"))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `elysium metadata files have the expected contents`() {
        val rootfs = newRootfs()
        try {
            val overlay = ElysiumOsReleaseOverlay(
                elysiumVersion = "1.0.0-TITAN+12.1",
                baseDistro = "ubuntu-noble-24.04",
                channel = ElysiumOsReleaseOverlay.Channel.NIGHTLY
            )
            val applied = overlay.apply(rootfs)

            assertEquals("1.0.0-TITAN+12.1\n", applied.version.readText())
            assertEquals("ubuntu-noble-24.04\n", applied.baseDistro.readText())
            assertEquals("nightly\n", applied.channel.readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `apply is idempotent - second call overwrites in place`() {
        val rootfs = newRootfs()
        try {
            val first = ElysiumOsReleaseOverlay(
                elysiumVersion = "1.0.0-TITAN+12.1",
                baseDistro = "debian-stable-13",
                channel = ElysiumOsReleaseOverlay.Channel.STABLE
            )
            val second = ElysiumOsReleaseOverlay(
                elysiumVersion = "1.0.0-TITAN+12.2",
                baseDistro = "debian-stable-13",
                channel = ElysiumOsReleaseOverlay.Channel.STABLE
            )
            first.apply(rootfs)
            val secondApplied = second.apply(rootfs)

            assertEquals("1.0.0-TITAN+12.2\n", secondApplied.version.readText())
            assertTrue(
                "os-release must reflect the new version",
                secondApplied.osRelease.readText().contains("ELYSIUM_VERSION=1.0.0-TITAN+12.2")
            )
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `remove deletes the four files but does not touch other content`() {
        val rootfs = newRootfs()
        try {
            File(rootfs, "etc").mkdirs()
            File(rootfs, "etc/apt").mkdirs()
            File(rootfs, "etc/os-release").writeText("NAME=Debian\n")
            File(rootfs, "etc/apt/sources.list").writeText("deb http://deb.debian.org/debian trixie main\n")
            val overlay = ElysiumOsReleaseOverlay(
                "1.0.0-TITAN+12.1",
                "debian-stable-13",
                ElysiumOsReleaseOverlay.Channel.STABLE
            )
            overlay.apply(rootfs)
            // Sanity: the four overlay files exist.
            assertTrue(File(rootfs, "etc/os-release.d/elysium.conf").isFile)
            assertTrue(File(rootfs, "etc/elysium/VERSION").isFile)

            overlay.remove(rootfs)

            assertFalse(
                "elysium.conf must be gone after remove",
                File(rootfs, "etc/os-release.d/elysium.conf").isFile
            )
            assertFalse(
                "/etc/elysium/VERSION must be gone after remove",
                File(rootfs, "etc/elysium/VERSION").isFile
            )
            assertFalse(
                "/etc/elysium/BASE_DISTRO must be gone after remove",
                File(rootfs, "etc/elysium/BASE_DISTRO").isFile
            )
            assertFalse(
                "/etc/elysium/CHANNEL must be gone after remove",
                File(rootfs, "etc/elysium/CHANNEL").isFile
            )
            // Upstream content must NOT be touched.
            assertEquals(
                "NAME=Debian\n",
                File(rootfs, "etc/os-release").readText()
            )
            assertEquals(
                "deb http://deb.debian.org/debian trixie main\n",
                File(rootfs, "etc/apt/sources.list").readText()
            )
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `remove is a no-op when the overlay was never applied`() {
        val rootfs = newRootfs()
        try {
            val overlay = ElysiumOsReleaseOverlay(
                "1.0.0-TITAN+12.1",
                "debian-stable-13",
                ElysiumOsReleaseOverlay.Channel.STABLE
            )
            // No exception, no side effects.
            overlay.remove(rootfs)
            assertTrue(rootfs.isDirectory)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `apply creates os-release-d and etc-elysium directories on demand`() {
        val rootfs = newRootfs()
        try {
            val overlay = ElysiumOsReleaseOverlay(
                "1.0.0-TITAN+12.1",
                "alpine-3.21",
                ElysiumOsReleaseOverlay.Channel.STABLE
            )
            // No pre-existing etc/os-release.d or etc/elysium.
            assertFalse(File(rootfs, "etc/os-release.d").exists())
            assertFalse(File(rootfs, "etc/elysium").exists())

            overlay.apply(rootfs)

            assertTrue("etc/os-release.d must be created", File(rootfs, "etc/os-release.d").isDirectory)
            assertTrue("etc/elysium must be created", File(rootfs, "etc/elysium").isDirectory)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `apply rejects a missing rootfs directory`() {
        val overlay = ElysiumOsReleaseOverlay(
            "1.0.0-TITAN+12.1",
            "debian-stable-13",
            ElysiumOsReleaseOverlay.Channel.STABLE
        )
        overlay.apply(File("/no/such/elysium/rootfs"))
    }

    private fun newRootfs(): File {
        val rootfs = Files.createTempDirectory("elysium-overlay").toFile()
        return rootfs
    }
}
