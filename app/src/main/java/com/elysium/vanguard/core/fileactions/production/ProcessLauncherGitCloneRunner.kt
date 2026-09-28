package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.GitCloneResult
import com.elysium.vanguard.core.fileactions.handlers.GitCloneRunner
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import java.io.File

/**
 * Phase 94 — the production
 * [GitCloneRunner].
 *
 * The runner shells out to `git clone` via the
 * production [ProcessLauncher]. The clone
 * destination is the file's parent directory by
 * default; the caller can override.
 *
 * **Why a separate class?** The handler
 * (`GitCloneHandler`) is the surface that reads
 * the descriptor file + validates the URL. The
 * runner is the surface that actually invokes
 * `git`. Splitting the two lets the handler be
 * tested with a fake runner (the 6 tests in
 * [com.elysium.vanguard.core.fileactions.handlers.GitCloneHandlerTest])
 * and the runner be tested separately with a
 * fake `ProcessLauncher`.
 *
 * **JVM testability**: the runner takes a
 * [ProcessLauncher] in its constructor. Tests
 * pass a fake that records the call and returns
 * a fake `LaunchedProcess`. Production uses
 * `AndroidProcessLauncher` (Hilt-injected).
 */
class ProcessLauncherGitCloneRunner(
    private val processLauncher: ProcessLauncher,
) : GitCloneRunner {

    override suspend fun clone(url: String, destination: File): GitCloneResult {
        val exitCode = try {
            runGitClone(url, destination)
        } catch (e: Exception) {
            return GitCloneResult.Failure(
                message = "git clone failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        return if (exitCode == 0) {
            GitCloneResult.Success(
                url = url,
                destination = destination.absolutePath,
                exitCode = 0,
            )
        } else {
            GitCloneResult.Failure(
                message = "git clone exited with code $exitCode"
            )
        }
    }

    /**
     * Run `git clone <url> <destination>` via the
     * [ProcessLauncher]. The launch is sync
     * (waitFor-equivalent): the runner blocks
     * until the process exits.
     */
    private fun runGitClone(url: String, destination: File): Int {
        val cmd = listOf("git", "clone", url, destination.absolutePath)
        val launched = processLauncher.start(
            command = cmd,
            env = listOf("GIT_TERMINAL_PROMPT" to "0"),
            cwd = destination.parentFile ?: File("."),
        )
        return launched.waitFor()
    }
}
