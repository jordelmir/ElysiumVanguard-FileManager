package com.elysium.vanguard.foundry.core.audit

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * A signed, append-only event in the audit trail.
 *
 * Per `.ai/STANDARDS.md` 2.2 + ADR-0006 + `R-DI-4` in
 * `docs/foundry/risk-register.md`:
 *   - The event is **immutable** once written. A rollback is a new
 *     event, not an edit.
 *   - The event is **content-addressed**: `contentHash` is the
 *     SHA-256 of the canonical form of the event. A content
 *     mismatch is a hard rejection.
 *   - The event is **signed**: the `signature` binds the event
 *     to the producer (a user, a service, an AI council). A
 *     signature failure is a hard rejection.
 *
 * The event payload is typed (`SignedEventPayload`); the
 * audit trail stores the typed payload, not a free-form
 * string.
 */
data class SignedEvent(
    val id: String,
    val eventType: String,
    val subjectId: String,
    val payload: SignedEventPayload,
    val signature: Signature,
    val contentHash: ContentHash,
    val createdAt: Timestamp,
) {
    init {
        require(id.isNotBlank()) { "SignedEvent id must not be blank" }
        require(eventType.isNotBlank()) { "SignedEvent eventType must not be blank" }
        require(subjectId.isNotBlank()) { "SignedEvent subjectId must not be blank" }
    }
}

/**
 * The typed payload of a signed event. Phase 1 ships a single
 * variant (`ProvenanceAppended`); Phase 4 adds more variants
 * (AIProposalApplied, RoyaltyContractActivated, etc.).
 *
 * Why a sealed class: the consumer pattern-matches on the
 * variant; a free-form string is never the value (per
 * `.ai/AGENTS.md` 24.1).
 */
sealed class SignedEventPayload {

    /**
     * A `ProvenanceRecord` was appended to the trail. The
     * payload carries the record's `subjectId` + the record's
     * own `contentHash` + the record's `source` for the
     * downstream consumer.
     */
    data class ProvenanceAppended(
        val provenanceSubjectId: String,
        val provenanceContentHash: String,
        val source: String,
    ) : SignedEventPayload()
}
