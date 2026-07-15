package com.elysium.vanguard.core.runtime.distros.pipeline

import com.elysium.vanguard.core.runtime.distros.DistroFamily
import com.elysium.vanguard.core.runtime.distros.ElysiumOsReleaseOverlay
import com.elysium.vanguard.core.runtime.distros.layer.ManifestSigner
import com.elysium.vanguard.core.runtime.distros.layer.ManifestVerifier
import com.elysium.vanguard.core.runtime.distros.layer.SystemLayer
import com.elysium.vanguard.core.runtime.distros.layer.SystemLayerApplier
import com.elysium.vanguard.core.runtime.distros.layer.SystemLayerManifest
import com.elysium.vanguard.core.runtime.distros.layer.UpdateChannel
import com.elysium.vanguard.core.runtime.distros.profile.ElysiumProfile
import com.elysium.vanguard.core.runtime.distros.profile.ProfileInstaller
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair

/**
 * Phase 16 — end-to-end provisioning tests.
 *
 * The pipeline is the last mile: a real rootfs dir, a real
 * layer tarball, a real Ed25519 keypair, and the pipeline
 * produces a real signed manifest. The tests pin every step
 * (overlay, plan, apply, write, sign, verify) and the
 * re-verification step that closes the loop.
 *
 * Tests build a real rootfs directory (with a few files
 * typical of an extracted distro) and a real layer tarball
 * with Apache Commons Compress. The signing keypair is a
 * freshly-minted Ed25519 pair.
 */
class DistroProvisioningPipelineTest {

    private val keyPair: KeyPair = ManifestSigner.generateKeyPair()

    private fun newPipeline(
        logger: ProvisioningLogger = ProvisioningLogger.NoOp,
        channel: UpdateChannel = UpdateChannel.STABLE
    ): DistroProvisioningPipeline = DistroProvisioningPipeline(
        osReleaseOverlay = ElysiumOsReleaseOverlay(
            elysiumVersion = "1.0.0-TITAN+16.0",
            baseDistro = "debian-stable-13",
            channel = ElysiumOsReleaseOverlay.Channel.STABLE
        ),
        profileInstaller = ProfileInstaller(),
        layerApplier = SystemLayerApplier(),
        manifestSigner = ManifestSigner,
        manifestVerifier = ManifestVerifier,
        privateKey = keyPair.private,
        publicKey = keyPair.public,
        channel = channel,
        logger = logger
    )

    // --- happy path: layer present ---

    @Test
    fun `provision applies overlay, plan, layer, and signs the manifest`() {
        val (rootfs, layerTarball, manifestDir) = prepareFixture(
            id = "elysium-cli",
            version = "1.0.0",
            layerEntries = listOf(
                LayerEntry("opt/elysium/bin/elysium", "#!/bin/sh\necho elysium\n"),
                LayerEntry("opt/elysium/lib/version.txt", "1.0.0\n")
            )
        )
        try {
            val pipeline = newPipeline()
            val result = pipeline.provision(
                rootfsDir = rootfs,
                profile = ElysiumProfile.LITE,
                family = DistroFamily.DEBIAN,
                layerTarball = layerTarball,
                manifestDir = manifestDir
            )

            // 1. Overlay was applied.
            assertTrue(result.appliedOverlay.osRelease.isFile)
            assertTrue(result.appliedOverlay.version.isFile)
            assertTrue(result.appliedOverlay.baseDistro.isFile)
            assertTrue(result.appliedOverlay.channel.isFile)
            assertTrue(result.appliedOverlay.osRelease.readText().contains("Elysium Vanguard Linux"))

            // 2. Plan was built.
            assertEquals(ElysiumProfile.LITE, result.profilePlan.profile)
            assertEquals(DistroFamily.DEBIAN, result.profilePlan.family)
            assertTrue(result.profilePlan.installCommand.contains("apt-get install"))

            // 3. Layer was applied; the manifest now references
            //    the shipped tarball (sibling of manifest.json).
            assertEquals(1, result.manifest.layers.size)
            val layer = result.manifest.layers.single()
            assertEquals("elysium-profile-lite", layer.id)
            assertEquals("1.0.0", layer.version)
            // The shipped tarball is the bare name next to manifest.json.
            assertEquals(layerTarball.name, layer.tarball.name)
            val shipped = File(manifestDir, layerTarball.name)
            assertTrue("shipped tarball must exist in manifest dir", shipped.isFile)
            // The live layer dir landed at elysium-layer-<id> in rootfs.
            val layerDir = File(rootfs, "elysium-layer-elysium-profile-lite")
            assertTrue(layerDir.isDirectory)
            assertTrue(File(layerDir, "opt/elysium/bin/elysium").isFile)

            // 4. Manifest file was written and the signature file
            //    is exactly 64 bytes (Ed25519).
            assertTrue(result.manifestFile.isFile)
            assertEquals(64, result.signatureBytes.size)
            assertTrue(result.signatureFile.isFile)
            assertEquals(64, result.signatureFile.length())

            // 5. The manifest on disk is valid JSON; the SHA-256
            //    recorded for the layer matches the actual tarball.
            val manifestJson = JSONObject(result.manifestFile.readText())
            assertEquals(1, manifestJson.getInt("version"))
            assertEquals("stable", manifestJson.getString("channel"))
            val layers = manifestJson.getJSONArray("layers")
            assertEquals(1, layers.length())
            val layerObj = layers.getJSONObject(0)
            assertEquals("elysium-profile-lite", layerObj.getString("id"))
            val declaredHash = layerObj.getString("sha256")
            val actualHash = ManifestSigner.sha256Hex(shipped)
            assertEquals(actualHash, declaredHash)

            // 6. The signature is verifiable on disk (re-load the
            //    bytes; the runtime does this on every boot).
            val verified = ManifestVerifier.verify(
                manifestBytes = result.manifestFile.readBytes(),
                signatureBytes = result.signatureFile.readBytes(),
                publicKey = keyPair.public
            )
            assertTrue("signature must verify on disk", verified)
        } finally {
            rootfs.deleteRecursively()
            layerTarball.delete()
            manifestDir.deleteRecursively()
        }
    }

    // --- happy path: no layer (synthetic identity layer) ---

    @Test
    fun `provision with no layer tarball still emits a signed manifest with a synthetic identity layer`() {
        val (rootfs, manifestDir) = prepareRootfsOnly()
        try {
            val pipeline = newPipeline()
            val result = pipeline.provision(
                rootfsDir = rootfs,
                profile = ElysiumProfile.HEADLESS,
                family = DistroFamily.DEBIAN,
                layerTarball = null,
                manifestDir = manifestDir
            )

            assertEquals(1, result.manifest.layers.size)
            val identity = result.manifest.layers.single()
            assertEquals("elysium-identity", identity.id)
            // The synthetic layer's tarball is the os-release
            // file itself; its SHA-256 in the manifest matches
            // the file we just wrote.
            assertEquals("elysium.conf", identity.tarball.name)
            assertEquals(
                ManifestSigner.sha256Hex(result.appliedOverlay.osRelease),
                identity.sha256
            )
            // Signature still verifies.
            val verified = ManifestVerifier.verify(
                result.manifestFile.readBytes(),
                result.signatureBytes,
                keyPair.public
            )
            assertTrue(verified)
        } finally {
            rootfs.deleteRecursively()
            manifestDir.deleteRecursively()
        }
    }

    // --- logger is wired correctly ---

    @Test
    fun `provision logs every step through the logger`() {
        val (rootfs, layerTarball, manifestDir) = prepareFixture(
            id = "x", version = "1.0.0", layerEntries = listOf(
                LayerEntry("opt/x/version.txt", "1.0.0\n")
            )
        )
        try {
            val recording = RecordingLogger()
            val pipeline = newPipeline(logger = recording)
            pipeline.provision(
                rootfsDir = rootfs,
                profile = ElysiumProfile.LITE,
                family = DistroFamily.DEBIAN,
                layerTarball = layerTarball,
                manifestDir = manifestDir
            )
            // Every step must have a `step` and a `done` call.
            for (name in listOf(
                "apply-os-release", "plan-profile", "apply-layer",
                "write-manifest", "sign-manifest", "verify-manifest"
            )) {
                assertTrue(
                    "step $name must have started",
                    recording.steps.contains(name)
                )
                assertTrue(
                    "step $name must have completed",
                    recording.dones.containsKey(name)
                )
            }
        } finally {
            rootfs.deleteRecursively()
            layerTarball.delete()
            manifestDir.deleteRecursively()
        }
    }

    // --- channel flows into the manifest ---

    @Test
    fun `provision honours the configured channel in the manifest`() {
        val (rootfs, manifestDir) = prepareRootfsOnly()
        try {
            val pipeline = newPipeline(channel = UpdateChannel.NIGHTLY)
            val result = pipeline.provision(
                rootfsDir = rootfs,
                profile = ElysiumProfile.HEADLESS,
                family = DistroFamily.DEBIAN,
                layerTarball = null,
                manifestDir = manifestDir
            )
            assertEquals(UpdateChannel.NIGHTLY, result.manifest.channel)
            val manifestJson = JSONObject(result.manifestFile.readText())
            assertEquals("nightly", manifestJson.getString("channel"))
        } finally {
            rootfs.deleteRecursively()
            manifestDir.deleteRecursively()
        }
    }

    // --- re-verify loop: a tampered signature is rejected ---

    @Test
    fun `provision refuses to report success when re-verification fails`() {
        val (rootfs, layerTarball, manifestDir) = prepareFixture(
            id = "x", version = "1.0.0", layerEntries = listOf(
                LayerEntry("opt/x/v.txt", "1.0.0\n")
            )
        )
        try {
            val wrongKey = ManifestSigner.generateKeyPair()
            val pipeline = DistroProvisioningPipeline(
                osReleaseOverlay = ElysiumOsReleaseOverlay(
                    elysiumVersion = "1.0.0",
                    baseDistro = "debian-stable-13",
                    channel = ElysiumOsReleaseOverlay.Channel.STABLE
                ),
                profileInstaller = ProfileInstaller(),
                layerApplier = SystemLayerApplier(),
                manifestSigner = ManifestSigner,
                manifestVerifier = ManifestVerifier,
                privateKey = wrongKey.private, // signs with one key
                publicKey = keyPair.public,    // ...verifies with another
                channel = UpdateChannel.STABLE
            )
            try {
                pipeline.provision(
                    rootfsDir = rootfs,
                    profile = ElysiumProfile.LITE,
                    family = DistroFamily.DEBIAN,
                    layerTarball = layerTarball,
                    manifestDir = manifestDir
                )
                fail("expected IOException: re-verification must fail with mismatched keypair")
            } catch (expected: java.io.IOException) {
                assertTrue(
                    "error message must mention the verify step",
                    expected.message!!.contains("signature did not verify")
                )
            }
        } finally {
            rootfs.deleteRecursively()
            layerTarball.delete()
            manifestDir.deleteRecursively()
        }
    }

    // --- input validation ---

    @Test
    fun `provision rejects a non-directory rootfsDir`() {
        val (_, manifestDir) = prepareRootfsOnly()
        try {
            try {
                newPipeline().provision(
                    rootfsDir = File("/no/such/elysium/rootfs"),
                    profile = ElysiumProfile.LITE,
                    family = DistroFamily.DEBIAN,
                    layerTarball = null,
                    manifestDir = manifestDir
                )
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) { /* */ }
        } finally {
            manifestDir.deleteRecursively()
        }
    }

    @Test
    fun `provision rejects a missing layerTarball file`() {
        val (rootfs, manifestDir) = prepareRootfsOnly()
        try {
            try {
                newPipeline().provision(
                    rootfsDir = rootfs,
                    profile = ElysiumProfile.LITE,
                    family = DistroFamily.DEBIAN,
                    layerTarball = File("/no/such/elysium/layer.tar.gz"),
                    manifestDir = manifestDir
                )
                fail("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) { /* */ }
        } finally {
            rootfs.deleteRecursively()
            manifestDir.deleteRecursively()
        }
    }

    // --- the manifest is re-loadable from disk via the official loader ---

    @Test
    fun `provision writes a manifest that the official loader can read back`() {
        val (rootfs, layerTarball, manifestDir) = prepareFixture(
            id = "elysium-cli", version = "1.0.0",
            layerEntries = listOf(LayerEntry("opt/cli/version.txt", "1.0.0\n"))
        )
        try {
            val result = newPipeline().provision(
                rootfsDir = rootfs,
                profile = ElysiumProfile.LITE,
                family = DistroFamily.DEBIAN,
                layerTarball = layerTarball,
                manifestDir = manifestDir
            )
            // The official loader resolves the layer tarball
            // against the manifest's parent directory; with the
            // tarball sitting next to manifest.json, the load
            // round-trips.
            val reloaded = SystemLayerManifest.load(result.manifestFile)
            assertEquals(result.manifest.layers.size, reloaded.layers.size)
            assertEquals(
                result.manifest.layers.single().id,
                reloaded.layers.single().id
            )
            assertEquals(
                result.manifest.layers.single().sha256,
                reloaded.layers.single().sha256
            )
        } finally {
            rootfs.deleteRecursively()
            layerTarball.delete()
            manifestDir.deleteRecursively()
        }
    }

    // --- helpers ---

    private data class LayerEntry(val path: String, val content: String)

    /**
     * Build a real rootfs dir + a real layer tarball + a real
     * manifest dir. Used by the tests that pass a layer
     * tarball to the pipeline.
     */
    private fun prepareFixture(
        id: String,
        version: String,
        layerEntries: List<LayerEntry>
    ): Triple<File, File, File> {
        val rootfs = makeRootfs()
        val layerTarball = if (layerEntries.isNotEmpty()) {
            val tarball = Files.createTempFile("elysium-$id-$version", ".tar.gz").toFile()
            writeTarGz(tarball, layerEntries)
            tarball
        } else null
        val manifestDir = Files.createTempDirectory("elysium-prov-manifest").toFile()
        return Triple(rootfs, layerTarball!!, manifestDir)
    }

    /**
     * Build a real rootfs dir + a real manifest dir, with no
     * layer tarball. Used by the "no layer" tests.
     */
    private fun prepareRootfsOnly(): Pair<File, File> {
        val rootfs = makeRootfs()
        val manifestDir = Files.createTempDirectory("elysium-prov-manifest").toFile()
        return rootfs to manifestDir
    }

    private fun makeRootfs(): File {
        val rootfs = Files.createTempDirectory("elysium-prov-rootfs").toFile()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "var/lib/dpkg").mkdirs()
        File(rootfs, "etc/os-release").writeText(
            """
            PRETTY_NAME="Debian GNU/Linux trixie/sid"
            NAME="Debian GNU/Linux"
            ID=debian
            """.trimIndent() + "\n"
        )
        File(rootfs, "var/lib/dpkg/status").writeText(
            "Package: bash\nStatus: install ok installed\n"
        )
        return rootfs
    }

    private fun writeTarGz(out: File, entries: List<LayerEntry>) {
        GzipCompressorOutputStream(out.outputStream().buffered()).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                for (entry in entries) {
                    val tarEntry = TarArchiveEntry(entry.path)
                    val bytes = entry.content.toByteArray(Charsets.UTF_8)
                    tarEntry.size = bytes.size.toLong()
                    tar.putArchiveEntry(tarEntry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    private class RecordingLogger : ProvisioningLogger {
        val steps: MutableList<String> = mutableListOf()
        val dones: MutableMap<String, Map<String, String>> = mutableMapOf()
        override fun step(name: String) { steps += name }
        override fun done(name: String, detail: Map<String, String>) { dones[name] = detail }
    }
}
