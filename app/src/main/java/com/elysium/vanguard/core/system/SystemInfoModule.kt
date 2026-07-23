package com.elysium.vanguard.core.system

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 114 — the Hilt module that wires
 * the production [SystemInfoProvider]
 * implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
object SystemInfoModule {

    @Provides
    @Singleton
    fun provideSystemInfoProvider(): SystemInfoProvider =
        AndroidSystemInfoProvider()
}
