package com.elysium.vanguard.core.runtime.distros

import com.elysium.vanguard.core.runtime.distros.layer.ManifestSigner
import com.elysium.vanguard.core.runtime.distros.layer.ManifestVerifier
import com.elysium.vanguard.core.runtime.distros.layer.SystemLayerApplier
import com.elysium.vanguard.core.runtime.distros.pipeline.DistroProvisioningPipeline
import com.elysium.vanguard.core.runtime.distros.profile.ElysiumProfile
import com.elysium.vanguard.core.runtime.distros.profile.ProfileInstaller
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair

/**
 * Phase 17 — wiring tests for [DistroManager.installBlocking] +
 * the [DistroProvisioningPipeline].
 *
 * The test mints a real rootfs tarball in a temp dir, points a
 * fake [DistroHttpDownloader] at it, builds a real
 * [DistroProvisioningPipeline] with a freshly-generated
 * Ed25519 keypair, and calls [DistroManager.installBlocking].
 * The assertions are end-to-end:
 *
 *   - The rootfs has the four Elysium identity files
 *     (`/etc/os-release.d/elysium.conf` + `/etc/elysium/{...}`).
 *   - A signed `manifest.json` + `manifest.json.sig` were
 *     written next to the rootfs.
 *   - The signature verifies on disk (re-load the bytes and
 *     call [ManifestVerifier.verify]).
 *   - The original upstream files (e.g. `/etc/os-release`,
 *     `bin/sh`) are intact.
 */
class DistroManagerProvisioningTest {

    private val keyPair: KeyPair = ManifestSigner.generateKeyPair()

    private fun newManager(
        archive: File,
        baseDir: File,
        distro: Distro? = null
    ): DistroManager = DistroManager(
        baseDir = baseDir,
        downloader = DistroHttpDownloader { archive.inputStream().buffered() },
        provisioningPipeline = DistroProvisioningPipeline(
            osReleaseOverlay = ElysiumOsReleaseOverlay(
                elysiumVersion = "1.0.0-TITAN+17.0",
                baseDistro = "test-distro",
                channel = ElysiumOsReleaseOverlay.Channel.STABLE
            ),
            profileInstaller = ProfileInstaller(),
            layerApplier = SystemLayerApplier(),
            manifestSigner = ManifestSigner,
            manifestVerifier = ManifestVerifier,
            privateKey = keyPair.private,
            publicKey = keyPair.public
        ),
        distroResolver = distro?.let { d -> { _ -> d } } ?: { null }
    )

    @Test
    fun `installBlocking extracts rootfs, applies overlay, and signs the manifest`() {
        val baseDir = Files.createTempDirectory("elysium-mgr-base").toFile()
        val (archive, distro) = prepareRootfsFixture(
            id = "test-distro",
            family = DistroFamily.DEBIAN,
            rootfsKind = RootfsKind.TarGz
        )
        try {
            val manager = newManager(archive, baseDir, distro)
            val result = manager.installBlocking(distro.id)
            assertTrue(
                "install must succeed; failure was: ${result.exceptionOrNull()?.message}",
                result.isSuccess
            )
            val rootfs = result.getOrThrow()
            assertTrue(rootfs.isDirectory)
            // Rootfs is healthy (installer validates it).
            val health = RootfsHealth.inspect(rootfs)
            assertTrue(
                "rootfs must be healthy; reason=${health.reason}",
                health.isHealthy
            )
            // Overlay files exist.
            assertTrue(
                "os-release overlay must exist",
                File(rootfs, "etc/os-release.d/elysium.conf").isFile
            )
            assertTrue(
                "VERSION file must exist",
                File(rootfs, "etc/elysium/VERSION").isFile
            )
            assertTrue(
                "BASE_DISTRO file must exist",
                File(rootfs, "etc/elysium/BASE_DISTRO").isFile
            )
            assertTrue(
                "CHANNEL file must exist",
                File(rootfs, "etc/elysium/CHANNEL").isFile
            )
            // Overlay is real Elysium content.
            val osReleaseOverlay =
                File(rootfs, "etc/os-release.d/elysium.conf").readText()
            assertTrue(
                "overlay must say Elysium Vanguard Linux",
                osReleaseOverlay.contains("Elysium Vanguard Linux")
            )
            // The original /etc/os-release is intact.
            assertTrue(
                "upstream /etc/os-release must remain",
                File(rootfs, "etc/os-release").isFile
            )
            // bin/sh is intact.
            assertTrue(
                "upstream bin/sh must remain",
                File(rootfs, "bin/sh").isFile
            )
            // The signed manifest is next to the rootfs.
            val manifestFile = File(rootfs.parentFile, "manifest/manifest.json")
            val sigFile = File(rootfs.parentFile, "manifest/manifest.json.sig")
            assertTrue("manifest.json must exist at $manifestFile", manifestFile.isFile)
            assertTrue("manifest.json.sig must exist at $sigFile", sigFile.isFile)
            // The signature is 64 raw bytes (Ed25519).
            assertEquals(64, sigFile.length())
            // The signature verifies.
            val verified = ManifestVerifier.verify(
                manifestBytes = manifestFile.readBytes(),
                signatureBytes = sigFile.readBytes(),
                publicKey = keyPair.public
            )
            assertTrue("manifest signature must verify", verified)
        } finally {
            baseDir.deleteRecursively()
            archive.delete()
        }
    }

    @Test
    fun `installBlocking fails when the rootfs is unhealthy`() {
        val baseDir = Files.createTempDirectory("elysium-mgr-base-bad").toFile()
        // Mint a tarball that RootfsHealth will reject: empty
        // (no /etc/os-release, no bin/sh). The installer must
        // surface the failure and the manager must wrap it in
        // a failed Result.
        val archive = Files.createTempFile("bad-rootfs", ".tar.gz").toFile()
        writeTarGz(archive, listOf("etc/hostname" to "elysium-test\n"))
        val distro = testDistro(
            id = "bad-distro",
            family = DistroFamily.DEBIAN,
            rootfsKind = RootfsKind.TarGz
        )
        try {
            val manager = newManager(archive, baseDir, distro)
            val result = manager.installBlocking(distro.id)
            assertTrue("install must fail on unhealthy rootfs", result.isFailure)
            val err = result.exceptionOrNull()
            assertNotNull("failure must have a non-null cause", err)
            assertTrue(
                "error must mention the health check",
                err!!.message!!.contains("rootfs") || err.message!!.contains("shell")
            )
            // No manifest directory is left behind.
            assertTrue(
                "no manifest dir must remain after failed install",
                !File(baseDir, "bad-distro/manifest").exists()
            )
        } finally {
            baseDir.deleteRecursively()
            archive.delete()
        }
    }

    @Test
    fun `installBlocking without a pipeline still applies the fallback overlay`() {
        // The legacy path: no pipeline, just the fallback
        // overlay. The installer's overlay step writes the
        // four identity files; no manifest is produced.
        val baseDir = Files.createTempDirectory("elysium-mgr-legacy").toFile()
        val (archive, distro) = prepareRootfsFixture(
            id = "legacy-distro",
            family = DistroFamily.MUSL,
            rootfsKind = RootfsKind.TarGz
        )
        try {
            val manager = DistroManager(
                baseDir = baseDir,
                downloader = DistroHttpDownloader { archive.inputStream().buffered() },
                distroResolver = { _ -> distro }
                // no provisioningPipeline
            )
            val result = manager.installBlocking(distro.id)
            assertTrue(result.isSuccess)
            val rootfs = result.getOrThrow()
            // The fallback overlay (default version) was applied
            // by the installer, not the pipeline.
            assertTrue(
                File(rootfs, "etc/os-release.d/elysium.conf").isFile
            )
            val overlay = File(rootfs, "etc/os-release.d/elysium.conf").readText()
            assertTrue(overlay.contains("Elysium Vanguard Linux"))
            // No manifest dir was created.
            assertTrue(
                "no manifest dir must exist on the legacy path",
                !File(rootfs.parentFile, "manifest").exists()
            )
        } finally {
            baseDir.deleteRecursively()
            archive.delete()
        }
    }

    @Test
    fun `installBlocking rejects an unknown distro id`() {
        val baseDir = Files.createTempDirectory("elysium-mgr-unknown").toFile()
        try {
            val manager = DistroManager(
                baseDir = baseDir,
                downloader = DistroHttpDownloader { _ ->
                    error("downloader must not be called")
                }
            )
            val result = manager.installBlocking("no-such-distro")
            assertTrue(result.isFailure)
            assertTrue(
                "error must mention the unknown distro",
                result.exceptionOrNull()!!.message!!.contains("Unknown distro")
            )
        } finally {
            baseDir.deleteRecursively()
        }
    }

    // --- helpers ---

    private data class RootfsFixture(val archive: File, val distro: Distro)

    /**
     * Build a real (small) rootfs tarball + a matching [Distro]
     * whose catalog entry we can use with the installer.
     * `stripComponents` is 0 so the tarball's first entries
     * become the rootfs root, and `sha256` is null so the
     * installer skips the archive SHA-256 check.
     */
    private fun prepareRootfsFixture(
        id: String,
        family: DistroFamily,
        rootfsKind: RootfsKind
    ): RootfsFixture {
        val archive = Files.createTempFile("elysium-mgr-$id", ".tar.gz").toFile()
        writeTarGz(
            archive,
            listOf(
                "etc/os-release" to "PRETTY_NAME=\"Test Distro\"\nID=test-distro\n",
                "etc/hostname" to "elysium-test\n",
                "bin/sh" to "#!/bin/sh\necho test\n"
            )
        )
        return RootfsFixture(archive, testDistro(id, family, rootfsKind))
    }

    private fun testDistro(
        id: String,
        family: DistroFamily,
        rootfsKind: RootfsKind
    ): Distro = Distro(
        id = id,
        displayName = "Test $id",
        family = family,
        version = "1.0.0",
        approxSizeBytes = 8L * 1024 * 1024,
        minAndroidVersion = 26,
        rootfsUrl = "file://$id.tar.gz",
        rootfsKind = rootfsKind,
        bootstrapCommand = null,
        packageManager = when (family) {
            DistroFamily.DEBIAN -> "apt"
            DistroFamily.MUSL -> "apk"
            DistroFamily.ARCH -> "pacman"
        },
        homepage = "https://example.com/$id",
        sha256 = null,
        stripComponents = 0
    )

    private fun writeTarGz(out: File, entries: List<Pair<String, String>>) {
        GzipCompressorOutputStream(out.outputStream().buffered()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for ((path, content) in entries) {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    val tarEntry = TarArchiveEntry(path)
                    tarEntry.size = bytes.size.toLong()
                    tar.putArchiveEntry(tarEntry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
    }
}
