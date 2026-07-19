package com.elysium.vanguard.core.runtime.agent

import com.elysium.vanguard.core.runtime.build.LocalBuildRunner
import com.elysium.vanguard.core.runtime.build.ToolchainRegistry
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.snapshots.MountPlan
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 73 — the production implementation of
 * [AgentCollaborators].
 *
 * Until Phase 73 the rule-based Vanguard AI had a
 * fully-typed contract ([AgentCollaborators] in
 * [PlanExecutor]) and unit tests for the executor
 * + parser, but **no production collaborator**
 * existed. The collaborators' six methods are the
 * bridge from the agent's typed plans to the
 * platform's actual subsystems:
 *
 *   - `installDistro` → [DistroManager] (the
 *     runtime's distro installer; ships the
 *     proot-rootfs + the Elysium overlay).
 *   - `createWindowsEnvironment` → reserved for
 *     Phase 74 (the [com.elysium.vanguard.core.runtime.windows.WindowsVmManager]
 *     needs an `installFromBinary` seam that
 *     doesn't exist yet).
 *   - `createSnapshot` → [WorkspaceManager.snapshotWorkspace]
 *     (Phase 49; needs the workspace's rootfs
 *     path, which the impl derives from the
 *     workspace's first LinuxProot session).
 *   - `rollbackToSnapshot` → [WorkspaceManager.rollbackWorkspace]
 *     (Phase 49).
 *   - `runBuild` → [LocalBuildRunner.build]
 *     (Phase 56 — the build runner that the
 *     LocalBuildRunner is the production seam
 *     for; the agent's "build rust" plan
 *     delegates to it).
 *   - `runCommand` → [ProcessLauncher.start]
 *     (Phase 36 — the runner that spawns host
 *     processes; the agent's "run command"
 *     plan delegates to it).
 *
 * ## Threading
 *
 * The [AgentCollaborators] interface is
 * synchronous. The agent's [PlanExecutor] runs on
 * the thread the caller passes it (typically
 * `Dispatchers.IO` from the
 * [com.elysium.vanguard.features.agent.LocalAgentViewModel]).
 * The blocking work (the
 * [DistroManager.installBlocking] download +
 * extract, the [ProcessLauncher.start] fork) is
 * the caller's responsibility to dispatch off
 * the main thread; the collaborators do not
 * switch threads themselves.
 *
 * ## Failure model
 *
 * Every method returns a typed [AgentStepResult]:
 * - [AgentStepResult.Success] carries a
 *   human-readable confirmation message (the
 *   executor publishes it on the runtime event
 *   bus).
 * - [AgentStepResult.Failure] carries the error
 *   message; the executor rolls back the
 *   workspace to the pre-execution snapshot and
 *   surfaces the failure to the UI.
 *
 * No method throws. Exceptions are caught at
 * the boundary and converted to typed failures
 * (the executor's `when (result)` branches on
 * the variant, not on a thrown exception).
 */
@Singleton
class RealAgentCollaborators @Inject constructor(
    private val distroManager: DistroManager,
    private val workspaceManager: WorkspaceManager,
    private val localBuildRunner: LocalBuildRunner,
    private val toolchainRegistry: ToolchainRegistry,
    private val processLauncher: ProcessLauncher,
) : AgentCollaborators {

    /**
     * Install a distro by id. Delegates to
     * [DistroManager.installBlocking], which
     * downloads + extracts + verifies + applies
     * the Elysium overlay + atomically
     * activates the rootfs.
     *
     * The distro id must match an entry in the
     * [com.elysium.vanguard.core.runtime.distros.DistroCatalog]
     * (Phase 9.6.2). Unknown ids fail with a
     * typed [AgentStepResult.Failure].
     */
    override fun installDistro(distroId: String): AgentStepResult {
        val result = distroManager.installBlocking(distroId)
        return result.fold(
            onSuccess = { rootfs ->
                AgentStepResult.Success(
                    "Installed '$distroId' at ${rootfs.absolutePath}"
                )
            },
            onFailure = { error ->
                AgentStepResult.Failure(
                    error.message ?: "install '$distroId' failed"
                )
            },
        )
    }

    /**
     * Phase 73 — stub. The Windows environment
     * creation seam
     * ([com.elysium.vanguard.core.runtime.windows.WindowsVmManager])
     * doesn't yet expose a `createEnvironment(binaryPath, runtimeKind)`
     * method; the master vision's "create
     * windows" plan is wired into the parser +
     * executor, but the production install path
     * is Phase 74 work. The method returns a
     * typed failure so the executor's audit log
     * records "windows environment not yet wired"
     * instead of silently succeeding.
     */
    override fun createWindowsEnvironment(
        binaryPath: String,
        runtimeKind: String
    ): AgentStepResult {
        return AgentStepResult.Failure(
            "Windows environment creation is not yet wired in the rule-based " +
                "agent (Phase 74). binary='$binaryPath' runtime='$runtimeKind'. " +
                "Use the HTTP-gateway Command Core (Phase 58) or the runtime's " +
                "manual Windows VM flow for now."
        )
    }

    /**
     * Capture a snapshot of the workspace's
     * live rootfs. The impl resolves the
     * workspace's first LinuxProot session to
     * find the rootfs path (the orchestrator's
     * `sourceRootfsPath` argument to
     * [WorkspaceManager.snapshotWorkspace]).
     */
    override fun createSnapshot(workspaceId: String, label: String): AgentStepResult {
        val workspace = findWorkspace(workspaceId)
            ?: return AgentStepResult.Failure("workspace not found: $workspaceId")
        val rootfsPath = resolveRootfsPath(workspace)
            ?: return AgentStepResult.Failure(
                "workspace '$workspaceId' has no LinuxProot session; cannot resolve rootfs"
            )
        val result = workspaceManager.snapshotWorkspace(
            workspaceId = workspaceId,
            sourceRootfsPath = rootfsPath,
            mountPlan = MountPlan.EMPTY,
            label = label,
        )
        return result.fold(
            onSuccess = { snap ->
                AgentStepResult.Success(
                    "Snapshot '${snap.id}' captured for workspace '$workspaceId' (label='$label')"
                )
            },
            onFailure = { error ->
                AgentStepResult.Failure(
                    error.message ?: "snapshot failed for workspace '$workspaceId'"
                )
            },
        )
    }

    /**
     * Restore a workspace to a previous snapshot.
     * The impl resolves the workspace's
     * LinuxProot session rootfs and delegates
     * to [WorkspaceManager.rollbackWorkspace].
     */
    override fun rollbackToSnapshot(
        workspaceId: String,
        snapshotId: String
    ): AgentStepResult {
        val workspace = findWorkspace(workspaceId)
            ?: return AgentStepResult.Failure("workspace not found: $workspaceId")
        val rootfsPath = resolveRootfsPath(workspace)
            ?: return AgentStepResult.Failure(
                "workspace '$workspaceId' has no LinuxProot session; cannot resolve rootfs"
            )
        val result = workspaceManager.rollbackWorkspace(
            workspaceId = workspaceId,
            snapshotId = snapshotId,
            liveRootfsPath = rootfsPath,
        )
        return result.fold(
            onSuccess = {
                AgentStepResult.Success(
                    "Workspace '$workspaceId' rolled back to snapshot '$snapshotId'"
                )
            },
            onFailure = { error ->
                AgentStepResult.Failure(
                    error.message ?: "rollback failed for workspace '$workspaceId'"
                )
            },
        )
    }

    /**
     * Run a local build via
     * [LocalBuildRunner.build]. The agent's
     * `toolchainKind` is parsed against
     * [com.elysium.vanguard.core.runtime.build.ToolchainKind]
     * (the [LocalBuildRunner] is the production
     * seam; the runner returns a typed
     * [com.elysium.vanguard.core.runtime.build.BuildResult]
     * that the agent surfaces).
     */
    override fun runBuild(
        toolchainKind: String,
        command: List<String>
    ): AgentStepResult {
        val kind = parseToolchainKind(toolchainKind)
            ?: return AgentStepResult.Failure(
                "unknown toolchain kind: '$toolchainKind' " +
                    "(supported: RUST, GRADLE, NODE, GO, MAKE, NPM)"
            )
        val request = com.elysium.vanguard.core.runtime.build.BuildRequest(
            projectPath = java.io.File("."),
            kind = kind,
            command = command,
        )
        val result = localBuildRunner.build(request, toolchainRegistry)
        return result.fold(
            onSuccess = { buildResult ->
                val summary = buildResult.stdout.ifBlank { "<no stdout>" }.take(200)
                AgentStepResult.Success(
                    "Build '$toolchainKind' exited ${buildResult.exitCode} " +
                        "in ${buildResult.durationMs}ms: $summary"
                )
            },
            onFailure = { error ->
                AgentStepResult.Failure(
                    error.message ?: "build '$toolchainKind' failed"
                )
            },
        )
    }

    /**
     * Run a generic command via
     * [ProcessLauncher.start]. The agent's
     * `run command` plan is the most powerful
     * (and most dangerous) primitive; the
     * executor marks the plan HIGH-risk and
     * requires user confirmation (the
     * [PlanExecutor.execute] gate).
     */
    override fun runCommand(
        command: List<String>,
        workingDirectory: String?
    ): AgentStepResult {
        if (command.isEmpty()) {
            return AgentStepResult.Failure("command list must not be empty")
        }
        val cwd = workingDirectory?.let { java.io.File(it) } ?: java.io.File(".")
        val launched = try {
            processLauncher.start(
                command = command,
                env = emptyList(),
                cwd = cwd,
            )
        } catch (io: java.io.IOException) {
            return AgentStepResult.Failure(
                "failed to spawn '${command.first()}': ${io.message ?: io::class.java.simpleName}"
            )
        } catch (other: RuntimeException) {
            // Defensive: a stub or a misbehaving
            // launcher might wrap a real
            // spawn failure in a
            // `RuntimeException`. The agent must
            // never let an unhandled exception
            // escape — a failed spawn is an
            // [AgentStepResult.Failure] the
            // executor can roll back from.
            return AgentStepResult.Failure(
                "failed to spawn '${command.first()}': ${other.message ?: other::class.java.simpleName}"
            )
        }
        // NOTE: the agent's "run command" plan is
        // fire-and-forget at the collaborator
        // boundary. The process is spawned, the
        // pid is returned, and the executor moves
        // to the next action. A future phase
        // tracks the launched process's lifetime
        // so the executor can stop it on
        // failure-driven rollback.
        return AgentStepResult.Success(
            "Spawned '${command.first()}' (pid=${launched.pid})"
        )
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private fun findWorkspace(workspaceId: String): Workspace? =
        workspaceManager.listWorkspaces().firstOrNull { it.id == workspaceId }

    /**
     * Resolve a workspace's rootfs path by
     * looking up the first `LinuxProot` session
     * in the workspace's sessions, then asking
     * [DistroManager] for the installed
     * distro's rootfs directory. A workspace
     * without a LinuxProot session has no
     * rootfs to snapshot/rollback — the
     * [PlanExecutor] surfaces a typed failure.
     */
    private fun resolveRootfsPath(workspace: Workspace): String? {
        val linuxSession = workspace.sessions.firstOrNull { it is WorkspaceSession.LinuxProot }
            as? WorkspaceSession.LinuxProot ?: return null
        return distroManager.findInstalled(linuxSession.distroId)?.rootfsDir?.absolutePath
    }

    private fun parseToolchainKind(
        toolchainKind: String
    ): com.elysium.vanguard.core.runtime.build.ToolchainKind? = try {
        com.elysium.vanguard.core.runtime.build.ToolchainKind.valueOf(toolchainKind.uppercase())
    } catch (_: IllegalArgumentException) {
        null
    }
}
