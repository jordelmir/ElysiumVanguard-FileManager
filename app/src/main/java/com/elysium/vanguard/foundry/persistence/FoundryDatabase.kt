package com.elysium.vanguard.foundry.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.elysium.vanguard.foundry.persistence.daos.ContributorDao
import com.elysium.vanguard.foundry.persistence.daos.EngineeringArtifactDao
import com.elysium.vanguard.foundry.persistence.daos.ProjectDao
import com.elysium.vanguard.foundry.persistence.daos.ProvenanceRecordDao
import com.elysium.vanguard.foundry.persistence.daos.VehicleProgramDao
import com.elysium.vanguard.foundry.persistence.daos.VehicleRevisionDao
import com.elysium.vanguard.foundry.persistence.entities.ContributorEntity
import com.elysium.vanguard.foundry.persistence.entities.EngineeringArtifactEntity
import com.elysium.vanguard.foundry.persistence.entities.ProjectEntity
import com.elysium.vanguard.foundry.persistence.entities.ProvenanceRecordEntity
import com.elysium.vanguard.foundry.persistence.entities.VehicleProgramEntity
import com.elysium.vanguard.foundry.persistence.entities.VehicleRevisionEntity

/**
 * The Foundry domain database. The database
 * is the persistence layer for the 6
 * aggregates (Project, VehicleProgram,
 * Contributor, EngineeringArtifact,
 * VehicleRevision, ProvenanceRecord).
 *
 * Per `docs/foundry/domain-ownership.md`
 * section 6.3 + the implementation roadmap
 * I-1.3 + I-1.5 + I-1.8 (persistence +
 * audit-trail wiring): the database is the
 * only path to a persistent mutation. The
 * `FoundryRepository` (Phase 2 follow-up)
 * is the layer that mediates between the
 * domain services + the DAOs.
 *
 * Phase 1 ships the database + the entities
 * + the DAOs. The Hilt module
 * (`FoundryPersistenceModule`) wires the
 * database + the DAOs. The repositories
 * (one per aggregate) + the service
 * migration are Phase 2 follow-ups.
 */
@Database(
    entities = [
        ProjectEntity::class,
        VehicleProgramEntity::class,
        ContributorEntity::class,
        EngineeringArtifactEntity::class,
        VehicleRevisionEntity::class,
        ProvenanceRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FoundryDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun vehicleProgramDao(): VehicleProgramDao
    abstract fun contributorDao(): ContributorDao
    abstract fun engineeringArtifactDao(): EngineeringArtifactDao
    abstract fun vehicleRevisionDao(): VehicleRevisionDao
    abstract fun provenanceRecordDao(): ProvenanceRecordDao

    companion object {
        /**
         * The database name. The Hilt module
         * uses this name to construct the
         * `Room.databaseBuilder`.
         */
        const val DATABASE_NAME: String = "foundry.db"
    }
}
