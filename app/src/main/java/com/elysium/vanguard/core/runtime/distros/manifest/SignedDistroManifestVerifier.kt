package com.elysium.vanguard.core.runtime.distros.manifest

import com.elysium.vanguard.core.runtime.distros.layer.ManifestVerifier
import java.security.GeneralSecurityException
import java.security.PublicKey

/**
 * Phase 51 — the runtime-side signed manifest
 * verifier.
 *
 * The verifier takes a [DistroManifest] and an
 * Ed25519 public key and returns either
 * [SignedDistroManifestVerification.Verified]
 * (the manifest is authentic; the caller can
 * trust the [DistroManifest.sha256] field) or
 * [SignedDistroManifestVerification.Rejected]
 * (the manifest is tampered, the wrong key,
 * or the signature is malformed).
 *
 * The verifier uses the existing
 * [ManifestVerifier] (Phase 12.4), which
 * calls `Signature.getInstance("Ed25519")` on
 * the JDK 15+ crypto provider. The verify
 * call is the canonical Ed25519 verify
 * pattern: `update(manifestBody)` then
 * `verify(signature)`.
 *
 * ## What the verifier does NOT do
 *
 * - It does NOT check the manifest's `id`
 *   against a Distro. The caller (the
 *   [com.elysium.vanguard.core.runtime.distros.DistroInstaller.installWithSignedManifest])
 *   does that.
 * - It does NOT check `signedAtMs` against
 *   the system clock. A future "manifest
 *   expiry" feature is a Phase 60+ concern.
 * - It does NOT download anything. The
 *   caller (the installer) downloads the
 *   manifest + signature bytes; the
 *   verifier consumes them.
 *
 * ## Thread safety
 *
 * The verifier is a stateless function over
 * its inputs. Multiple threads can call
 * [verify] concurrently with no
 * synchronisation.
 */
class SignedDistroManifestVerifier {

    /**
     * Verify [manifest] against [publicKey].
     * Returns a [SignedDistroManifestVerification]
     * that the caller branches on.
     *
     * The verifier uses [DistroManifest.bodyBytes]
     * — the EXACT bytes the signature was
     * computed over — to avoid JSON
     * canonicalization drift.
     */
    fun verify(
        manifest: DistroManifest,
        publicKey: PublicKey
    ): SignedDistroManifestVerification {
        val valid = try {
            ManifestVerifier.verify(manifest.bodyBytes, manifest.signature, publicKey)
        } catch (failure: GeneralSecurityException) {
            return SignedDistroManifestVerification.Rejected(
                reason = "crypto API rejected the signature: ${failure.message ?: failure.javaClass.simpleName}"
            )
        } catch (failure: IllegalArgumentException) {
            return SignedDistroManifestVerification.Rejected(
                reason = "malformed signature: ${failure.message ?: failure.javaClass.simpleName}"
            )
        }
        return if (valid) {
            SignedDistroManifestVerification.Verified(manifest)
        } else {
            SignedDistroManifestVerification.Rejected(
                reason = "Ed25519 signature does not match manifest body"
            )
        }
    }
}

/**
 * The verifier's result.
 *
 * - [Verified] — the manifest is authentic.
 *   The [DistroManifest.sha256] field can
 *   be trusted as the rootfs's hash.
 * - [Rejected] — the manifest is tampered
 *   or signed with the wrong key. The
 *   [reason] is a human-readable
 *   explanation. The install path must
 *   abort.
 */
sealed class SignedDistroManifestVerification {
    data class Verified(val manifest: DistroManifest) : SignedDistroManifestVerification()
    data class Rejected(val reason: String) : SignedDistroManifestVerification()
}
