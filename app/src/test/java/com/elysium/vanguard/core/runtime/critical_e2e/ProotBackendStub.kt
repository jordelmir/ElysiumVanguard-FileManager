package com.elysium.vanguard.core.runtime.critical_e2e

import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession

/**
 * Phase 70 — the proot backend stub for the
 * Critical E2E test.
 *
 * The real proot backend (Phase 24's
 * `LinuxProotSessionRunner`) is an Android-only
 * implementation that depends on `ProcessLauncher`
 * + `RuntimeEventBus` + Android Context. The E2E
 * test runs on the JVM (no Android context), so
 * this stub simulates the proot execution.
 *
 * The stub records every launch / stop / snapshot
 * operation. The E2E orchestrator asserts the stub
 * was called with the right parameters (entrypoint,
 * mounts, env).
 *
 * The stub also records the writes the simulated
 * process makes. The default writes are within the
 * authorized mount list; the E2E test can override
 * the writes to assert the audit catches
 * unauthorized writes.
 */
class ProotBackendStub {

    /**
     * The result of a launch. The stub returns
     * the writes the process made (the audit log
     * gets each write in step 9).
     */
    data class LaunchResult(
        val pid: Int,
        val exitCode: Int,
        val writes: List<String>,
    )

    /**
     * A launch invocation the test can inspect.
     */
    data class LaunchInvocation(
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

    data class StopInvocation(val sessionId: String)

    data class RestoreInvocation(val sessionId: String)

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
     * The launches the stub has recorded.
     */
    val launches: MutableList<LaunchInvocation> = mutableListOf()

    /**
     * The stops the stub has recorded.
     */
    val stops: MutableList<StopInvocation> = mutableListOf()

    /**
     * The restores the stub has recorded.
     */
    val restores: MutableList<RestoreInvocation> = mutableListOf()

    /**
     * Simulate a launch. The stub records the
     * invocation + returns the writes the process
     * made (the audit log gets each write in
     * step 9).
     */
    fun launch(
        session: WorkspaceSession,
        executable: String,
        args: List<String>,
        workingDirectory: String,
        bindMounts: List<com.elysium.vanguard.core.runtime.workspace_orchestrator.BindMount>,
        environment: Map<String, String>,
    ): Result<LaunchResult> {
        if (nextLaunchFails) {
            return Result.failure(RuntimeException("simulated launch failure"))
        }
        val invocation = LaunchInvocation(
            sessionId = session.id,
            executable = executable,
            args = args,
            workingDirectory = workingDirectory,
            bindMounts = bindMounts.map { BindMountRecord(it.hostPath, it.containerPath, it.readOnly) },
            environment = environment,
        )
        launches.add(invocation)
        return Result.success(
            LaunchResult(
                pid = nextPid,
                exitCode = 0,
                writes = nextWrites,
            ),
        )
    }

    /**
     * Simulate a stop. The stub records the
     * invocation.
     */
    fun stop(session: WorkspaceSession): Result<Unit> {
        stops.add(StopInvocation(session.id))
        return Result.success(Unit)
    }

    /**
     * Simulate a snapshot restore. The stub
     * records the invocation.
     */
    fun restoreSnapshot(session: WorkspaceSession): Result<Unit> {
        restores.add(RestoreInvocation(session.id))
        return Result.success(Unit)
    }
}
