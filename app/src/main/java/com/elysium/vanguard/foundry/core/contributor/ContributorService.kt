package com.elysium.vanguard.foundry.core.contributor

import com.elysium.vanguard.foundry.core.concurrency.OptimisticConcurrency
import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * Use case: create + update a `Contributor`.
 *
 * The contributor's private data (`email`) is documented as
 * "must be encrypted at rest" per `R-T-10` + skill 12. Phase 1
 * does NOT encrypt the email (no storage layer yet); the
 * encryption is wired in Phase 5 when the vault + the audit
 * trail are added.
 *
 * Mutations are optimistic-concurrency-controlled: the caller
 * supplies the expected `version`; the service raises a typed
 * `FoundryError.RevisionConflict` if the stored version differs.
 */
class ContributorService(
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) {

    fun createContributor(
        displayName: String,
        email: String,
        role: ContributorRole,
    ): Result<Contributor> {
        if (displayName.isBlank()) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.displayName",
                    reason = "displayName must not be blank",
                ),
            )
        }
        if (!email.contains("@")) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.email",
                    reason = "email must contain '@', got: $email",
                ),
            )
        }
        return Result.success(
            Contributor(
                id = ContributorId.random(),
                displayName = displayName.trim(),
                email = email.trim(),
                role = role,
                createdAt = clock.now(),
                version = 0L,
            ),
        )
    }

    /**
     * Update the contributor's role. The role is the only field
     * that can change in Phase 1 (displayName + email changes are
     * wired in Phase 5 with the identity provider).
     */
    fun updateRole(
        contributor: Contributor,
        newRole: ContributorRole,
        expectedVersion: Long,
    ): Result<Contributor> {
        val conflict = OptimisticConcurrency.check(
            aggregateType = "Contributor",
            aggregateId = contributor.id.value.toString(),
            expectedVersion = expectedVersion,
            actualVersion = contributor.version,
        )
        if (conflict != null) {
            return Result.failure(conflict)
        }
        if (contributor.role == newRole) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "Contributor.role",
                    reason = "new role must differ from current role",
                ),
            )
        }
        return Result.success(
            contributor.copy(
                role = newRole,
                version = contributor.version + 1,
            ),
        )
    }
}
