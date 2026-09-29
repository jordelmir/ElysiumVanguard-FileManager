package com.elysium.vanguard.core.encryption

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted Vault — AES-256-GCM encrypted container with password-based key derivation.
 *
 * Container layout:
 * ```
 * offset  size  field
 * ------  ----  --------------------------------------------------------
 *   0       7   magic  = "ELYSVLT" (ASCII)
 *   7       1   version = 0x01
 *   8      16   salt (for PBKDF2)
 *  24      12   nonce (for GCM)
 *  36       *   ciphertext + 16-byte GCM tag
 * ```
 *
 * Key derivation: PBKDF2-HMAC-SHA256, 100,000 iterations, 256-bit key.
 * The derived key encrypts the payload directly (JCA `AES/GCM/NoPadding`),
 * so encrypt and decrypt are exact inverses: same password → same key →
 * successful roundtrip; wrong password → AEAD tag mismatch → exception.
 */
class EncryptedVault {

    companion object {
        private val MAGIC = "ELYSVLT".toByteArray(Charsets.UTF_8)
        private const val VERSION: Byte = 0x01
        private const val SALT_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE = 16
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_SIZE = 256
        private const val GCM_TAG_BITS = TAG_SIZE * 8
        private const val CIPHER = "AES/GCM/NoPadding"

        /**
         * Derive a 256-bit AES key from password using PBKDF2-HMAC-SHA256.
         */
        fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }

        /**
         * Create an encrypted container from plaintext using a password.
         * The salt is random per container, so the same password produces
         * a different key (and different ciphertext) every time.
         */
        fun encrypt(password: CharArray, plaintext: ByteArray): ByteArray {
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)
            val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(plaintext)

            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { dos ->
                dos.write(MAGIC)
                dos.writeByte(VERSION.toInt())
                dos.write(salt)
                dos.write(nonce)
                dos.write(ciphertext)
            }
            return out.toByteArray()
        }

        /**
         * Decrypt a container using a password. Returns plaintext or throws on failure.
         */
        fun decrypt(password: CharArray, container: ByteArray): ByteArray {
            if (container.size < MIN_CONTAINER_SIZE) {
                throw EncryptedVaultException("Container too small")
            }

            val dis = DataInputStream(ByteArrayInputStream(container))
            val magic = ByteArray(MAGIC.size)
            dis.readFully(magic)
            if (!Arrays.equals(magic, MAGIC)) {
                throw EncryptedVaultException("Invalid container format")
            }

            val version = dis.readByte()
            if (version != VERSION) {
                throw EncryptedVaultException("Unsupported version: $version")
            }

            val salt = ByteArray(SALT_SIZE)
            dis.readFully(salt)

            val nonce = ByteArray(NONCE_SIZE)
            dis.readFully(nonce)

            val remaining = container.size - HEADER_SIZE
            if (remaining < TAG_SIZE) {
                throw EncryptedVaultException("Ciphertext too short")
            }
            val ciphertext = ByteArray(remaining)
            dis.readFully(ciphertext)

            val key = deriveKey(password, salt)
            return try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                throw EncryptedVaultException("Decryption failed: wrong password or corrupted data", e)
            }
        }

        private val HEADER_SIZE = MAGIC.size + 1 + SALT_SIZE + NONCE_SIZE
        private val MIN_CONTAINER_SIZE = HEADER_SIZE + TAG_SIZE
    }
}

class EncryptedVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
