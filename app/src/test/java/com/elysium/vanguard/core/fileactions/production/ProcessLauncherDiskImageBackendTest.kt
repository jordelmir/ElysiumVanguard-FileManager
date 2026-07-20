package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.DiskImageFormat
import com.elysium.vanguard.core.fileactions.handlers.DiskImageResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 94 — the test suite for the
 * [ProcessLauncherDiskImageBackend]. The
 * backend uses the [ProcessLauncher] to
 * invoke `mount`, `qemu-img convert`, and
 * `qemu-system-x86_64`. The tests use a fake
 * launcher that records the calls and
 * returns a stub [LaunchedProcess].
 */
class ProcessLauncherDiskImageBackendTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `mount ISO uses mount -o ro loop and returns the mount point`() = runTest {
        val image = tmp.newFile("win10.iso")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.ISO)
        assertTrue("expected Mounted, got $result", result is DiskImageResult.Mounted)
        val mounted = result as DiskImageResult.Mounted
        assertEquals(DiskImageFormat.ISO, mounted.format)
        assertTrue(
            "mountPoint should end with win10: ${mounted.mountPoint}",
            mounted.mountPoint.endsWith("win10")
        )
        val cmd = launcher.calls[0].first
        assertEquals("mount", cmd[0])
        assertEquals("-o", cmd[1])
        assertEquals("ro,loop", cmd[2])
        assertEquals(image.absolutePath, cmd[3])
    }

    @Test
    fun `mount IMG uses the same loop mount as ISO`() = runTest {
        val image = tmp.newFile("raspbian.img")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.IMG)
        assertTrue(result is DiskImageResult.Mounted)
    }

    @Test
    fun `mount QCOW2 first converts to raw then mounts`() = runTest {
        val image = tmp.newFile("win11.qcow2")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.QCOW2)
        assertTrue(result is DiskImageResult.Mounted)
        // The first call must be qemu-img convert.
        val firstCmd = launcher.calls[0].first
        assertEquals("qemu-img", firstCmd[0])
        assertEquals("convert", firstCmd[1])
        // The second call must be mount -o ro,loop.
        val secondCmd = launcher.calls[1].first
        assertEquals("mount", secondCmd[0])
    }

    @Test
    fun `mount returns Failure when the image file is missing`() = runTest {
        val missing = File(tmp.root, "missing.iso")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(missing, DiskImageFormat.ISO)
        assertTrue(result is DiskImageResult.Failure)
    }

    @Test
    fun `boot VM from QCOW2 spawns qemu-system directly`() = runTest {
        val image = tmp.newFile("win11.qcow2")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.bootVm(image, DiskImageFormat.QCOW2, preferredVmId = null)
        assertTrue("expected VmBooted, got $result", result is DiskImageResult.VmBooted)
        val cmd = launcher.calls[0].first
        assertEquals("qemu-system-x86_64", cmd[0])
        assertTrue("cmd must include -hda <image>: $cmd", cmd.contains("-hda"))
        assertTrue("cmd must include the image path: $cmd", cmd.contains(image.absolutePath))
    }

    @Test
    fun `boot VM from ISO first converts to QCOW2 then boots`() = runTest {
        val image = tmp.newFile("win10.iso")
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.bootVm(image, DiskImageFormat.ISO, preferredVmId = null)
        assertTrue(result is DiskImageResult.VmBooted)
        val firstCmd = launcher.calls[0].first
        assertEquals("qemu-img", firstCmd[0])
        assertEquals("convert", firstCmd[1])
        assertEquals("qcow2", firstCmd[3])
        val secondCmd = launcher.calls[1].first
        assertEquals("qemu-system-x86_64", secondCmd[0])
    }
}
