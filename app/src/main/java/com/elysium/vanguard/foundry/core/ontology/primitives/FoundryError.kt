package com.elysium.vanguard.foundry.core.ontology.primitives

import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId

/**
 * Typed error envelope for the Elysium Automotive Foundry platform.
 *
 * Per `.ai/STANDARDS.md` section 7 + `.ai/AGENTS.md` section 24.1:
 *   - A free-form string is never the value of an error.
 *   - A `Map<String, Any>` is never the value of an error.
 *   - A `null` is never the value where a typed value is required.
 *   - Every error has a retry classification (per `.ai/AGENTS.md` 24.4).
 *
 * The `FoundryError` class is a sealed hierarchy; the consumer pattern-
 * matches on the variant. Each variant carries the typed context required
 * to recover + the retry classification + the safe user-facing message
 * (per `.ai/AGENTS.md` 24.2 — no stack traces, no internal class names).
 *
 * Why `RuntimeException`: the test `assertFailsWith<FrozenRevisionMutationRejected>`
 * needs the error to be a throwable. Extending `RuntimeException` makes the
 * error both a typed value (the sealed class) and a throwable (for
 * integration-test assertions). The integration boundary (API surface)
 * catches + serializes; the typed value is the contract.
 */
sealed class FoundryError(
    message: String,
    val code: String,
    val retryClassification: RetryClassification,
) : RuntimeException(message) {

    /**
     * The underlying parse failure, if any. The platform strips this from
     * user-facing responses (per `.ai/AGENTS.md` 24.5) but keeps it in
     * the server-side log.
     */
    open val parseFailure: Throwable? = null

    /**
     * Retry classification per `.ai/AGENTS.md` 24.4.
     */
    enum class RetryClassification {
        /** The client MAY retry the same request immediately. */
        RETRYABLE_IMMEDIATE,

        /** The client MAY retry after exponential backoff. */
        RETRYABLE_BACKOFF,

        /** The client MAY retry only if the request is idempotent. */
        RETRYABLE_IDEMPOTENT_ONLY,

        /** The client MUST NOT retry. */
        NON_RETRYABLE,
    }

    /**
     * A UUID string was not a valid UUID. Raised at the boundary (per
     * `.ai/AGENTS.md` 24.1) — never inside the domain.
     */
    data class InvalidUuidFormat(
        val idTypeName: String,
        val rawInput: String,
        override val parseFailure: Throwable,
    ) : FoundryError(
        message = "Invalid UUID format for $idTypeName: $rawInput",
        code = "INVALID_UUID_FORMAT",
        retryClassification = RetryClassification.NON_RETRYABLE,
    )

    /**
     * A `VehicleRevision` has been frozen (signed + appended to the audit
     * trail) and any mutation attempt is rejected. This error is the
     * platform's hard guard against mutating signed engineering data
     * (per `.ai/STANDARDS.md` section 2.2 + ADR-0006).
     */
    data class FrozenRevisionMutationRejected(
        val revisionId: VehicleRevisionId,
    ) : FoundryError(
        message = "Cannot mutate frozen revision ${revisionId.value}",
        code = "FROZEN_REVISION_MUTATION_REJECTED",
        retryClassification = RetryClassification.NON_RETRYABLE,
    )

    /**
     * An optimistic-concurrency conflict (a `version` mismatch). The
     * losing update is rejected; the client is expected to re-read +
     * retry the operation (with the user's explicit consent).
     */
    data class RevisionConflict(
        val aggregateType: String,
        val aggregateId: String,
        val expectedVersion: Long,
        val actualVersion: Long,
    ) : FoundryError(
        message = "Revision conflict on $aggregateType($aggregateId): expected v$expectedVersion, found v$actualVersion",
        code = "REVISION_CONFLICT",
        retryClassification = RetryClassification.RETRYABLE_IDEMPOTENT_ONLY,
    )

    /**
     * A `ProvenanceRecord` is missing required metadata (source, signature,
     * or witness). The engineering fact cannot be relied upon until the
     * provenance is complete.
     */
    data class ProvenanceIncomplete(
        val subjectId: String,
        val missingFields: List<String>,
    ) : FoundryError(
        message = "Provenance incomplete for $subjectId: missing $missingFields",
        code = "PROVENANCE_INCOMPLETE",
        retryClassification = RetryClassification.NON_RETRYABLE,
    )

    /**
     * A `VehicleDefinition` failed validation. The reason is a typed
     * field reference (never free-form text).
     */
    data class VehicleDefinitionInvalid(
        val field: String,
        val reason: String,
    ) : FoundryError(
        message = "Vehicle definition invalid at $field: $reason",
        code = "VEHICLE_DEFINITION_INVALID",
        retryClassification = RetryClassification.NON_RETRYABLE,
    )

    /**
     * A schema version is incompatible with the consumer's expected
     * version. The orchestrator coordinates the upgrade.
     */
    data class SchemaVersionIncompatible(
        val shape: String,
        val expectedVersion: String,
        val actualVersion: String,
    ) : FoundryError(
        message = "Schema version incompatible for $shape: expected $expectedVersion, got $actualVersion",
        code = "SCHEMA_VERSION_INCOMPATIBLE",
        retryClassification = RetryClassification.NON_RETRYABLE,
    )

    /**
     * A compilation failed because the deterministic compiler could not
     * produce a stable result (e.g. inputs contain a non-deterministic
     * value such as a wall-clock timestamp).
     */
    data class CompilationNonDeterministic(
        val reason: String,
    ) : FoundryError(
        message = "Compilation non-deterministic: $reason",
        code = "COMPILATION_NON_DETERMINISTIC",
        retryClassification = RetryClassification.NON_RETRYABLE,
    )
}
