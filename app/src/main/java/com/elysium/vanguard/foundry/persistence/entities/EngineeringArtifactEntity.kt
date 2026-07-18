package com.elysium.vanguard.foundry.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifact
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifactFormat

/**
 * Room entity for the `EngineeringArtifact`
 * aggregate. The `contentHash` is a
 * SHA-256 hex string; the `format` is a
 * `EngineeringArtifactFormat` enum value
 * (stored as a string).
 *
 * Per `docs/foundry/domain-ownership.md` section 2.11:
 * an `EngineeringArtifact` is a typed reference
 * to a content-addressed engineering artifact
 * (glTF, STEP, USD, etc.).
 */
@Entity(tableName = "engineering_artifacts")
data class EngineeringArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    @ColumnInfo(name = "format")
    val format: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "version")
    val version: Long,
) {
    fun toDomain(): EngineeringArtifact = EngineeringArtifact(
        id = EngineeringArtifactId.from(id).getOrThrow(),
        contentHash = ContentHash(contentHash),
        format = EngineeringArtifactFormat.valueOf(format),
        sizeBytes = sizeBytes,
        subjectId = subjectId,
        createdAt = Timestamp(createdAtEpochMs),
        version = version,
    )

    companion object {
        fun fromDomain(artifact: EngineeringArtifact): EngineeringArtifactEntity = EngineeringArtifactEntity(
            id = artifact.id.value.toString(),
            contentHash = artifact.contentHash.value,
            format = artifact.format.name,
            sizeBytes = artifact.sizeBytes,
            subjectId = artifact.subjectId,
            createdAtEpochMs = artifact.createdAt.epochMs,
            version = artifact.version,
        )
    }
}
