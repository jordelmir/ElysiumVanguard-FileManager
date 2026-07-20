package com.elysium.vanguard.core.database.runtime

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeDatabaseModule {

    /**
     * Phase 94 — the migration that adds the
     * `mime_type` column to the `media_index`
     * table. The migration is a typed
     * `ALTER TABLE` that adds the column
     * with a `NOT NULL DEFAULT ''`
     * constraint (the empty string is the
     * "MIME type unknown" sentinel; the
     * indexer's next scan will fill in the
     * real value for existing rows).
     */
    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE media_index " +
                    "ADD COLUMN mime_type TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    @Provides
    @Singleton
    fun provideRuntimeDatabase(@ApplicationContext context: Context): RuntimeDatabase {
        val builder = Room.databaseBuilder(
            context,
            RuntimeDatabase::class.java,
            "elysium_runtime.db"
        )
            // Section 25 of the master order prohibits destructive migration
            // in production. Every schema bump MUST add a Migration(N, N+1)
            // and register it via addMigrations(...) below — never via
            // fallbackToDestructiveMigration().
            .addMigrations(MIGRATION_1_2)
        return builder.build()
    }

    @Provides
    fun provideDistroInstallDao(db: RuntimeDatabase): DistroInstallDao = db.distroInstallDao()

    @Provides
    fun provideSessionDao(db: RuntimeDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideApplicationCapsuleDao(db: RuntimeDatabase): ApplicationCapsuleDao = db.applicationCapsuleDao()

    @Provides
    fun provideHardwareAccessAuditDao(db: RuntimeDatabase): HardwareAccessAuditDao = db.hardwareAccessAuditDao()

    @Provides
    fun provideDiagnosticEventDao(db: RuntimeDatabase): DiagnosticEventDao = db.diagnosticEventDao()

    @Provides
    fun provideWorkspaceDao(db: RuntimeDatabase): WorkspaceDao = db.workspaceDao()

    @Provides
    fun provideNetworkRuleDao(db: RuntimeDatabase): NetworkRuleDao = db.networkRuleDao()

    @Provides
    fun provideMediaIndexDao(db: RuntimeDatabase): com.elysium.vanguard.core.database.media.MediaIndexDao =
        db.mediaIndexDao()
}
