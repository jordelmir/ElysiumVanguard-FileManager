package com.elysium.vanguard.core.palette

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 10.8 — Hilt module for the palette subsystem.
 *
 * The [PaletteManager] is `@Inject` annotated, so it gets created
 * automatically once we provide a [PaletteStore] binding. The
 * store needs the [Context] to obtain SharedPreferences, so we
 * wire that here at the singleton scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object PaletteModule {

    @Provides
    @Singleton
    fun providePaletteStore(@ApplicationContext context: Context): PaletteStore {
        return PaletteStore.fromContext(context)
    }
}
