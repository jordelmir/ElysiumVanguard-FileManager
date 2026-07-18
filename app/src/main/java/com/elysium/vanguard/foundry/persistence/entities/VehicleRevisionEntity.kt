package com.elysium.vanguard.foundry.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.core.revision.VehicleRevision
import com.elysium.vanguard.foundry.core.scene.SceneManifest

/**
 * Room entity for the `VehicleRevision`
 * aggregate. The revision is **immutable**
 * (per ADR-0006); the entity is a snapshot
 * of the frozen state.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.3:
 * a `VehicleRevision` is the unit of
 * immutability. The `provenance` + the
 * `sceneManifest` are stored as JSON
 * (unit-separator-joined content-hash
 * strings) — the full reconstruction is
 * in the `FoundryRepository` (Phase 2
 * follow-up).
 */
@Entity(tableName = "vehicle_revisions")
data class VehicleRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    @ColumnInfo(name = "provenance_id")
    val provenanceId: String,

    @ColumnInfo(name = "provenance_source")
    val provenanceSource: String,

    @ColumnInfo(name = "provenance_signature")
    val provenanceSignature: String,

    @ColumnInfo(name = "scene_manifest_components")
    val sceneManifestComponents: String, // unit-separator-joined "id:label" pairs

    @ColumnInfo(name = "scene_manifest_lods")
    val sceneManifestLods: String, // unit-separator-joined "level:resolution" pairs

    @ColumnInfo(name = "scene_manifest_hash")
    val sceneManifestHash: String,

    @ColumnInfo(name = "representation_level")
    val representationLevel: String,

    @ColumnInfo(name = "is_immutable")
    val isImmutable: Boolean,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
) {
    /**
     * Reconstruct the domain `VehicleRevision`.
     * The `provenance` and the `sceneManifest`
     * are reconstructed from the stored
     * snapshots; the full `SignedEvent` chain
     * is in the audit trail (Phase 2 follow-up).
     */
    fun toDomain(): VehicleRevision {
        val contentHashDomain = ContentHash(contentHash)
        val provenance = ProvenanceRecord(
            id = com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId.from(provenanceId).getOrThrow(),
            subjectId = provenanceId,
            source = provenanceSource,
            signature = com.elysium.vanguard.foundry.core.ontology.primitives.Signature(provenanceSignature),
            witnesses = emptyList(),
            createdAt = Timestamp(createdAtEpochMs),
        )
        val sceneManifest = SceneManifest(
            revisionContentHash = contentHashDomain,
            components = sceneManifestComponents
                .split("\u001F")
                .filter { it.isNotEmpty() }
                .map { entry ->
                    val (id, label) = entry.split(":", limit = 2).let {
                        if (it.size == 2) it[0] to it[1] else it[0] to ""
                    }
                    com.elysium.vanguard.foundry.core.scene.ComponentRef(id = id, label = label)
                },
            lods = sceneManifestLods
                .split("\u001F")
                .filter { it.isNotEmpty() }
                .map { entry ->
                    val (level, resolution) = entry.split(":", limit = 2).let {
                        if (it.size == 2) it[0].toInt() to it[1] else 0 to it[0]
                    }
                    com.elysium.vanguard.foundry.core.scene.LodRef(level = level, resolution = resolution)
                },
            representationLevel = RepresentationLevel.valueOf(representationLevel),
        )
        return VehicleRevision(
            id = VehicleRevisionId.from(id).getOrThrow(),
            projectId = ProjectId.from(projectId).getOrThrow(),
            contentHash = contentHashDomain,
            provenance = provenance,
            sceneManifest = sceneManifest,
            representationLevel = RepresentationLevel.valueOf(representationLevel),
            createdAt = Timestamp(createdAtEpochMs),
            isImmutable = isImmutable,
        )
    }

    companion object {
        fun fromDomain(revision: VehicleRevision): VehicleRevisionEntity = VehicleRevisionEntity(
            id = revision.id.value.toString(),
            projectId = revision.projectId.value.toString(),
            contentHash = revision.contentHash.value,
            provenanceId = revision.provenance.id.value.toString(),
            provenanceSource = revision.provenance.source,
            provenanceSignature = revision.provenance.signature.value,
            sceneManifestComponents = revision.sceneManifest.components
                .joinToString(separator = "\u001F") { "${it.id}:${it.label}" },
            sceneManifestLods = revision.sceneManifest.lods
                .joinToString(separator = "\u001F") { "${it.level}:${it.resolution}" },
            sceneManifestHash = revision.sceneManifest.contentHash.value,
            representationLevel = revision.representationLevel.name,
            isImmutable = revision.isImmutable,
            createdAtEpochMs = revision.createdAt.epochMs,
        )
    }
}
