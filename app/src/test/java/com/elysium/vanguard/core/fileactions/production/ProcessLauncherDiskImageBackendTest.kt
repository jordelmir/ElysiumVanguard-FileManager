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

    // ──────────────────────────────────────────────
    // PHASE 117 — real `waitFor` (replaces 60s poll)
    // ──────────────────────────────────────────────

    /**
     * The disk-image backend used to poll the pid for 60
     * seconds in `waitForExit` and always return `-1` (the
     * `pid <= 0` check was a no-op because pids are
     * assigned at fork time). Phase 117 replaces the poll
     * with a direct call to `LaunchedProcess.waitFor`. This
     * test asserts the new behavior: the waitFor callback
     * is invoked exactly once per started process and its
     * return value is treated as the exit code.
     */
    @Test
    fun `mount invokes waitFor exactly once and returns the exit code`() = runTest {
        val image = tmp.newFile("win10.iso")
        val launcher = RecordingProcessLauncher(launchedPid = 4242)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.ISO)
        assertTrue("expected Mounted, got $result", result is DiskImageResult.Mounted)
        // One process started (the mount), one waitFor call.
        assertEquals(1, launcher.calls.size)
        assertEquals(1, launcher.waitForCalls.size)
    }

    /**
     * The 60-second poll never detected failure, so a
     * failed `mount` always reported timeout (-1). With
     * the real `waitFor`, the failure exit code
     * propagates and the backend surfaces a Failure
     * with the original code.
     */
    @Test
    fun `mount failure exit code propagates as Failure with the code`() = runTest {
        val image = tmp.newFile("win10.iso")
        val launcher = RecordingProcessLauncher(launchedPid = 4242, waitForExitCode = 32)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.ISO)
        assertTrue("expected Failure, got $result", result is DiskImageResult.Failure)
        val msg = (result as DiskImageResult.Failure).message
        assertTrue("message should include exit=32: $msg", msg.contains("exit=32"))
    }

    /**
     * QCOW2 mount runs two processes (qemu-img convert +
     * mount), so the new waitFor must be called twice —
     * once per helper process.
     */
    @Test
    fun `qcow2 mount invokes waitFor once per helper process`() = runTest {
        val image = tmp.newFile("win11.qcow2")
        val launcher = RecordingProcessLauncher(launchedPid = 4242)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.QCOW2)
        assertTrue(result is DiskImageResult.Mounted)
        // qemu-img convert + mount = 2 helper processes
        // = 2 waitFor calls.
        assertEquals(2, launcher.calls.size)
        assertEquals(2, launcher.waitForCalls.size)
    }

    /**
     * If qemu-img convert fails (non-zero exit), the mount
     * must short-circuit with a typed Failure — no second
     * process is started and only one waitFor was called.
     */
    @Test
    fun `qcow2 mount short-circuits when qemu-img convert fails`() = runTest {
        val image = tmp.newFile("win11.qcow2")
        val launcher = RecordingProcessLauncher(launchedPid = 4242, waitForExitCode = 1)
        val backend = ProcessLauncherDiskImageBackend(launcher, tmp.root)
        val result = backend.mountReadOnly(image, DiskImageFormat.QCOW2)
        assertTrue("expected Failure, got $result", result is DiskImageResult.Failure)
        // Only qemu-img convert was started; the mount was
        // never attempted.
        assertEquals(1, launcher.calls.size)
        assertEquals(1, launcher.waitForCalls.size)
    }
}
