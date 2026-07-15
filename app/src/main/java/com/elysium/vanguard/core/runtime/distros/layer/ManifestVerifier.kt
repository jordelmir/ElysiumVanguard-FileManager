package com.elysium.vanguard.core.runtime.distros.layer

import java.io.File
import java.security.PublicKey
import java.security.Signature

/**
 * Phase 12.4 — Ed25519 manifest verifier.
 *
 * Master order §11.5 (ADR-004): every manifest the device
 * accepts is verified against the public key shipped in the
 * APK. A manifest without a valid signature is rejected — no
 * unsigned-update escape hatch.
 *
 * The verifier is **strict**:
 *
 *   - It does not fall back to "trust the SHA-256 alone".
 *   - A failed verification is a typed error, not a degraded
 *     mode.
 *   - It verifies against the **exact bytes** of the manifest,
 *     not a re-serialization, to avoid canonicalization drift.
 *
 * The verifier is the **device side** of the signing flow. The
 * build-pipeline side lives in [ManifestSigner].
 */
object ManifestVerifier {

    private const val ALGORITHM = "Ed25519"
    private const val EXPECTED_SIGNATURE_BYTES = 64

    /**
     * Verify [manifestBytes] against [signatureBytes] using
     * [publicKey]. Returns true when the signature is valid.
     *
     * Returns false (not throws) on a failed verification. The
     * caller turns the false into a typed error so the
     * provisioning pipeline can surface the reason to the
     * user. Throwing on failure would conflate "wrong key" and
     * "crypto API broken", which the caller does not want to
     * treat the same way.
     *
     * Throws [java.security.GeneralSecurityException] on a
     * cryptographically invalid request (e.g. wrong key type
     * for the algorithm, malformed signature bytes). The caller
     * wraps that into a typed error.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray, publicKey: PublicKey): Boolean {
        require(manifestBytes.isNotEmpty()) { "manifest bytes must not be empty" }
        require(signatureBytes.size == EXPECTED_SIGNATURE_BYTES) {
            "Ed25519 signature must be $EXPECTED_SIGNATURE_BYTES bytes; got ${signatureBytes.size}"
        }
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        return verifier.verify(signatureBytes)
    }

    /**
     * Verify the on-disk pair `manifestFile` + `manifestFile.sig`
     * against [publicKey]. Convenience for the provisioning
     * pipeline, which reads both files off the CDN.
     */
    @Throws(java.security.GeneralSecurityException::class, java.io.IOException::class)
    fun verifyFile(manifestFile: File, publicKey: PublicKey): Boolean {
        val manifestBytes = manifestFile.readBytes()
        val signatureFile = File(manifestFile.parentFile, manifestFile.name + ".sig")
        if (!signatureFile.isFile) {
            throw java.io.IOException(
                "no signature file at ${signatureFile.absolutePath}; " +
                    "refusing to apply an unsigned manifest"
            )
        }
        val signatureBytes = signatureFile.readBytes()
        return verify(manifestBytes, signatureBytes, publicKey)
    }
}
