package com.elysium.vanguard.core.runtime.distros.terminal

import com.elysium.vanguard.core.runtime.distros.bundled.BundledDistro
import com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsSource
import com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsExtractor
import com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.ProotLocation
import com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PHASE 143 — the real terminal runner, now with real PTY semantics.
 *
 * Phase 141 used `ProcessBuilder` (no PTY) — that worked for
 * non-interactive commands but line discipline, resize, and signal
 * delivery were not honored, so interactive programs (vi, htop)
 * were broken.
 *
 * Phase 143 swaps `ProcessBuilder` for [TerminalSession], which
 * is backed by [com.elysium.vanguard.core.runtime.terminal.pty.NativePty]
 * — a Rust-owned PTY (forkpty + epoll on the host side, JNI bridge
 * on the JVM side). With a real PTY the user can:
 *   - Run `vi /etc/hostname` (line discipline + cursor positioning)
 *   - Run `htop` (resize, signals)
 *   - Run `python3` (REPL with line editing + history)
 *   - Run `bash` (job control)
 *
 * The runner's public API is unchanged from Phase 141:
 *   - `state: StateFlow<State>`
 *   - `output: SharedFlow<String>` (stdout chunks)
 *   - `events: SharedFlow<Event>`
 *   - `start()`, `write(bytes)`, `sendInterrupt()`, `stop()`, `dispose()`
 *
 * Plus a new method: [session] returns the underlying
 * [TerminalSession] so the UI can use the proper
 * [com.elysium.vanguard.core.runtime.terminal.view.TerminalHost]
 * composable (full ANSI parser + line editor + colors + cursor
 * positioning) instead of the Phase 142 text-dump body.
 */
class ProotTerminalRunner(
    private val prootLocation: ProotLocation,
    private val distro: BundledDistro,
    private val extractor: BundledRootfsExtractor,
    private val prootLibraryDir: File,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow<State>(State.NotStarted)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val output: SharedFlow<String> = _output.asSharedFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    @Volatile private var session: TerminalSession? = null
    @Volatile private var prootSessionConfig: ProotSessionConfig? = null

    /**
     * Spawn the proot process running `/bin/sh -l` inside
     * the extracted rootfs. Idempotent.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        _state.value = State.Starting
        try {
            val rootfsDir = extractor.ensureExtracted(
                distro = distro,
                source = AssetManagerSourceProvider.sourceFor(distro),
            )
            // PHASE 145 — wire the proot detector + the runner's
            // library dir into the launcher. Phase 141 / 143 created
            // `NativeProotLauncher()` with no args, which left
            // `nativeLibrary = null` → `isAvailable()` returned
            // false → `buildShellCommand` returned the
            // `["proot-missing"]` sentinel, which then bubbled up
            // as the ENOENT we saw on-device. Pass the detector
            // explicitly so the launcher resolves the real
            // `libproot.so` path under `nativeLibraryDir`.
            val launcher = NativeProotLauncher(
                bundledAbis = setOf("arm64-v8a"),
                nativeLibrary = ProotNativeLibrary(
                    bundledAbis = setOf("arm64-v8a"),
                    nativeLibraryDir = prootLibraryDir,
                    userProotDir = null,
                    termuxProotCandidates = ProotNativeLibrary.DEFAULT_TERMUX_PROBES,
                ),
                runtimeTmpDir = File(rootfsDir.parentFile, "proot-tmp"),
            )
            val args = launcher.buildShellCommand(rootfsDir, script = "")
            val baseEnv = launcher.environmentVariables(rootfsDir).toMap()
            // Append the proot-specific env (LD_LIBRARY_PATH,
            // PROOT_LOADER, etc.) on top of the launcher's
            // defaults. Same as Phase 141.
            val fullEnv = baseEnv + mapOf(
                "LD_LIBRARY_PATH" to prootLibraryDir.absolutePath,
                "PROOT_LOADER" to prootLocation.loaderPath.absolutePath,
                "PROOT_NO_SECCOMP" to "1",
                "PROOT_DONT_POLLUTE_ROOTFS" to "1",
            )
            val sessionConfig = ProotSessionConfig(
                command = args,
                workingDirectory = rootfsDir,
                rootfsDir = rootfsDir,
                environment = fullEnv.map { it.key + "=" + it.value },
            )
            prootSessionConfig = sessionConfig
            val terminalSession = TerminalSession(
                config = TerminalSession.Config(
                    command = sessionConfig.command,
                    workingDirectory = sessionConfig.workingDirectory,
                    rootfsDir = sessionConfig.rootfsDir,
                    cols = 80,
                    rows = 24,
                    termName = "xterm-256color",
                    colorTermSupport = true,
                    environmentVariables = fullEnv.toList(),
                )
            )
            session = terminalSession
            // Bridge the TerminalSession's flows to our
            // runner's API. Same shape as Phase 141.
            scope.launch {
                terminalSession.output.collect { chunk -> _output.tryEmit(chunk) }
            }
            scope.launch {
                // Track the session state over time so
                // _state.value reflects the live PID
                // (the initial state is NotStarted, not
                // Running; the Running state is published
                // when NativePty.spawn succeeds).
                terminalSession.state.collect { sessionState ->
                    when (sessionState) {
                        is TerminalSession.State.Running -> {
                            _state.value = State.Running(pid = sessionState.pid)
                        }
                        else -> { /* State transitions are handled in events below */ }
                    }
                }
            }
            scope.launch {
                terminalSession.events.collect { event ->
                    when (event) {
                        is TerminalSession.Event.Exited -> {
                            _state.value = State.Exited(event.exitCode)
                            _events.tryEmit(Event.Exited(event.exitCode))
                        }
                        is TerminalSession.Event.Failed -> {
                            _state.value = State.Error(event.message)
                            _events.tryEmit(Event.Failed(event.message))
                        }
                        is TerminalSession.Event.TitleChanged -> { /* no-op */ }
                        is TerminalSession.Event.Bel -> { /* Haptic feedback handled by ViewModel. */ }
                    }
                }
            }
            terminalSession.start()
        } catch (e: Throwable) {
            started.set(false)
            val message = e.message ?: e::class.java.simpleName
            _state.value = State.Error(message)
            _events.tryEmit(Event.Failed(message))
        }
    }

    /**
     * Send raw bytes to the shell's stdin. Safe from any thread.
     */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        session?.write(bytes)
    }

    /** Send Ctrl+C (ASCII 0x03). */
    fun sendInterrupt() {
        session?.sendInterrupt()
    }

    /**
     * Resize the PTY + the terminal model. The body should
     * call this on layout changes so vi / htop / etc. see
     * the right window size.
     */
    fun resize(cols: Int, rows: Int) {
        session?.resize(cols, rows)
    }

    /** Stop the proot process. Idempotent. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        session?.stop()
    }

    /** Cancel the runner's coroutine scope. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    /**
     * Returns the underlying [TerminalSession] so the UI
     * can use the proper [TerminalHost] composable (full
     * ANSI parser, line editor, colors, cursor positioning).
     * Returns null until [start] has been called.
     */
    fun session(): TerminalSession? = session

    /**
     * Coarse lifecycle exposed to the UI as a Flow.
     */
    sealed class State {
        object NotStarted : State()
        object Starting : State()
        data class Running(val pid: Long?) : State()
        data class Exited(val exitCode: Int) : State()
        data class Error(val message: String) : State()
    }

    sealed class Event {
        data class Exited(val exitCode: Int) : Event()
        data class Failed(val message: String) : Event()
    }
}

/**
 * Internal snapshot of the runner's spawn-time configuration.
 * Captured at [ProotTerminalRunner.start] so the runner can
 * report the exact command + environment in error messages.
 */
internal data class ProotSessionConfig(
    val command: List<String>,
    val workingDirectory: File,
    val rootfsDir: File,
    val environment: List<String>,
)

/**
 * Lazy-loaded provider of the [BundledRootfsSource] for
 * a given [BundledDistro]. Production wires this in
 * [ProotTerminalRunnerModule]; tests register a
 * [com.elysium.vanguard.core.runtime.distros.bundled.FileBundledRootfsSource]
 * factory in `@Before`.
 */
internal object AssetManagerSourceProvider {
    @Volatile private var factory: ((BundledDistro) -> BundledRootfsSource)? = null

    fun register(factory: (BundledDistro) -> BundledRootfsSource) {
        this.factory = factory
    }

    fun sourceFor(distro: BundledDistro): BundledRootfsSource {
        val f = factory
            ?: throw IllegalStateException(
                "ProotTerminalRunner: AssetManagerSourceProvider not registered. " +
                    "Production wires this in ProotTerminalRunnerModule. " +
                    "Tests must register a FileBundledRootfsSource factory in @Before."
            )
        return f(distro)
    }
}
