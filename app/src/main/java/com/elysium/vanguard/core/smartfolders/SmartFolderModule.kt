package com.elysium.vanguard.core.smartfolders

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SmartFolderModule {
    @Provides
    @Singleton
    fun provideSmartFolderRepository(
        dao: com.elysium.vanguard.core.database.SmartFolderDao,
        parser: com.elysium.vanguard.core.search.FileFilterParser
    ): SmartFolderRepository = SmartFolderRepository(dao, parser)
}