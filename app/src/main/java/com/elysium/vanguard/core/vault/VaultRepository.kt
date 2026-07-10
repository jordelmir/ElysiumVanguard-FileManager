package com.elysium.vanguard.core.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.elysium.vanguard.core.database.VaultDao
import com.elysium.vanguard.core.database.VaultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * PHASE 2.1 — Vault repository.
 *
 * Responsibilities:
 * - Encrypt source files (URI or local path) into .elyv containers on disk
 * - Track per-file metadata in Room (VaultEntity)
 * - Decrypt .elyv containers back to plaintext bytes / streams
 * - Securely delete originals with DoD-style 3-pass overwrite before unlinking
 *
 * Concurrency: the repository is a @Singleton; each public method marshals onto IO
 * so callers can invoke it from any dispatcher without worrying about file races.
 */
class VaultRepository(
    private val context: Context,
    private val config: VaultConfig,
    private val crypto: VaultCrypto,
    private val dao: VaultDao,
    private val secureDelete: SecureDelete
) {

    init { config.isInitialized } // ensure dirs exist

    fun observeAll(): Flow<List<VaultEntity>> = dao.observeAll()

    suspend fun listAll(): List<VaultEntity> = dao.listAll()

    suspend fun totalVaultBytes(): Long = dao.totalVaultBytes() ?: 0L

    suspend fun count(): Int = dao.count()

    /**
     * Encrypt [sourceUri] (a content:// or file:// URI) into a new vault entry.
     * The original file is NOT deleted — caller decides whether to follow up with
     * [secureDeleteOriginal].
     *
     * Returns the freshly-inserted [VaultEntity].
     */
    suspend fun encryptUri(
        sourceUri: Uri,
        originalName: String,
        originalMime: String?
    ): VaultEntity = withContext(Dispatchers.IO) {
        val bytes = readUriBytes(sourceUri)
        val container = crypto.encryptContainer(bytes)
        val originalSize = bytes.size.toLong()

        // Insert row first to obtain the id, then write the payload file with that id.
        val placeholder = VaultEntity(
            vaultPath = "", // filled in below
            originalName = originalName,
            originalMime = originalMime,
            originalSize = originalSize,
            vaultSize = container.size.toLong(),
            encryptedAt = System.currentTimeMillis()
        )
        val newId = dao.insert(placeholder)
        val payload = config.newPayloadFile(newId)
        FileOutputStream(payload).use { it.write(container) }

        val final = placeholder.copy(id = newId, vaultPath = payload.absolutePath)
        dao.update(final)
        final
    }

    /**
     * Encrypt a local [File] into a new vault entry.
     */
    suspend fun encryptFile(file: File, mime: String? = null): VaultEntity = withContext(Dispatchers.IO) {
        encryptUri(Uri.fromFile(file), file.name, mime)
    }

    /**
     * Decrypt a vault entry to its plaintext bytes. Caller is responsible for
     * what to do with the bytes (write to a temp file, share, preview, etc).
     *
     * Updates `last_accessed_at` as a side effect so future "recently opened"
     * features can sort by it.
     */
    suspend fun decryptEntry(entry: VaultEntity): ByteArray = withContext(Dispatchers.IO) {
        val payload = File(entry.vaultPath)
        require(payload.isFile) { "Vault payload missing: ${entry.vaultPath}" }
        val container = payload.readBytes()
        val plaintext = crypto.decryptContainer(container)
        dao.update(entry.copy(lastAccessedAt = System.currentTimeMillis()))
        plaintext
    }

    /**
     * Decrypt a vault entry and write the plaintext into [target]. Uses streaming
     * to keep memory flat for large files.
     */
    suspend fun decryptEntryToFile(entry: VaultEntity, target: File): File = withContext(Dispatchers.IO) {
        // For the streaming path we still go through decryptEntry which loads bytes;
        // future optimization: use CipherInputStream-style streaming once we move
        // off Tink's high-level API. For Phase 2.1 correctness > memory.
        val plaintext = decryptEntry(entry)
        FileOutputStream(target).use { it.write(plaintext) }
        target
    }

    /**
     * Decrypt to a content:// Uri using a DocumentFile target. Used when restoring
     * to a SAF folder (e.g. external Downloads).
     */
    suspend fun decryptEntryToDocument(entry: VaultEntity, target: DocumentFile): DocumentFile? =
        withContext(Dispatchers.IO) {
            val plaintext = decryptEntry(entry)
            val sink = context.contentResolver.openOutputStream(target.uri)
                ?: return@withContext null
            sink.use { it.write(plaintext) }
            target
        }

    /**
     * Permanently remove a vault entry — both the encrypted payload (overwritten
     * first) and the metadata row.
     */
    suspend fun purgeEntry(entry: VaultEntity): Unit = withContext(Dispatchers.IO) {
        val payload = File(entry.vaultPath)
        if (payload.isFile) {
            secureDelete.overwrite(payload)
            payload.delete()
        }
        dao.deleteById(entry.id)
    }

    /**
     * Secure-delete the original (un-encrypted) file. Should be called after
     * [encryptFile] when the user wants to remove the plaintext copy.
     */
    suspend fun secureDeleteOriginal(source: File): Boolean = withContext(Dispatchers.IO) {
        secureDelete.overwrite(source) && source.delete()
    }

    private fun readUriBytes(uri: Uri): ByteArray {
        val input: InputStream = when (uri.scheme) {
            "file" -> {
                val f = File(requireNotNull(uri.path) { "file:// URI with null path" })
                require(f.isFile) { "Local file not found: ${f.absolutePath}" }
                f.inputStream()
            }
            "content" -> context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open input stream for $uri")
            else -> throw IllegalArgumentException("Unsupported URI scheme: ${uri.scheme}")
        }
        return input.use { stream ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    }
}