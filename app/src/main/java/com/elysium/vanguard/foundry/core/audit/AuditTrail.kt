package com.elysium.vanguard.foundry.core.audit

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * The audit trail's interface. The trail is the platform's
 * append-only + signed + content-addressed record of every
 * event that mutates authoritative state.
 *
 * Per `docs/foundry/domain-ownership.md` section 6.4 +
 * `.ai/STANDARDS.md` 2.2:
 *   - `append` adds a new event; the event is signed +
 *     content-addressed; the append is the only path to a
 *     mutation.
 *   - `findBySubject` returns the events for a given subject
 *     (the subject is a `ProjectId`, a `VehicleRevisionId`,
 *     a `RoyaltyContractId`, etc.).
 *   - `count` returns the total number of events (for
 *     monitoring + test assertions).
 *
 * Phase 1 ships the in-memory implementation. Phase 2 wires
 * the Room-backed implementation (per skill 08).
 *
 * The interface is `Result`-based so the consumer pattern-
 * matches on the typed error. A `AuditTrailError` variant
 * is added to the `FoundryError` hierarchy when the Room
 * implementation is wired.
 */
interface AuditTrail {

    fun append(event: SignedEvent): Result<SignedEvent>

    fun findBySubject(subjectId: String): List<SignedEvent>

    fun count(): Int
}
