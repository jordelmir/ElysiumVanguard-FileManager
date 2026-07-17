package com.elysium.vanguard.core.runtime.runner

import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession

/**
 * Phase 32 — the dispatch table that picks the right
 * [SessionRunner] for a [WorkspaceSession].
 *
 * The registry is a thin facade that lets the UI call
 * `runner.start(workspace, session)` without knowing
 * which [SessionRunner] impl handles which kind. The
 * registry itself is a [SessionRunner] (so it slots
 * into the same call sites) and routes by
 * [WorkspaceSession.kind]:
 *
 *   - [WorkspaceSession.LinuxProot] ->
 *     [LinuxProotSessionRunner] (Phase 30)
 *   - [WorkspaceSession.WindowsVm] ->
 *     [WindowsVmSessionRunner] (Phase 31)
 *
 * Why a registry, not a `when (kind)` switch in the
 * caller: the registry keeps the kind-to-impl mapping
 * in one obvious place. A future kind beyond
 * `LinuxProot` and `WindowsVm` (Android-side microVM,
 * WebAssembly, GPU passthrough) adds a new constructor
 * parameter; no call-site code changes.
 *
 * The registry is `Context`-free and JVM-testable end-
 * to-end. Tests pass two fake runners; production
 * wires the real `LinuxProotSessionRunner` and
 * `WindowsVmSessionRunner`.
 */
class SessionRunnerRegistry(
    private val linuxRunner: SessionRunner,
    private val windowsRunner: SessionRunner
) : SessionRunner {

    /**
     * Resolve the runner for [session]'s kind. Throws
     * [IllegalStateException] if no runner is
     * registered for the kind — this is a
     * programmer error, not a user-facing failure.
     */
    private fun runnerFor(session: WorkspaceSession): SessionRunner = when (session) {
        is WorkspaceSession.LinuxProot -> linuxRunner
        is WorkspaceSession.WindowsVm -> windowsRunner
    }

    override fun start(workspace: Workspace, session: WorkspaceSession): Result<SessionState> =
        runnerFor(session).start(workspace, session)

    override fun stop(workspace: Workspace, session: WorkspaceSession): Result<SessionState> =
        runnerFor(session).stop(workspace, session)

    override fun state(workspaceId: String, sessionId: String): SessionState {
        // A workspace can hold sessions of both kinds
        // sharing the same (workspaceId, sessionId)
        // (different sessions, but the registry has no
        // way to know which kind without the kind
        // hint). The first non-Idle wins. In practice
        // sessionIds are unique within a workspace, so
        // only one of the two runners can hold a
        // non-Idle state for any given id.
        val linuxState = linuxRunner.state(workspaceId, sessionId)
        if (linuxState != SessionState.Idle) return linuxState
        return windowsRunner.state(workspaceId, sessionId)
    }

    override fun listActive(): List<ActiveSession> {
        // Merge the two runners' active-session lists
        // and sort by `startedAtMs` ascending so the
        // UI sees a stable order.
        return (linuxRunner.listActive() + windowsRunner.listActive())
            .sortedBy { (it.state as? SessionState.Running)?.startedAtMs ?: 0L }
    }
}
