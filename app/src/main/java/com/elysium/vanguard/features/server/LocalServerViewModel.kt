package com.elysium.vanguard.features.server

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.server.LocalServerOrchestrator
import com.elysium.vanguard.core.server.qr.QrCodeRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PHASE 2.3 — Drives the local server UI.
 *
 * Responsibilities:
 *   - Toggle the server on/off
 *   - Build the landing URL the user scans
 *   - Render the QR Bitmap (off the main thread; ZXing is fast but we shouldn't
 *     block Compose layout for it)
 *   - Surface server stats (port, in-flight requests, total requests) to the screen
 */
@HiltViewModel
class LocalServerViewModel @Inject constructor(
    private val orchestrator: LocalServerOrchestrator,
    @Suppress("UNUSED_PARAMETER") savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    val state: StateFlow<LocalServerOrchestrator.State> = orchestrator.state
    val stats: StateFlow<LocalServerOrchestrator.Stats> = orchestrator.stats
    val lastError: StateFlow<String?> = orchestrator.lastError

    val url: String?
        get() = orchestrator.landingUrl()

    val authToken: String
        get() = orchestrator.authTokenString

    /** Combined UI state — single subscription for the screen. */
    val uiState: StateFlow<UiState> = combine(state, stats, _qrBitmap) { s, st, qr ->
        UiState(
            state = s,
            stats = st,
            url = orchestrator.landingUrl(),
            qr = qr,
            safTree = orchestrator.describeSafTree(),
            root = orchestrator.transfer.let {
                // The fsRoot lives in a supplier; we don't have direct access from here.
                // The screen can read orchestrator's status() if it needs the path.
                ""
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    data class UiState(
        val state: LocalServerOrchestrator.State = LocalServerOrchestrator.State.STOPPED,
        val stats: LocalServerOrchestrator.Stats = LocalServerOrchestrator.Stats(),
        val url: String? = null,
        val qr: Bitmap? = null,
        val safTree: String? = null,
        val root: String = ""
    )

    fun start() {
        if (orchestrator.start()) {
            regenerateQr()
        }
    }

    fun stop() {
        orchestrator.stop()
        viewModelScope.launch { _qrBitmap.value = null }
    }

    fun toggle() {
        if (orchestrator.state.value == LocalServerOrchestrator.State.RUNNING) {
            stop()
        } else {
            start()
        }
    }

    /** Recompute the QR bitmap for the current URL. No-op when server is stopped. */
    fun regenerateQr() {
        val url = orchestrator.landingUrl() ?: run {
            _qrBitmap.value = null
            return
        }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                QrCodeRenderer.renderBitmap(content = url, sizePx = 480)
            }
            _qrBitmap.value = bitmap
        }
    }
}