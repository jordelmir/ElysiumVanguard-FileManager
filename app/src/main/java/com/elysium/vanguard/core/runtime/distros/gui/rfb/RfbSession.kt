package com.elysium.vanguard.core.runtime.distros.gui.rfb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.Closeable
import java.io.IOException

/** Narrow seam used by [RfbSession] and its deterministic lifecycle tests. */
internal interface RfbConnection : Closeable {
    val server: RfbServerInfo
    fun requestFramebufferUpdate(incremental: Boolean)
    fun readFrame(): RfbFrame?
    fun sendPointer(x: Int, y: Int, buttonMask: Int)
    fun sendKey(keysym: Int, down: Boolean)
}

/**
 * Owns one localhost RFB stream independently from any Activity or Surface.
 *
 * The later SurfaceView may detach on rotation without killing a graphical
 * guest. Conversely, [stop] closes the socket first, which releases a blocked
 * frame read before cancelling the coroutine and avoids a dangling VNC client.
 */
internal class RfbSession(
    val config: Config = Config(),
    private val connector: (Config) -> RfbConnection = { spec ->
        RfbClient.connect(
            host = spec.host,
            port = spec.port,
            timeoutMs = spec.connectTimeoutMs,
            passwordProvider = spec.passwordProvider
        )
    }
) : Closeable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    private val _frames = MutableStateFlow<RfbFrame?>(null)
    private val inputQueue = Channel<(RfbConnection) -> Unit>(INPUT_QUEUE_CAPACITY)
    private var connection: RfbConnection? = null
    private var pump: Job? = null
    private var inputPump: Job? = null

    val state: StateFlow<State> = _state.asStateFlow()
    val frames: StateFlow<RfbFrame?> = _frames.asStateFlow()

    /** Opens the local VNC stream exactly once; safe against repeated UI taps. */
    fun start() = synchronized(lock) {
        if (_state.value != State.Idle) return
        _state.value = State.Connecting
        pump = scope.launch {
            try {
                val active = connectWhileServerStarts()
                val accepted = synchronized(lock) {
                    if (_state.value is State.Stopped) false else {
                        connection = active
                        true
                    }
                }
                if (!accepted) {
                    active.closeQuietly()
                    return@launch
                }
                _state.value = State.Connected(active.server.copy())
                inputPump = scope.launch { pumpInput(active) }
                pumpFrames(active)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                publishFailure(error)
            } catch (error: IllegalArgumentException) {
                publishFailure(error)
            } finally {
                synchronized(lock) {
                    connection?.closeQuietly()
                    connection = null
                    inputPump?.cancel()
                    inputPump = null
                }
            }
        }
    }

    /** Pointer writes never run on the caller's thread. */
    fun sendPointer(x: Int, y: Int, buttonMask: Int) = sendInput { it.sendPointer(x, y, buttonMask) }

    /** Keyboard writes never run on the caller's thread. */
    fun sendKey(keysym: Int, down: Boolean) = sendInput { it.sendKey(keysym, down) }

    /** Explicit terminal condition: close I/O, cancel work, release references. */
    fun stop() {
        val active = synchronized(lock) {
            if (_state.value == State.Stopped) return
            _state.value = State.Stopped
            connection.also { connection = null }
        }
        active.closeQuietly()
        inputQueue.close()
        scope.cancel()
    }

    override fun close() = stop()

    private fun pumpFrames(active: RfbConnection) {
        var receivedFrame = false
        var frameCount = 0L
        while (scope.isActive && _state.value !is State.Stopped) {
            active.requestFramebufferUpdate(incremental = receivedFrame)
            val frame = active.readFrame() ?: continue
            receivedFrame = true
            frameCount += 1
            _frames.value = frame
            _state.value = State.Streaming(
                server = active.server.copy(),
                frameCount = frameCount,
                lastFrameAtMs = System.currentTimeMillis()
            )
        }
    }

    private suspend fun connectWhileServerStarts(): RfbConnection {
        val deadline = System.currentTimeMillis() + config.connectTimeoutMs
        var lastFailure: IOException? = null
        while (scope.isActive) {
            try {
                return connector(config)
            } catch (error: RfbAuthenticationException) {
                throw error
            } catch (error: IOException) {
                lastFailure = error
                if (System.currentTimeMillis() >= deadline) throw error
                delay(CONNECT_RETRY_DELAY_MS)
            }
        }
        throw lastFailure ?: IOException("RFB session stopped before connection")
    }

    private fun sendInput(operation: (RfbConnection) -> Unit) {
        if (_state.value !is State.Connected && _state.value !is State.Streaming) return
        inputQueue.trySend(operation)
    }

    private suspend fun pumpInput(active: RfbConnection) {
        for (operation in inputQueue) {
            try {
                operation(active)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                publishFailure(error)
            } catch (error: IllegalArgumentException) {
                publishFailure(error)
            }
        }
    }

    private fun publishFailure(error: Exception) {
        if (_state.value !is State.Stopped) {
            _state.value = State.Failed(error.message?.take(MAX_ERROR_CHARS) ?: "RFB session failed")
        }
    }

    private fun RfbConnection?.closeQuietly() {
        try {
            this?.close()
        } catch (_: IOException) {
            // Close is best-effort after state has become terminal.
        }
    }

    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = DEFAULT_PORT,
        val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        val passwordProvider: RfbPasswordProvider? = null
    )

    sealed class State {
        data object Idle : State()
        data object Connecting : State()
        data class Connected(val server: RfbServerInfo) : State()
        data class Streaming(val server: RfbServerInfo, val frameCount: Long, val lastFrameAtMs: Long) : State()
        data class Failed(val detail: String) : State()
        data object Stopped : State()
    }

    private companion object {
        const val DEFAULT_PORT = 5901
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val CONNECT_RETRY_DELAY_MS = 100L
        const val MAX_ERROR_CHARS = 240
        const val INPUT_QUEUE_CAPACITY = 128
    }
}
