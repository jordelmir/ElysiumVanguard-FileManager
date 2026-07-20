package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 99 — the test suite for the
 * [BinaryRunnerHandler]. The handler takes a
 * `BinaryRunner` interface for each runtime
 * (AppImage + Windows); the runners are fakes
 * in tests, production uses the
 * `ProcessLauncher`-backed impls.
 */
class BinaryRunnerHandlerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `run AppImage delegates to the AppImage runner`() = runTest {
        val appImage = tmp.newFile("Blender.AppImage")
        appImage.setExecutable(true)
        val appImageRunner = RecordingBinaryRunner(
            expectedResult = BinaryRunResult.Launched(
                runtimeLabel = "AppImage",
                targetId = "debian-12",
                binaryPath = appImage.absolutePath,
            )
        )
        val windowsRunner = RecordingBinaryRunner()
        val handler = BinaryRunnerHandler(appImageRunner, windowsRunner)
        val action = FileAction.RunAppImage(
            id = "test",
            appImagePath = appImage.absolutePath,
            targetDistroId = "debian-12",
            targetDistroName = "Debian 12",
        )
        val result = handler.run(action)
        assertTrue("expected Launched, got $result", result is BinaryRunResult.Launched)
        assertEquals(1, appImageRunner.calls.size)
        assertEquals(appImage.absolutePath, appImageRunner.calls[0].first.absolutePath)
        assertEquals("debian-12", appImageRunner.calls[0].second)
        // The Windows runner should NOT have been called.
        assertEquals(0, windowsRunner.calls.size)
    }

    @Test
    fun `run Windows binary delegates to the Windows runner`() = runTest {
        val exe = tmp.newFile("setup.exe")
        exe.setExecutable(true)
        val appImageRunner = RecordingBinaryRunner()
        val windowsRunner = RecordingBinaryRunner(
            expectedResult = BinaryRunResult.Launched(
                runtimeLabel = "Windows",
                targetId = "win10",
                binaryPath = exe.absolutePath,
            )
        )
        val handler = BinaryRunnerHandler(appImageRunner, windowsRunner)
        val action = FileAction.RunWindowsBinary(
            id = "test",
            binaryPath = exe.absolutePath,
            targetVmId = "win10",
            targetVmName = "Windows 10",
        )
        val result = handler.run(action)
        assertTrue("expected Launched, got $result", result is BinaryRunResult.Launched)
        assertEquals(1, windowsRunner.calls.size)
        assertEquals(exe.absolutePath, windowsRunner.calls[0].first.absolutePath)
        assertEquals("win10", windowsRunner.calls[0].second)
        // The AppImage runner should NOT have been called.
        assertEquals(0, appImageRunner.calls.size)
    }

    @Test
    fun `run AppImage with missing file returns Failure`() = runTest {
        val runner = RecordingBinaryRunner()
        val handler = BinaryRunnerHandler(runner, runner)
        val action = FileAction.RunAppImage(
            id = "test",
            appImagePath = File(tmp.root, "missing.AppImage").absolutePath,
            targetDistroId = "debian-12",
            targetDistroName = "Debian 12",
        )
        val result = handler.run(action)
        assertTrue("expected Failure, got $result", result is BinaryRunResult.Failure)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `run AppImage with non-executable file returns Failure`() = runTest {
        val appImage = tmp.newFile("no-exec.AppImage")
        appImage.writeText("not executable")
        // Do not call setExecutable — file is not +x.
        val runner = RecordingBinaryRunner()
        val handler = BinaryRunnerHandler(runner, runner)
        val action = FileAction.RunAppImage(
            id = "test",
            appImagePath = appImage.absolutePath,
            targetDistroId = "debian-12",
            targetDistroName = "Debian 12",
        )
        val result = handler.run(action)
        assertTrue("expected Failure, got $result", result is BinaryRunResult.Failure)
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `run Windows binary with missing file returns Failure`() = runTest {
        val runner = RecordingBinaryRunner()
        val handler = BinaryRunnerHandler(runner, runner)
        val action = FileAction.RunWindowsBinary(
            id = "test",
            binaryPath = File(tmp.root, "missing.exe").absolutePath,
            targetVmId = "win10",
            targetVmName = "Windows 10",
        )
        val result = handler.run(action)
        assertTrue(result is BinaryRunResult.Failure)
    }

    @Test
    fun `run propagates the runner's Failure message`() = runTest {
        val appImage = tmp.newFile("Blender.AppImage")
        appImage.setExecutable(true)
        val runner = RecordingBinaryRunner(
            expectedResult = BinaryRunResult.Failure(
                message = "AppImage is not a FUSE-capable binary"
            )
        )
        val handler = BinaryRunnerHandler(runner, runner)
        val action = FileAction.RunAppImage(
            id = "test",
            appImagePath = appImage.absolutePath,
            targetDistroId = "debian-12",
            targetDistroName = "Debian 12",
        )
        val result = handler.run(action)
        assertTrue(result is BinaryRunResult.Failure)
        assertEquals(
            "AppImage is not a FUSE-capable binary",
            (result as BinaryRunResult.Failure).message
        )
    }
}

private class RecordingBinaryRunner(
    private val expectedResult: BinaryRunResult = BinaryRunResult.Failure(
        message = "no test result configured"
    ),
) : BinaryRunner {
    val calls: MutableList<Triple<File, String, String>> = mutableListOf()

    override suspend fun run(
        binary: File,
        targetId: String,
        runtimeLabel: String,
    ): BinaryRunResult {
        calls.add(Triple(binary, targetId, runtimeLabel))
        return expectedResult
    }
}
