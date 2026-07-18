package com.elysium.vanguard.foundry.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.core.project.ProjectStatus

/**
 * Room entity for the `Project` aggregate.
 * The entity mirrors the domain `Project`
 * data class; the conversion is in
 * `ProjectEntity.toDomain()` + `ProjectEntity.fromDomain()`.
 *
 * Per `docs/foundry/domain-ownership.md` section 2.1:
 * a `Project` has an `id` + a `name` + an
 * `ownerId` + a `status` + a `createdAt` +
 * a `version` (optimistic concurrency).
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "owner_id")
    val ownerId: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "version")
    val version: Long,
) {
    fun toDomain(): Project = Project(
        id = ProjectId.from(id).getOrThrow(),
        name = name,
        ownerId = UserId.from(ownerId).getOrThrow(),
        status = ProjectStatus.valueOf(status),
        createdAt = Timestamp(createdAtEpochMs),
        version = version,
    )

    companion object {
        fun fromDomain(project: Project): ProjectEntity = ProjectEntity(
            id = project.id.value.toString(),
            name = project.name,
            ownerId = project.ownerId.value.toString(),
            status = project.status.name,
            createdAtEpochMs = project.createdAt.epochMs,
            version = project.version,
        )
    }
}
