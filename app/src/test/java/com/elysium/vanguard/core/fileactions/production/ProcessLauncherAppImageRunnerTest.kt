package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.BinaryRunResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 99 — the test suite for the
 * [ProcessLauncherAppImageRunner]. The runner
 * shells out to `proot` via the production
 * `ProcessLauncher`; the tests use a fake
 * launcher that records the call.
 */
class ProcessLauncherAppImageRunnerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `run spawns proot with the distro rootfs and bind mounts dev fuse`() = runTest {
        val appImage = tmp.newFile("Blender.AppImage")
        appImage.setExecutable(true)
        val rootfs = tmp.newFolder("debian-12", "rootfs")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val runner = ProcessLauncherAppImageRunner(
            processLauncher = launcher,
            resolveRootfs = { rootfs },
        )
        val result = runner.run(
            binary = appImage,
            targetId = "debian-12",
            runtimeLabel = "AppImage",
        )
        assertTrue("expected Launched, got $result", result is BinaryRunResult.Launched)
        val cmd = launcher.calls[0].first
        assertEquals("proot", cmd[0])
        assertEquals("--link2symlink", cmd[1])
        assertEquals("-r", cmd[2])
        assertEquals(rootfs.absolutePath, cmd[3])
        assertTrue("must bind-mount /dev/fuse: $cmd", cmd.contains("/dev/fuse"))
        assertTrue("must bind-mount /dev/null: $cmd", cmd.contains("/dev/null"))
        // The AppImage path is the last arg.
        assertEquals(appImage.absolutePath, cmd.last())
        // APPIMAGE_EXTRACT_AND_RUN is set in the env.
        val env = launcher.calls[0].second
        assertTrue(
            "env must set APPIMAGE_EXTRACT_AND_RUN=1; got $env",
            env.any { it.first == "APPIMAGE_EXTRACT_AND_RUN" && it.second == "1" }
        )
    }

    @Test
    fun `run returns Failure when the distro is not installed`() = runTest {
        val appImage = tmp.newFile("Blender.AppImage")
        appImage.setExecutable(true)
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val runner = ProcessLauncherAppImageRunner(
            processLauncher = launcher,
            resolveRootfs = { null }, // not installed
        )
        val result = runner.run(
            binary = appImage,
            targetId = "missing",
            runtimeLabel = "AppImage",
        )
        assertTrue("expected Failure, got $result", result is BinaryRunResult.Failure)
        assertEquals(0, launcher.calls.size)
    }

    @Test
    fun `run returns Failure when the rootfs path is not a directory`() = runTest {
        val appImage = tmp.newFile("Blender.AppImage")
        appImage.setExecutable(true)
        val notADir = tmp.newFile("not-a-dir")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val runner = ProcessLauncherAppImageRunner(
            processLauncher = launcher,
            resolveRootfs = { File(notADir.absolutePath) },
        )
        val result = runner.run(
            binary = appImage,
            targetId = "broken",
            runtimeLabel = "AppImage",
        )
        assertTrue(result is BinaryRunResult.Failure)
        assertEquals(0, launcher.calls.size)
    }

    @Test
    fun `run returns Failure when the launcher throws`() = runTest {
        val appImage = tmp.newFile("Blender.AppImage")
        appImage.setExecutable(true)
        val rootfs = tmp.newFolder("debian-12", "rootfs")
        val launcher = ThrowingProcessLauncher(IllegalStateException("spawn failed"))
        val runner = ProcessLauncherAppImageRunner(
            processLauncher = launcher,
            resolveRootfs = { rootfs },
        )
        val result = runner.run(
            binary = appImage,
            targetId = "debian-12",
            runtimeLabel = "AppImage",
        )
        assertTrue(result is BinaryRunResult.Failure)
        assertTrue(
            "error must mention the spawn failure: ${(result as BinaryRunResult.Failure).message}",
            result.message.contains("spawn failed")
        )
    }
}
