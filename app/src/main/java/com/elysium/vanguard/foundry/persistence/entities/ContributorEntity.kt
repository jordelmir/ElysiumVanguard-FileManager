package com.elysium.vanguard.foundry.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.contributor.Contributor
import com.elysium.vanguard.foundry.core.contributor.ContributorRole

/**
 * Room entity for the `Contributor` aggregate.
 * The contributor's PII (the `email` field)
 * is stored as-is in Phase 1; the encryption
 * at rest is wired in Phase 5 (per skill 12
 * + the platform's Zero Trust model).
 *
 * Per `docs/foundry/domain-ownership.md` section 2.10:
 * a `Contributor` is a human or an organization
 * that has contributed to a project. The
 * contributor's PII is encrypted at rest in
 * Phase 5.
 */
@Entity(tableName = "contributors")
data class ContributorEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "email")
    val email: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "version")
    val version: Long,
) {
    fun toDomain(): Contributor = Contributor(
        id = ContributorId.from(id).getOrThrow(),
        displayName = displayName,
        email = email,
        role = ContributorRole.valueOf(role),
        createdAt = Timestamp(createdAtEpochMs),
        version = version,
    )

    companion object {
        fun fromDomain(contributor: Contributor): ContributorEntity = ContributorEntity(
            id = contributor.id.value.toString(),
            displayName = contributor.displayName,
            email = contributor.email,
            role = contributor.role.name,
            createdAtEpochMs = contributor.createdAt.epochMs,
            version = contributor.version,
        )
    }
}
