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
 *   7. (Phase 72) Start a [WriteCapture] watching the
 *      bind-mounted host paths BEFORE the spawn. The
 *      capture records every file create / modify /
 *      move-in / close-write to those paths. The
 *      orchestrator reads the capture after `stop` (via
 *      [writes]) to populate the audit log; step 9 of
 *      the E2E then asserts every write is within an
 *      authorized mount.
 *
 * The `writes` list on the returned [ProotBackend.LaunchResult]
 * is a snapshot at the moment the spawn returned (typically
 * empty — the proot process hasn't done any I/O yet). The
 * orchestrator reads the **final** writes via [writes] after
 * `stop` and before `restoreSnapshot` (see
 * `CriticalE2EOrchestrator.run`).
 *
 * Thread-safety: the `handles` + `rootfsBySession` maps
 * are `ConcurrentHashMap`s; the [ProcessLauncher] is
 * assumed thread-safe (it is on Android — `ProcessBuilder`
 * is internally synchronized; the test impl is a
 * 5-line hand-rolled stub). The [WriteCapture] is shared
 * across sequential sessions of the same backend; the
 * capture resets on each [launch] via `start(watching)`,
 * which clears the previous watch set + writes list.
 */
class ProotBackendReal(
    private val backend: DistroSessionBackend,
    private val processLauncher: ProcessLauncher,
    private val workspaceManager: WorkspaceManager,
    /**
     * Phase 72 — the write capture (defaults to an
     * in-memory recorder; production wires
     * [AndroidFileObserverWriteCapture] via Hilt). The
     * capture is the audit half of the master vision's
     * Definition of Done.
     */
    private val writeCapture: WriteCapture = InMemoryWriteCapture(),
) : ProotBackend {

    private data class SessionKey(val workspaceId: String, val sessionId: String)

    private val handles = ConcurrentHashMap<SessionKey, LaunchedProcess>()
    private val rootfsBySession = ConcurrentHashMap<SessionKey, File>()

    /**
     * Phase 71/72 — the [launch] translation. See the class
     * doc for the full step list. Returns
     * [ProotBackend.LaunchResult] with the spawned pid;
     * the `writes` field is a snapshot of the capture
     * at the moment the spawn returned (typically
     * empty). The orchestrator reads the **final**
     * writes via [writes] after `stop`.
     *
     * Phase 72 addition: the bind-mounted host paths
     * are passed to [WriteCapture.start] BEFORE the
     * spawn, so the capture is live before the proot
     * process does any I/O.
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

        // Step 6.5 (Phase 72): start the write capture
        // BEFORE the spawn, watching every host path the
        // orchestrator authorized. The capture clears
        // any previous watch set + writes list.
        val watchedHostPaths = bindMounts.map { it.hostPath }.toSet()
        writeCapture.start(watchedHostPaths)

        // Step 7: spawn.
        val key = SessionKey(workspaceId, session.id)
        val launched: LaunchedProcess = try {
            processLauncher.start(command = command, env = mergedEnv, cwd = rootfsDir)
        } catch (io: IOException) {
            // The capture was started but the spawn
            // failed — stop it so the next launch
            // starts clean.
            writeCapture.stop()
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
                // Snapshot at spawn time; typically empty.
                // The orchestrator reads the final list
                // via [writes] after `stop`.
                writes = writeCapture.writes(),
            )
        )
    }

    /**
     * Stop the process. The handle is removed
     * atomically (so a concurrent `stop` sees no
     * handle and is a no-op).
     *
     * Phase 72: the [WriteCapture] is **not** stopped
     * here. The orchestrator calls [writes] next to
     * read the captured writes; [restoreSnapshot] is
     * the "session is fully over" signal that stops
     * the capture.
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
     *
     * Phase 72: the [WriteCapture] is stopped here
     * (the session is fully over — the orchestrator
     * has already read the writes via [writes]).
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
        // Stop the capture (session is over). Wrapped in
        // a try/finally-style: even if rollback failed,
        // we still want the capture stopped.
        writeCapture.stop()
        return result.map { }
    }

    /**
     * Phase 72 — return the writes the capture
     * recorded during the current session. Called
     * by the orchestrator after [stop] and before
     * [restoreSnapshot] to populate the audit log.
     *
     * If no [launch] was called, the capture was
     * never started, so the returned list is empty.
     * If [restoreSnapshot] was called, the capture
     * is stopped, but the captured writes are
     * preserved (the [WriteCapture] contract is
     * "stop preserves the captured list").
     */
    override fun writes(workspaceId: String, session: WorkspaceSession): List<String> {
        return writeCapture.writes()
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
