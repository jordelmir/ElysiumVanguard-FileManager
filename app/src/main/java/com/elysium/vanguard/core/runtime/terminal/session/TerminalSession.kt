package com.elysium.vanguard.core.runtime.terminal.session

import com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalBuffer
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalInputModes
import com.elysium.vanguard.core.runtime.terminal.engine.TerminalParser
import com.elysium.vanguard.core.runtime.terminal.input.TerminalInputEncoder
import com.elysium.vanguard.core.runtime.terminal.pty.NativePty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import java.util.UUID

/**
 * Native-PTY-backed terminal session.
 *
 * This is the OS bridge: it owns a native PTY process group running the user's
 * shell, brokers bytes between the PTY and the [TerminalParser], and
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
    private var pty: NativePty? = null
    private var pumpOut: Job? = null
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
            val nativePty = NativePty.spawn(
                command = config.command,
                environment = resolvedEnvironment(),
                workingDirectory = config.workingDirectory,
                columns = config.cols,
                rows = config.rows
            )
            pty = nativePty
            _state.value = State.Running(
                sinceMs = System.currentTimeMillis(),
                pid = nativePty.pid
            )
            pumpOut = pump(nativePty)
            waitJob = sessionScope.launch {
                while (isActive) {
                    val rc = try {
                        nativePty.waitForExit(WAIT_POLL_MS)
                    } catch (io: IOException) {
                        if (_state.value !is State.Stopped) {
                            _state.value = State.Error(io.message ?: "native wait failed")
                            _events.trySend(Event.Failed(io.message ?: "native wait failed"))
                        }
                        return@launch
                    }
                    if (rc == null) continue
                    if (_state.value !is State.Stopped) {
                        _state.value = State.Exited(rc)
                        _events.trySend(Event.Exited(rc))
                    }
                    return@launch
                }
            }
        } catch (io: IOException) {
            val message = io.message ?: "native PTY start failed"
            _state.value = State.Error(message)
            _events.trySend(Event.Failed(message))
        }
    }

    /**
     * Pump a single stream into the parser + output flow. We use a
     * fixed-size buffer read on a single dedicated coroutine per stream.
     * Reads return when the process exits or the stream is closed, so
     * coroutines also complete naturally; we only cancel them on
     * explicit [stop].
     */
    private fun pump(nativePty: NativePty): Job = sessionScope.launch {
        val buf = ByteArray(PUMP_CHUNK)
        while (isActive) {
            val n = try {
                nativePty.read(buf)
            } catch (io: IOException) {
                if (_state.value !is State.Stopped && _state.value !is State.Exited) {
                    _events.trySend(Event.Failed("read failed: ${io.message}"))
                }
                break
            }
            if (n < 0) break
            if (n == 0) continue
            parser.feed(buf, length = n)
            // Output flow is a repaint signal. The terminal model itself is
            // fed raw bytes above, so a split UTF-8 sequence cannot corrupt
            // rendering merely because this debug-facing chunk is incomplete.
            _output.tryEmit(String(buf, 0, n, Charsets.UTF_8))
        }
        parser.finishInput()
    }

    /**
     * Send raw bytes from user input to the process's stdin. The OS pipe
     * is non-blocking on the read side, but writes can block on the
     * shell side (rare for interactive commands, common for `cat > big`).
     * We must NOT do the write on the Compose thread, so this method
     * launches on the session scope.
     */
    fun write(bytes: ByteArray) {
        val nativePty = pty ?: return
        sessionScope.launch(Dispatchers.IO) {
            try {
                nativePty.write(bytes)
            } catch (io: IOException) {
                _events.trySend(Event.Failed("write failed: ${io.message}"))
            }
        }
    }

    /** Current VT input protocol requested by the program in the PTY. */
    internal fun inputModes(): TerminalInputModes = parser.inputModes()

    /** Sends clipboard text without exposing it to the terminal command parser. */
    fun writePaste(bytes: ByteArray) = write(TerminalInputEncoder.paste(bytes, parser.inputModes()))

    /** Convenience for text input — converts String → bytes as UTF-8. */
    fun writeText(s: String) = write(s.toByteArray(Charsets.UTF_8))

    /** Send Ctrl+C (ASCII 0x03) without flushing — same as a real ctrl-c. */
    fun sendInterrupt() = write(byteArrayOf(0x03))

    /** Resize both the kernel PTY and the terminal model. */
    fun resize(cols: Int, rows: Int) {
        val nativePty = pty ?: return
        sessionScope.launch(Dispatchers.IO) {
            try {
                nativePty.resize(columns = cols, rows = rows)
                buffer.resize(cols, rows)
            } catch (io: IOException) {
                _events.trySend(Event.Failed("resize failed: ${io.message}"))
            }
        }
    }

    /** Stop the session. Idempotent; safe to call from any thread. */
    fun stop() {
        if (_state.value == State.NotStarted) return
        try {
            pty?.close()
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

    companion object {
        private const val PUMP_CHUNK = 4096
        private const val WAIT_POLL_MS = 250

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

    private fun resolvedEnvironment(): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        System.getenv().forEach { (key, value) ->
            if (key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value) {
                environment[key] = value
            }
        }
        environment["TERM"] = config.termName
        if (config.colorTermSupport) environment["COLORTERM"] = "truecolor"
        else environment.remove("COLORTERM")
        environment["LINES"] = config.rows.toString()
        environment["COLUMNS"] = config.cols.toString()
        if ("LANG" !in environment && "LC_ALL" !in environment) {
            environment["LANG"] = "en_US.UTF-8"
        }
        config.environmentVariables.forEach { (key, value) ->
            require(key.isNotEmpty() && '=' !in key && '\u0000' !in key) {
                "invalid environment key"
            }
            require('\u0000' !in value) { "environment value cannot contain NUL" }
            environment[key] = value
        }
        return environment
    }
}
