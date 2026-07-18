package com.elysium.vanguard.foundry.core.provenance

import com.elysium.vanguard.foundry.core.audit.AuditTrail
import com.elysium.vanguard.foundry.core.audit.SignedEvent
import com.elysium.vanguard.foundry.core.audit.SignedEventPayload
import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create a `ProvenanceRecord` AND append the
 * corresponding `SignedEvent` to the audit trail.
 *
 * The service is the only legitimate way to produce a
 * `ProvenanceRecord`. The flow:
 *   1. Sign the record's payload (the `Signature` primitive
 *      signs `(subjectId|source)` with the signing key).
 *   2. Build the record.
 *   3. Compute the record's content hash (SHA-256 of the
 *      canonical form).
 *   4. Build the `SignedEvent` (audit trail event).
 *   5. Append the `SignedEvent` to the audit trail. The
 *      append is the only path to a `ProvenanceRecord`'s
 *      persistence; the consumer is expected to read the
 *      event from the trail.
 *
 * The audit trail is **required** (no default). A service
 * that needs to skip the audit trail is a P0 contract
 * violation; the orchestrator (skill 00) arbitrates.
 */
class ProvenanceService(
    private val auditTrail: AuditTrail,
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    fun createProvenance(
        subjectId: String,
        source: String,
        signingKey: ByteArray,
        witnesses: List<Signature> = emptyList(),
    ): Result<ProvenanceRecord> {
        if (subjectId.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "ProvenanceRecord.subjectId",
                    reason = "subjectId must not be blank",
                ),
            )
        }
        if (source.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "ProvenanceRecord.source",
                    reason = "source must not be blank",
                ),
            )
        }

        // 1. Sign the payload.
        val payload = "$subjectId|$source"
        val signature = Signature.sign(payload, signingKey)

        // 2. Build the record. The system's signature is the
        //    first witness; any human countersignatures are
        //    appended.
        val record = ProvenanceRecord(
            id = ProvenanceRecordId.random(),
            subjectId = subjectId,
            source = source,
            signature = signature,
            witnesses = listOf(signature) + witnesses,
            createdAt = clock.now(),
        )

        // 3. Compute the record's content hash.
        val recordHash = ContentHash.of(
            "provenance:v1|${record.id.value}|$subjectId|$source|${signature.value}",
        )

        // 4. Build the signed event.
        val event = SignedEvent(
            id = record.id.value.toString(),
            eventType = "provenance.appended",
            subjectId = subjectId,
            payload = SignedEventPayload.ProvenanceAppended(
                provenanceSubjectId = subjectId,
                provenanceContentHash = recordHash.value,
                source = source,
            ),
            signature = signature,
            contentHash = recordHash,
            createdAt = record.createdAt,
        )

        // 5. Append to the audit trail.
        return auditTrail.append(event).map { record }
    }
}
