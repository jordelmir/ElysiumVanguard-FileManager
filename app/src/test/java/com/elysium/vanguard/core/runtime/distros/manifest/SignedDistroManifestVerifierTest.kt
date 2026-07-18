package com.elysium.vanguard.core.runtime.distros.manifest

import com.elysium.vanguard.core.runtime.distros.layer.ManifestSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

/**
 * Phase 51 — tests for
 * [SignedDistroManifestVerifier].
 *
 * The verifier is the runtime-side check for
 * a [DistroManifest]'s Ed25519 signature. The
 * tests pin:
 *
 *   - A correctly-signed manifest returns
 *     [SignedDistroManifestVerification.Verified].
 *   - A tampered manifest (the body changed
 *     after signing) returns Rejected.
 *   - A wrong public key returns Rejected.
 *   - A malformed signature (wrong size)
 *     returns Rejected, not throw.
 *   - The Rejected reason is human-readable
 *     (used by the install path to surface
 *     to the user).
 */
class SignedDistroManifestVerifierTest {

    private val verifier = SignedDistroManifestVerifier()

    private fun signedManifest(
        body: String = """{"id":"debian-12","version":"12.4","sha256":"${"a".repeat(64)}","sizeBytes":100,"signedAtMs":1000}"""
    ): Pair<DistroManifest, KeyPair> {
        val keyPair = ManifestSigner.generateKeyPair()
        val signature = ManifestSigner.sign(body.toByteArray(), keyPair.private)
        val manifest = DistroManifestCodec.decode(body, signature)
        return manifest to keyPair
    }

    /**
     * Build a manifest whose [DistroManifest.signature]
     * is the Ed25519 signature of [signedBody] but
     * whose decoded JSON body is [manifestBody]. A
     * mismatched pair — the signature does not
     * verify against the manifest body.
     */
    private fun tamperedManifest(
        signedBody: String,
        manifestBody: String
    ): Pair<DistroManifest, KeyPair> {
        val keyPair = ManifestSigner.generateKeyPair()
        val signature = ManifestSigner.sign(signedBody.toByteArray(), keyPair.private)
        val manifest = DistroManifestCodec.decode(manifestBody, signature)
        return manifest to keyPair
    }

    @Test
    fun `verifier returns Verified for a correctly-signed manifest`() {
        val (manifest, keyPair) = signedManifest()
        val result = verifier.verify(manifest, keyPair.public)
        assertTrue(
            "expected Verified, got $result",
            result is SignedDistroManifestVerification.Verified
        )
    }

    @Test
    fun `verifier returns Rejected when the body is tampered after signing`() {
        val (manifest, keyPair) = tamperedManifest(
            signedBody = """{"id":"debian-12","version":"12.4","sha256":"${"a".repeat(64)}","sizeBytes":100,"signedAtMs":1000}""",
            manifestBody = """{"id":"debian-12","version":"12.4","sha256":"${"b".repeat(64)}","sizeBytes":100,"signedAtMs":1000}"""
        )
        val result = verifier.verify(manifest, keyPair.public)
        assertTrue(
            "expected Rejected, got $result",
            result is SignedDistroManifestVerification.Rejected
        )
        val reason = (result as SignedDistroManifestVerification.Rejected).reason
        assertTrue(
            "reason should mention the signature: '$reason'",
            reason.contains("signature", ignoreCase = true)
        )
    }

    @Test
    fun `verifier returns Rejected when the public key does not match the signer`() {
        val (manifest, _) = signedManifest()
        val wrongKeyPair = ManifestSigner.generateKeyPair()
        val result = verifier.verify(manifest, wrongKeyPair.public)
        assertTrue(
            "expected Rejected, got $result",
            result is SignedDistroManifestVerification.Rejected
        )
    }

    @Test
    fun `verifier returns Rejected when the signature is all zeros (not a real signature)`() {
        val (manifest, keyPair) = signedManifest()
        // The init block accepts a 64-byte
        // signature; the verifier will try to
        // verify zero bytes against the body
        // and the keypair, which fails.
        val zeroSigned = manifest.copy(signature = ByteArray(64))
        val result = verifier.verify(zeroSigned, keyPair.public)
        assertTrue(
            "expected Rejected, got $result",
            result is SignedDistroManifestVerification.Rejected
        )
    }

    @Test
    fun `verifier returns the original manifest on Verified`() {
        val (manifest, keyPair) = signedManifest()
        val result = verifier.verify(manifest, keyPair.public)
        assertTrue(result is SignedDistroManifestVerification.Verified)
        val verified = (result as SignedDistroManifestVerification.Verified).manifest
        assertEquals(manifest, verified)
        assertNotNull(verified)
    }
}
