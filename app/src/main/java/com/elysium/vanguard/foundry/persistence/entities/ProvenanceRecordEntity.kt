package com.elysium.vanguard.foundry.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord

/**
 * Room entity for the `ProvenanceRecord`
 * aggregate. The record is **append-only**
 * (per ADR-0006 + the `FoundryError` envelope);
 * the entity is a snapshot of the signed
 * event.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.12:
 * a `ProvenanceRecord` is the only proof of
 * the engineering fact's source. The record
 * is signed + content-addressed + append-only.
 */
@Entity(tableName = "provenance_records")
data class ProvenanceRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "subject_id")
    val subjectId: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "signature")
    val signature: String,

    @ColumnInfo(name = "witnesses")
    val witnesses: String, // unit-separator-joined signatures

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
) {
    fun toDomain(): ProvenanceRecord = ProvenanceRecord(
        id = ProvenanceRecordId.from(id).getOrThrow(),
        subjectId = subjectId,
        source = source,
        signature = Signature(signature),
        witnesses = witnesses
            .split("\u001F")
            .filter { it.isNotEmpty() }
            .map(::Signature),
        createdAt = Timestamp(createdAtEpochMs),
    )

    companion object {
        fun fromDomain(record: ProvenanceRecord): ProvenanceRecordEntity = ProvenanceRecordEntity(
            id = record.id.value.toString(),
            subjectId = record.subjectId,
            source = record.source,
            signature = record.signature.value,
            witnesses = record.witnesses.joinToString(separator = "\u001F") { it.value },
            createdAtEpochMs = record.createdAt.epochMs,
        )
    }
}
