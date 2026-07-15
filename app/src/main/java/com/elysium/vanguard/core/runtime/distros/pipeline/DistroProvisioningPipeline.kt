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
import java.io.File
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Phase 16 — end-to-end provisioning pipeline.
 *
 * Master order §11 says: "a guest rootfs is *installed* (extracted
 * from the upstream tarball), *overlaid* with the Elysium identity
 * (`/etc/os-release.d/elysium.conf`, `/etc/elysium/{VERSION,
 * BASE_DISTRO, CHANNEL}`), *profiled* (a package set + a
 * SystemLayer per [ElysiumProfile]), and *signed* (Ed25519 over
 * the layer manifest). Until Phase 16 these were four separate
 * pieces wired by hand in the installer's `try` block. This class
 * is the wiring.
 *
 * The pipeline is the **last mile** of provisioning. It does not
 * download the rootfs (the [com.elysium.vanguard.core.runtime.distros.DistroInstaller]
 * does that), and it does not execute `apt-get` (the install
 * command is built and returned, but the actual execution lives
 * in the installer's chroot). It does the file work that the
 * installer can do *after* the rootfs is on disk.
 *
 * The pipeline is pure JVM-testable end-to-end:
 *
 *   - The rootfs can be a real directory built from a real tarball
 *     (the test suite uses Apache Commons Compress to mint one).
 *   - The layer tarball can be a real (empty) tarball in a temp
 *     dir.
 *   - The signing keypair can be a real Ed25519 keypair minted in
 *     the test.
 *
 * The pipeline's job is to make the "provisioning is done" boundary
 * explicit. After [provision] returns successfully, the runtime
 * has a rootfs that
 *
 *   1. carries the Elysium os-release overlay,
 *   2. has a SystemLayer applied (if the caller asked for one),
 *   3. has a signed manifest on disk, and
 *   4. has a verified signature — the pipeline always re-verifies
 *      the signature it just produced, so a misbehaving signer
 *      cannot reach the caller unnoticed.
 */
class DistroProvisioningPipeline(
    private val osReleaseOverlay: ElysiumOsReleaseOverlay,
    private val profileInstaller: ProfileInstaller,
    private val layerApplier: SystemLayerApplier,
    private val manifestSigner: ManifestSigner,
    private val manifestVerifier: ManifestVerifier,
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    /**
     * The "channel" stamp written into the manifest. Defaults to
     * STABLE; production wires this from the build host's env.
     */
    private val channel: UpdateChannel = UpdateChannel.STABLE,
    /**
     * Optional hook for the host's structured-log bridge. The
     * default is a no-op (the test suite does not care about
     * logging; production wires this to its Timber / Logcat
     * bridge).
     */
    private val logger: ProvisioningLogger = ProvisioningLogger.NoOp
) {

    /**
     * Provision [rootfsDir] for the given [profile] on [family].
     *
     * The steps, in order:
     *
     *   1. Apply the Elysium os-release overlay.
     *   2. Build the profile plan (install command + layer metadata).
     *   3. If [layerTarball] is non-null, mint a [SystemLayer] from
     *      it (id, displayName, version from the [profile]) and
     *      apply it through [SystemLayerApplier] (which SHA-256
     *      verifies + extracts the tarball).
     *   4. Build a [SystemLayerManifest] (1 layer = the profile
     *      layer, or 0 if no tarball was given).
     *   5. Write `manifest.json` next to the rootfs, sign it
     *      (Ed25519), and write `manifest.json.sig`.
     *   6. Re-verify the signature on the just-written file. A
     *      mismatch is a hard error — the pipeline refuses to
     *      report success without a verifiable signature.
     *
     * Returns a [DistroProvisioningResult] with the manifest, the
     * signature, and the per-step artifacts. Throws
     * [java.io.IOException] (or a subtype) on any failure; the
     * caller is expected to be in a `try` block with a staging
     * directory to clean up.
     */
    @Throws(java.io.IOException::class)
    fun provision(
        rootfsDir: File,
        profile: ElysiumProfile,
        family: DistroFamily,
        layerTarball: File?,
        manifestDir: File,
        generatedAtMs: Long = System.currentTimeMillis()
    ): DistroProvisioningResult {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        require(manifestDir.isDirectory) { "manifestDir is not a directory: $manifestDir" }

        // 1. Elysium identity overlay.
        logger.step("apply-os-release")
        val overlay = osReleaseOverlay.apply(rootfsDir)
        logger.done("apply-os-release", mapOf("os-release" to overlay.osRelease.absolutePath))

        // 2. Profile plan.
        logger.step("plan-profile")
        val plan = profileInstaller.plan(profile, family)
        logger.done(
            "plan-profile",
            mapOf("command" to plan.installCommand, "layer-id" to plan.layerId)
        )

        // 3. Layer (optional).
        val layers = mutableListOf<SystemLayer>()
        if (layerTarball != null) {
            logger.step("apply-layer")
            require(layerTarball.isFile) { "layerTarball is not a file: $layerTarball" }
            val systemLayer = SystemLayer(
                id = plan.layerId,
                displayName = plan.layerDisplayName,
                version = plan.layerVersion,
                tarball = layerTarball,
                sha256 = ManifestSigner.sha256Hex(layerTarball)
            )
            val layerDir = layerApplier.apply(systemLayer, rootfsDir)
            // The manifest ships with its tarball in the same
            // directory so the device can find it at verify time.
            // We move the tarball next to where the manifest will
            // be written; the JSON's "tarball" field is the bare
            // filename, but the in-memory [SystemLayer] still
            // points at the real file (it must, because the
            // SystemLayer init block requires `tarball.isFile`).
            val shippedTarball = File(manifestDir, layerTarball.name)
            if (shippedTarball.absolutePath != layerTarball.absolutePath) {
                if (shippedTarball.exists()) shippedTarball.delete()
                layerTarball.copyTo(shippedTarball, overwrite = true)
            }
            layers += systemLayer
            logger.done(
                "apply-layer",
                mapOf(
                    "layer-dir" to layerDir.absolutePath,
                    "shipped-tarball" to shippedTarball.absolutePath
                )
            )
        } else {
            logger.step("apply-layer (skipped)")
            logger.done("apply-layer (skipped)", emptyMap())
        }

        // 4. Manifest.
        logger.step("write-manifest")
        val manifest = SystemLayerManifest(
            version = SystemLayerManifest.CURRENT_VERSION,
            channel = channel,
            generatedAtMs = generatedAtMs,
            layers = layers.ifEmpty {
                // The manifest schema requires at least one layer.
                // When the caller didn't ship a layer, we emit a
                // synthetic "identity" layer that just records the
                // overlay application. The layer's tarball is the
                // os-release file itself — a small but real artifact.
                listOf(
                    SystemLayer(
                        id = "elysium-identity",
                        displayName = "Elysium Identity Overlay",
                        version = profile.layerVersion,
                        tarball = overlay.osRelease,
                        sha256 = ManifestSigner.sha256Hex(overlay.osRelease)
                    )
                )
            }
        )
        val manifestFile = File(manifestDir, "manifest.json")
        manifestFile.writeText(manifest.toJson())
        logger.done("write-manifest", mapOf("file" to manifestFile.absolutePath))

        // 5. Sign.
        logger.step("sign-manifest")
        val manifestBytes = manifestFile.readBytes()
        val signatureBytes = try {
            manifestSigner.sign(manifestBytes, privateKey)
        } catch (e: java.security.GeneralSecurityException) {
            throw java.io.IOException("could not sign manifest: ${e.message}", e)
        }
        val sigFile = File(manifestDir, "manifest.json.sig")
        sigFile.writeBytes(signatureBytes)
        logger.done("sign-manifest", mapOf("sig-bytes" to signatureBytes.size.toString()))

        // 6. Re-verify. A misbehaving signer must not pass.
        logger.step("verify-manifest")
        val verify = try {
            manifestVerifier.verify(manifestBytes, signatureBytes, publicKey)
        } catch (e: java.security.GeneralSecurityException) {
            throw java.io.IOException("could not re-verify manifest: ${e.message}", e)
        }
        if (!verify) {
            throw java.io.IOException(
                "manifest signature did not verify after signing; refusing to report success"
            )
        }
        logger.done("verify-manifest", mapOf("ok" to "true"))

        return DistroProvisioningResult(
            distroProfile = profile,
            family = family,
            rootfsDir = rootfsDir,
            manifest = manifest,
            manifestFile = manifestFile,
            signatureBytes = signatureBytes,
            signatureFile = sigFile,
            appliedOverlay = overlay,
            profilePlan = plan,
            generatedAtMs = generatedAtMs
        )
    }
}

/**
 * The pipeline's output. Carries the manifest, the signature,
 * the per-step artifacts, and the timestamps. The UI surfaces
 * the manifest's `channel` and `generatedAtMs`; the runtime
 * keeps the [signatureFile] and [manifestFile] on disk so the
 * next boot can re-verify the signature.
 */
data class DistroProvisioningResult(
    val distroProfile: ElysiumProfile,
    val family: DistroFamily,
    val rootfsDir: File,
    val manifest: SystemLayerManifest,
    val manifestFile: File,
    val signatureBytes: ByteArray,
    val signatureFile: File,
    val appliedOverlay: ElysiumOsReleaseOverlay.AppliedOverlay,
    val profilePlan: ProfileInstaller.Plan,
    val generatedAtMs: Long
) {
    /** Hex form of the signature. */
    val signatureHex: String
        get() = signatureBytes.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DistroProvisioningResult) return false
        if (distroProfile != other.distroProfile) return false
        if (family != other.family) return false
        if (rootfsDir != other.rootfsDir) return false
        if (manifest != other.manifest) return false
        if (manifestFile != other.manifestFile) return false
        if (!signatureBytes.contentEquals(other.signatureBytes)) return false
        if (signatureFile != other.signatureFile) return false
        if (appliedOverlay != other.appliedOverlay) return false
        if (profilePlan != other.profilePlan) return false
        if (generatedAtMs != other.generatedAtMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = distroProfile.hashCode()
        result = 31 * result + family.hashCode()
        result = 31 * result + rootfsDir.hashCode()
        result = 31 * result + manifest.hashCode()
        result = 31 * result + manifestFile.hashCode()
        result = 31 * result + signatureBytes.contentHashCode()
        result = 31 * result + signatureFile.hashCode()
        result = 31 * result + appliedOverlay.hashCode()
        result = 31 * result + profilePlan.hashCode()
        result = 31 * result + generatedAtMs.hashCode()
        return result
    }
}

/**
 * The pipeline's structured-log seam. Production wires this to
 * Timber / Logcat; the test suite uses the no-op.
 */
interface ProvisioningLogger {
    fun step(name: String)
    fun done(name: String, detail: Map<String, String>)

    object NoOp : ProvisioningLogger {
        override fun step(name: String) { /* no-op */ }
        override fun done(name: String, detail: Map<String, String>) { /* no-op */ }
    }
}

/**
 * Extension to render a [SystemLayerManifest] as JSON. Kept
 * here (not in the manifest class) so the manifest class stays
 * focused on the in-memory model.
 *
 * We hand-roll the serializer (no `org.json.JSONObject`) because
 * the Android `org.json` is a stub on the unit-test classpath —
 * calls to `JSONObject.toString(int)` throw on the JVM test
 * runtime. The hand-rolled version is a few extra lines but is
 * stable, dependency-free, and produces output that the official
 * [SystemLayerManifest.load] (which DOES use `org.json` to parse
 * it) accepts without round-trip issues.
 */
fun SystemLayerManifest.toJson(): String {
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"version\": ").append(version).append(",\n")
    sb.append("  \"channel\": ").append(jsonString(channel.id)).append(",\n")
    sb.append("  \"generatedAtMs\": ").append(generatedAtMs).append(",\n")
    sb.append("  \"layers\": [")
    if (layers.isNotEmpty()) sb.append("\n")
    layers.forEachIndexed { index, layer ->
        if (index > 0) sb.append(",\n")
        sb.append("    {\n")
        sb.append("      \"id\": ").append(jsonString(layer.id)).append(",\n")
        sb.append("      \"displayName\": ").append(jsonString(layer.displayName)).append(",\n")
        sb.append("      \"version\": ").append(jsonString(layer.version)).append(",\n")
        sb.append("      \"tarball\": ").append(jsonString(layer.tarball.name)).append(",\n")
        sb.append("      \"sha256\": ").append(jsonString(layer.sha256)).append(",\n")
        sb.append("      \"notes\": ").append(jsonString(layer.notes)).append("\n")
        sb.append("    }")
    }
    if (layers.isNotEmpty()) sb.append("\n  ")
    sb.append("]\n")
    sb.append("}")
    return sb.toString()
}

/**
 * Minimal JSON string escaper. Handles the four characters the
 * JSON spec requires (`"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t`)
 * and falls back to a pass-through for everything else (the
 * manifest only carries ASCII identifiers and SHA-256 hex
 * strings, so the "control char must be escaped" edge case
 * never fires in practice).
 */
private fun jsonString(value: String): String {
    val sb = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
