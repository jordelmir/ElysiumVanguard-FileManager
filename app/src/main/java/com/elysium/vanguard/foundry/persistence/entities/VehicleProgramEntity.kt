package com.elysium.vanguard.foundry.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.program.VehicleProgram
import com.elysium.vanguard.foundry.core.program.VehicleProgramStatus

/**
 * Room entity for the `VehicleProgram`
 * aggregate. The `revisions` list is stored
 * as a unit-separator-joined string (per the
 * `Converters.stringListToString` pattern).
 *
 * Per `docs/foundry/domain-ownership.md` section 2.2:
 * a `VehicleProgram` is a vehicle family
 * under a `Project`. The `revisions` list is
 * append-only.
 */
@Entity(tableName = "vehicle_programs")
data class VehicleProgramEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description")
    val description: String,

    @ColumnInfo(name = "revisions")
    val revisions: String, // unit-separator-joined VehicleRevisionId UUIDs

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "version")
    val version: Long,
) {
    fun toDomain(): VehicleProgram = VehicleProgram(
        id = VehicleProgramId.from(id).getOrThrow(),
        projectId = ProjectId.from(projectId).getOrThrow(),
        name = name,
        description = description,
        revisions = revisions
            .split("\u001F")
            .filter { it.isNotEmpty() }
            .map { VehicleRevisionId.from(it).getOrThrow() },
        status = VehicleProgramStatus.valueOf(status),
        createdAt = Timestamp(createdAtEpochMs),
        version = version,
    )

    companion object {
        fun fromDomain(program: VehicleProgram): VehicleProgramEntity = VehicleProgramEntity(
            id = program.id.value.toString(),
            projectId = program.projectId.value.toString(),
            name = program.name,
            description = program.description,
            revisions = program.revisions.joinToString(separator = "\u001F") { it.value.toString() },
            status = program.status.name,
            createdAtEpochMs = program.createdAt.epochMs,
            version = program.version,
        )
    }
}
