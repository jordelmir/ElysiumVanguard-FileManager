package com.elysium.vanguard.core.recent

import com.elysium.vanguard.core.database.RecentFileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RecentFileModule {
    @Provides
    @Singleton
    fun provideRecentFileRepository(
        dao: RecentFileDao,
    ): RecentFileRepository = RecentFileRepository(dao)
}
