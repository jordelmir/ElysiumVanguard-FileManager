package com.elysium.vanguard.core.runtime.distros.manifest

import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroInstaller
import java.io.File
import java.io.IOException
import java.security.PublicKey

/**
 * Phase 51 — the install entry point for a
 * signed distro manifest.
 *
 * The function is a thin wrapper over the
 * existing [DistroInstaller.install] that
 * inserts a manifest verification step BEFORE
 * the rootfs download. The flow is:
 *
 * 1. Verify [manifest]'s signature against
 *    [publicKey] via
 *    [SignedDistroManifestVerifier]. A failed
 *    verification throws [IOException] with a
 *    typed message; the install aborts before
 *    any disk write.
 * 2. Verify the manifest's `id` matches
 *    [distro]'s `id`. A mismatch is a hard
 *    error.
 * 3. Delegate to
 *    [DistroInstaller.install] with
 *    `distro.copy(sha256 = manifest.sha256)`.
 *    The existing `VERIFYING` stage then
 *    checks the rootfs's hash against the
 *    manifest's declared `sha256`.
 *
 * The function is a top-level helper rather
 * than a method on [DistroInstaller] so the
 * existing class signature stays untouched.
 * The new method depends on the existing
 * install path; the existing class is
 * unaware of the new path. A future
 * refactor can fold the two together.
 *
 * @param installer the existing installer
 *   to delegate to.
 * @param distro the distro to install.
 * @param baseDir the install base directory.
 * @param manifest the signed manifest.
 * @param publicKey the Ed25519 public key.
 *
 * @throws IOException on a failed signature
 *   verification, an id mismatch, or any
 *   install-stage failure.
 */
@Throws(IOException::class)
fun installWithSignedManifest(
    installer: DistroInstaller,
    distro: Distro,
    baseDir: File,
    manifest: DistroManifest,
    publicKey: PublicKey
): File {
    val verifier = SignedDistroManifestVerifier()
    val verification = verifier.verify(manifest, publicKey)
    if (verification is SignedDistroManifestVerification.Rejected) {
        throw IOException(
            "signed-manifest verification failed for ${distro.id}: ${verification.reason}"
        )
    }
    if (manifest.id != distro.id) {
        throw IOException(
            "manifest id mismatch: distro='${distro.id}', manifest='${manifest.id}'"
        )
    }
    // Trust the manifest's sha256 for the
    // rootfs hash check. The installer's
    // existing VERIFYING stage will check the
    // downloaded archive against this value.
    val trusted = distro.copy(sha256 = manifest.sha256)
    return installer.install(trusted, baseDir)
}
