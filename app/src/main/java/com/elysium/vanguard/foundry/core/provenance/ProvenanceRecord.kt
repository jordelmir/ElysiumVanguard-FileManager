package com.elysium.vanguard.foundry.core.provenance

import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * A signed record of where a piece of engineering data came from.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.12 +
 * `.ai/STANDARDS.md` section 3.2:
 *   - The `ProvenanceRecord` is the only proof of the engineering
 *     fact's source. A fact without provenance is `R-DI-1`
 *     (an `AI_INFERRED` fact masquerading as verified).
 *   - The record is **append-only + content-addressed + signed**.
 *     A mutation is a new record, not an edit.
 *   - The record is **complete** when the source is non-blank,
 *     the signature is non-empty, and at least one witness has
 *     countersigned.
 *
 * Phase 1 ships the minimum shape. Phase 4 wires the
 * `ProvenanceService` to the AI council + the human review
 * surface.
 */
data class ProvenanceRecord(
    val id: ProvenanceRecordId,
    val subjectId: String,
    val source: String,
    val signature: Signature,
    val witnesses: List<Signature>,
    val createdAt: Timestamp,
) {
    init {
        require(subjectId.isNotBlank()) { "ProvenanceRecord subjectId must not be blank" }
        require(source.isNotBlank()) { "ProvenanceRecord source must not be blank" }
    }

    /**
     * The record is **complete** when the source is non-blank,
     * the signature is non-empty, and at least one witness has
     * countersigned. The integration test asserts this is `true`
     * for every frozen revision.
     */
    val isComplete: Boolean
        get() = source.isNotBlank() &&
            signature.value.isNotEmpty() &&
            witnesses.isNotEmpty()
}
