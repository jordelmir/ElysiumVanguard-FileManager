package com.elysium.vanguard.core.runtime.distros.manifest

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.DistroHttpDownloader
import com.elysium.vanguard.core.runtime.distros.DistroInstaller
import com.elysium.vanguard.core.runtime.distros.RootfsKind
import com.elysium.vanguard.core.runtime.distros.layer.ManifestSigner
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Phase 51 — integration test for
 * [installWithSignedManifest].
 *
 * The flow under test:
 *
 * 1. Build a small but valid rootfs
 *    (tar.gz with /etc/os-release and /bin/sh)
 *    in a temp directory.
 * 2. Compute its SHA-256.
 * 3. Sign a [DistroManifest] declaring that
 *    hash.
 * 4. Use a fake [DistroHttpDownloader] that
 *    returns the rootfs bytes for the
 *    distro's `rootfsUrl`.
 * 5. Call [installWithSignedManifest] with a
 *    [DistroInstaller] built on the fake
 *    downloader.
 * 6. Assert the install succeeded and the
 *    rootfs is in the expected location.
 *
 * The tests also cover the negative paths:
 * tampered signature, wrong public key,
 * mismatched manifest id.
 */
class SignedDistroManifestInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootfsArchive: File
    private lateinit var rootfsBytes: ByteArray
    private lateinit var rootfsSha256: String
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        // Build a minimal valid rootfs tar.gz.
        // /etc/os-release is required by
        // RootfsHealth; /bin/sh is one of the
        // shell candidates.
        val stagingDir = tempFolder.newFolder("rootfs-src")
        File(stagingDir, "etc").mkdirs()
        File(stagingDir, "bin").mkdirs()
        File(stagingDir, "etc/os-release").writeText(
            """NAME="Elysium Vanguard Linux"
PRETTY_NAME="Elysium Vanguard Linux"
VERSION_ID="1.0"
"""
        )
        File(stagingDir, "bin/sh").writeText("#!/bin/sh\necho hi\n")

        // Tar.gz it via the system tar.
        val tarGzFile = File(tempFolder.newFolder("archive"), "rootfs.tar.gz")
        ProcessBuilder(
            "tar",
            "-czf", tarGzFile.absolutePath,
            "-C", stagingDir.absolutePath,
            "etc/os-release", "bin/sh"
        ).redirectErrorStream(true).start().waitFor()
        rootfsArchive = tarGzFile
        rootfsBytes = rootfsArchive.readBytes()
        rootfsSha256 = sha256Hex(rootfsBytes)
        baseDir = tempFolder.newFolder("install")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun makeDistro(
        id: String = "elysium-test",
        rootfsUrl: String = "https://example.invalid/elysium-test.tar.gz"
    ) = Distro(
        id = id,
        displayName = "Elysium Test",
        family = DistroFamily.DEBIAN,
        version = "1.0.0-test",
        approxSizeBytes = rootfsBytes.size.toLong() * 2L,
        minAndroidVersion = 26,
        rootfsUrl = rootfsUrl,
        rootfsKind = RootfsKind.TarGz,
        bootstrapCommand = null,
        packageManager = "apt",
        homepage = "https://example.invalid",
        sha256 = null
    )

    private fun makeInstaller(): DistroInstaller {
        val downloader = DistroHttpDownloader { url ->
            // The fake downloader always returns
            // the same rootfs bytes regardless
            // of the URL.
            ByteArrayInputStream(rootfsBytes)
        }
        return DistroInstaller(downloader = downloader)
    }

    private fun signedManifest(
        id: String = "elysium-test",
        version: String = "1.0.0-test",
        sha256: String = rootfsSha256,
        sizeBytes: Long = rootfsBytes.size.toLong(),
        signedAtMs: Long = 1_700_000_000_000L
    ): Pair<DistroManifest, java.security.PublicKey> {
        val keyPair = ManifestSigner.generateKeyPair()
        val body = """{"id":"$id","version":"$version","sha256":"$sha256","sizeBytes":$sizeBytes,"signedAtMs":$signedAtMs}"""
        val signature = ManifestSigner.sign(body.toByteArray(), keyPair.private)
        val manifest = DistroManifestCodec.decode(body, signature)
        return manifest to keyPair.public
    }

    /**
     * Build a manifest whose body claims one
     * sha256 but whose signature was computed
     * over a body that claims a different
     * sha256. The signature does not verify
     * against the manifest body.
     */
    private fun tamperedManifest(
        id: String = "elysium-test",
        claimedSha256: String = rootfsSha256,
        signedSha256: String = "f".repeat(64)
    ): Pair<DistroManifest, java.security.PublicKey> {
        val keyPair = ManifestSigner.generateKeyPair()
        val signedBody = """{"id":"$id","version":"1.0.0-test","sha256":"$signedSha256","sizeBytes":${rootfsBytes.size},"signedAtMs":1700000000000}"""
        val manifestBody = """{"id":"$id","version":"1.0.0-test","sha256":"$claimedSha256","sizeBytes":${rootfsBytes.size},"signedAtMs":1700000000000}"""
        val signature = ManifestSigner.sign(signedBody.toByteArray(), keyPair.private)
        val manifest = DistroManifestCodec.decode(manifestBody, signature)
        return manifest to keyPair.public
    }

    // --- positive path ---

    @Test
    fun `installWithSignedManifest succeeds when the signature is valid and the hash matches`() {
        val installer = makeInstaller()
        val distro = makeDistro()
        val (manifest, publicKey) = signedManifest()

        val rootfs = installWithSignedManifest(
            installer = installer,
            distro = distro,
            baseDir = baseDir,
            manifest = manifest,
            publicKey = publicKey
        )

        assertTrue("rootfs dir should exist", rootfs.isDirectory)
        assertTrue(
            "rootfs should contain /etc/os-release",
            File(rootfs, "etc/os-release").isFile
        )
        assertTrue(
            "rootfs should contain /bin/sh",
            File(rootfs, "bin/sh").isFile
        )
    }

    // --- negative paths ---

    @Test
    fun `installWithSignedManifest fails when the manifest body is tampered`() {
        val installer = makeInstaller()
        val distro = makeDistro()
        val (manifest, publicKey) = tamperedManifest()
        try {
            installWithSignedManifest(
                installer = installer,
                distro = distro,
                baseDir = baseDir,
                manifest = manifest,
                publicKey = publicKey
            )
            fail("expected IOException for tampered manifest")
        } catch (expected: IOException) {
            assertTrue(
                "error message should mention verification: ${expected.message}",
                (expected.message ?: "").contains("verification", ignoreCase = true)
            )
        }
    }

    @Test
    fun `installWithSignedManifest fails when the public key does not match the signer`() {
        val installer = makeInstaller()
        val distro = makeDistro()
        val (manifest, _) = signedManifest()
        val wrongKeyPair = ManifestSigner.generateKeyPair()
        try {
            installWithSignedManifest(
                installer = installer,
                distro = distro,
                baseDir = baseDir,
                manifest = manifest,
                publicKey = wrongKeyPair.public
            )
            fail("expected IOException for wrong public key")
        } catch (expected: IOException) {
            assertTrue(
                "error message should mention verification: ${expected.message}",
                (expected.message ?: "").contains("verification", ignoreCase = true)
            )
        }
    }

    @Test
    fun `installWithSignedManifest fails when the manifest id does not match the distro id`() {
        val installer = makeInstaller()
        val distro = makeDistro(id = "elysium-test")
        val (manifest, publicKey) = signedManifest(id = "different-id")
        try {
            installWithSignedManifest(
                installer = installer,
                distro = distro,
                baseDir = baseDir,
                manifest = manifest,
                publicKey = publicKey
            )
            fail("expected IOException for mismatched id")
        } catch (expected: IOException) {
            assertTrue(
                "error message should mention id mismatch: ${expected.message}",
                (expected.message ?: "").contains("id mismatch", ignoreCase = true)
            )
        }
    }

    @Test
    fun `installWithSignedManifest fails when the rootfs hash does not match the manifest hash`() {
        // The signed manifest claims hash X;
        // the actual rootfs has hash Y. The
        // installer's VERIFYING stage catches
        // the mismatch and throws.
        val installer = makeInstaller()
        val distro = makeDistro()
        val (manifest, publicKey) = signedManifest(
            sha256 = "a".repeat(64) // wrong on purpose
        )
        try {
            installWithSignedManifest(
                installer = installer,
                distro = distro,
                baseDir = baseDir,
                manifest = manifest,
                publicKey = publicKey
            )
            fail("expected IOException for hash mismatch")
        } catch (expected: IOException) {
            // The DistroInstaller's existing
            // VERIFYING stage throws
            // "SHA-256 mismatch" — the wrapper
            // just propagates it.
            assertNotNull(expected.message)
        }
    }
}
