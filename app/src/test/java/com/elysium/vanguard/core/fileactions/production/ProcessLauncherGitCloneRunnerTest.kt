package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.GitCloneResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 94 — the test suite for the
 * [ProcessLauncherGitCloneRunner]. The runner
 * is a thin shell over the [ProcessLauncher];
 * the tests use a fake launcher that records
 * the call and returns a stub [LaunchedProcess].
 *
 * The runner's `waitFor` is a polling loop
 * (the production `ProcessLauncher` does not
 * expose `waitFor()` until Phase 95+). The
 * tests use a launcher that returns
 * `pid = -1` to short-circuit the wait loop
 * (the runner interprets `pid <= 0` as
 * "process exited" + returns exit code 0).
 */
class ProcessLauncherGitCloneRunnerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `clone launches git clone with the URL and destination`() = runTest {
        val dest = tmp.newFolder("repo")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val runner = ProcessLauncherGitCloneRunner(launcher)
        val result = runner.clone("https://github.com/elysium-vanguard/repo.git", dest)
        assertTrue("expected Success, got $result", result is GitCloneResult.Success)
        assertEquals(1, launcher.calls.size)
        val cmd = launcher.calls[0].first
        assertEquals("git", cmd[0])
        assertEquals("clone", cmd[1])
        assertEquals("https://github.com/elysium-vanguard/repo.git", cmd[2])
        assertEquals(dest.absolutePath, cmd[3])
    }

    @Test
    fun `clone returns Failure when the launcher throws`() = runTest {
        val launcher = ThrowingProcessLauncher(IllegalStateException("spawn failed"))
        val runner = ProcessLauncherGitCloneRunner(launcher)
        val result = runner.clone("https://github.com/foo/bar.git", tmp.root)
        assertTrue(result is GitCloneResult.Failure)
        assertTrue(
            "error must mention the spawn failure: ${(result as GitCloneResult.Failure).message}",
            (result).message.contains("spawn failed")
        )
    }

    @Test
    fun `clone sets GIT_TERMINAL_PROMPT=0 to avoid interactive prompts`() = runTest {
        val dest = tmp.newFolder("repo")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val runner = ProcessLauncherGitCloneRunner(launcher)
        runner.clone("https://github.com/foo/bar.git", dest)
        val env = launcher.calls[0].second
        assertTrue(
            "env must include GIT_TERMINAL_PROMPT=0; got $env",
            env.any { it.first == "GIT_TERMINAL_PROMPT" && it.second == "0" }
        )
    }
}
