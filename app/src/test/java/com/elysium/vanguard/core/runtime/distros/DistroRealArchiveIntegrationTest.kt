package com.elysium.vanguard.core.runtime.distros

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class DistroRealArchiveIntegrationTest {

    @Test
    fun `real catalog archive extracts into a structurally healthy rootfs`() {
        val fixturePath = System.getenv("ELYSIUM_ROOTFS_FIXTURE")
        assumeTrue("Set ELYSIUM_ROOTFS_FIXTURE for the real-archive integration test", !fixturePath.isNullOrBlank())
        val archive = File(fixturePath!!)
        assumeTrue("Fixture must exist", archive.isFile)
        val distro = fixtureDistro()
        val rootfs = kotlin.io.path.createTempDirectory("real-rootfs").toFile()

        val result = archive.inputStream().buffered().use { input ->
            DistroRootfsExtractor().extract(
                stream = input,
                destDir = rootfs,
                kind = distro.rootfsKind,
                stripComponents = distro.stripComponents
            )
        }

        assertTrue("Real archive must contain many entries", result.entriesExtracted > 100)
        assertTrue("Real archive must write payload bytes", result.bytesWritten > 1_000_000L)
        val health = RootfsHealth.inspect(rootfs, result.bytesWritten)
        assertTrue(health.reason ?: "Rootfs is unhealthy", health.isHealthy)
    }

    @Test
    fun `real catalog archive completes the transactional install pipeline`() {
        val fixturePath = System.getenv("ELYSIUM_ROOTFS_FIXTURE")
        assumeTrue("Set ELYSIUM_ROOTFS_FIXTURE for the real-archive integration test", !fixturePath.isNullOrBlank())
        val archive = File(fixturePath!!)
        assumeTrue("Fixture must exist", archive.isFile)
        val distro = fixtureDistro()
        val baseDir = kotlin.io.path.createTempDirectory("real-install").toFile()
        val rootfs = DistroInstaller(
            downloader = DistroHttpDownloader { archive.inputStream().buffered() }
        ).install(distro, baseDir)

        val rootDir = File(baseDir, distro.id)
        val health = RootfsHealth.inspect(rootfs)
        assertTrue(health.reason ?: "Installed rootfs is unhealthy", health.isHealthy)
        assertTrue(File(rootDir, "manifest.json").readText().contains(distro.sha256!!))
        assertFalse(File(rootDir, "install.error").exists())
        assertFalse(File(rootDir, "download.part").exists())
        assertFalse(File(rootDir, "rootfs.staging").exists())
    }

    private fun fixtureDistro(): Distro {
        val id = System.getenv("ELYSIUM_ROOTFS_DISTRO_ID")
            ?.takeIf { it.isNotBlank() }
            ?: "alpine-latest"
        return requireNotNull(DistroCatalog.find(id)) { "Unknown fixture distro: $id" }
    }
}
