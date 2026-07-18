package com.elysium.vanguard.features.runtime.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.gui.x11.X11DisplayService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FASE 4 / section 13 — ViewModel that owns the [X11DisplayService]
 * for a desktop session and exposes the connection lifecycle to the
 * UI as a single [X11DisplayUiState].
 *
 * The VM is process-scoped to the desktop screen: when the user
 * navigates away the screen's DisposableEffect calls [disconnect],
 * which closes the underlying RFB session and cancels the service
 * scope. A subsequent visit creates a fresh service.
 */
@HiltViewModel
class X11DisplayViewModel @Inject constructor() : ViewModel() {

    private val service: X11DisplayService = X11DisplayService()

    private val _ui = MutableStateFlow(
        X11DisplayUiState(
            displayState = service.state.value,
            frame = service.currentFrame.value
        )
    )
    val ui: StateFlow<X11DisplayUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            service.state.collect { state ->
                _ui.value = _ui.value.copy(displayState = state)
            }
        }
        viewModelScope.launch {
            service.currentFrame.collect { frame ->
                _ui.value = _ui.value.copy(frame = frame)
            }
        }
    }

    fun connect(
        host: String = "127.0.0.1",
        port: Int = 5901,
        geometry: X11DisplayService.DisplayGeometry = X11DisplayService.DisplayGeometry(1280, 720)
    ) {
        service.connect(host = host, port = port, geometry = geometry)
    }

    fun disconnect() {
        service.disconnect()
    }

    /**
     * Map a touch coordinate to a framebuffer pointer coordinate.
     * Returns null if the service has not produced a frame yet.
     */
    fun mapTouch(
        touchX: Float,
        touchY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int
 ): Pair<Int, Int>? {
        val currentFrame = _ui.value.frame ?: return null
        return service.mapTouch(
            touchX = touchX,
            touchY = touchY,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight,
            framebufferWidth = currentFrame.width,
            framebufferHeight = currentFrame.height
        )
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        service.sendPointer(x, y, buttonMask)
    }

    fun sendKey(keysym: Int, down: Boolean) {
        service.sendKey(keysym, down)
    }

    override fun onCleared() {
        service.close()
        super.onCleared()
    }
}

data class X11DisplayUiState(
    val displayState: X11DisplayService.DisplayState,
    val frame: X11DisplayService.FrameData?
) {
    val isStreaming: Boolean
        get() = displayState is X11DisplayService.DisplayState.Streaming
    val isFailed: Boolean
        get() = displayState is X11DisplayService.DisplayState.Failed
    val failureMessage: String?
        get() = (displayState as? X11DisplayService.DisplayState.Failed)?.error
}
