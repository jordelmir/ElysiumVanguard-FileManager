package com.elysium.vanguard.foundry.core.project

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * The `Project` aggregate — the top-level container for everything the
 * user creates on the Elysium Automotive Foundry.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.1:
 *   - A `Project` has a `Brand`, a name, a description, a list of
 *     `VehicleProgram` references, a list of `Contributor` references,
 *     a default `Locale`, a default `Jurisdiction`, an audit-trail
 *     reference, and a status.
 *   - Phase 1 ships the minimum: `id`, `name`, `ownerId`, `status`,
 *     `createdAt`. Brand + programs + contributors + locale +
 *     jurisdiction are added incrementally in Phase 1 increments.
 *
 * The aggregate is **owned by skill 03 (ontology)**. The `ProjectService`
 * is the only component that may create a `Project`; the mutation is
 * mediated through the service to keep invariants auditable.
 */
data class Project(
    val id: ProjectId,
    val name: String,
    val ownerId: UserId,
    val status: ProjectStatus,
    val createdAt: Timestamp,
) {
    init {
        require(name.isNotBlank()) { "Project name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) {
            "Project name must be <= $MAX_NAME_LENGTH characters, got ${name.length}"
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 120
    }
}

/**
 * The lifecycle status of a `Project`. Transitions are append-only +
 * signed in the audit trail (per `.ai/STANDARDS.md` 2.2).
 */
enum class ProjectStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED,
}
