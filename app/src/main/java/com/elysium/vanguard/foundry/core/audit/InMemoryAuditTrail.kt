package com.elysium.vanguard.foundry.core.audit

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The in-memory implementation of the audit trail. Phase 1
 * default; Phase 2 swaps in a Room-backed implementation
 * without changing the public interface.
 *
 * Concurrency: the trail uses a `ReentrantReadWriteLock` so
 * `append` is exclusive (only one writer at a time) +
 * `findBySubject` + `count` are concurrent (many readers).
 * The append-only invariant is enforced by the lock + by
 * the `LinkedHashMap`'s insertion-order guarantee.
 *
 * Append-only invariant (per `.ai/STANDARDS.md` 2.2 +
 * ADR-0006): the events are stored in a `LinkedHashMap`
 * keyed by the event's `id`. A second append with the same
 * `id` is a duplicate (the same event was sent twice) and
 * is rejected with a typed `FoundryError.VehicleDefinitionInvalid`
 * (the most generic "this input is wrong" variant; a
 * dedicated `DuplicateEvent` variant is added in Phase 2
 * when the Room implementation is wired).
 */
class InMemoryAuditTrail : AuditTrail {

    private val events: MutableMap<String, SignedEvent> = LinkedHashMap()
    private val lock = ReentrantReadWriteLock()

    override fun append(event: SignedEvent): Result<SignedEvent> = lock.write {
        if (events.containsKey(event.id)) {
            return@write Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "AuditTrail.events",
                    reason = "event with id ${event.id} already exists (append-only)",
                ),
            )
        }
        events[event.id] = event
        Result.success(event)
    }

    override fun findBySubject(subjectId: String): List<SignedEvent> = lock.read {
        events.values.filter { it.subjectId == subjectId }
    }

    override fun count(): Int = lock.read {
        events.size
    }
}
