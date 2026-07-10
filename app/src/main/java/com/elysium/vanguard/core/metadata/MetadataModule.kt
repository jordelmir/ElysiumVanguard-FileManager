package com.elysium.vanguard.core.metadata

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 2.12 — Hilt module for file metadata.
 *
 * Kept minimal because the repository only needs the DAO, which Hilt can resolve
 * via DatabaseModule. We could lean on @Inject constructor + @Singleton on the
 * repository itself, but going through an explicit @Provides makes it easy to
 * swap in a fake for tests.
 */
@Module
@InstallIn(SingletonComponent::class)
object MetadataModule {
    @Provides
    @Singleton
    fun provideFileMetadataRepository(
        dao: com.elysium.vanguard.core.database.FileMetadataDao
    ): FileMetadataRepository = FileMetadataRepository(dao)
}