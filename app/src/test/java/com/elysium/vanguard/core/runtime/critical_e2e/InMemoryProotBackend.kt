package com.elysium.vanguard.core.runtime.critical_e2e

import com.elysium.vanguard.core.runtime.proot.ProotBackend
import com.elysium.vanguard.core.runtime.workspace_orchestrator.BindMount
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession

/**
 * Phase 71 — the in-memory proot backend (test fixture).
 *
 * The real proot backend (`ProotBackendReal`) is an
 * Android-only implementation that depends on
 * `ProcessLauncher` + `RuntimeEventBus` + the
 * `LinuxProotSessionRunner`. The JVM-side E2E test
 * cannot stand up a real proot binary, so this in-memory
 * implementation simulates the proot execution.
 *
 * The in-memory backend records every launch / stop /
 * snapshot operation so the E2E orchestrator can assert
 * the backend was called with the right parameters
 * (entrypoint, mounts, env).
 *
 * The in-memory backend also records the writes the
 * simulated process makes. The default writes are within
 * the authorized mount list; the E2E test can override
 * the writes to assert the audit catches unauthorized
 * writes.
 *
 * Phase 71 changes: the backend now **implements** the
 * production [ProotBackend] interface (Phase 70's
 * `ProotBackendStub` was a standalone class with a
 * parallel API). This is the production seam — the
 * orchestrator runs against the interface, and the test
 * wires the in-memory implementation.
 */
class InMemoryProotBackend : ProotBackend {

    /**
     * A launch invocation the test can inspect.
     */
    data class LaunchInvocation(
        val workspaceId: String,
        val sessionId: String,
        val executable: String,
        val args: List<String>,
        val workingDirectory: String,
        val bindMounts: List<BindMountRecord>,
        val environment: Map<String, String>,
    )

    data class BindMountRecord(
        val hostPath: String,
        val containerPath: String,
        val readOnly: Boolean,
    )

    data class StopInvocation(val workspaceId: String, val sessionId: String)

    data class RestoreInvocation(val workspaceId: String, val sessionId: String)

    /**
     * The writes the simulated process will make
     * on the next launch. The default is the
     * typical "the process writes to its working
     * directory" — a single write that's within
     * the authorized mounts.
     */
    var nextWrites: List<String> = listOf("/workspace/projects/output.json")

    /**
     * The PID the next launch will return.
     */
    var nextPid: Int = 12345

    /**
     * The exit code the next stop will return.
     */
    var nextExitCode: Int = 0

    /**
     * Whether the next launch will fail.
     */
    var nextLaunchFails: Boolean = false

    /**
     * Whether the next stop will fail.
     */
    var nextStopFails: Boolean = false

    /**
     * Whether the next restore will fail.
     */
    var nextRestoreFails: Boolean = false

    /**
     * The launches the backend has recorded.
     */
    val launches: MutableList<LaunchInvocation> = mutableListOf()

    /**
     * The stops the backend has recorded.
     */
    val stops: MutableList<StopInvocation> = mutableListOf()

    /**
     * The restores the backend has recorded.
     */
    val restores: MutableList<RestoreInvocation> = mutableListOf()

    /**
     * Simulate a launch. The backend records the
     * invocation + returns the writes the process
     * made (the audit log gets each write in
     * step 9).
     */
    override fun launch(
        workspaceId: String,
        session: WorkspaceSession,
        executable: String,
        args: List<String>,
        workingDirectory: String,
        bindMounts: List<BindMount>,
        environment: Map<String, String>,
    ): Result<ProotBackend.LaunchResult> {
        if (nextLaunchFails) {
            return Result.failure(RuntimeException("simulated launch failure"))
        }
        val invocation = LaunchInvocation(
            workspaceId = workspaceId,
            sessionId = session.id,
            executable = executable,
            args = args,
            workingDirectory = workingDirectory,
            bindMounts = bindMounts.map { BindMountRecord(it.hostPath, it.containerPath, it.readOnly) },
            environment = environment,
        )
        launches.add(invocation)
        return Result.success(
            ProotBackend.LaunchResult(
                pid = nextPid,
                exitCode = 0,
                writes = nextWrites,
            ),
        )
    }

    /**
     * Simulate a stop. The backend records the
     * invocation.
     */
    override fun stop(workspaceId: String, session: WorkspaceSession): Result<Unit> {
        if (nextStopFails) {
            return Result.failure(RuntimeException("simulated stop failure"))
        }
        stops.add(StopInvocation(workspaceId, session.id))
        return Result.success(Unit)
    }

    /**
     * Simulate a snapshot restore. The backend
     * records the invocation.
     */
    override fun restoreSnapshot(workspaceId: String, session: WorkspaceSession): Result<Unit> {
        if (nextRestoreFails) {
            return Result.failure(RuntimeException("simulated restore failure"))
        }
        restores.add(RestoreInvocation(workspaceId, session.id))
        return Result.success(Unit)
    }

    /**
     * Phase 72 — return the pre-canned writes the
     * simulated process made. The orchestrator
     * calls this after `stop` and before
     * `restoreSnapshot` to populate the audit
     * log; step 9 then asserts every write is
     * within the authorized mount list.
     *
     * Backward-compat: this returns the same list
     * the launch result used to carry, so the
     * existing tests (which set `nextWrites` and
     * expect step 9 to detect it) keep working
     * without changes.
     */
    override fun writes(workspaceId: String, session: WorkspaceSession): List<String> {
        return nextWrites
    }
}
