package com.elysium.vanguard.core.encryption

import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * EncFS Volume — per-file encrypted directory (EncFS-style layout).
 *
 * Unlike EncryptedVault which creates a single container file,
 * an EncFS volume is a directory where each file is individually
 * encrypted and filenames are obfuscated:
 *
 * ```
 * <volume-dir>/
 *   .encfs6.xml     <- config: salt (hex) + password verifier (HMAC-SHA256 hex)
 *   .encfs.names    <- obfuscated-name -> original-name map (originals hex-encoded)
 *   <obfuscated>    <- per file: 12-byte nonce || AES-256-GCM ciphertext+tag
 * ```
 *
 * Guarantees:
 * - Key = PBKDF2-HMAC-SHA256(password, salt, 100k, 256-bit).
 * - Salt is persisted in the config, so reopening with the same
 *   password derives the same key (roundtrip works).
 * - The config stores an HMAC verifier, so [open] returns `null`
 *   for a wrong password instead of producing an undecryptable volume.
 * - Per-file AES-256-GCM with a fresh random nonce per encryption.
 */
class EncFsVolume private constructor(
    private val rootDir: File,
    private val masterKey: ByteArray,
    private val nameMap: MutableMap<String, String>,
) {

    private val nameMapFile = File(rootDir, NAMES_FILE)

    init {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
        }
    }

    /**
     * Encrypt a file and store it in the volume under an obfuscated name.
     * Returns the stored (encrypted) file.
     */
    fun encryptFile(sourceFile: File, preserveName: Boolean = false): File {
        val plaintext = sourceFile.readBytes()
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val storedName = if (preserveName) sourceFile.name else allocateObfuscatedName(sourceFile.name)
        val destFile = File(rootDir, storedName)
        FileOutputStream(destFile).use { out ->
            out.write(nonce)
            out.write(ciphertext)
        }
        if (!preserveName) {
            nameMap[storedName] = sourceFile.name
            saveNameMap()
        }
        return destFile
    }

    /**
     * Decrypt a file from the volume. Returns `false` on any failure
     * (wrong key, truncated file, tampered ciphertext).
     */
    fun decryptFile(encryptedFile: File, outputFile: File): Boolean {
        return try {
            val raw = encryptedFile.readBytes()
            if (raw.size < NONCE_SIZE + TAG_SIZE) return false
            val nonce = raw.copyOfRange(0, NONCE_SIZE)
            val ciphertext = raw.copyOfRange(NONCE_SIZE, raw.size)

            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val plaintext = cipher.doFinal(ciphertext)
            outputFile.writeBytes(plaintext)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * All stored (encrypted) files in the volume, excluding control files.
     */
    fun listFiles(): List<File> {
        return rootDir.listFiles()
            ?.filter { it.isFile && it.name != CONFIG_NAME && it.name != NAMES_FILE }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Original name for an obfuscated file (falls back to the stored name
     * for files added with `preserveName = true` or unknown entries).
     */
    fun getDecryptedName(encryptedName: String): String = nameMap[encryptedName] ?: encryptedName

    /**
     * Deterministic obfuscation: HMAC-SHA256(masterKey, originalName),
     * truncated to 32 hex chars + ".enc". A suffix is appended when the
     * candidate is already used by a *different* original name.
     */
    private fun allocateObfuscatedName(originalName: String): String {
        val digest = hmacHex(originalName.toByteArray(Charsets.UTF_8))
        val base = digest.substring(0, 32)
        var candidate = "$base.enc"
        var counter = 1
        while (nameMap[candidate] != null && nameMap[candidate] != originalName) {
            candidate = "$base-$counter.enc"
            counter++
        }
        return candidate
    }

    private fun hmacHex(data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    private fun saveNameMap() {
        val body = nameMap.entries.joinToString("\n") { (stored, original) ->
            "$stored=${original.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }}"
        }
        nameMapFile.writeText(body)
    }

    companion object {
        const val CONFIG_NAME = ".encfs6.xml"
        private const val NAMES_FILE = ".encfs.names"
        private const val VERIFIER_CONTEXT = "ElysiumEncFS-v1"
        private const val SALT_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE = 16
        private const val GCM_TAG_BITS = TAG_SIZE * 8
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_SIZE = 256
        private const val CIPHER = "AES/GCM/NoPadding"

        /**
         * Create a new EncFS volume at [rootDir]. Fails if a volume
         * already exists there.
         */
        fun create(rootDir: File, password: CharArray): EncFsVolume {
            if (File(rootDir, CONFIG_NAME).exists()) {
                throw EncFsVolumeException("An EncFS volume already exists at ${rootDir.absolutePath}")
            }
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                throw EncFsVolumeException("Cannot create directory ${rootDir.absolutePath}")
            }
            val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
            val masterKey = deriveKey(password, salt)
            val verifier = verifierFor(masterKey)
            writeConfig(File(rootDir, CONFIG_NAME), salt, verifier)
            return EncFsVolume(rootDir, masterKey, mutableMapOf())
        }

        /**
         * Open an existing EncFS volume. Returns `null` when the config is
         * missing/corrupt or the password is wrong (verifier mismatch).
         */
        fun open(rootDir: File, password: CharArray): EncFsVolume? {
            val configFile = File(rootDir, CONFIG_NAME)
            if (!configFile.exists()) return null

            val config = parseConfig(configFile) ?: return null
            val salt = try {
                Hex.decode(config.salt)
            } catch (e: Exception) {
                return null
            }
            if (salt.size != SALT_SIZE) return null

            val masterKey = deriveKey(password, salt)
            val expected = verifierFor(masterKey)
            if (!constantTimeEquals(expected, config.verifier)) return null

            return EncFsVolume(rootDir, masterKey, loadNameMap(File(rootDir, NAMES_FILE)))
        }

        private fun writeConfig(configFile: File, salt: ByteArray, verifier: String) {
            val xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <encfs>
                    <version>20190730</version>
                    <cipher>
                        <name>aes</name>
                        <mode>gcm</mode>
                        <keySize>256</keySize>
                        <ivSize>12</ivSize>
                    </cipher>
                    <keyDerivation>
                        <name>pbkdf2</name>
                        <iterations>$PBKDF2_ITERATIONS</iterations>
                    </keyDerivation>
                    <salt>${Hex.encode(salt)}</salt>
                    <verifier>$verifier</verifier>
                </encfs>
            """.trimIndent()
            configFile.writeText(xml)
        }

        private data class VolumeConfig(val salt: String, val verifier: String)

        private fun parseConfig(configFile: File): VolumeConfig? = try {
            val doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(configFile)
            val root = doc.documentElement
            if (root.tagName != "encfs") {
                null
            } else {
                val salt = root.getElementsByTagName("salt").item(0)?.textContent
                val verifier = root.getElementsByTagName("verifier").item(0)?.textContent
                if (salt.isNullOrEmpty() || verifier.isNullOrEmpty()) null
                else VolumeConfig(salt = salt, verifier = verifier)
            }
        } catch (e: Exception) {
            null
        }

        private fun loadNameMap(nameMapFile: File): MutableMap<String, String> {
            val map = mutableMapOf<String, String>()
            if (!nameMapFile.exists()) return map
            nameMapFile.readLines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val stored = line.substring(0, idx)
                    val originalHex = line.substring(idx + 1)
                    try {
                        map[stored] = String(Hex.decode(originalHex), Charsets.UTF_8)
                    } catch (e: Exception) {
                        // skip malformed entries; the file stays usable
                    }
                }
            }
            return map
        }

        private fun verifierFor(masterKey: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
            return mac.doFinal(VERIFIER_CONTEXT.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }
    }
}

/**
 * Thrown when an EncFS volume cannot be created (volume already exists,
 * unwritable directory).
 */
class EncFsVolumeException(message: String) : Exception(message)

/**
 * Minimal hex codec (no Android/JVM API differences to worry about).
 */
internal object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            out[i * 2] = DIGITS[(b.toInt() shr 4) and 0x0f]
            out[i * 2 + 1] = DIGITS[b.toInt() and 0x0f]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) throw IllegalArgumentException("invalid hex character")
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
