package com.elysium.vanguard.foundry.core.program

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * A `VehicleProgram` is a vehicle family under a `Project`. It is the
 * "Mustang line" or the "Accent line" — a logical grouping of related
 * vehicle revisions across model years, trims, and regions.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.2:
 *   - A `VehicleProgram` is owned by skill 03 (ontology).
 *   - The program has a `name` + a `description` + a list of
 *     `VehicleRevisionId` references (append-only).
 *   - The status is `DRAFT` / `ACTIVE` / `ARCHIVED`; transitions are
 *     append-only + signed.
 *
 * The `revisions` list is **append-only**: a revision is added; a
 * revision is never removed. The order is preserved (the list is a
 * `List`, not a `Set`).
 */
data class VehicleProgram(
    val id: VehicleProgramId,
    val projectId: ProjectId,
    val name: String,
    val description: String,
    val revisions: List<VehicleRevisionId>,
    val status: VehicleProgramStatus,
    val createdAt: Timestamp,
    val version: Long = 0L,
) {
    init {
        require(name.isNotBlank()) { "VehicleProgram name must not be blank" }
        require(name.length <= MAX_NAME_LENGTH) {
            "VehicleProgram name must be <= $MAX_NAME_LENGTH characters, got ${name.length}"
        }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "VehicleProgram description must be <= $MAX_DESCRIPTION_LENGTH characters, got ${description.length}"
        }
    }

    companion object {
        const val MAX_NAME_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}

enum class VehicleProgramStatus { DRAFT, ACTIVE, ARCHIVED }
