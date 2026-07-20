package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.runtime.runner.LaunchedProcess
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import java.io.File

/**
 * Phase 94 — the shared [ProcessLauncher] fakes
 * for the production-impl test suites. Both
 * [ProcessLauncherGitCloneRunnerTest] and
 * [ProcessLauncherDiskImageBackendTest] need
 * a launcher that records the call + returns a
 * stub [LaunchedProcess]; the fakes live here
 * to avoid redeclaration errors.
 */

internal class RecordingProcessLauncher(
    private val launchedPid: Int,
) : ProcessLauncher {
    val calls: MutableList<Triple<List<String>, List<Pair<String, String>>, File>> = mutableListOf()

    override fun start(
        command: List<String>,
        env: List<Pair<String, String>>,
        cwd: File,
    ): LaunchedProcess {
        calls.add(Triple(command, env, cwd))
        return LaunchedProcess(pid = launchedPid, stop = {})
    }
}

internal class ThrowingProcessLauncher(
    private val failure: Throwable,
) : ProcessLauncher {
    override fun start(
        command: List<String>,
        env: List<Pair<String, String>>,
        cwd: File,
    ): LaunchedProcess = throw failure
}
