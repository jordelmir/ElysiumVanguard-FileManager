package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession

/**
 * Phase 30 — the orchestrator that turns a [Workspace] + a
 * [WorkspaceSession] into a live process.
 *
 * The runner is the single public surface the UI calls when the user
 * taps "Start session" or "Stop session". The runner:
 *
 *   - looks up the right backend for the session kind
 *     ([com.elysium.vanguard.core.runtime.runner.LinuxProotSessionRunner]
 *     for `LinuxProot`, a future Windows VM runner for `WindowsVm`),
 *   - delegates to that backend to start / stop the underlying
 *     process (or VM),
 *   - tracks the per-session state in memory,
 *   - publishes a runtime event on every state transition so the
 *     audit log + the main-screen ViewModel pick the change up.
 *
 * The runner is `Context`-free (no `Application`, no `Context`),
 * JVM-testable end-to-end. The runtime wires the real impl in via
 * Hilt; tests construct a runner with an in-memory distro manager,
 * a no-op process launcher, and a recording event bus.
 */
interface SessionRunner {

    /**
     * Start the session. The state machine moves:
     * `Idle/Stopped/Error → Starting → Running`.
     *
     * Returns the resulting [SessionState] on success, or a
     * [Result.failure] with a [SessionRunnerError] when the
     * session could not be started (distro missing, launcher
     * unavailable, already running, etc.).
     */
    fun start(workspace: Workspace, session: WorkspaceSession): Result<SessionState>

    /**
     * Stop the session. The state machine moves:
     * `Starting/Running → Stopping → Stopped`.
     *
     * Returns the resulting [SessionState] on success, or a
     * [Result.failure] with a [SessionRunnerError] when the
     * session is not currently running.
     */
    fun stop(workspace: Workspace, session: WorkspaceSession): Result<SessionState>

    /**
     * Read the current [SessionState] for a `(workspace, session)`
     * pair. Returns [SessionState.Idle] when the session has
     * never been started by this runner.
     */
    fun state(workspaceId: String, sessionId: String): SessionState

    /**
     * Every session this runner currently tracks. The list is
     * `Starting` / `Running` / `Stopping` only; idle and stopped
     * sessions are not returned. Sorted by `startedAtMs`
     * ascending.
     */
    fun listActive(): List<ActiveSession>

    /**
     * The number of currently-active sessions (Starting + Running +
     * Stopping). Useful for the UI status bar.
     */
    fun activeCount(): Int = listActive().size
}

/**
 * A snapshot of a single live session. Captures the
 * `(workspaceId, sessionId)` pair + the kind + the state + the
 * launcher kind (the [com.elysium.vanguard.core.runtime.distros.launcher.LauncherKind]
 * is null for non-Linux sessions).
 */
data class ActiveSession(
    val workspaceId: String,
    val sessionId: String,
    val kind: WorkspaceSession.SessionKind,
    val state: SessionState,
    val launcherKind: String?
)

/**
 * Typed errors the runner returns. The UI branches on the kind
 * (e.g. "Distro not installed" → show the install button;
 * "Already running" → show a snackbar; etc.).
 */
sealed class SessionRunnerError(message: String) : RuntimeException(message) {

    /** A `start()` call on a session that is already in a
     *  live state (`Starting` / `Running` / `Stopping`). */
    data class SessionAlreadyRunning(
        val workspaceId: String,
        val sessionId: String,
        val currentState: SessionState
    ) : SessionRunnerError("Session $sessionId in workspace $workspaceId is already running ($currentState)")

    /** A `stop()` call on a session that is not currently
     *  live. The UI catches this and treats it as a no-op. */
    data class SessionNotRunning(
        val workspaceId: String,
        val sessionId: String,
        val currentState: SessionState
    ) : SessionRunnerError("Session $sessionId in workspace $workspaceId is not running ($currentState)")

    /** The session's distroId is not in the [com.elysium.vanguard.core.runtime.distros.DistroManager]'s
     *  installed list. The UI catches this and shows the
     *  install button for the distro. */
    data class DistroNotInstalled(
        val workspaceId: String,
        val sessionId: String,
        val distroId: String
    ) : SessionRunnerError("Distro $distroId is not installed (session $sessionId in workspace $workspaceId)")

    /** The [com.elysium.vanguard.core.runtime.distros.DistroManager.launcherFor]
     *  call returned null. Either the distro is unhealthy or the
     *  resolver could not find a launcher for the device's ABI. */
    data class LauncherUnavailable(
        val workspaceId: String,
        val sessionId: String,
        val distroId: String
    ) : SessionRunnerError("No launcher available for distro $distroId (session $sessionId in workspace $workspaceId)")

    /** The [ProcessLauncher.start] call threw. The runtime
     *  logs the underlying cause and rolls the session back to
     *  [SessionState.Error]. */
    data class StartFailed(
        val workspaceId: String,
        val sessionId: String,
        val causeMessage: String
    ) : SessionRunnerError("Failed to start session $sessionId in workspace $workspaceId: $causeMessage")

    /** The session's kind is not supported by this runner. The
     *  UI catches this and shows an unsupported-kind error. */
    data class UnsupportedKind(
        val workspaceId: String,
        val sessionId: String,
        val kind: WorkspaceSession.SessionKind
    ) : SessionRunnerError("Session kind $kind is not supported by this runner (session $sessionId in workspace $workspaceId)")
}
