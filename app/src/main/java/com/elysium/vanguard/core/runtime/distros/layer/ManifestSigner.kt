package com.elysium.vanguard.core.runtime.distros.layer

import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Phase 12.4 — Ed25519 manifest signer.
 *
 * Master order §11.5 (ADR-004): every published manifest is
 * signed with the channel's offline private key. The device
 * verifies the signature with the channel's public key shipped
 * in the APK.
 *
 * This class is the **build-pipeline side** of the signing
 * flow. It runs in CI / on the build host / on the offline
 * HSM; it never runs on the device. The verification side lives
 * in [ManifestVerifier].
 *
 * The signer is deliberately minimal:
 *
 *   - It takes raw manifest bytes (the file `manifest.json`) and
 *     produces a 64-byte Ed25519 signature.
 *   - It does NOT parse the manifest. Parsing is the consumer's
 *     job, after the signature has been verified.
 *   - It does NOT depend on any third-party crypto library. The
 *     JDK 15+ ships Ed25519 as a first-class algorithm
 *     (`Signature.getInstance("Ed25519")`), and we are on JDK 17.
 *
 * The signer also exposes a small helper to generate a fresh
 * keypair. The build pipeline calls this exactly once at key
 * ceremony time, not on every build. The generated keypair is
 * then loaded from a `keys/` directory for each subsequent
 * build that needs to sign.
 */
object ManifestSigner {

    private const val ALGORITHM = "Ed25519"

    /**
     * Sign [manifestBytes] with [privateKey]. Returns the raw
     * 64-byte Ed25519 signature.
     *
     * Throws [java.security.GeneralSecurityException] on a
     * cryptographically invalid request (e.g. wrong key type
     * for the algorithm, malformed bytes). The caller wraps that
     * into a typed error.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun sign(manifestBytes: ByteArray, privateKey: PrivateKey): ByteArray {
        require(manifestBytes.isNotEmpty()) { "manifest bytes must not be empty" }
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(privateKey)
        signer.update(manifestBytes)
        return signer.sign()
    }

    /**
     * Convenience: sign the file at [manifestFile] and write
     * the signature to `<manifestFile>.sig` as raw bytes.
     */
    @Throws(java.security.GeneralSecurityException::class, java.io.IOException::class)
    fun signToFile(manifestFile: File, privateKey: PrivateKey) {
        val bytes = manifestFile.readBytes()
        val signature = sign(bytes, privateKey)
        File(manifestFile.parentFile, manifestFile.name + ".sig").writeBytes(signature)
    }

    /**
     * Generate a fresh Ed25519 keypair. Used at key-ceremony
     * time, NOT on every build. The returned [KeyPair] is
     * suitable for in-memory use; callers that need persistence
     * call [exportPrivate] / [exportPublic] and store the
     * resulting bytes in a key vault.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        return generator.generateKeyPair()
    }

    /**
     * Encode the private key in PKCS#8 format. PKCS#8 is the
     * standard interchange format for private keys; the offline
     * HSM can read it and re-import the key for signing.
     */
    fun exportPrivate(key: PrivateKey): ByteArray = key.encoded

    /**
     * Encode the public key in X.509 SubjectPublicKeyInfo
     * format. The APK ships the public key in this format.
     */
    fun exportPublic(key: PublicKey): ByteArray = key.encoded

    /**
     * Re-import a private key from PKCS#8 bytes. The HSM
     * exports the private key in this format; the build host
     * reads it and signs manifests with it.
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun importPrivate(pkcs8: ByteArray): PrivateKey {
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    /**
     * Re-import a public key from X.509 bytes. The APK ships
     * the public key as a raw `assets/elysium/keys/<channel>.pub`
     * file; the device reads it and calls this to reconstruct
     * a [PublicKey] for [ManifestVerifier].
     */
    @Throws(java.security.GeneralSecurityException::class)
    fun importPublic(x509: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance(ALGORITHM)
        return keyFactory.generatePublic(X509EncodedKeySpec(x509))
    }
}
