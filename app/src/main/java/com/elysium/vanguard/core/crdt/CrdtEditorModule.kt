package com.elysium.vanguard.core.crdt

import android.content.Context
import com.elysium.vanguard.core.server.LocalServerOrchestrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PHASE 10.1 — Hilt bindings for the CRDT editor stack.
 *
 * Provides:
 *   - [NodeIdStore] — a single, file-backed node id stored in
 *     the app's `filesDir`. Persists across process restarts so
 *     the companion file's `<node>` suffix stays stable.
 *   - [EditorSyncHost] — the bridge between
 *     [com.elysium.vanguard.features.crdteditor.CrdtDocumentEditorEngine]
 *     and the running [LocalServerOrchestrator]. We adapt the
 *     orchestrator to the small [EditorSyncHost.Source]
 *     interface so the host stays pure-JVM / testable.
 *
 * The orchestrator itself is provided by `LocalServerModule`
 * (SingletonComponent) — we just reference it here.
 */
@Module
@InstallIn(SingletonComponent::class)
object CrdtEditorModule {

    @Provides
    @Singleton
    fun provideNodeIdStore(
        @ApplicationContext context: Context
    ): NodeIdStore {
        return NodeIdStore(NodeIdStore.defaultStoreFile(context.filesDir, "main"))
    }

    @Provides
    @Singleton
    fun provideEditorSyncHost(
        orchestrator: LocalServerOrchestrator
    ): EditorSyncHost {
        return EditorSyncHost(
            source = object : EditorSyncHost.Source {
                override fun isRunning(): Boolean =
                    orchestrator.state.value == LocalServerOrchestrator.State.RUNNING
                override fun serviceBaseUrl(): String? = orchestrator.serviceBaseUrl()
                override fun authToken(): String = orchestrator.authTokenString
            },
            fsRoot = { orchestrator.currentFsRoot() }
        )
    }
}
