package com.elysium.vanguard.core.runtime.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 8 — Audio bridge interface.
 *
 * Bridges guest audio to Android's AudioTrack/AAudio. The current
 * implementation ([AudioBridgeImpl]) accepts raw PCM bytes over a
 * loopback TCP socket. It does NOT implement the PulseAudio
 * wire protocol — that is a separate, larger piece of work tracked
 * under 'PulseAudio native protocol' in the FASE 9 backlog. Until
 * that lands, the Linux side needs to send S16LE/F32LE/S24LE raw
 * frames at the configured sample rate and channel layout. The
 * environment variables exported by [environmentVariables] are
 * advisory only; do not rely on them to point a real PulseAudio
 * client at this socket.
 *
 * Each session gets its own audio stream with independent volume,
 * device routing, and focus management.
 */
interface AudioBridge {
    val state: StateFlow<AudioBridgeState>
    val volume: StateFlow<Float>

    /**
     * Initialize the audio bridge for a session.
     * Creates a PulseAudio socket in the rootfs and connects to Android audio.
     */
    suspend fun initialize(config: AudioConfig): Result<Unit>

    /**
     * Set output volume (0.0 to 1.0).
     */
    suspend fun setVolume(level: Float): Result<Unit>

    /**
     * Mute/unmute output.
     */
    suspend fun setMuted(muted: Boolean): Result<Unit>

    /**
     * Request audio focus for this session.
     */
    suspend fun requestFocus(): Result<AudioFocusResult>

    /**
     * Release audio focus.
     */
    suspend fun releaseFocus(): Result<Unit>

    /**
     * Get the PulseAudio socket path inside the rootfs.
     */
    fun pulseSocketPath(): String

    /**
     * Get the environment variables to inject into the guest process.
     */
    fun environmentVariables(): Map<String, String>

    /**
     * Shut down the audio bridge and release all resources.
     */
    suspend fun shutdown(): Result<Unit>
}

data class AudioConfig(
    val sampleRate: Int = 44_100,
    val channels: Int = 2,
    val format: AudioFormat = AudioFormat.S16LE,
    val bufferSizeMs: Int = 50,
    val enableInput: Boolean = false,
    val deviceRoute: AudioDeviceRoute = AudioDeviceRoute.DEFAULT
)

enum class AudioFormat {
    S16LE,
    F32LE,
    S24LE
}

enum class AudioDeviceRoute {
    DEFAULT,
    SPEAKER,
    HEADPHETS,
    BLUETOOTH,
    USB
}

sealed class AudioBridgeState {
    data object Idle : AudioBridgeState()
    data object Initializing : AudioBridgeState()
    data object Active : AudioBridgeState()
    data class Failed(val error: String) : AudioBridgeState()
    data object Shutdown : AudioBridgeState()
}

data class AudioFocusResult(
    val granted: Boolean,
    val reason: String
)
