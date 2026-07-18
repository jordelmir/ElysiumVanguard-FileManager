package com.elysium.vanguard.core.runtime.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

class AudioBridgeImpl(
    private val appContext: Context,
    /**
     * Reserved for the future PulseAudio Unix-socket path. The current
     * implementation opens a loopback TCP ServerSocket on an ephemeral
     * port; this parameter is ignored until the native PulseAudio
     * protocol lands. See [AudioBridge] for the limitations.
     */
    @Suppress("unused") private val pulseSocketDir: File = DEFAULT_SOCKET_DIR
) : AudioBridge, Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<AudioBridgeState>(AudioBridgeState.Idle)
    override val state: StateFlow<AudioBridgeState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    @Volatile
    private var isMuted = false

    private var config = AudioConfig()
    private var serverSocket: ServerSocket? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var pulsePort: Int = 0

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioTrack?.let { t ->
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    @Suppress("DEPRECATION")
                    t.setVolume(if (isMuted) 0.0f else _volume.value)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioTrack?.let { t ->
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                @Suppress("DEPRECATION")
                audioTrack?.setVolume(0.3f)
            }
        }
    }

    override suspend fun initialize(config: AudioConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (_state.value !is AudioBridgeState.Idle) {
            return@withContext Result.failure(
                IllegalStateException("AudioBridge is already initialized or in progress")
            )
        }

        _state.value = AudioBridgeState.Initializing
        this@AudioBridgeImpl.config = config

        try {
            audioTrack = createAudioTrack(config).also { it.play() }
            pulsePort = startPulseServer()
            audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            requestAudioFocus()

            _state.value = AudioBridgeState.Active
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = AudioBridgeState.Failed(e.message ?: "Initialization failed")
            releaseResources()
            Result.failure(e)
        }
    }

    override suspend fun setVolume(level: Float): Result<Unit> = withContext(Dispatchers.IO) {
        if (level < 0.0f || level > 1.0f) {
            return@withContext Result.failure(
                IllegalArgumentException("Volume must be between 0.0 and 1.0")
            )
        }
        _volume.value = level
        if (!isMuted) {
            @Suppress("DEPRECATION")
            audioTrack?.setVolume(level)
        }
        Result.success(Unit)
    }

    override suspend fun setMuted(muted: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        isMuted = muted
        @Suppress("DEPRECATION")
        audioTrack?.setVolume(if (muted) 0.0f else _volume.value)
        Result.success(Unit)
    }

    override suspend fun requestFocus(): Result<AudioFocusResult> = withContext(Dispatchers.IO) {
        val manager = audioManager
        if (manager == null) {
            return@withContext Result.failure(
                IllegalStateException("AudioBridge has not been initialized")
            )
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes())
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = request
        val result = manager.requestAudioFocus(request)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Result.success(
            AudioFocusResult(
                granted = granted,
                reason = if (granted) "Audio focus granted" else "Audio focus request denied"
            )
        )
    }

    override suspend fun releaseFocus(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun pulseSocketPath(): String {
        return if (pulsePort > 0) "tcp:127.0.0.1:$pulsePort" else ""
    }

    override fun environmentVariables(): Map<String, String> {
        return if (pulsePort > 0) {
            mapOf(
                // Advisory only: this is a raw-PCM loopback, not a real
                // PulseAudio server. A real PulseAudio client will not be
                // able to complete its handshake. The Linux side must be
                // configured to send raw frames at sampleRate/channels
                // matching [config] until the native protocol lands.
                "ELYSIUM_AUDIO_LOOPBACK" to "tcp:127.0.0.1:$pulsePort",
                "PULSE_SERVER" to "tcp:127.0.0.1:$pulsePort",
                "PULSE_SINK" to "elysium-vanguard-output",
                "PULSE_SOURCE" to "elysium-vanguard-input",
                "PULSE_LATENCY_MSEC" to "${config.bufferSizeMs}"
            )
        } else {
            emptyMap()
        }
    }

    override suspend fun shutdown(): Result<Unit> = withContext(Dispatchers.IO) {
        if (_state.value is AudioBridgeState.Shutdown) {
            return@withContext Result.success(Unit)
        }
        try {
            releaseResources()
            scope.cancel()
            _state.value = AudioBridgeState.Shutdown
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun close() {
        if (_state.value is AudioBridgeState.Shutdown) return
        releaseResources()
        scope.cancel()
        _state.value = AudioBridgeState.Shutdown
    }

    private fun createAudioTrack(config: AudioConfig): AudioTrack {
        val channelMask = when (config.channels) {
            1 -> AndroidAudioFormat.CHANNEL_OUT_MONO
            2 -> AndroidAudioFormat.CHANNEL_OUT_STEREO
            4 -> AndroidAudioFormat.CHANNEL_OUT_QUAD
            6 -> AndroidAudioFormat.CHANNEL_OUT_5POINT1
            8 -> AndroidAudioFormat.CHANNEL_OUT_7POINT1
            else -> AndroidAudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = when (config.format) {
            AudioFormat.S16LE -> AndroidAudioFormat.ENCODING_PCM_16BIT
            AudioFormat.F32LE -> AndroidAudioFormat.ENCODING_PCM_FLOAT
            AudioFormat.S24LE -> AndroidAudioFormat.ENCODING_PCM_24BIT_PACKED
        }
        val bytesPerSample = when (config.format) {
            AudioFormat.S16LE -> 2
            AudioFormat.F32LE -> 4
            AudioFormat.S24LE -> 3
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            config.sampleRate, channelMask, encoding
        )
        val desiredLatencyBytes = config.sampleRate *
            config.channels *
            bytesPerSample *
            config.bufferSizeMs / 1000
        val bufferSize = maxOf(minBufferSize, desiredLatencyBytes)

        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes())
            .setAudioFormat(
                AndroidAudioFormat.Builder()
                    .setSampleRate(config.sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun startPulseServer(): Int {
        val socket = ServerSocket(0)
        socket.reuseAddress = true
        serverSocket = socket
        val port = socket.localPort

        scope.launch {
            try {
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    scope.launch {
                        handlePulseClient(client)
                    }
                }
            } catch (_: IOException) {
                // Server socket closed during shutdown
            }
        }

        return port
    }

    private fun handlePulseClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val buffer = ByteArray(4096)
            val track = audioTrack

            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                track?.write(buffer, 0, bytesRead)
            }
        } catch (_: IOException) {
            // Client disconnected or shutdown in progress
        } finally {
            try {
                client.close()
            } catch (_: IOException) {}
        }
    }

    private fun requestAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes())
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = request
        audioManager?.requestAudioFocus(request)
    }

    private fun releaseResources() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null

        try {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        audioFocusRequest = null

        pulsePort = 0
    }

    private fun audioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    companion object {
        private val DEFAULT_SOCKET_DIR = File("/tmp/pulse")
    }
}
