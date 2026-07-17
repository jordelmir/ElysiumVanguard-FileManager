package com.elysium.vanguard.foundry.core.provenance

import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create a `ProvenanceRecord` for a given subject.
 *
 * The service is a thin wrapper that signs the record + assigns the
 * ID + the timestamp. The signature key is a per-revision byte
 * array (Phase 1: the content hash bytes; production: the user's
 * Ed25519 / ML-DSA-65 keypair).
 *
 * In Phase 4 the service is wired to the AI council + the human
 * review surface; the witness list is populated by the human
 * countersignature.
 */
class ProvenanceService(
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    /**
     * Create a new provenance record for the given subject.
     *
     * The `signature` is computed over `(subjectId|source)` using
     * the provided `signingKey`. The `witnesses` list is the list
     * of countersignatures — Phase 1 supplies the system's own
     * signature as a single witness so `isComplete` is `true`.
     */
    fun createProvenance(
        subjectId: String,
        source: String,
        signingKey: ByteArray,
        witnesses: List<Signature> = emptyList(),
    ): ProvenanceRecord {
        val payload = "$subjectId|$source"
        val signature = Signature.sign(payload, signingKey)
        // The system's own signature is the first witness; any
        // human countersignatures are appended.
        val allWitnesses = listOf(signature) + witnesses
        return ProvenanceRecord(
            id = ProvenanceRecordId.random(),
            subjectId = subjectId,
            source = source,
            signature = signature,
            witnesses = allWitnesses,
            createdAt = clock.now(),
        )
    }
}
