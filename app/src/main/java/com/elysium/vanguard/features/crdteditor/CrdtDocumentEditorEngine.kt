package com.elysium.vanguard.features.crdteditor

import com.elysium.vanguard.core.crdt.CrdtDocumentSession
import com.elysium.vanguard.core.crdt.HybridLogicalClock
import com.elysium.vanguard.core.office.ElysiumDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

/**
 * PHASE 9.14 — Pure-logic engine that drives the CRDT editor.
 *
 * All state-transition logic lives here so we can test it
 * without spinning up a [androidx.lifecycle.ViewModel], Compose,
 * or Hilt. The [CrdtDocumentEditorViewModel] is a thin Hilt shell
 * that constructs (or receives) one of these and forwards intents.
 *
 * The engine:
 *   1. Owns a single [CrdtDocumentSession] (constructed from a
 *      [File] + nodeId).
 *   2. Holds an optional [SyncHost] for transport-bound sync.
 *   3. Exposes [state] as a [StateFlow] of [EditorState] for the
 *      Compose screen to collect.
 *   4. Mirrors every mutation back into [state] by re-reading
 *      the session (we don't try to keep a parallel shadow of
 *      the body — the CRDT is the source of truth).
 *
 * Phase 9.14 — first build; intentionally minimal.
 */
class CrdtDocumentEditorEngine(
    val session: CrdtDocumentSession,
    syncAdapter: SyncHost? = null
) {

    /**
     * Late-bound transport. Mutable so the ViewModel can plug
     * in a real adapter (e.g. the `EditorSyncHost` pointing at
     * the running local server) at sync time even though the
     * engine was constructed without one.
     *
     * The compiler field is private + @Volatile; the public
     * [setSyncHost] and the internal reads in [syncSync] both
     * see consistent values. Single-threaded intent dispatch
     * plus the engine's own coroutine means the volatile read
     * is enough — no need for `@Synchronized`.
     */
    @Volatile
    private var syncAdapter: SyncHost? = syncAdapter

    /**
     * Rebind the [SyncHost] used by the next [syncSync] call.
     * The ViewModel calls this from its `sync()` entry point
     * with the live adapter resolved against the current
     * `LocalServerOrchestrator` state.
     */
    fun setSyncHost(host: SyncHost?) {
        syncAdapter = host
    }

    private val _state = MutableStateFlow<EditorState>(
        EditorState.Ready(
            title = session.doc.metadata.get("title") ?: "",
            author = session.doc.metadata.get("author") ?: "",
            body = session.bodyAsString(),
            isDirty = false,
            lastSavedHlc = session.syncFile.lastSeen,
            nodeId = session.nodeId,
            filePath = session.file.absolutePath,
            lastResult = null
        )
    )
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /**
     * Dispatch an [EditorIntent] to the engine. Mutating intents
     * call into the session synchronously (so the CRDT op log is
     * advanced before we re-snapshot). Save and Sync are async
     * because they touch disk and (potentially) a remote peer;
     * the engine exposes coroutine entry points for them.
     */
    fun dispatchSync(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.SetTitle -> session.setTitle(intent.value)
            is EditorIntent.SetAuthor -> session.setAuthor(intent.value)
            is EditorIntent.AppendChar -> {
                require(intent.ch.length == 1) {
                    "AppendChar expects a single char, got '${intent.ch}'"
                }
                session.insertCharacter(intent.ch)
            }
            is EditorIntent.AppendString -> {
                for (c in intent.text) session.insertCharacter(c.toString())
            }
            is EditorIntent.Backspace -> {
                val n = session.bodyLength()
                if (n == 0) return
                val liveIdx = (intent.liveIndex ?: (n - 1)).coerceIn(0, n - 1)
                session.deleteCharacterAt(liveIdx)
            }
            is EditorIntent.Save -> {
                // Dispatched as sync work in the VM; for direct
                // tests use saveSync() below.
                return
            }
            is EditorIntent.Sync -> {
                // Dispatched as sync work in the VM; for direct
                // tests use syncNow() below.
                return
            }
        }
        refreshFromSession()
    }

    /**
     * Synchronous save: persists the session to disk and refreshes
     * [state]. Used by tests and by the VM's coroutine.
     */
    fun saveSync() {
        session.save()
        _state.update { current ->
            if (current is EditorState.Ready) {
                current.copy(
                    isDirty = false,
                    lastSavedHlc = session.syncFile.lastSeen,
                    lastResult = EditorResult.Saved
                )
            } else current
        }
    }

    /**
     * Synchronous sync round: defers to the configured [syncAdapter]
     * (may be null), refreshes state to reflect any remote ops that
     * landed, and returns the count of ops absorbed.
     */
    fun syncSync(): Int {
        val adapter = syncAdapter
        if (adapter == null) {
            _state.update { current ->
                if (current is EditorState.Ready) {
                    current.copy(lastResult = EditorResult.SyncNoPeer)
                } else current
            }
            return 0
        }
        val absorbed = adapter.syncWith(session)
        refreshFromSession()
        _state.update { current ->
            if (current is EditorState.Ready) {
                current.copy(lastResult = EditorResult.Synced(absorbed))
            } else current
        }
        return absorbed
    }

    private fun refreshFromSession() {
        val title = session.doc.metadata.get("title") ?: ""
        val author = session.doc.metadata.get("author") ?: ""
        val body = session.bodyAsString()
        _state.update { current ->
            if (current is EditorState.Ready) {
                current.copy(
                    title = title,
                    author = author,
                    body = body,
                    isDirty = true,
                    lastSavedHlc = session.syncFile.lastSeen
                )
            } else {
                EditorState.Ready(
                    title = title,
                    author = author,
                    body = body,
                    isDirty = true,
                    lastSavedHlc = session.syncFile.lastSeen,
                    nodeId = session.nodeId,
                    filePath = session.file.absolutePath,
                    lastResult = null
                )
            }
        }
    }

    /**
     * Bridge for any transport to provide a `syncWith(session)`
     * API to the editor. Real impls wrap a [com.elysium.vanguard.core.crdt.CrdtSyncAdapter]
     * and add error handling.
     */
    interface SyncHost {
        fun syncWith(session: CrdtDocumentSession): Int
    }

    companion object {
        /**
         * Open a session from [file] (creating it if empty) and
         * wrap it in a fresh engine. Caller can supply a
         * [syncAdapter]; if null the editor will simply report
         * "no peer" when the user taps Sync.
         */
        fun forFile(
            file: File,
            nodeId: String = "node-${UUID.randomUUID()}",
            syncAdapter: SyncHost? = null
        ): CrdtDocumentEditorEngine {
            val session = if (file.length() == 0L) {
                CrdtDocumentSession.create(file, kindFor(file), nodeId)
            } else {
                CrdtDocumentSession.open(file, nodeId)
            }
            return CrdtDocumentEditorEngine(session, syncAdapter)
        }

        fun kindFor(file: File): ElysiumDocument.Kind = when {
            file.name.endsWith(".elysium.sheet") -> ElysiumDocument.Kind.SHEET
            file.name.endsWith(".elysium.deck") -> ElysiumDocument.Kind.DECK
            else -> ElysiumDocument.Kind.WORD
        }
    }
}

/**
 * View intents: a closed set the UI dispatches to the engine.
 */
sealed interface EditorIntent {
    data class SetTitle(val value: String) : EditorIntent
    data class SetAuthor(val value: String) : EditorIntent
    data class AppendChar(val ch: String) : EditorIntent
    data class AppendString(val text: String) : EditorIntent
    data class Backspace(val liveIndex: Int? = null) : EditorIntent
    data object Save : EditorIntent
    data object Sync : EditorIntent
}

/**
 * One-off UI events surfaced via [EditorState.lastResult].
 */
sealed interface EditorResult {
    data object Saved : EditorResult
    data class Synced(val opsAbsorbed: Int) : EditorResult
    data object SyncNoPeer : EditorResult
}

/**
 * Editor state for the screen.
 */
sealed interface EditorState {
    /**
     * Pre-load placeholder for the screen. We don't try to
     * surface errors as a separate branch — the engine handles
     * malformed-input cases internally and the screen simply
     * renders "no file selected" when state is [Empty].
     */
    data object Empty : EditorState

    data class Ready(
        val title: String,
        val author: String,
        val body: String,
        val isDirty: Boolean,
        val lastSavedHlc: HybridLogicalClock?,
        val nodeId: String,
        val filePath: String,
        val lastResult: EditorResult?
    ) : EditorState
}
