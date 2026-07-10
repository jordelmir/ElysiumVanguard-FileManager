package com.elysium.vanguard.core.vault

import com.google.crypto.tink.Aead
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * PHASE 2.1 — Vault container format + crypto engine.
 *
 * Container layout (big-endian, all lengths unsigned):
 *
 * ```
 * offset  size  field
 * ------  ----  --------------------------------------------------------
 *   0       4   magic  = "ELYV"          (ASCII)
 *   4       1   version = 0x01
 *   5       2   wrapped_key_len  (uint16, max 1024)
 *   7      WL   wrapped_key  (random DEK encrypted with master AEAD)
 *   7+WL   12   nonce  (random per-container, MUST be unique)
 *   19+WL   *   ciphertext  (plaintext encrypted with DEK)
 *   last   16   GCM tag
 * ```
 *
 * Why a per-file DEK instead of a single key for the whole vault:
 * - Master-key rotation only requires re-wrapping each entry's DEK, not re-encrypting
 *   the file content.
 * - A leaked DEK compromises exactly one file; the master key stays safe.
 * - Per-file nonce randomness is preserved even when many files share the same master.
 *
 * Total overhead: 23 bytes + wrapped_key_len (typically ~64-80 bytes for an AES256_GCM
 * keyset wrapped by a master key of the same template, so ~95 bytes per container).
 */
class VaultCrypto(private val masterAead: Aead) {

    init {
        AeadConfig.register()
    }

    /**
     * Encrypt [plaintext] into a fully self-describing .elyv container.
     * Safe to call concurrently from many threads — uses a per-call fresh DEK + nonce.
     */
    fun encryptContainer(plaintext: ByteArray, associatedData: ByteArray = EMPTY_AAD): ByteArray {
        // 1) Generate a fresh 256-bit DEK
        val dekHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
        val dekAead: Aead = dekHandle.getPrimitive(Aead::class.java)

        // 2) Serialize the DEK keyset to bytes, then wrap with master AEAD
        val dekSerialized = serializeKeyset(dekHandle)
        val wrappedKey = masterAead.encrypt(dekSerialized, DEK_AAD)

        // 3) Encrypt the actual content with the DEK. Bind the nonce into the AAD so a
        //    replayer can't swap nonces between containers — AAD is authenticated but
        //    not encrypted, so this is a pure integrity upgrade with no size cost.
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val ciphertext = dekAead.encrypt(plaintext, nonce + associatedData)

        // 4) Assemble container
        val out = ByteArrayOutputStream(
            HEADER_SIZE + wrappedKey.size + NONCE_SIZE + ciphertext.size
        )
        DataOutputStream(out).use { dos ->
            dos.write(MAGIC)
            dos.writeByte(VERSION.toInt())
            if (wrappedKey.size > MAX_WRAPPED_KEY_LEN) {
                throw IllegalStateException(
                    "Wrapped DEK too large: ${wrappedKey.size} bytes (max $MAX_WRAPPED_KEY_LEN)"
                )
            }
            dos.writeShort(wrappedKey.size)
            dos.write(wrappedKey)
            dos.write(nonce)
            dos.write(ciphertext)
        }
        return out.toByteArray()
    }

    /**
     * Reverse of [encryptContainer]. Throws [VaultFormatException] for any structural
     * problem and the underlying Tink AEAD exception for any tag mismatch (tampering).
     */
    fun decryptContainer(container: ByteArray, associatedData: ByteArray = EMPTY_AAD): ByteArray {
        if (container.size < MIN_CONTAINER_SIZE) {
            throw VaultFormatException("Container too small: ${container.size} bytes")
        }
        val dis = DataInputStream(ByteArrayInputStream(container))

        val magic = ByteArray(4)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw VaultFormatException("Bad magic: ${magic.toString(Charsets.US_ASCII)}")
        }

        val version = dis.readUnsignedByte()
        if (version != VERSION.toInt() and 0xff) {
            throw VaultFormatException("Unsupported version: $version")
        }

        val wrappedKeyLen = dis.readUnsignedShort()
        if (wrappedKeyLen == 0 || wrappedKeyLen > MAX_WRAPPED_KEY_LEN) {
            throw VaultFormatException("Invalid wrapped key length: $wrappedKeyLen")
        }
        val wrappedKey = ByteArray(wrappedKeyLen)
        dis.readFully(wrappedKey)

        val nonce = ByteArray(NONCE_SIZE)
        dis.readFully(nonce)

        val remaining = container.size - HEADER_SIZE - wrappedKeyLen - NONCE_SIZE
        if (remaining < TAG_SIZE) {
            throw VaultFormatException("Ciphertext shorter than GCM tag")
        }
        val ciphertext = ByteArray(remaining)
        dis.readFully(ciphertext)

        val dekSerialized = try {
            masterAead.decrypt(wrappedKey, DEK_AAD)
        } catch (e: Exception) {
            throw VaultFormatException("Failed to unwrap DEK: ${e.message}", e)
        }

        val dekHandle = try {
            deserializeKeyset(dekSerialized)
        } catch (e: Exception) {
            throw VaultFormatException("Failed to parse DEK keyset", e)
        }
        val dekAead: Aead = dekHandle.getPrimitive(Aead::class.java)

        return try {
            dekAead.decrypt(ciphertext, nonce + associatedData)
        } catch (e: Exception) {
            // Don't leak crypto details — the container either corrupted or the key changed.
            throw VaultFormatException("Decryption failed (corrupted or wrong key)", e)
        }
    }

    /**
     * Cheap structural validation without performing decryption. Useful for the
     * "is this even a vault file?" check before launching a heavyweight unlock.
     */
    fun isValidContainerShape(container: ByteArray): Boolean {
        return try {
            if (container.size < MIN_CONTAINER_SIZE) return false
            container[0] == MAGIC[0] &&
                container[1] == MAGIC[1] &&
                container[2] == MAGIC[2] &&
                container[3] == MAGIC[3] &&
                container[4] == VERSION
        } catch (_: IndexOutOfBoundsException) {
            false
        }
    }

    private fun serializeKeyset(handle: KeysetHandle): ByteArray {
        val baos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(baos))
        return baos.toByteArray()
    }

    private fun deserializeKeyset(bytes: ByteArray): KeysetHandle {
        return CleartextKeysetHandle.read(BinaryKeysetReader.withInputStream(ByteArrayInputStream(bytes)))
    }

    companion object {
        val MAGIC = byteArrayOf('E'.code.toByte(), 'L'.code.toByte(), 'Y'.code.toByte(), 'V'.code.toByte())
        const val VERSION: Byte = 0x01
        const val HEADER_SIZE = 4 + 1 + 2
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
        const val MAX_WRAPPED_KEY_LEN = 1024
        const val MIN_CONTAINER_SIZE = HEADER_SIZE + 1 + NONCE_SIZE + TAG_SIZE

        /** Domain-separation tag for the wrapped DEK — must not collide with file AAD. */
        val DEK_AAD: ByteArray = "elysium/vault/dek/v1".toByteArray(Charsets.US_ASCII)
        val EMPTY_AAD: ByteArray = ByteArray(0)
    }
}

class VaultFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)