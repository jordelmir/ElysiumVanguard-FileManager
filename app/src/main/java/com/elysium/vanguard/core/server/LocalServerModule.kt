package com.elysium.vanguard.core.server

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * PHASE 2.3 — DI bindings for the local server stack.
 *
 * The orchestrator is application-scoped because the server socket has to outlive any
 * single screen. It uses the SAF tree URI when the user has granted one (preserved
 * across config changes via the orchestrator's internal state) and falls back to the
 * app's external files dir otherwise.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalServerModule {

    @Provides
    @Singleton
    fun provideOrchestrator(
        @ApplicationContext context: Context
    ): LocalServerOrchestrator {
        return LocalServerOrchestrator(
            context = context,
            fsRootSupplier = {
                // Prefer the primary external storage root when accessible; fall back to
                // the app-scoped external dir which is always available.
                @Suppress("DEPRECATION")
                val ext = context.getExternalFilesDir(null)
                ext ?: context.filesDir
            }
        )
    }
}