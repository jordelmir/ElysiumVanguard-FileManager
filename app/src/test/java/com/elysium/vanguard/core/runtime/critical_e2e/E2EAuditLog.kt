package com.elysium.vanguard.core.runtime.critical_e2e

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Phase 70 — the E2E audit log.
 *
 * The E2E test needs to record every step + every
 * write + every mount decision. The full
 * `SecurityAudit` (Phase 63) has a more complex
 * `SecurityAuditEvent` shape that's overkill for the
 * E2E. This is a simple, focused log for the E2E test.
 *
 * The log is **append-only** (per the master vision's
 * "Registro inmutable de operaciones críticas"): every
 * event is recorded; events are never modified or
 * removed.
 *
 * The log is **thread-safe** (per `.ai/AGENTS.md` 24.1):
 * the `record` method is guarded by an exclusive lock;
 * the read methods are guarded by a shared lock.
 */
class E2EAuditLog {

    /**
     * A single audit event. The event is
     * append-only.
     */
    data class Event(
        val subjectId: String,
        val eventType: String,
        val detail: String,
        val atMs: Long,
    )

    private val events: MutableList<Event> = mutableListOf()
    private val lock = ReentrantReadWriteLock()

    /**
     * Record an event. The event is appended to
     * the log; the recording is thread-safe.
     */
    fun record(subjectId: String, eventType: String, detail: String): Event = lock.write {
        val event = Event(
            subjectId = subjectId,
            eventType = eventType,
            detail = detail,
            atMs = System.currentTimeMillis(),
        )
        events.add(event)
        event
    }

    /**
     * All events in the log (oldest first). The
     * list is a copy.
     */
    fun all(): List<Event> = lock.read { events.toList() }

    /**
     * The events for a given subject (e.g. a
     * specific session id).
     */
    fun eventsForSubject(subjectId: String): List<Event> = lock.read {
        events.filter { it.subjectId == subjectId }
    }

    /**
     * The count of events for a given subject.
     */
    fun countForSubject(subjectId: String): Int = lock.read {
        events.count { it.subjectId == subjectId }
    }

    /**
     * The total event count.
     */
    fun size(): Int = lock.read { events.size }
}
