package com.elysium.vanguard.core.runtime.distros.gui.x11

import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbFrame
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbPasswordProvider
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbViewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Phase 4 — X11 display server bridge.
 *
 * Bridges the X11/Xvnc framebuffer running inside the Linux rootfs to an
 * Android SurfaceView. The service manages the lifecycle of a VNC session
 * and renders frames to a hardware-accelerated Canvas on the Android side.
 *
 * This replaces the previous ad-hoc RfbSurfaceView approach with a
 * structured service that:
 *  - Manages session lifecycle (connect, stream, disconnect)
 *  - Provides frame-level control for smooth rendering
 *  - Handles input events (pointer, keyboard) from the Android side
 *  - Supports geometry changes (resize, rotation)
 */
class X11DisplayService : Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DisplayState>(DisplayState.Idle)
    private val _currentFrame = MutableStateFlow<FrameData?>(null)
    private var session: RfbSession? = null
    private var renderJob: Job? = null

    val state: StateFlow<DisplayState> = _state.asStateFlow()
    val currentFrame: StateFlow<FrameData?> = _currentFrame.asStateFlow()

    /**
     * Connect to a local VNC server running in the rootfs.
     */
    fun connect(
        host: String = "127.0.0.1",
        port: Int = 5901,
        password: CharArray? = null,
        geometry: DisplayGeometry = DisplayGeometry(1280, 720)
    ) {
        if (_state.value !is DisplayState.Idle) return
        _state.value = DisplayState.Connecting

        val passwordProvider = if (password != null && password.isNotEmpty()) {
            RfbPasswordProvider { password.copyOf() }
        } else null

        val config = RfbSession.Config(
            host = host,
            port = port,
            connectTimeoutMs = 8_000,
            passwordProvider = passwordProvider
        )

        val newSession = RfbSession(config = config)
        session = newSession

        newSession.start()

        renderJob = scope.launch {
            var lastWidth = 0
            var lastHeight = 0
            newSession.frames.collect { frame ->
                if (frame != null) {
                    _currentFrame.value = FrameData(
                        width = frame.width,
                        height = frame.height,
                        argb = frame.argb
                    )
                    val currentState = newSession.state.value
                    if (currentState is RfbSession.State.Streaming) {
                        val width = frame.width
                        val height = frame.height
                        if (width != lastWidth || height != lastHeight) {
                            lastWidth = width
                            lastHeight = height
                        }
                        _state.value = DisplayState.Streaming(
                            width = width,
                            height = height,
                            frameCount = currentState.frameCount
                        )
                    }
                }
            }
        }
    }

    /**
     * Disconnect from the VNC server and clean up resources.
     */
    fun disconnect() {
        renderJob?.cancel()
        renderJob = null
        session?.close()
        session = null
        _currentFrame.value = null
        _state.value = DisplayState.Idle
    }

    /**
     * Send a pointer event to the VNC server.
     */
    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        session?.sendPointer(x, y, buttonMask)
    }

    /**
     * Send a keyboard event to the VNC server.
     */
    fun sendKey(keysym: Int, down: Boolean) {
        session?.sendKey(keysym, down)
    }

    /**
     * Map a touch coordinate to framebuffer coordinates using viewport.
     */
    fun mapTouch(
        touchX: Float,
        touchY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        framebufferWidth: Int,
        framebufferHeight: Int
    ): Pair<Int, Int>? {
        val viewport = RfbViewport(
            framebufferWidth = framebufferWidth,
            framebufferHeight = framebufferHeight,
            surfaceWidth = surfaceWidth,
            surfaceHeight = surfaceHeight
        )
        val pointer = viewport.map(touchX, touchY) ?: return null
        return pointer.x to pointer.y
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    sealed class DisplayState {
        data object Idle : DisplayState()
        data object Connecting : DisplayState()
        data class Connected(
            val width: Int,
            val height: Int
        ) : DisplayState()
        data class Streaming(
            val width: Int,
            val height: Int,
            val frameCount: Long
        ) : DisplayState()
        data class Failed(val error: String) : DisplayState()
    }

    data class FrameData(
        val width: Int,
        val height: Int,
        val argb: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FrameData) return false
            return width == other.width && height == other.height && argb.contentEquals(other.argb)
        }
        override fun hashCode(): Int = 31 * (31 * width + height) + argb.contentHashCode()
    }

    data class DisplayGeometry(val width: Int, val height: Int) {
        init {
            require(width in 640..4096) { "display width out of range: $width" }
            require(height in 480..4096) { "display height out of range: $height" }
        }
    }
}
