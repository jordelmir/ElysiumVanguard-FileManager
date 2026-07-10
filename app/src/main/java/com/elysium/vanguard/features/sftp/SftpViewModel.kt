package com.elysium.vanguard.features.sftp

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.server.qr.QrCodeRenderer
import com.elysium.vanguard.core.sftp.SftpConfig
import com.elysium.vanguard.core.sftp.SftpOrchestrator
import com.elysium.vanguard.core.sftp.SftpServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * PHASE 2.4 — Drives the SFTP server UI.
 *
 * Same UX pattern as the HTTP server:
 *   - Tap "Start" → server starts, password rotates, QR renders
 *   - User scans QR from any sftp client (FileZilla, terminal, etc.)
 *   - Tap "Stop" → server shuts down
 *
 * The QR encodes `sftp://user:password@ip:port/` so the client can pre-fill
 * credentials. (Note: sftp:// URIs that include credentials are non-standard
 * — most clients ignore the password part. We still encode it for the few
 * that do; the password is always shown as a copyable field too.)
 */
@HiltViewModel
class SftpViewModel @Inject constructor(
    private val orchestrator: SftpOrchestrator
) : ViewModel() {

    private val _qrBitmap = MutableStateFlow<Bitmap?>(null)
    val qrBitmap: StateFlow<Bitmap?> = _qrBitmap.asStateFlow()

    val state: StateFlow<SftpServer.Status> = orchestrator.state
    val snapshot: StateFlow<SftpOrchestrator.ServerSnapshot?> = orchestrator.snapshot

    val uiState: StateFlow<UiState> = combine(state, snapshot, _qrBitmap) { s, snap, qr ->
        UiState(
            running = s == SftpServer.Status.RUNNING,
            url = snap?.url ?: "",
            password = snap?.password ?: "",
            username = snap?.username ?: "elysium",
            port = snap?.port ?: 0,
            root = snap?.root ?: "",
            qr = qr
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun start(rootDir: File) {
        val cfg = SftpConfig(
            port = SftpConfig.DEFAULT_PORT,
            root = SftpConfig.RootSpec.Filesystem(rootDir)
        )
        if (orchestrator.start(cfg)) {
            regenerateQr()
        }
    }

    fun stop() {
        orchestrator.stop()
        viewModelScope.launch { _qrBitmap.value = null }
    }

    fun toggle(rootDir: File) {
        if (state.value == SftpServer.Status.RUNNING) stop() else start(rootDir)
    }

    fun regenerateQr() {
        val snap = snapshot.value ?: run { _qrBitmap.value = null; return }
        val text = "${snap.url}\nUser: ${snap.username}\nPassword: ${snap.password}"
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                QrCodeRenderer.renderBitmap(content = text, sizePx = 480)
            }
            _qrBitmap.value = bmp
        }
    }

    data class UiState(
        val running: Boolean = false,
        val url: String = "",
        val username: String = "elysium",
        val password: String = "",
        val port: Int = 0,
        val root: String = "",
        val qr: Bitmap? = null
    )
}