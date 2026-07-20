package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 93 — the test suite for the
 * [GitCloneHandler]. The handler reads the URL
 * from the descriptor file body, validates it,
 * and delegates the actual `git clone` to the
 * [GitCloneRunner]. The runner is a fake in
 * tests; production wraps the [ProcessLauncher].
 */
class GitCloneHandlerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `clone reads the URL from the first non-blank line`() = runTest {
        val descriptor = tmp.newFile("repo.git")
        descriptor.writeText(
            "# comment line\n" +
                "\n" +
                "https://github.com/elysium-vanguard/repo.git\n"
        )
        val runner = RecordingGitCloneRunner()
        val handler = GitCloneHandler(runner)
        val action = FileAction.GitClone(
            id = "test",
            repoUrl = descriptor.absolutePath,
            destinationDir = tmp.root.absolutePath,
        )
        val result = handler.clone(action)
        assertTrue("expected Success, got $result", result is GitCloneResult.Success)
        val success = result as GitCloneResult.Success
        assertEquals("https://github.com/elysium-vanguard/repo.git", success.url)
        assertEquals(1, runner.calls.size)
        assertEquals("https://github.com/elysium-vanguard/repo.git", runner.calls[0].first)
    }

    @Test
    fun `clone rejects descriptor with no URL`() = runTest {
        val descriptor = tmp.newFile("empty.git")
        descriptor.writeText("# only comments\n# no URL\n")
        val runner = RecordingGitCloneRunner()
        val handler = GitCloneHandler(runner)
        val result = handler.clone(
            FileAction.GitClone(
                id = "test",
                repoUrl = descriptor.absolutePath,
                destinationDir = tmp.root.absolutePath,
            )
        )
        assertTrue(result is GitCloneResult.InvalidDescriptor)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `clone rejects non-URL content`() = runTest {
        val descriptor = tmp.newFile("bad.git")
        descriptor.writeText("not a url at all")
        val runner = RecordingGitCloneRunner()
        val handler = GitCloneHandler(runner)
        val result = handler.clone(
            FileAction.GitClone(
                id = "test",
                repoUrl = descriptor.absolutePath,
                destinationDir = tmp.root.absolutePath,
            )
        )
        assertTrue(result is GitCloneResult.InvalidDescriptor)
    }

    @Test
    fun `clone accepts https URLs`() = runTest {
        val descriptor = tmp.newFile("repo.git")
        descriptor.writeText("https://github.com/foo/bar.git")
        val runner = RecordingGitCloneRunner()
        val handler = GitCloneHandler(runner)
        val result = handler.clone(
            FileAction.GitClone(
                id = "test",
                repoUrl = descriptor.absolutePath,
                destinationDir = tmp.root.absolutePath,
            )
        )
        assertTrue(result is GitCloneResult.Success)
    }

    @Test
    fun `clone accepts ssh git URLs`() = runTest {
        val descriptor = tmp.newFile("repo.git")
        descriptor.writeText("git@github.com:foo/bar.git")
        val runner = RecordingGitCloneRunner()
        val handler = GitCloneHandler(runner)
        val result = handler.clone(
            FileAction.GitClone(
                id = "test",
                repoUrl = descriptor.absolutePath,
                destinationDir = tmp.root.absolutePath,
            )
        )
        assertTrue(result is GitCloneResult.Success)
    }

    @Test
    fun `clone creates the destination directory if missing`() = runTest {
        val descriptor = tmp.newFile("repo.git")
        descriptor.writeText("https://github.com/foo/bar.git")
        val nestedDest = File(tmp.root, "a/b/c")
        assertFalse(nestedDest.exists())
        val runner = RecordingGitCloneRunner()
        val handler = GitCloneHandler(runner)
        val result = handler.clone(
            FileAction.GitClone(
                id = "test",
                repoUrl = descriptor.absolutePath,
                destinationDir = nestedDest.absolutePath,
            )
        )
        assertTrue(result is GitCloneResult.Success)
        assertTrue(nestedDest.isDirectory)
    }
}

private class RecordingGitCloneRunner : GitCloneRunner {
    val calls: MutableList<Pair<String, File>> = mutableListOf()

    override suspend fun clone(url: String, destination: File): GitCloneResult {
        calls.add(url to destination)
        return GitCloneResult.Success(
            url = url,
            destination = destination.absolutePath,
            exitCode = 0,
        )
    }
}
