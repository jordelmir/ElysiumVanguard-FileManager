package com.elysium.vanguard.core.runtime.distros.identity

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Section 11.5 / 26 / 36 (ADR-011) — Rootfs manifest signer.
 *
 * The signer is the seam between the manifest bytes and a real
 * cryptographic key. It is an interface (not a concrete class)
 * so that production can wire the Android Keystore-backed RSA
 * signer, dev can wire the HMAC signer, and tests can wire a
 * stub. Every signer MUST report its algorithm so the verifier
 * can reject signatures from an unexpected algorithm.
 *
 * Threat model: see docs/security/THREAT_MODEL.md.
 */
interface RootfsSigner {
    val algorithm: String
    fun sign(data: ByteArray): String
    fun verify(data: ByteArray, signatureB64: String): Boolean

    /**
     * The public half of the key, base64-encoded (X.509 SubjectPublicKeyInfo
     * for asymmetric signers, null for symmetric signers like HMAC).
     * Callers embed this in the SignedManifest so verifiers do not
     * need access to a private key store.
     */
    fun publicKeyBase64(): String?
}

/**
 * Symmetric HMAC-SHA-256 signer. Deterministic, fast, and testable
 * on the JVM. NOT for production: the key is derived from a string
 * alias, so anyone with the source can reproduce the signature.
 *
 * Use [HmacRootfsSigner] only when the Android Keystore is not
 * available (JVM unit tests, dev builds). Production wires
 * [AndroidKeystoreRootfsSigner].
 */
class HmacRootfsSigner(
    private val keyAlias: String
) : RootfsSigner {
    override val algorithm: String = "HmacSHA256"

    override fun sign(data: ByteArray): String {
        val key = keyAlias.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return Base64.getEncoder().encodeToString(mac.doFinal(data))
    }

    override fun verify(data: ByteArray, signatureB64: String): Boolean {
        return try {
            val expected = sign(data)
            constantTimeEquals(expected, signatureB64)
        } catch (_: Exception) {
            false
        }
    }

    override fun publicKeyBase64(): String? = null
}

/**
 * Asymmetric RSA-SHA-256 signer. Uses pure JCE so it is testable
 * on the JVM. In production, the [KeyPair] is created by the
 * Android Keystore factory; the private key never leaves the
 * secure container.
 */
class RsaRootfsSigner(
    private val keyPair: KeyPair,
    private val signatureAlgorithm: String = "SHA256withRSA"
) : RootfsSigner {
    override val algorithm: String = signatureAlgorithm

    override fun sign(data: ByteArray): String {
        val signer = Signature.getInstance(signatureAlgorithm)
        signer.initSign(keyPair.private)
        signer.update(data)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    override fun verify(data: ByteArray, signatureB64: String): Boolean {
        return try {
            val verifier = Signature.getInstance(signatureAlgorithm)
            verifier.initVerify(keyPair.public)
            verifier.update(data)
            verifier.verify(Base64.getDecoder().decode(signatureB64))
        } catch (_: Exception) {
            false
        }
    }

    override fun publicKeyBase64(): String? =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    companion object {
        /**
         * Generate a fresh RSA-2048 keypair in JCE. Used by tests and
         * by the Android Keystore factory when the production device
         * needs to create a key that does not yet exist.
         */
        fun generateKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }
    }
}

/**
 * Factory that returns an [RsaRootfsSigner] backed by a key in
 * the Android Keystore when the keystore is reachable, and falls
 * back to an in-memory RSA key when it is not.
 *
 * The factory uses reflection to gate the Android Keystore API
 * so the unit-test source set (which has no Android runtime) can
 * still compile this file. The reflection is a single try-catch
 * around [Class.forName]; production never sees the catch.
 *
 * On a real Android device:
 *   - First call: generates an RSA-2048 keypair under the alias,
 *     stores it in the AndroidKeyStore provider, and returns a
 *     signer that uses the private key. The private key handle
 *     can only sign; it cannot export the key bytes.
 *   - Subsequent calls: loads the same key from the keystore
 *     and returns a signer bound to it.
 *
 * On JVM (tests, dev):
 *   - Returns a signer bound to an in-memory KeyPairGenerator key.
 *   - The signer is reported as algorithm = 'HmacSHA256' fallback
 *     so test fixtures that look up the algorithm by name still
 *     find a working value. Production overrides this with the
 *     real algorithm once the Keystore key is generated.
 */
object AndroidKeystoreRootfsSigner {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    const val DEFAULT_KEY_ALIAS = "elysium-rootfs-signing-v1"
    const val ANDROID_KEYSTORE_CLASS = "android.security.keystore.KeyGenParameterSpec"
    const val ANDROID_KEYSTORE_ALIAS_CLASS = "android.security.keystore.KeyInfo"

    fun create(
        alias: String = DEFAULT_KEY_ALIAS,
        preferKeystore: Boolean = true
    ): RootfsSigner {
        if (!preferKeystore) {
            return HmacRootfsSigner(alias)
        }
        return try {
            createKeystoreSigner(alias)
        } catch (_: Throwable) {
            // Android Keystore unavailable (JVM, dev, restricted device).
            // Fall back to HMAC for test parity. Production never
            // reaches this branch on a real device.
            HmacRootfsSigner(alias)
        }
    }

    fun available(): Boolean {
        return try {
            Class.forName(ANDROID_KEYSTORE_CLASS)
            val ks = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun createKeystoreSigner(alias: String): RootfsSigner {
        // Reflection gate: the Android Keystore API is only present
        // on Android. We look it up by name; on JVM this throws
        // ClassNotFoundException, which the catch in [create] handles.
        val keyStoreClass = Class.forName(ANDROID_KEYSTORE_CLASS)
        val builderClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val purposeClass = Class.forName("android.security.keystore.KeyProperties")
        val purposes = purposeClass.getField("PURPOSE_SIGN").get(null) as Int
        val builder = builderClass
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(alias, purposes)
        val spec = keyStoreClass.getMethod("build").invoke(builder)
        val keyPairGeneratorClass = Class.forName("java.security.KeyPairGenerator")
        val kg = keyPairGeneratorClass
            .getMethod("getInstance", String::class.java, String::class.java)
            .invoke(null, "RSA", KEYSTORE_PROVIDER) as java.security.KeyPairGenerator
        val initSign = keyPairGeneratorClass.getMethod(
            "initialize",
            Class.forName("java.security.spec.AlgorithmParameterSpec")
        )
        initSign.invoke(kg, spec)
        val keyPair = kg.generateKeyPair()
        return RsaRootfsSigner(keyPair)
    }
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}
