package com.elysium.vanguard.foundry.core.concurrency

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * Optimistic-concurrency control for every mutable aggregate in the
 * Foundry platform.
 *
 * Per `docs/foundry/implementation-roadmap.md` I-1.9 +
 * `.ai/STANDARDS.md` 2.2 + `R-CO-2` in `docs/foundry/risk-register.md`:
 *   - Every mutable aggregate has a `version: Long` field.
 *   - A mutation is a `withVersion(expectedVersion)` operation.
 *   - If `expectedVersion` differs from the stored version, the
 *     mutation is rejected with a typed `FoundryError.RevisionConflict`.
 *   - The losing update does NOT silently overwrite the winning
 *     update.
 *   - The retry classification is `RETRYABLE_IDEMPOTENT_ONLY`
 *     (per `.ai/AGENTS.md` 24.4): the client MAY retry only if
 *     the request is idempotent.
 *
 * The helper is a single `check` function that the services call
 * before any mutation. The check returns `null` on success
 * (caller proceeds) or a `FoundryError.RevisionConflict` on
 * failure (caller returns `Result.failure`).
 *
 * The `VehicleRevision` is intentionally NOT covered by this
 * helper because the revision is **immutable** (per ADR-0006);
 * a frozen revision has no `version` to check. The
 * `RevisionService.modifyFrozenRevision` always throws a
 * `FrozenRevisionMutationRejected` error instead.
 */
object OptimisticConcurrency {

    /**
     * Check that the expected version matches the actual version.
     * Returns `null` on success; returns a `FoundryError.RevisionConflict`
     * on mismatch.
     *
     * Usage:
     * ```
     * val conflict = OptimisticConcurrency.check(
     *     aggregateType = "Project",
     *     aggregateId = project.id.value.toString(),
     *     expectedVersion = expectedVersion,
     *     actualVersion = project.version,
     * )
     * if (conflict != null) return Result.failure(conflict)
     * ```
     */
    fun check(
        aggregateType: String,
        aggregateId: String,
        expectedVersion: Long,
        actualVersion: Long,
    ): FoundryError.RevisionConflict? {
        if (expectedVersion == actualVersion) {
            return null
        }
        return FoundryError.RevisionConflict(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            expectedVersion = expectedVersion,
            actualVersion = actualVersion,
        )
    }
}
