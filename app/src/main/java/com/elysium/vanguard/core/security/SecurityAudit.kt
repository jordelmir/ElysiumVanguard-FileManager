package com.elysium.vanguard.core.security

import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The security audit log. Every security-
 * sensitive operation (device integrity
 * check, secret access, integrity failure,
 * CVE detection) is recorded in the log.
 *
 * The log is **append-only** (per
 * `.ai/STANDARDS.md` 2.2 + ADR-0006): events
 * are added; events are never modified or
 * removed. The log is the only path to
 * record a security event.
 *
 * Phase 1 implementation: an in-memory log
 * (per the existing `InMemoryAuditTrail`
 * pattern in the Foundry). Phase 2
 * implementation: a content-addressed log
 * (per `.ai/STANDARDS.md` 2.2 + the
 * `audit/` package).
 */
class SecurityAudit {

    private val events: MutableList<SecurityAuditEvent> = mutableListOf()
    private val lock = ReentrantReadWriteLock()

    /**
     * Record a security event. The event is
     * appended to the log; the recording is
     * thread-safe (the lock is exclusive).
     */
    fun record(event: SecurityAuditEvent): SecurityAuditEvent = lock.write {
        events.add(event)
        event
    }

    /**
     * All events in the log (oldest first).
     * The list is a copy; mutations to the
     * returned list do not affect the log.
     */
    fun all(): List<SecurityAuditEvent> = lock.read {
        events.toList()
    }

    /**
     * The events for a given subject (e.g. a
     * specific `SecretId` or a specific user
     * id).
     */
    fun forSubject(subjectId: String): List<SecurityAuditEvent> = lock.read {
        events.filter { it.subjectId == subjectId }
    }

    /**
     * The count of events. Used by the
     * monitoring + the test suite.
     */
    fun count(): Int = lock.read {
        events.size
    }
}

/**
 * A single security audit event. The event
 * is a typed value (per `.ai/AGENTS.md` 24.1)
 * — a free-form string is never the value.
 *
 * The event carries:
 *   - `eventType`: the kind of event
 *     (INTEGRITY_CHECK, SECRET_READ, etc.).
 *   - `subjectId`: the id of the thing the
 *     event is about (a `SecretId`, a user
 *     id, a device id, etc.).
 *   - `outcome`: the result of the event
 *     (SUCCESS, FAILURE, DENIED).
 *   - `details`: the typed details (a typed
 *     data class, not a free-form string).
 *   - `at`: the timestamp of the event.
 */
data class SecurityAuditEvent(
    val eventType: SecurityEventType,
    val subjectId: String,
    val outcome: SecurityEventOutcome,
    val details: SecurityEventDetails,
    val at: Timestamp,
) {
    init {
        require(subjectId.isNotBlank()) { "SecurityAuditEvent subjectId must not be blank" }
    }
}

enum class SecurityEventType {
    /** A `DeviceIntegrity.check()` was performed. */
    INTEGRITY_CHECK,

    /** A secret was read from the `SecretStore`. */
    SECRET_READ,

    /** A secret was written to the `SecretStore`. */
    SECRET_WRITE,

    /** A `SecretStore` access was denied. */
    SECRET_ACCESS_DENIED,

    /** A CVE detection was performed. */
    CVE_DETECTION,
}

enum class SecurityEventOutcome {
    SUCCESS,
    FAILURE,
    DENIED,
}

/**
 * The typed details of a security event. A
 * sealed class so the consumer can pattern-
 * match on the variant. A free-form string
 * is never the value.
 */
sealed class SecurityEventDetails {

    data class IntegrityCheckDetails(
        val isTrusted: Boolean,
        val failures: List<IntegrityFailure>,
        val appSignatureDigest: String?,
    ) : SecurityEventDetails()

    data class SecretAccessDetails(
        val secretId: String,
        val secretType: String,
        val accessReason: String,
    ) : SecurityEventDetails()

    data class CveDetectionDetails(
        val cveId: String,
        val severity: String,
        val affectedComponent: String,
    ) : SecurityEventDetails()
}
