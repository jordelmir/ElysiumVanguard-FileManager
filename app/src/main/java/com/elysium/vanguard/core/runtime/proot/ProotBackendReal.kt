package com.elysium.vanguard.core.runtime.proot

import com.elysium.vanguard.core.runtime.runner.DistroSessionBackend
import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.workspace_orchestrator.BindMount
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 71 — the **production** [ProotBackend] implementation.
 *
 * The [ProotBackendReal] wraps the existing
 * `LinuxProotSessionRunner` (Phase 30) seam and translates
 * the orchestrator's typed contract (the [BindMount] list
 * + the [LaunchCommand] + the environment map) into a
 * proot invocation on the device.
 *
 * Translation steps (per `launch`):
 *
 *   1. Look up the distro installation + the launcher pick
 *      via the [DistroSessionBackend] (Phase 30 seam).
 *   2. Build the script the launcher will execute: `cd
 *      <workingDirectory> && <executable> <args>`.
 *   3. Call `launcher.buildShellCommand(rootfsDir, script)`
 *      to get the base proot command. The launcher
 *      contributes its standard flags (`-r <rootfs>`,
 *      `-b /dev /proc /sys`, `-w /root`, env setup).
 *   4. Inject the orchestrator's [bindMounts] as proot
 *      `-b host:container` flags, placed after the `-r
 *      <rootfs>` pair. The orchestrator's mounts are
 *      the runtime's mount allowlist.
 *   5. Merge the launcher's environment variables with
 *      the orchestrator's environment map. The
 *      orchestrator's env wins (the user's intent
 *      overrides the launcher's defaults).
 *   6. Spawn the process via the [ProcessLauncher] and
 *      remember the [LaunchedProcess] handle so `stop`
 *      can find it.
 *
 * The `writes` list on the returned [ProotBackend.LaunchResult]
 * is empty for now. Phase 72 wires a `FileObserver` so
 * the real device captures the actual writes; the
 * `CriticalE2EOrchestrator`'s audit step is exercised
 * by the JVM-side test (which uses [com.elysium.vanguard.core.runtime.critical_e2e.InMemoryProotBackend]
 * with a controlled writes list).
 *
 * Thread-safety: the `handles` + `rootfsBySession` maps
 * are `ConcurrentHashMap`s; the [ProcessLauncher] is
 * assumed thread-safe (it is on Android — `ProcessBuilder`
 * is internally synchronized; the test impl is a
 * 5-line hand-rolled stub).
 */
class ProotBackendReal(
    private val backend: DistroSessionBackend,
    private val processLauncher: ProcessLauncher,
    private val workspaceManager: WorkspaceManager,
) : ProotBackend {

    private data class SessionKey(val workspaceId: String, val sessionId: String)

    private val handles = ConcurrentHashMap<SessionKey, LaunchedProcess>()
    private val rootfsBySession = ConcurrentHashMap<SessionKey, File>()

    /**
     * Phase 71 — the [launch] translation. See the class
     * doc for the full step list. Returns
     * [ProotBackend.LaunchResult] with the spawned pid;
     * the `writes` list is empty (Phase 72 wires
     * `FileObserver`).
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
        // Step 1: only LinuxProot sessions are supported.
        val linuxProot = session as? WorkspaceSession.LinuxProot
            ?: return Result.failure(
                IllegalStateException(
                    "ProotBackendReal: only LinuxProot sessions are supported, " +
                        "got ${session.kind}"
                )
            )
        val pick = backend.launcherFor(linuxProot.distroId)
            ?: return Result.failure(
                IllegalStateException(
                    "ProotBackendReal: no launcher available for distro " +
                        "'${linuxProot.distroId}' (is it installed?)"
                )
            )
        val installation = backend.findInstalled(linuxProot.distroId)
            ?: return Result.failure(
                IllegalStateException(
                    "ProotBackendReal: distro '${linuxProot.distroId}' is " +
                        "not installed"
                )
            )
        val rootfsDir = installation.rootfsDir

        // Step 2: build the script. The launcher will run
        // `sh -lc "<script>"` inside the rootfs.
        val script = buildScript(executable, args, workingDirectory)

        // Step 3 + 4: build the proot command (with our
        // bindMounts injected).
        val baseCommand = pick.launcher.buildShellCommand(rootfsDir, script)
        val command = injectE2EBindMounts(baseCommand, bindMounts, rootfsDir)

        // Step 5: merge env. Orchestrator's env wins.
        val launcherEnv = pick.launcher.environmentVariables(rootfsDir)
        val mergedEnv = mergeEnvironment(launcherEnv, environment)

        // Step 6: spawn.
        val key = SessionKey(workspaceId, session.id)
        val launched: LaunchedProcess = try {
            processLauncher.start(command = command, env = mergedEnv, cwd = rootfsDir)
        } catch (io: IOException) {
            return Result.failure(
                IllegalStateException(
                    "ProotBackendReal: failed to spawn process: " +
                        (io.message ?: io::class.java.simpleName)
                )
            )
        }
        handles[key] = launched
        rootfsBySession[key] = rootfsDir

        return Result.success(
            ProotBackend.LaunchResult(
                pid = launched.pid,
                exitCode = 0,
                writes = emptyList(),
            )
        )
    }

    /**
     * Stop the process. The handle is removed
     * atomically (so a concurrent `stop` sees no
     * handle and is a no-op).
     */
    override fun stop(workspaceId: String, session: WorkspaceSession): Result<Unit> {
        val key = SessionKey(workspaceId, session.id)
        val handle = handles.remove(key) ?: return Result.success(Unit)
        handle.stop.invoke()
        return Result.success(Unit)
    }

    /**
     * Restore the workspace's rootfs to the most
     * recent snapshot. The manager is the orchestrator
     * for the snapshot engine; the backend delegates
     * to it.
     */
    override fun restoreSnapshot(workspaceId: String, session: WorkspaceSession): Result<Unit> {
        val key = SessionKey(workspaceId, session.id)
        val rootfsDir = rootfsBySession[key]
            ?: return Result.failure(
                IllegalStateException(
                    "ProotBackendReal: no rootfs known for session " +
                        "${session.id} in workspace $workspaceId (was launch called?)"
                )
            )
        val snapshots = workspaceManager.listSnapshots(workspaceId)
        if (snapshots.isEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "ProotBackendReal: no snapshots to restore for " +
                        "workspace $workspaceId"
                )
            )
        }
        // Restore the most recent snapshot (last in the
        // manager's ascending-by-createdAtMs list).
        val latest = snapshots.last()
        val result = workspaceManager.rollbackWorkspace(
            workspaceId = workspaceId,
            snapshotId = latest.id,
            liveRootfsPath = rootfsDir.absolutePath,
        )
        return result.map { }
    }

    // ----------------------------------------------------------------
    // Translation helpers (visible to tests via the public API).
    // ----------------------------------------------------------------

    /**
     * Build the shell script the launcher executes.
     * The script is `cd <cwd> && <executable> <args>`,
     * with single-quoted args (preserving spaces +
     * special chars).
     */
    private fun buildScript(executable: String, args: List<String>, workingDirectory: String): String =
        buildString {
            append("cd ").append(workingDirectory).append(" && ")
            append(executable)
            for (arg in args) {
                append(" '").append(arg.replace("'", "'\\''")).append("'")
            }
        }

    /**
     * Inject the orchestrator's [bindMounts] as proot
     * `-b host:container` flags. The mount flags go
     * after the `-r <rootfs>` pair (where the
     * launcher's own `-b` flags already live).
     *
     * The proot command structure is
     * `<proot> --kill-on-exit --link2symlink ... -r
     * <rootfs> -b /dev -b /proc -b /sys ... -w /root
     * /usr/bin/env ... /bin/sh -lc <script>`. Inserting
     * after `-r <rootfs>` keeps our mounts alongside
     * the launcher's standard mounts.
     *
     * Read-only mounts are inserted as `-b
     * host:container` (proot does not have a
     * read-only flag; the mount policy is enforced at
     * the `MountPolicyEnforcer` layer).
     */
    private fun injectE2EBindMounts(
        command: List<String>,
        bindMounts: List<BindMount>,
        rootfsDir: File,
    ): List<String> {
        if (bindMounts.isEmpty()) return command
        val mountArgs = bindMounts.flatMap { mount ->
            listOf("-b", "${mount.hostPath}:${mount.containerPath}")
        }
        val rootfsAbs = rootfsDir.absolutePath
        val rIndex = command.indexOf("-r")
        // The proot command's last entries are
        // `... /bin/sh -lc <script>`. We want to
        // insert the mounts after `-r <rootfs>` and
        // before the env + script setup.
        val insertionIndex = if (rIndex >= 0 && rIndex + 1 < command.size && command[rIndex + 1] == rootfsAbs) {
            rIndex + 2
        } else {
            // No `-r <rootfs>` pair found. Insert
            // before the last 2 entries (`/bin/sh`
            // + the script).
            (command.size - 2).coerceAtLeast(0)
        }
        return command.subList(0, insertionIndex) +
            mountArgs +
            command.subList(insertionIndex, command.size)
    }

    /**
     * Merge the launcher's environment with the
     * orchestrator's environment. The orchestrator's
     * values win (the user's intent overrides the
     * launcher's defaults).
     */
    private fun mergeEnvironment(
        launcherEnv: List<Pair<String, String>>,
        orchestratorEnv: Map<String, String>,
    ): List<Pair<String, String>> {
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in launcherEnv) merged[k] = v
        for ((k, v) in orchestratorEnv) merged[k] = v
        return merged.toList()
    }
}
