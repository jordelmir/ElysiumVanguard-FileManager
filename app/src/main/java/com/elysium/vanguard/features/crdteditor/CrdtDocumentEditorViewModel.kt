package com.elysium.vanguard.features.crdteditor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.crdt.EditorSyncHost
import com.elysium.vanguard.core.crdt.NodeIdStore
import com.elysium.vanguard.core.server.LocalServerOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * PHASE 9.14 — Hilt-shell ViewModel that drives the CRDT-backed
 * document editor.
 *
 * Loads the file referenced by the nav arg `{path}` on init and
 * wraps it in a [CrdtDocumentEditorEngine]. All content-edit
 * intent routing runs through the engine so the pure logic is
 * testable without Hilt / Compose / lifecycle scaffolding; this
 * ViewModel just adapts engine APIs to viewModelScope.
 *
 * The screen observes [state] as a single [StateFlow] and
 * dispatches [EditorIntent]s through [dispatch]. Save and Sync
 * are async and launched on the IO dispatcher.
 *
 * Phase 10.1 — Hilt-wires the editor to the running local
 * server: the persistent [NodeIdStore] keeps the node id
 * stable across process restarts, and the [EditorSyncHost]
 * resolves a per-file transport against the
 * [LocalServerOrchestrator] at sync time. We also
 * idempotently auto-start the orchestrator on init so the
 * "Sync" button has a peer the moment the editor opens.
 *
 * Phase 9.14 — first build; intentionally minimal.
 */
@HiltViewModel
class CrdtDocumentEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val nodeIdStore: NodeIdStore,
    private val orchestrator: LocalServerOrchestrator,
    private val editorSyncHost: EditorSyncHost
) : ViewModel() {

    /**
     * The file path this editor was opened for. URL-decoded
     * because Compose Navigation keeps path arguments URL-
     * encoded in SavedStateHandle.
     */
    val filePath: String
        get() = URLDecoder.decode(
            savedStateHandle["path"] ?: "",
            StandardCharsets.UTF_8.toString()
        )

    /**
     * Stable per-process node id, persisted across launches via
     * [NodeIdStore]. The first read mints a fresh UUID and
     * caches it; subsequent reads return the same value.
     */
    val nodeId: String by lazy { nodeIdStore.getOrCreate() }

    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /**
     * The loaded engine. Null until [load] finishes.
     */
    private var engine: CrdtDocumentEditorEngine? = null

    init {
        // Idempotently start the local server so the editor's
        // "Sync" button has a peer. start() returns true on
        // success and false on bind failure; we don't surface
        // the failure here because the engine will fall back to
        // SyncNoPeer if the server isn't running.
        viewModelScope.launch(Dispatchers.IO) { orchestrator.start() }
        viewModelScope.launch { load() }
    }

    /**
     * Open the file from [filePath] and bind it to a fresh
     * [CrdtDocumentEditorEngine]. The state flow goes
     * `Empty → Ready` (or stays `Empty` if open failed).
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        val path = filePath
        if (path.isBlank()) {
            _state.value = EditorState.Empty
            return@withContext
        }
        val file = File(path)
        if (!file.isFile) {
            _state.value = EditorState.Empty
            return@withContext
        }
        try {
            val eng = CrdtDocumentEditorEngine.forFile(file, nodeId, syncAdapter = null)
            engine = eng
            _state.value = eng.state.value
            // Mirror subsequent engine state into our flow so
            // the Compose screen can observe both.
            viewModelScope.launch {
                eng.state.collect { s -> _state.value = s }
            }
        } catch (t: Throwable) {
            _state.value = EditorState.Empty
        }
    }

    /**
     * Dispatch a content-edit intent. Mutating intents are
     * applied to the underlying CRDT synchronously and the
     * state re-emits on the next tick.
     */
    fun dispatch(intent: EditorIntent) {
        engine?.dispatchSync(intent)
    }

    /**
     * Persist the session to disk on the IO dispatcher.
     */
    fun save() {
        val e = engine ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { e.saveSync() }
        }
    }

    /**
     * Run a sync round against the running local server.
     *
     * If the orchestrator is RUNNING and the document lives
     * inside the server's sandbox, the engine receives a
     * real [CrdtDocumentEditorEngine.SyncHost] and absorbs
     * whatever remote ops the server has. Otherwise the engine
     * reports `EditorResult.SyncNoPeer` and absorbs 0.
     */
    fun sync() {
        val e = engine ?: return
        // Resolve the adapter fresh on every call so a process
        // that started the server *after* opening the editor
        // still finds a peer on the first manual tap.
        val file = File(filePath)
        val host = editorSyncHost.adapterFor(file)
        if (host != null) e.setSyncHost(host)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { e.syncSync() }
        }
    }
}
