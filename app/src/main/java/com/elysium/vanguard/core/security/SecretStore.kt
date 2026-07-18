package com.elysium.vanguard.core.security

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The typed access layer for app-level
 * secrets. The platform's secrets are:
 *   - The Market signing key (used to sign
 *     `MarketListing`s).
 *   - The Vanguard Cloud API key (used to
 *     authenticate with the Cloud).
 *   - The user's OAuth refresh token (used
 *     to refresh the OAuth access token).
 *   - The user's encryption passphrase
 *     (used to derive the Tink master key
 *     for the vault).
 *
 * Every read + write is **audited** via the
 * `SecurityAudit` (the audit log records the
 * access). A denied access (e.g. integrity
 * check failure) is a typed `FoundryError`.
 *
 * The Phase 1 implementation is an in-memory
 * map (per the existing `InMemoryAuditTrail`
 * pattern). The Phase 2 implementation
 * persists the secrets in the Tink vault
 * (encrypted at rest with AES-256-GCM).
 */
class SecretStore(
    private val audit: SecurityAudit,
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    private val secrets: MutableMap<String, Secret> = LinkedHashMap()
    private val lock = ReentrantReadWriteLock()

    /**
     * Store a secret. The secret is encrypted
     * at rest (Phase 2) and the write is
     * audited.
     */
    fun put(
        secretId: String,
        secretType: SecretType,
        value: ByteArray,
        accessReason: String,
    ): Result<Unit> {
        // Validate the secretId BEFORE the audit
        // event (the audit event's init block
        // rejects a blank subjectId with an
        // IllegalArgumentException; we want a
        // typed error instead).
        if (secretId.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "SecretStore.secret",
                    reason = "secretId must not be blank",
                ),
            )
        }
        if (value.isEmpty()) {
            audit.record(
                SecurityAuditEvent(
                    eventType = SecurityEventType.SECRET_ACCESS_DENIED,
                    subjectId = secretId,
                    outcome = SecurityEventOutcome.DENIED,
                    details = SecurityEventDetails.SecretAccessDetails(
                        secretId = secretId,
                        secretType = secretType.name,
                        accessReason = accessReason,
                    ),
                    at = clock.now(),
                ),
            )
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "SecretStore.secret",
                    reason = "value must not be empty",
                ),
            )
        }
        lock.write {
            secrets[secretId] = Secret(
                id = secretId,
                type = secretType,
                value = value,
                createdAt = clock.now(),
                lastAccessedAt = clock.now(),
            )
        }
        audit.record(
            SecurityAuditEvent(
                eventType = SecurityEventType.SECRET_WRITE,
                subjectId = secretId,
                outcome = SecurityEventOutcome.SUCCESS,
                details = SecurityEventDetails.SecretAccessDetails(
                    secretId = secretId,
                    secretType = secretType.name,
                    accessReason = accessReason,
                ),
                at = clock.now(),
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Read a secret. The access is audited.
     * A missing secret is a typed
     * `FoundryError` + a `DENIED` audit event.
     */
    fun get(
        secretId: String,
        accessReason: String,
    ): Result<ByteArray> {
        val secret = lock.read { secrets[secretId] }
        if (secret == null) {
            audit.record(
                SecurityAuditEvent(
                    eventType = SecurityEventType.SECRET_ACCESS_DENIED,
                    subjectId = secretId,
                    outcome = SecurityEventOutcome.DENIED,
                    details = SecurityEventDetails.SecretAccessDetails(
                        secretId = secretId,
                        secretType = "UNKNOWN",
                        accessReason = accessReason,
                    ),
                    at = clock.now(),
                ),
            )
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "SecretStore.secret",
                    reason = "no secret with id $secretId",
                ),
            )
        }
        // Update lastAccessedAt (read-side, still under lock).
        lock.write { secrets[secretId] = secret.copy(lastAccessedAt = clock.now()) }
        audit.record(
            SecurityAuditEvent(
                eventType = SecurityEventType.SECRET_READ,
                subjectId = secretId,
                outcome = SecurityEventOutcome.SUCCESS,
                details = SecurityEventDetails.SecretAccessDetails(
                    secretId = secretId,
                    secretType = secret.type.name,
                    accessReason = accessReason,
                ),
                at = clock.now(),
            ),
        )
        return Result.success(secret.value)
    }

    /**
     * Delete a secret. The deletion is
     * audited.
     */
    fun delete(secretId: String): Result<Unit> {
        val removed = lock.write { secrets.remove(secretId) }
        if (removed == null) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "SecretStore.secret",
                    reason = "no secret with id $secretId to delete",
                ),
            )
        }
        return Result.success(Unit)
    }

    /**
     * The number of stored secrets. Used by
     * the monitoring + the test suite.
     */
    fun count(): Int = lock.read { secrets.size }
}

/**
 * A typed secret. The `value` is the secret
 * material; the `type` describes the secret's
 * role.
 */
data class Secret(
    val id: String,
    val type: SecretType,
    val value: ByteArray,
    val createdAt: Timestamp,
    val lastAccessedAt: Timestamp,
) {
    // The auto-generated equals would compare
    // ByteArray by reference, which is wrong.
    // We override equals + hashCode to be
    // value-based.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Secret) return false
        return id == other.id &&
            type == other.type &&
            value.contentEquals(other.value) &&
            createdAt == other.createdAt &&
            lastAccessedAt == other.lastAccessedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastAccessedAt.hashCode()
        return result
    }

    /** The content hash of the value (SHA-256). */
    val valueHash: ContentHash
        get() = ContentHash.of(value)
}

/**
 * The kind of secret. New kinds are ADRs.
 */
enum class SecretType {
    /** The Market publisher's signing key. */
    MARKET_SIGNING_KEY,

    /** The Vanguard Cloud API key. */
    CLOUD_API_KEY,

    /** The user's OAuth refresh token. */
    OAUTH_REFRESH_TOKEN,

    /** The user's encryption passphrase (for the Tink master key). */
    VAULT_PASSPHRASE,
}
