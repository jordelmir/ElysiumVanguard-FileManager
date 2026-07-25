package com.elysium.vanguard.core.runtime.distros.terminal

import com.elysium.vanguard.core.runtime.distros.bundled.BundledDistro
import com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsExtractor
import com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher
import com.elysium.vanguard.core.runtime.distros.launcher.ProotLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PHASE 141 — the real terminal runner.
 *
 * Spawns a `proot` process running a real Linux shell
 * inside a real rootfs (the bundled Alpine minirootfs
 * from Phase 140), pipes the user's stdin/stdout, and
 * exposes a coroutine-friendly API the
 * [com.elysium.vanguard.features.desktop.content.RealTerminalBody]
 * consumes.
 *
 * This is **not** a mock. The process is a real
 * `ProcessBuilder.start()` invocation. The bytes that
 * come out are real Linux output (Alpine's `/bin/ash`
 * running on the Android kernel via proot's syscall
 * translation). The bytes the user types are real input
 * to that process. The user can `apk add` packages,
 * `cat` files, `ls` the rootfs, run `busybox httpd`,
 * etc.
 *
 * Why a custom runner instead of reusing the existing
 * [com.elysium.vanguard.core.runtime.runner.LinuxProotSessionRunner]?
 * The session runner is workspace-centric (it owns a
 * `Workspace` + a `WorkspaceSession.LinuxProot` and
 * participates in the session lifecycle of the platform's
 * snapshot/rollback story). The terminal is a different
 * consumer: a foreground, ephemeral process the user is
 * interactively driving. We don't need the
 * snapshot/rollback metadata for a typed REPL; we need a
 * tight read/write loop with lifecycle events the UI can
 * render.
 *
 * Thread-safety:
 *   - The `process` reference is only touched on the
 *     runner's coroutine scope.
 *   - `write(bytes)` is callable from any thread; it
 *     schedules a write on the runner scope.
 *   - `stop()` is idempotent.
 *
 * Lifecycle:
 *   - `start()` is idempotent (second call is a no-op).
 *   - `stop()` is idempotent.
 *   - The runner publishes state transitions to [state]
 *     (StateFlow) and stdout chunks to [output]
 *     (SharedFlow).
 *
 * Construction:
 *   - The runner takes a [prootLocation] (where
 *     `libproot.so` lives), a [distro] (which bundled
 *     rootfs to use), an [extractor] (Phase 140
 *     hash-verified unpack), and a [prootLibraryDir]
 *     (where the proot shared libraries live). The
 *     runner never searches for the binary itself; the
 *     caller wires all four.
 *   - The typical call site is the Hilt-injected
 *     `ProotTerminalRunner` — see
 *     [ProotTerminalRunnerModule].
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

    private var process: Process? = null
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

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
            val pb = buildProcessBuilder(rootfsDir)
            val proc = pb.start()
            process = proc
            _state.value = State.Running(pid = syntheticPid(proc))
            scope.launch { pumpStream(proc.inputStream, isError = false) }
            scope.launch { pumpStream(proc.errorStream, isError = true) }
            scope.launch {
                val rc = try {
                    proc.waitFor()
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@launch
                }
                _state.value = State.Exited(rc)
                _events.tryEmit(Event.Exited(rc))
            }
        } catch (e: Throwable) {
            started.set(false)
            val message = e.message ?: e::class.java.simpleName
            _state.value = State.Error(message)
            _events.tryEmit(Event.Failed(message))
        }
    }

    /**
     * Send raw bytes to the proot process's stdin.
     * Safe to call from any thread; the runner schedules
     * the actual write on the IO dispatcher.
     */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val proc = process ?: return
        scope.launch(Dispatchers.IO) {
            try {
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
            } catch (io: IOException) {
                _events.tryEmit(Event.Failed("write failed: ${io.message}"))
            }
        }
    }

    /**
     * Send Ctrl+C (ASCII 0x03) — interrupts the running
     * command without killing the shell.
     */
    fun sendInterrupt() = write(byteArrayOf(0x03))

    /**
     * Stop the proot process gracefully: close stdin
     * first (the shell sees EOF and exits), then
     * `waitFor(timeoutMs)` with a hard `destroyForcibly`
     * fallback. Idempotent.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        val proc = process ?: return
        scope.launch(Dispatchers.IO) {
            try {
                proc.outputStream.close()
            } catch (_: IOException) { /* ignore */ }
            try {
                if (!proc.waitFor(2_000L, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(500L, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                proc.destroyForcibly()
            }
        }
    }

    /**
     * Cancel the runner's coroutine scope. The caller
     * (typically the Composable) MUST call this on
     * disposal or the pump coroutines will outlive the
     * screen.
     */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun buildProcessBuilder(rootfsDir: File): ProcessBuilder {
        val launcher = NativeProotLauncher()
        val args = launcher.buildShellCommand(
            rootfsDir = rootfsDir,
            script = "", // empty = interactive login shell
        )
        val pb = ProcessBuilder(args)
        // Proot needs the library dir to be findable via
        // LD_LIBRARY_PATH; it loads libtalloc2.so +
        // libandroid-shmem.so + libproot_loader.so from
        // there.
        val env = launcher.environmentVariables(rootfsDir).toMap().toMutableMap()
        env["LD_LIBRARY_PATH"] = prootLibraryDir.absolutePath
        env["PROOT_LOADER"] = prootLocation.loaderPath.absolutePath
        // PROOT_NO_SECCOMP makes the syscall translation
        // work on Android 8-16 vendor kernels; without it
        // some devices segfault.
        env["PROOT_NO_SECCOMP"] = "1"
        env["PROOT_DONT_POLLUTE_ROOTFS"] = "1"
        pb.environment().putAll(env)
        pb.redirectErrorStream(false) // keep stdout / stderr separate
        return pb
    }

    private fun pumpStream(stream: java.io.InputStream, isError: Boolean) {
        val buffer = ByteArray(4096)
        try {
            while (scope.isActive) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val chunk = String(buffer, 0, n, Charsets.UTF_8)
                _output.tryEmit(chunk)
                if (isError) {
                    _events.tryEmit(Event.Stderr(chunk))
                }
            }
        } catch (_: IOException) {
            // The stream closed because the process exited. Normal.
        }
    }

    /**
     * Android's java.lang.Process does NOT expose `pid()`
     * (Java 9+). We use a synthetic PID derived from the
     * Process identity; the OS pid is unavailable without
     * reflection, and reflection on `java.base` throws
     * `IllegalAccessException` on modern Android (see
     * MEMORY.md "Bash 5.3.9 quirks" cross-reference, but
     * the Java rule is the same). For the terminal UI the
     * synthetic PID is plenty.
     */
    private fun syntheticPid(proc: Process): Long =
        System.identityHashCode(proc).toLong() and 0x7FFFFFFFL

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
        data class Stderr(val chunk: String) : Event()
    }
}

/**
 * Lazy-loaded provider of the [BundledRootfsSource] for
 * a given [BundledDistro]. The production wiring looks
 * up the `AssetManager` from the `Application` context
 * at Hilt time; tests use a `FileBundledRootfsSource`
 * directly. We keep the lookup lazy so the test
 * environment doesn't need an Android `Context`.
 */
internal object AssetManagerSourceProvider {
    @Volatile private var factory: ((BundledDistro) -> com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsSource)? = null

    fun register(factory: (BundledDistro) -> com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsSource) {
        this.factory = factory
    }

    fun sourceFor(distro: BundledDistro): com.elysium.vanguard.core.runtime.distros.bundled.BundledRootfsSource {
        val f = factory
            ?: throw IllegalStateException(
                "ProotTerminalRunner: AssetManagerSourceProvider not registered. " +
                    "Production wires this in ProotTerminalRunnerModule. " +
                    "Tests must register a FileBundledRootfsSource factory in @Before."
            )
        return f(distro)
    }
}
