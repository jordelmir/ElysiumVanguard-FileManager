package com.elysium.vanguard.core.database.runtime

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeDatabaseModule {

    @Provides
    @Singleton
    fun provideRuntimeDatabase(@ApplicationContext context: Context): RuntimeDatabase {
        val builder = Room.databaseBuilder(
            context,
            RuntimeDatabase::class.java,
            "elysium_runtime.db"
        )
        // Section 25 of the master order prohibits destructive migration
        // in production. We rely on Room's default behavior: any schema
        // version mismatch without a registered Migration is a hard
        // IllegalStateException, which is the correct production posture.
        // A future migration to schemaVersion=2 must add a Migration(1,2)
        // and register it via addMigrations(...) below — never via
        // fallbackToDestructiveMigration().
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
}
