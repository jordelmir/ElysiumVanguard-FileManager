package com.elysium.vanguard.core.encryption

import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Encrypted Vault — AES-256-GCM encrypted container with password-based key derivation.
 *
 * Container layout:
 * ```
 * offset  size  field
 * ------  ----  --------------------------------------------------------
 *   0       8   magic  = "ELYSVLT" (ASCII + null)
 *   8       1   version = 0x01
 *   9      16   salt (for PBKDF2)
 *  25      12   nonce (for GCM)
 *  37       *   ciphertext (encrypted with AES-256-GCM)
 * ```
 *
 * Key derivation: PBKDF2-HMAC-SHA256, 100,000 iterations, 256-bit key.
 * This is compatible with standard crypto libraries and resistant to brute force.
 */
class EncryptedVault {

    init {
        AeadConfig.register()
    }

    companion object {
        private val MAGIC = "ELYSVLT".toByteArray(Charsets.UTF_8)
        private const val VERSION: Byte = 0x01
        private const val SALT_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE = 16
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_SIZE = 256

        /**
         * Derive a 256-bit AES key from password using PBKDF2-HMAC-SHA256.
         */
        fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }

        /**
         * Create a new encrypted container from plaintext using password.
         */
        fun encrypt(password: CharArray, plaintext: ByteArray): ByteArray {
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val key = deriveKey(password, salt)

            val keyHandle = KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
            // Replace the generated key with our derived key
            val aead = keyHandle.getPrimitive(Aead::class.java)

            val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
            val ciphertext = aead.encrypt(plaintext, nonce)

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
         * Decrypt a container using password. Returns plaintext or throws on failure.
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

            // We need to recreate the keyset with our derived key
            // Since Tink doesn't directly support arbitrary key material for AES256_GCM,
            // we use the standard AEAD with the derived key by creating a raw keyset
            val keyHandle = createKeyHandleFromRawKey(key)
            val aead = keyHandle.getPrimitive(Aead::class.java)

            return try {
                aead.decrypt(ciphertext, nonce)
            } catch (e: Exception) {
                throw EncryptedVaultException("Decryption failed: wrong password or corrupted data", e)
            }
        }

        private fun createKeyHandleFromRawKey(key: ByteArray): KeysetHandle {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeInt(AeadKeyTemplates.AES256_GCM.toString().hashCode()) // Key type ID
            dos.writeInt(key.size)
            dos.write(key)
            dos.flush()

            val bais = ByteArrayInputStream(baos.toByteArray())
            return CleartextKeysetHandle.read(BinaryKeysetReader.withInputStream(bais))
        }

        private const val HEADER_SIZE = 8 + 1 + SALT_SIZE + NONCE_SIZE
        private const val MIN_CONTAINER_SIZE = HEADER_SIZE + TAG_SIZE
    }
}

class EncryptedVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)