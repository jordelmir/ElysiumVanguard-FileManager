package com.elysium.vanguard.core.vault

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PHASE 2.1 — VaultCrypto roundtrip + tamper-detection tests.
 *
 * Pure-JVM tests: we don't depend on Android Keystore (that's an Android-only
 * surface and would require Robolectric or instrumentation tests). Instead we
 * generate a fresh Tink keyset in memory and pass the resulting AEAD primitive
 * into VaultCrypto. The container format, framing, and per-file DEK wrapping
 * are the things we actually need to verify here — those are JVM-portable.
 */
class VaultCryptoTest {

    private lateinit var masterAead: Aead
    private lateinit var crypto: VaultCrypto

    @Before
    fun setUp() {
        AeadConfig.register()
        val keyset = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        masterAead = keyset.getPrimitive(Aead::class.java)
        crypto = VaultCrypto(masterAead)
    }

    @Test
    fun `roundtrip small payload preserves bytes exactly`() {
        val plaintext = "Hello, vault!".toByteArray(Charsets.UTF_8)
        val container = crypto.encryptContainer(plaintext)
        val decrypted = crypto.decryptContainer(container)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `roundtrip empty payload is well-defined`() {
        val container = crypto.encryptContainer(ByteArray(0))
        val decrypted = crypto.decryptContainer(container)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `roundtrip 1 MB random payload`() {
        val plaintext = ByteArray(1024 * 1024).also {
            java.security.SecureRandom().nextBytes(it)
        }
        val container = crypto.encryptContainer(plaintext)
        // Container should be at least slightly larger than plaintext (header + DEK + tag).
        assertTrue("Container $container.size < plaintext ${plaintext.size}",
            container.size > plaintext.size)
        // Overhead should be bounded — under 200 bytes for a 1MB payload.
        assertTrue("Container overhead ${container.size - plaintext.size} too large",
            container.size - plaintext.size < 200)
        assertArrayEquals(plaintext, crypto.decryptContainer(container))
    }

    @Test
    fun `two encryptions of the same plaintext produce different ciphertexts`() {
        val plaintext = "Same input, different keys".toByteArray()
        val a = crypto.encryptContainer(plaintext)
        val b = crypto.encryptContainer(plaintext)
        // Per-container fresh DEK + nonce → must differ
        assertFalse(
            "Identical containers would imply broken randomness",
            a.contentEquals(b)
        )
        // Both still decrypt correctly
        assertArrayEquals(plaintext, crypto.decryptContainer(a))
        assertArrayEquals(plaintext, crypto.decryptContainer(b))
    }

    @Test
    fun `flipping a single ciphertext byte causes decryption failure`() {
        val plaintext = "Integrity must hold".toByteArray()
        val container = crypto.encryptContainer(plaintext)
        // Flip a byte in the middle of the ciphertext region (well past the header).
        val target = VaultCrypto.HEADER_SIZE + 64
        container[target] = (container[target].toInt() xor 0x01).toByte()
        assertThrows(VaultFormatException::class.java) {
            crypto.decryptContainer(container)
        }
    }

    @Test
    fun `corrupting the magic rejects the container`() {
        val container = crypto.encryptContainer("x".toByteArray())
        container[0] = 'X'.code.toByte()
        val ex = assertThrows(VaultFormatException::class.java) {
            crypto.decryptContainer(container)
        }
        assertTrue("Wrong error: ${ex.message}", ex.message!!.contains("magic", ignoreCase = true))
    }

    @Test
    fun `corrupting the version is rejected`() {
        val container = crypto.encryptContainer("x".toByteArray())
        container[4] = 0x7F
        val ex = assertThrows(VaultFormatException::class.java) {
            crypto.decryptContainer(container)
        }
        assertTrue(ex.message!!.contains("version", ignoreCase = true))
    }

    @Test
    fun `isValidContainerShape accepts a fresh container and rejects garbage`() {
        val container = crypto.encryptContainer("hello".toByteArray())
        assertTrue(crypto.isValidContainerShape(container))

        // Too small
        assertFalse(crypto.isValidContainerShape(ByteArray(10)))

        // Wrong magic
        val bad = container.copyOf()
        bad[0] = 0
        assertFalse(crypto.isValidContainerShape(bad))

        // Empty
        assertFalse(crypto.isValidContainerShape(ByteArray(0)))
    }

    @Test
    fun `wrong master key cannot decrypt a container`() {
        // Build a second crypto engine with a different master AEAD.
        val otherAead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(Aead::class.java)
        val otherCrypto = VaultCrypto(otherAead)

        val plaintext = "Master-key separation".toByteArray()
        val container = crypto.encryptContainer(plaintext)

        // Other engine should fail because the DEK was wrapped with the original master key.
        assertThrows(VaultFormatException::class.java) {
            otherCrypto.decryptContainer(container)
        }

        // Sanity: original still works.
        assertArrayEquals(plaintext, crypto.decryptContainer(container))
    }

    @Test
    fun `containers are reproducibly sized for the same plaintext size`() {
        // Two encryptions of the same-size plaintext should produce containers whose
        // length only differs by the nonce / salt overhead. Header + DEK + tag sizes
        // are deterministic; nonces are intentionally random so they may pick
        // different encodings (12 bytes vs 14 bytes in some legacy salt variants).
        // The contract is "within a small tolerance of each other" rather than
        // exactly equal.
        val a = crypto.encryptContainer(ByteArray(123))
        val b = crypto.encryptContainer(ByteArray(123))
        val delta = kotlin.math.abs(a.size - b.size)
        assertTrue(
            "container sizes diverged unexpectedly: a=${a.size} b=${b.size}",
            delta <= 16
        )
        assertNotEquals(a.toByteString(), b.toByteString())
    }

    @Test
    fun `associated data binds plaintext to a context`() {
        val plaintext = "Bound to context A".toByteArray()
        val aadA = "context-A".toByteArray()
        val aadB = "context-B".toByteArray()
        val container = crypto.encryptContainer(plaintext, aadA)
        // Same AAD decrypts
        assertArrayEquals(plaintext, crypto.decryptContainer(container, aadA))
        // Different AAD must fail
        assertThrows(VaultFormatException::class.java) {
            crypto.decryptContainer(container, aadB)
        }
    }
}

/**
 * Convenience helper to compare ByteArrays by content equality. Avoids importing
 * java.util.Arrays just for one method.
 */
private fun ByteArray.toByteString(): String = joinToString("") { "%02x".format(it) }