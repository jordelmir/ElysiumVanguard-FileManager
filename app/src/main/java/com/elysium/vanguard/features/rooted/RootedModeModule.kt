package com.elysium.vanguard.features.rooted

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 102 — Hilt module that binds the production
 * [SharedPreferencesRootedModePrefs] as the
 * [RootedModePrefs] implementation.
 *
 * Keeping the interface seam in [RootedModePrefs]
 * means the [RootedModeViewModel] stays JVM-testable
 * (tests supply a 5-line in-memory impl, production
 * supplies the SharedPreferences-backed one).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RootedModeModule {

    @Binds
    @Singleton
    abstract fun bindRootedModePrefs(
        impl: SharedPreferencesRootedModePrefs
    ): RootedModePrefs
}
