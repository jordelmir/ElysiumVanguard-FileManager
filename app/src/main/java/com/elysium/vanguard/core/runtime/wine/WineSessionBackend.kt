package com.elysium.vanguard.core.runtime.wine

import com.elysium.vanguard.core.runtime.runner.LaunchedProcess

/**
 * Phase 54 — the Wine session's
 * specification.
 *
 * A [WineSessionSpec] is the input to the
 * [WineSessionBackend] (the persistence +
 * state-management seam). The spec
 * carries everything the backend needs to
 * start + stop a session: the manifest the
 * orchestrator produced, the Wine prefix,
 * the Box64 config, and the
 * [com.elysium.vanguard.core.runtime.orchestrator.RuntimeKind]
 * (always `WINE_BOX64` for this runner;
 * the orchestrator's `WINE_FEX` branch is a
 * follow-up).
 *
 * The spec is a value type. The runner
 * constructs the spec from an
 * [com.elysium.vanguard.core.runtime.orchestrator.ExecutionManifest];
 * the backend consumes it.
 */
data class WineSessionSpec(
    val sessionId: String,
    val manifestBinaryPath: String,
    val commandLineArgs: List<String>,
    val environmentVariables: Map<String, String>,
    val prefix: WinePrefix,
    val box64: Box64Config,
    val workspaceId: String? = null
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(manifestBinaryPath.isNotBlank()) { "manifestBinaryPath must not be blank" }
    }
}

/**
 * Phase 54 — the Wine session's state.
 *
 * The state is what the UI renders. The
 * runner publishes state transitions on
 * the [com.elysium.vanguard.core.runtime.observability.RuntimeEventBus];
 * the bus subscribers (UI, audit log) see
 * every state change.
 *
 * - [Idle]: the session has been created
 *   but not started.
 * - [Starting]: the runner is building the
 *   command line + resolving the prefix.
 * - [Running]: the Wine + Box64 process is
 *   alive; carries the OS pid and a
 *   `stop()` callback.
 * - [Stopping]: the runner is sending the
 *   SIGTERM / wine shutdown sequence.
 * - [Stopped]: the process has exited;
 *   carries the exit code.
 * - [Error]: the session could not be
 *   started or stopped cleanly; carries a
 *   message.
 *
 * The state is a sealed class with data
 * classes so the runner can `when` on it
 * without a default branch.
 */
sealed class WineSessionState {
    object Idle : WineSessionState()
    object Starting : WineSessionState()
    data class Running(
        val pid: Int,
        val stop: () -> Unit
    ) : WineSessionState()
    object Stopping : WineSessionState()
    data class Stopped(val exitCode: Int) : WineSessionState()
    data class Error(val message: String) : WineSessionState()
}

/**
 * Phase 54 — the Wine session backend.
 *
 * The backend is the persistence + state-
 * management seam. The runner orchestrates
 * the lifecycle; the backend owns the
 * state. This split mirrors the existing
 * [com.elysium.vanguard.core.runtime.runner.DistroSessionBackend]
 * (Phase 30) and
 * [com.elysium.vanguard.core.runtime.runner.WindowsVmSessionBackend]
 * (Phase 31).
 *
 * ## Thread safety
 *
 * The backend is safe to call from
 * multiple threads. Implementations may
 * use a per-session lock internally; the
 * interface does not promise a particular
 * strategy.
 */
interface WineSessionBackend {

    /**
     * Start a session for [spec]. Returns
     * the [WineSessionState.Running] on
     * success (with the pid + stop callback)
     * or [WineSessionState.Error] on
     * failure.
     */
    fun start(spec: WineSessionSpec): WineSessionState

    /**
     * Query the current state of the
     * session identified by [sessionId].
     * Returns `null` if the session is
     * unknown.
     */
    fun state(sessionId: String): WineSessionState?

    /**
     * Stop the session. Idempotent: a
     * stopped session is a no-op. Returns
     * the [WineSessionState.Stopped] with
     * the exit code, or
     * [WineSessionState.Error] on failure.
     */
    fun stop(sessionId: String): WineSessionState
}

/**
 * Phase 54 — the in-process Wine session
 * backend.
 *
 * The production backend. The backend holds
 * a thread-safe map of session id →
 * current state. The [start] method delegates
 * to the [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
 * to spawn the Wine + Box64 process; the
 * spawned [LaunchedProcess] is stored as
 * the session's `Running` state. The
 * [stop] method calls the stored
 * `stop()` callback.
 *
 * The backend does NOT actually invoke
 * `wine` / `box64` on the host (the JVM
 * tests do not have Wine + Box64 installed).
 * The test backend (a hand-rolled fake)
 * is the path the JVM tests exercise; a
 * future instrumented test on a real
 * Android device (Phase 58+) will exercise
 * the production binary invocation.
 */
class InProcessWineSessionBackend(
    private val processLauncher: com.elysium.vanguard.core.runtime.runner.ProcessLauncher,
    private val stack: WineStack,
    private val prefixesBaseDir: java.io.File,
    private val clock: () -> Long = System::currentTimeMillis
) : WineSessionBackend {

    private val states = java.util.concurrent.ConcurrentHashMap<String, WineSessionState>()

    override fun start(spec: WineSessionSpec): WineSessionState {
        val nowMs = clock()
        states[spec.sessionId] = WineSessionState.Starting
        // Resolve the prefix path: under
        // prefixesBaseDir / <sessionId>.
        val prefix = WinePrefix(
            path = java.io.File(prefixesBaseDir, spec.sessionId)
        )
        prefix.initialise()
        // Build the command line:
        //   box64 wine <binary> <args>
        // with WINEPREFIX=<prefix> and the
        // Box64 environment.
        val box64Bin = stack.box64Path
            ?: return WineSessionState.Error("Box64 not installed; cannot run x86-64 Windows apps")
        val wineBin = stack.winePath
        val env = HashMap<String, String>(spec.environmentVariables)
        env["WINEPREFIX"] = prefix.path.absolutePath
        env["WINEARCH"] = prefix.architecture
        env["WINEDEBUG"] = "-all"
        env.putAll(spec.box64.toEnvironment())
        val command = buildList {
            add(box64Bin.absolutePath)
            add(wineBin.absolutePath)
            add(spec.manifestBinaryPath)
            addAll(spec.commandLineArgs)
        }
        val launched = try {
            processLauncher.start(
                command = command,
                env = env.mapValues { it.value }.toList(),
                cwd = spec.prefix.driveC
            )
        } catch (failure: Throwable) {
            val error = WineSessionState.Error(
                "failed to start Wine + Box64: ${failure.message ?: failure.javaClass.simpleName}"
            )
            states[spec.sessionId] = error
            return error
        }
        val running = WineSessionState.Running(
            pid = launched.pid,
            stop = launched.stop
        )
        states[spec.sessionId] = running
        // Suppress unused parameter warning
        // for `nowMs`; reserved for future
        // "session started at" tracking in
        // audit log.
        @Suppress("UNUSED_VARIABLE")
        val _ignored = nowMs
        return running
    }

    override fun state(sessionId: String): WineSessionState? = states[sessionId]

    override fun stop(sessionId: String): WineSessionState {
        val current = states[sessionId]
            ?: return WineSessionState.Error("session not found: $sessionId")
        if (current is WineSessionState.Stopped) return current
        if (current is WineSessionState.Running) {
            states[sessionId] = WineSessionState.Stopping
            try {
                current.stop()
            } catch (failure: Throwable) {
                val error = WineSessionState.Error(
                    "failed to stop session: ${failure.message ?: failure.javaClass.simpleName}"
                )
                states[sessionId] = error
                return error
            }
        }
        val stopped = WineSessionState.Stopped(exitCode = 0)
        states[sessionId] = stopped
        return stopped
    }
}
