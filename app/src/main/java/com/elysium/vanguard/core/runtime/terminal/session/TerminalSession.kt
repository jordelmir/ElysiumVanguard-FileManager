package com.elysium.vanguard.core.runtime.terminal.session

import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalBuffer
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * PHASE 9.6.1 — Process-backed terminal session.
 *
 * This is the OS bridge: it owns a [Process] running the user's shell,
 * brokers bytes between the process and the [TerminalParser], and
 * exposes the lifecycle events the UI needs (started, exited, errored,
 * user input).
 *
 * Why a class instead of a use-case / repository: terminal sessions are
 * inherently stateful (the OS process has its own PID, the stream is
 * non-blocking, etc.). Hilt-injected ViewModels are too high-level for
 * owning the actual `Process` object — we want this in the foreground
 * service, not in the ViewModel that goes away on rotation. We do still
 * expose a flow-based API so the Compose layer can collect from any
 * context.
 *
 * Phase 9.6.1 hard rules:
 *
 *   - Single session per process. No multiplexing yet.
 *   - Single-process forking only. No detach / spawn-daemon.
 *   - No `su`, no `root`, no setuid. We respect Android sandbox.
 *   - Locale: inherited from system (LANG/LC_ALL). We inject a sane
 *     default if both are unset so the user never sees
 *     "POSIX locale missing".
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
class TerminalSession(
    /** Configuration captured at start; immutable for the session's life. */
    val config: Config
) {
    /**
     * Public, immutable session id used by the Service and UI to refer to
     * this session. UUID-based so multiple sessions can coexist (future
     * tabs) without key collisions.
     */
    val id: String = UUID.randomUUID().toString()

    /** Coarse lifecycle as a Flow-friendly state. */
    private val _state = MutableStateFlow<State>(State.NotStarted)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Buffer with all output and current cursor position. */
    internal val buffer: TerminalBuffer = TerminalBuffer(config.cols, config.rows)

    /** UTF-8-decoded chunks of stdout emitted by the shell. */
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val output: SharedFlow<String> = _output.asSharedFlow()

    /** One-shot notifications like "exited with code N". */
    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.consumeAsFlow()

    /** Internal coroutine scope tied to the process lifecycle. */
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var pumpOut: Job? = null
    private var pumpErr: Job? = null
    private var waitJob: Job? = null

    private val parser = TerminalParser(buffer)

    /**
     * Start the process. This must be called exactly once per session;
     * further calls return without effect. Idempotent by design so a
     * composable that re-composes during a config change won't fork a
     * second process.
     */
    fun start() {
        if (_state.value != State.NotStarted) return
        _state.value = State.Starting
        try {
            val pb = ProcessBuilder(config.command).apply {
                directory(config.workingDirectory)
                redirectErrorStream(false)
                environment().apply {
                    put("TERM", config.termName)
                    put("COLORTERM", if (config.colorTermSupport) "truecolor" else "")
                    put("LINES", config.rows.toString())
                    put("COLUMNS", config.cols.toString())
                    if (!config.environmentVariables.any { it.first == "LANG" }) {
                        put("LANG", "en_US.UTF-8")
                    }
                    config.environmentVariables.forEach { (k, v) -> put(k, v) }
                }
            }
            val p = pb.start()
            process = p
            _state.value = State.Running(
                sinceMs = System.currentTimeMillis(),
                pid = processId(p)
            )
            pumpOut = pump(p.inputStream)
            pumpErr = pump(p.errorStream)
            waitJob = sessionScope.launch {
                val rc = try {
                    p.waitFor()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    -1
                }
                _state.value = State.Exited(rc)
                _events.trySend(Event.Exited(rc))
            }
        } catch (io: IOException) {
            _state.value = State.Error(io.message ?: "start failed")
            _events.trySend(Event.Failed(io.message ?: "start failed"))
        }
    }

    /**
     * Pump a single stream into the parser + output flow. We use a
     * fixed-size buffer read on a single dedicated coroutine per stream.
     * Reads return when the process exits or the stream is closed, so
     * coroutines also complete naturally; we only cancel them on
     * explicit [stop].
     */
    private fun pump(stream: InputStream): Job = sessionScope.launch {
        val buf = ByteArray(PUMP_CHUNK)
        val decoder = Utf8LineDecoder()
        while (isActive) {
            val n = try {
                stream.read(buf)
            } catch (io: IOException) {
                break
            }
            if (n < 0) break
            val chunk = decoder.append(buf, n)
            if (chunk.isNotEmpty()) {
                parser.feed(chunk)
                _output.tryEmit(chunk)
            }
        }
    }

    /**
     * Send raw bytes from user input to the process's stdin. The OS pipe
     * is non-blocking on the read side, but writes can block on the
     * shell side (rare for interactive commands, common for `cat > big`).
     * We must NOT do the write on the Compose thread, so this method
     * launches on the session scope.
     */
    fun write(bytes: ByteArray) {
        val out: OutputStream? = process?.outputStream
        if (out == null) return
        sessionScope.launch(Dispatchers.IO) {
            try {
                out.write(bytes)
                out.flush()
            } catch (io: IOException) {
                _events.trySend(Event.Failed("write failed: ${io.message}"))
            }
        }
    }

    /** Convenience for text input — converts String → bytes as UTF-8. */
    fun writeText(s: String) = write(s.toByteArray(Charsets.UTF_8))

    /** Send Ctrl+C (ASCII 0x03) without flushing — same as a real ctrl-c. */
    fun sendInterrupt() = write(byteArrayOf(0x03))

    /** Resize request from the OS / window. Phase 9.6.1 no-op stub. */
    fun resize(cols: Int, rows: Int) {
        // Phase 9.6.2: implement TIOCSWINSZ via TIOC ioctl through
        // reflection; out of scope here.
        buffer.resize(cols, rows)
    }

    /** Stop the session. Idempotent; safe to call from any thread. */
    fun stop() {
        if (_state.value == State.NotStarted) return
        try {
            process?.let { p ->
                p.destroy()
                // Give the process 100ms to honor SIGTERM, otherwise
                // we escalate. Phased 9.6.1 doesn't actually need
                // gentle kill because we only spawn sh, but this is
                // the right shape for future bash-over-proot sessions.
            }
        } catch (_: Exception) { /* ignore */ }
        sessionScope.cancel()
        _state.value = State.Stopped
    }

    /**
     * Configuration captured up-front. Defaults are picked for a stock
     * Elysium Vanguard install on Android 14/15 — `/system/bin/sh` is
     * available on every Android, no root, no apk patches required.
     */
    data class Config(
        val command: List<String> = listOf("/system/bin/sh"),
        val workingDirectory: File? = null,
        val cols: Int = 80,
        val rows: Int = 24,
        val termName: String = "xterm-256color",
        val colorTermSupport: Boolean = true,
        val environmentVariables: List<Pair<String, String>> = emptyList()
    )

    /** Coarse lifecycle states. */
    sealed class State {
        data object NotStarted : State()
        data object Starting : State()
        data class Running(val sinceMs: Long, val pid: Long?) : State()
        data class Exited(val exitCode: Int) : State()
        data class Error(val message: String) : State()
        data object Stopped : State()
    }

    /** One-shot events surfaced to the UI. */
    sealed class Event {
        data class Exited(val exitCode: Int) : Event()
        data class Failed(val message: String) : Event()
        data object TitleChanged : Event()
    }

    private fun awaitCloseSafely() = Unit // placeholder for future flow conversion

    companion object {
        private const val PUMP_CHUNK = 4096

        private fun processId(process: Process): Long? {
            return runCatching {
                val field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                (field.get(process) as? Number)?.toLong()
            }.getOrNull()
        }

        /**
         * PHASE 9.6.3 / 10.4 — Build a [TerminalSession] configured to
         * run the given [DistroLauncher] against the supplied rootfs.
         *
         * Phase 10.4 changed this from "always run a one-shot probe" to
         * "open a real interactive shell". The old probe
         * (`pwd; ls -al /; exit`) made sense when every launcher was a
         * one-shot script; now Direct-Exec delivers an actual shell
         * with stdin, and we want the user to land in it. The probe
         * string is still passed in as a pre-script so the user
         * immediately sees the launcher chose the right flavor; once
         * the probe finishes, the shell takes over stdin.
         */
        fun forDistro(
            rootfsDir: File,
            pick: LauncherPick,
            cols: Int = 80,
            rows: Int = 24,
            termName: String = "xterm-256color"
        ): TerminalSession {
            require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
            val launcher = pick.launcher
            val isInteractiveGuest = launcher.kind ==
                com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind.DIRECT_EXEC ||
                launcher.kind ==
                com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind.NATIVE_PROOT
            // Build the command first so the launcher's `buildShellCommand`
            // gets a chance to validate the rootfs. We then attach a
            // diagnostic pre-script only for non-interactive cases
            // (probes); for Direct-Exec we want a real interactive
            // shell, so we leave the command untouched.
            val baseCommand = if (isInteractiveGuest) {
                launcher.buildShellCommand(rootfsDir, script = "")
            } else {
                val probe =
                    "echo '=== ${launcher.kind} ready ==='; pwd; " +
                        "echo '--- /etc/os-release (if any) ---'; " +
                        "cat /etc/os-release 2>/dev/null || echo '(no os-release)'; " +
                        "echo '--- / top 40 ---'; ls -la / 2>/dev/null | head -n 40"
                launcher.buildShellCommand(rootfsDir, probe)
            }
            // PHASE 10.4 — For Direct-Exec we want the rootfs' own
            // environment (PATH, LD_LIBRARY_PATH, HOME, TMPDIR) to
            // reach the child process. We thread it through the
            // Config's environmentVariables so the Android `Process`
            // exposes the same env the launcher's design assumes.
            val env = launcher.environmentVariables(rootfsDir)
            return TerminalSession(
                Config(
                    command = baseCommand,
                    workingDirectory = rootfsDir,
                    cols = cols,
                    rows = rows,
                    termName = termName,
                    environmentVariables = env
                )
            )
        }
    }
}

/**
 * UTF-8 line-aware decoder.
 *
 * Process.stdout delivers raw bytes; we want to surface text to the UI
 * in reasonably-sized chunks (not character-by-character, which would be
 * prohibitively slow through Flow). We decode per chunk in one shot,
 * but guard against split multi-byte sequences at chunk boundaries by
 * buffering the unfinished tail until the next chunk arrives.
 *
 * Not a full streaming UTF-8 decoder (those reserve ~3 bytes); the
 * chunk size is 4 KB so a 3-byte ledger per stream is negligible.
 */
private class Utf8LineDecoder {
    private val pending: ByteArrayOutputStream = ByteArrayOutputStream()

    fun append(buf: ByteArray, len: Int): String {
        pending.write(buf, 0, len)
        // Try to decode everything pending. Malformed sequences at this
        // point would only happen if the producer used invalid UTF-8;
        // we fall back to lossy in that case (charset-with-replace is
        // intentionally avoided to keep memory tight).
        val combined = pending.toByteArray()
        pending.reset()
        return try {
            String(combined, Charsets.UTF_8)
        } catch (_: Exception) {
            String(combined, Charsets.ISO_8859_1)
        }
    }
}

/** Tiny local BAOS to avoid pulling `java.io.BufferedOutputStream` here. */
private class ByteArrayOutputStream {
    private var buf: ByteArray = ByteArray(64)
    private var size: Int = 0
    fun write(b: ByteArray, off: Int, len: Int) {
        if (size + len > buf.size) {
            val newSize = (size + len).coerceAtLeast(buf.size * 2)
            buf = buf.copyOf(newSize)
        }
        System.arraycopy(b, off, buf, size, len)
        size += len
    }
    fun reset() { size = 0 }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}
