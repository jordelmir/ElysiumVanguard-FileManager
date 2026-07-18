package com.elysium.vanguard.foundry.core.contributor

import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * A `Contributor` is a human or an organization that has contributed
 * to a project.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.10:
 *   - The `Contributor` is owned by skill 09 (IP / provenance / royalties)
 *     because the contributor's identity is bound to the provenance
 *     ledger + the royalty contracts.
 *   - Skill 03 reads the contributor's display name + locale + public
 *     handle, but NOT the contributor's private data (the private
 *     data lives in skill 09's encrypted store, which is wired in
 *     Phase 5).
 *   - Phase 1 ships the minimum shape: `id` + `displayName` + `email`
 *     + `role` + `version`. The PII (email) is documented as
 *     "must be encrypted in production"; the encryption is wired
 *     in Phase 5 (per skill 12).
 */
data class Contributor(
    val id: ContributorId,
    val displayName: String,
    val email: String,
    val role: ContributorRole,
    val createdAt: Timestamp,
    val version: Long = 0L,
) {
    init {
        require(displayName.isNotBlank()) { "Contributor displayName must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Contributor displayName must be <= $MAX_DISPLAY_NAME_LENGTH characters, got ${displayName.length}"
        }
        // Email validation is intentionally lax: the platform accepts
        // any non-blank string with an `@`. Production-grade email
        // validation is added in Phase 5 (when the SMTP/identity
        // provider is wired).
        require(email.isNotBlank()) { "Contributor email must not be blank" }
        require(email.contains("@")) { "Contributor email must contain '@', got: $email" }
    }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 120
    }
}

/**
 * The role a contributor plays in the project. The role determines
 * the contributor's permissions + the contributor's visibility in the
 * audit trail.
 */
enum class ContributorRole {
    /** A user who designs the vehicle via the DSL. */
    DESIGNER,

    /** A user who reviews + signs off a design. */
    ENGINEER,

    /** A user who diagnoses + repairs a vehicle in the field. */
    MECHANIC,

    /** A user (regulator) who approves a regulatory submission. */
    REVIEWER,

    /** A user with full administrative access to the project. */
    ADMIN,
}
