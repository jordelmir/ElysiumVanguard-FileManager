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
 *
 * PHASE 117 — the recording launcher accepts an
 * optional `waitForExitCode` so callers can drive
 * the success / failure path the backends now
 * delegate to `LaunchedProcess.waitFor`. The default
 * is `0` (success) — the previous behavior was a
 * 60-second polling loop that always timed out
 * (returning `-1`), so the previous tests never
 * exercised the success branch. Tests that care
 * about the failure branch should pass a non-zero
 * value.
 */
internal class RecordingProcessLauncher(
    private val launchedPid: Int,
    private val waitForExitCode: Int = 0,
) : ProcessLauncher {
    val calls: MutableList<Triple<List<String>, List<Pair<String, String>>, File>> = mutableListOf()
    val waitForCalls: MutableList<Unit> = mutableListOf()

    override fun start(
        command: List<String>,
        env: List<Pair<String, String>>,
        cwd: File,
    ): LaunchedProcess {
        calls.add(Triple(command, env, cwd))
        return LaunchedProcess(
            pid = launchedPid,
            stop = {},
            waitFor = {
                waitForCalls += Unit
                waitForExitCode
            }
        )
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
