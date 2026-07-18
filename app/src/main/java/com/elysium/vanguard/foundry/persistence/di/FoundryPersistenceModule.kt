package com.elysium.vanguard.foundry.persistence.di

import android.content.Context
import androidx.room.Room
import com.elysium.vanguard.foundry.persistence.FoundryDatabase
import com.elysium.vanguard.foundry.persistence.repository.ContributorRepository
import com.elysium.vanguard.foundry.persistence.repository.EngineeringArtifactRepository
import com.elysium.vanguard.foundry.persistence.repository.ProjectRepository
import com.elysium.vanguard.foundry.persistence.repository.ProvenanceRecordRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomContributorRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomEngineeringArtifactRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomProjectRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomProvenanceRecordRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomVehicleProgramRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomVehicleRevisionRepository
import com.elysium.vanguard.foundry.persistence.repository.VehicleProgramRepository
import com.elysium.vanguard.foundry.persistence.repository.VehicleRevisionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the Foundry persistence layer.
 *
 * Per `docs/foundry/implementation-roadmap.md` I-1.5 +
 * `.ai/AGENTS.md` section 24.1:
 *   - The database is a `Singleton` (one per process).
 *   - Each repository is a `Singleton` (one per process,
 *     shared by all consumers — a repository is
 *     thread-safe because the underlying Room DAO is
 *     thread-safe).
 *   - The Hilt module is the only place that knows the
 *     concrete Room implementation; consumers depend on
 *     the interface.
 */
@Module
@InstallIn(SingletonComponent::class)
object FoundryPersistenceModule {

    @Provides
    @Singleton
    fun provideFoundryDatabase(
        @ApplicationContext context: Context,
    ): FoundryDatabase {
        return Room.databaseBuilder(
            context,
            FoundryDatabase::class.java,
            FoundryDatabase.DATABASE_NAME,
        )
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    }

    @Provides
    @Singleton
    fun provideProjectRepository(database: FoundryDatabase): ProjectRepository =
        RoomProjectRepository(database.projectDao())

    @Provides
    @Singleton
    fun provideVehicleProgramRepository(database: FoundryDatabase): VehicleProgramRepository =
        RoomVehicleProgramRepository(database.vehicleProgramDao())

    @Provides
    @Singleton
    fun provideContributorRepository(database: FoundryDatabase): ContributorRepository =
        RoomContributorRepository(database.contributorDao())

    @Provides
    @Singleton
    fun provideEngineeringArtifactRepository(database: FoundryDatabase): EngineeringArtifactRepository =
        RoomEngineeringArtifactRepository(database.engineeringArtifactDao())

    @Provides
    @Singleton
    fun provideVehicleRevisionRepository(database: FoundryDatabase): VehicleRevisionRepository =
        RoomVehicleRevisionRepository(database.vehicleRevisionDao())

    @Provides
    @Singleton
    fun provideProvenanceRecordRepository(database: FoundryDatabase): ProvenanceRecordRepository =
        RoomProvenanceRecordRepository(database.provenanceRecordDao())
}
