package com.elysium.vanguard.core.media

import com.elysium.vanguard.core.database.media.MediaIndexDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 93 — the **Media Index Module**, the
 * Hilt module that wires the media indexer
 * pipeline.
 *
 * The module provides:
 *   - The default [MediaIndexer] (the
 *     `DefaultMediaIndexer`, the
 *     production impl that uses the DAO).
 *
 * The module is **@InstallIn(SingletonComponent)**:
 * the indexer is **process-scoped** (one
 * instance for the app's lifetime).
 *
 * The module deliberately does **NOT**
 * provide the [MediaStoreObserver] (the
 * observer is a `@Singleton` already via
 * its `@Inject constructor`) or the
 * [MediaSourceProvider] (the provider is a
 * `@Singleton` already via its `@Inject
 * constructor`; Hilt constructs it from the
 * application [android.content.Context]
 * automatically).
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaIndexModule {

    @Provides
    @Singleton
    fun provideMediaIndexer(
        dao: MediaIndexDao,
    ): MediaIndexer = DefaultMediaIndexer(dao = dao)
}
