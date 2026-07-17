package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 30 — the [SessionRunner] implementation for
 * [WorkspaceSession.LinuxProot].
 *
 * The runner composes:
 *
 *   - the [DistroManager] (Phase 9.6.2) — the source of the rootfs
 *     path + the [com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick]
 *     for the session's `distroId` + `profileId`,
 *   - a [ProcessLauncher] — the seam the runner uses to actually
 *     fork a host OS process,
 *   - the [RuntimeEventBus] (Phase 25) — the runner publishes a
 *     [RuntimeEvent.SessionStartedEvent] / `SessionStoppedEvent` /
 *     `SessionStartFailedEvent` on every state transition.
 *
 * Lifecycle:
 *
 *   - `start()` looks up the launcher, builds the shell command
 *     (empty script = "drop the user into an interactive shell"),
 *     asks the [ProcessLauncher] to fork, stores the launched
 *     process in a per-session map, and returns the new
 *     [SessionState.Running] (or [SessionState.Error] on
 *     failure).
 *   - `stop()` looks up the launched process, calls its `stop()`
 *     callback, and moves the state to [SessionState.Stopped].
 *   - `state()` returns the in-memory state for the
 *     `(workspaceId, sessionId)` pair, or [SessionState.Idle] when
 *     the session has never been started.
 *
 * Thread-safety: the per-session state is a `ConcurrentHashMap`;
 * the [LaunchedProcess] is read-then-stop-ped inside the same
 * atomic removal so two concurrent `stop()` calls do not
 * double-signal. The [com.elysium.vanguard.core.runtime.distros.launcher.DistroLauncher.buildShellCommand]
 * is a pure function so it is safe to call from any thread.
 */
class LinuxProotSessionRunner(
    private val backend: DistroSessionBackend,
    private val processLauncher: ProcessLauncher,
    private val eventBus: RuntimeEventBus,
    private val clock: () -> Long = System::currentTimeMillis
) : SessionRunner {

    private val states = ConcurrentHashMap<SessionKey, SessionState>()
    private val handles = ConcurrentHashMap<SessionKey, LaunchedProcess>()

    private data class SessionKey(val workspaceId: String, val sessionId: String)

    override fun start(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
        // Only LinuxProot sessions. Windows VM is a future
        // SessionRunner impl (Phase 31).
        if (session !is WorkspaceSession.LinuxProot) {
            return Result.failure(
                SessionRunnerError.UnsupportedKind(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    kind = session.kind
                )
            )
        }
        val key = SessionKey(workspace.id, session.id)
        val current = states[key] ?: SessionState.Idle
        if (!current.isStartable()) {
            return Result.failure(
                SessionRunnerError.SessionAlreadyRunning(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    currentState = current
                )
            )
        }
        val nowMs = clock()
        states[key] = SessionState.Starting(nowMs)

        val installation = backend.findInstalled(session.distroId)
            ?: return failAndRollback(
                key, nowMs,
                SessionRunnerError.DistroNotInstalled(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    distroId = session.distroId
                )
            )
        val pick = backend.launcherFor(session.distroId)
            ?: return failAndRollback(
                key, nowMs,
                SessionRunnerError.LauncherUnavailable(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    distroId = session.distroId
                )
            )

        val rootfsDir = installation.rootfsDir
        val command = pick.launcher.buildShellCommand(rootfsDir, script = "")
        val env = pick.launcher.environmentVariables(rootfsDir)

        val launched: LaunchedProcess = try {
            processLauncher.start(command = command, env = env, cwd = rootfsDir)
        } catch (io: IOException) {
            return failAndRollback(
                key, nowMs,
                SessionRunnerError.StartFailed(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    causeMessage = io.message ?: io::class.java.simpleName
                )
            )
        }

        val running = SessionState.Running(
            pid = launched.pid,
            startedAtMs = nowMs
        )
        states[key] = running
        handles[key] = launched

        eventBus.publish(
            RuntimeEvent.SessionStartedEvent(
                atMs = nowMs,
                workspaceId = workspace.id,
                sessionId = session.id,
                kind = session.kind.toString(),
                launcherKind = pick.launcher.kind.toString(),
                pid = launched.pid
            )
        )
        return Result.success(running)
    }

    override fun stop(workspace: Workspace, session: WorkspaceSession): Result<SessionState> {
        val key = SessionKey(workspace.id, session.id)
        val current = states[key] ?: SessionState.Idle
        if (!current.isStoppable()) {
            return Result.failure(
                SessionRunnerError.SessionNotRunning(
                    workspaceId = workspace.id,
                    sessionId = session.id,
                    currentState = current
                )
            )
        }
        val nowMs = clock()
        states[key] = SessionState.Stopping(nowMs)
        // Atomic remove-and-stop: the next caller sees no
        // handle for this key, so a concurrent `stop()` will
        // see `Stopping` and the next one will see `Stopped` /
        // `Idle` (depending on whether the post-stop hook ran).
        val handle = handles.remove(key)
        handle?.stop?.invoke()
        val stopped = SessionState.Stopped
        states[key] = stopped
        val exitCode = (current as? SessionState.Running)?.let { 0 } ?: -1
        eventBus.publish(
            RuntimeEvent.SessionStoppedEvent(
                atMs = nowMs,
                workspaceId = workspace.id,
                sessionId = session.id,
                exitCode = exitCode
            )
        )
        return Result.success(stopped)
    }

    override fun state(workspaceId: String, sessionId: String): SessionState =
        states[SessionKey(workspaceId, sessionId)] ?: SessionState.Idle

    override fun listActive(): List<ActiveSession> {
        return states.entries
            .asSequence()
            .filter { it.value.isLive() }
            .map { (key, state) ->
                ActiveSession(
                    workspaceId = key.workspaceId,
                    sessionId = key.sessionId,
                    kind = WorkspaceSession.SessionKind.LINUX_PROOT,
                    state = state,
                    launcherKind = null  // Phase 30 only tracks state, not launcher
                )
            }
            .sortedBy { (it.state as? SessionState.Running)?.startedAtMs ?: 0L }
            .toList()
    }

    private inline fun failAndRollback(
        key: SessionKey,
        nowMs: Long,
        error: SessionRunnerError
    ): Result<SessionState> {
        states[key] = SessionState.Error(nowMs, error.message ?: "unknown")
        eventBus.publish(
            RuntimeEvent.SessionStartFailedEvent(
                atMs = nowMs,
                workspaceId = key.workspaceId,
                sessionId = key.sessionId,
                kind = WorkspaceSession.SessionKind.LINUX_PROOT.toString(),
                error = error.message ?: "unknown"
            )
        )
        return Result.failure(error)
    }
}
