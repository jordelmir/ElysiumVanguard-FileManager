package com.elysium.vanguard.core.runtime.proot

import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession

/**
 * Phase 71 — the proot backend contract.
 *
 * The [ProotBackend] is the **production seam** between
 * the typed
 * [com.elysium.vanguard.core.runtime.critical_e2e.CriticalE2EOrchestrator]
 * and the actual proot execution. The orchestrator
 * (Phase 70) consumes a [ProotBackend] to run the 8-step
 * E2E.
 *
 * The interface is the typed contract; the
 * implementations are:
 *   - [com.elysium.vanguard.core.runtime.critical_e2e.InMemoryProotBackend]
 *     (test, JVM-friendly) — the in-memory implementation
 *     used by the JVM-side `CriticalE2ETest`.
 *   - [ProotBackendReal] (production) — wraps the
 *     `LinuxProotSessionRunner` (Phase 30) and performs
 *     the actual proot execution on a real device.
 *
 * Per the master vision ("Motor universal de
 * ejecución"): the proot backend is the typed boundary
 * between the user's intent and the platform's
 * execution. The CriticalE2E orchestrator doesn't know
 * whether it's running against an in-memory
 * implementation or the real proot; it just calls the
 * contract.
 */
interface ProotBackend {

    /**
     * The result of a launch. The `writes` list
     * records the paths the process wrote to; the
     * audit step (step 9 of the E2E) asserts every
     * write is within the authorized mount list.
     */
    data class LaunchResult(
        val pid: Int,
        val exitCode: Int,
        val writes: List<String>,
    )

    /**
     * Launch the binary. The backend:
     *   - Translates the [bindMounts] into the
     *     platform's mount primitive (e.g. proot
     *     `-b` flags).
     *   - Builds the shell command from the
     *     [executable] + [args] + [workingDirectory].
     *   - Sets the [environment] variables on the
     *     child process.
     *   - Forks the host OS process via the
     *     platform's process launcher.
     *   - Records the writes the process makes
     *     (the audit log gets each write in step 9).
     *
     * The [workspaceId] is the id of the workspace
     * that owns the [session]; the backend uses it
     * to look up the workspace (needed for the
     * `SessionRunner.start(workspace, session)`
     * signature on the real backend).
     *
     * Returns a [LaunchResult] with the pid +
     * the simulated writes.
     */
    fun launch(
        workspaceId: String,
        session: WorkspaceSession,
        executable: String,
        args: List<String>,
        workingDirectory: String,
        bindMounts: List<com.elysium.vanguard.core.runtime.workspace_orchestrator.BindMount>,
        environment: Map<String, String>,
    ): Result<LaunchResult>

    /**
     * Stop the process. The backend signals the
     * child process to terminate; the exit code
     * is recorded in the security audit.
     */
    fun stop(workspaceId: String, session: WorkspaceSession): Result<Unit>

    /**
     * Restore the workspace's rootfs to the last
     * snapshot. The backend delegates to the
     * `SnapshotEngine` (Phase 49) to find + restore
     * the snapshot.
     */
    fun restoreSnapshot(workspaceId: String, session: WorkspaceSession): Result<Unit>

    /**
     * Phase 72 — return the host paths the process
     * wrote to during the session. The orchestrator
     * calls this **after** [stop] and **before**
     * [restoreSnapshot] to populate the audit log;
     * step 9 of the E2E then asserts every write
     * is within the authorized mount list.
     *
     * The production impl ([ProotBackendReal])
     * delegates to the [WriteCapture] that was
     * started in [launch] and stopped in
     * [restoreSnapshot]. The in-memory impl
     * ([com.elysium.vanguard.core.runtime.critical_e2e.InMemoryProotBackend])
     * returns the pre-canned [nextWrites] list
     * (the simulated process's writes).
     *
     * Calling [writes] without a prior [launch]
     * returns an empty list (no writes were ever
     * captured). Calling [writes] after
     * [restoreSnapshot] returns the final list
     * (the capture is stopped, but the captured
     * writes are preserved).
     */
    fun writes(workspaceId: String, session: WorkspaceSession): List<String>
}
