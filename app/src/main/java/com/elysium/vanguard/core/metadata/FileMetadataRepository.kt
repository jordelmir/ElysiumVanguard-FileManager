package com.elysium.vanguard.core.metadata

import com.elysium.vanguard.core.database.FileMetadataDao
import com.elysium.vanguard.core.database.FileMetadataEntity
import kotlinx.coroutines.flow.Flow

/**
 * PHASE 2.12 — Tags / color / notes metadata repository.
 *
 * Storage conventions:
 * - [uri] is the canonical lookup key. For local files we use the absolute path
 *   prefixed with `local://` so it can't collide with a `vault://<id>` key.
 * - Tags are persisted as a comma-separated string. We sanitize by stripping
 *   commas and trimming whitespace on input; this keeps the LIKE-based search
 *   simple and avoids pulling in a JSON dependency for what's effectively a set
 *   of short identifiers.
 */
class FileMetadataRepository(private val dao: FileMetadataDao) {

    /** Build the canonical key for a local file path. */
    fun localKey(absolutePath: String): String = "local://${absolutePath.trim()}"

    /** Build the canonical key for a vault entry. */
    fun vaultKey(vaultId: Long): String = "vault://$vaultId"

    fun observeAll(): Flow<List<FileMetadataEntity>> = dao.observeAll()

    suspend fun get(key: String): FileMetadataEntity? = dao.getByUri(key)

    suspend fun save(
        key: String,
        displayName: String,
        tags: List<String>,
        colorHex: String?,
        note: String?
    ): FileMetadataEntity {
        val now = System.currentTimeMillis()
        val existing = dao.getByUri(key)
        val sanitizedTags = tags
            .map { it.trim().replace(",", "").replace("\n", " ") }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")

        val entity = FileMetadataEntity(
            uri = key,
            displayName = displayName,
            colorHex = colorHex?.takeIf { it.isNotBlank() },
            note = note?.takeIf { it.isNotBlank() },
            tags = sanitizedTags,
            updatedAt = now,
            createdAt = existing?.createdAt ?: now
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun delete(key: String) {
        dao.delete(key)
    }

    suspend fun findByTag(tag: String): List<FileMetadataEntity> = dao.findByTag(tag)

    /**
     * Derive the unique tag vocabulary across all stored metadata. Used to drive
     * the tag suggestion chips in the editor.
     */
    suspend fun allTags(): List<String> {
        return dao.listAll()
            .flatMap { it.tags.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    fun parseTags(commaSeparated: String?): List<String> {
        if (commaSeparated.isNullOrBlank()) return emptyList()
        return commaSeparated.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}