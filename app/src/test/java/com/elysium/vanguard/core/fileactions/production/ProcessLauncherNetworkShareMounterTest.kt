package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.NetworkProtocol
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMountResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 97 — the test suite for the
 * [ProcessLauncherNetworkShareMounter]. The
 * mounter shells out to `mount` via the
 * production [com.elysium.vanguard.core.runtime.runner.ProcessLauncher].
 * The tests use a fake launcher that records
 * the call + returns a stub [com.elysium.vanguard.core.runtime.runner.LaunchedProcess].
 */
class ProcessLauncherNetworkShareMounterTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `mount SMB uses mount -t cifs with user and pass options`() = runTest {
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        val result = mounter.mount(
            url = "smb://192.168.1.10/jordan",
            protocol = NetworkProtocol.SMB,
            username = "jordan",
            password = "secret",
            descriptorName = "home",
        )
        assertTrue("expected Mounted, got $result", result is NetworkShareMountResult.Mounted)
        val mounted = result as NetworkShareMountResult.Mounted
        assertTrue("mountPoint ends with /mnt/home: ${mounted.mountPoint}", mounted.mountPoint.endsWith("/mnt/home"))
        val cmd = launcher.calls[0].first
        assertEquals("mount", cmd[0])
        assertEquals("-t", cmd[1])
        assertEquals("cifs", cmd[2])
        // The options block sits at index 4 (after "-o" at index 3).
        val opts = cmd[4]
        assertTrue("opts must include user=jordan: $opts", opts.contains("user=jordan"))
        assertTrue("opts must include pass=secret: $opts", opts.contains("pass=secret"))
        // SMB URLs are normalized to //server/share and live at index 5.
        assertEquals("//192.168.1.10/jordan", cmd[5])
        // Mount point is index 6.
        assertEquals(mounted.mountPoint, cmd[6])
    }

    @Test
    fun `mount SMB with no credentials uses guest`() = runTest {
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        mounter.mount(
            url = "smb://192.168.1.10/public",
            protocol = NetworkProtocol.SMB,
            username = null,
            password = null,
            descriptorName = "public",
        )
        val cmd = launcher.calls[0].first
        val opts = cmd[4]
        assertTrue("opts must default to user=guest: $opts", opts.contains("user=guest"))
    }

    @Test
    fun `mount WebDAV uses mount -t davfs and rewrites dav to http`() = runTest {
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        val result = mounter.mount(
            url = "dav://files.example.com/jordan",
            protocol = NetworkProtocol.WEBDAV,
            username = "jordan",
            password = null,
            descriptorName = "davhome",
        )
        assertTrue(result is NetworkShareMountResult.Mounted)
        val cmd = launcher.calls[0].first
        assertEquals("mount", cmd[0])
        assertEquals("davfs", cmd[2])
        // dav:// scheme is rewritten to http:// at index 5.
        assertEquals("http://files.example.com/jordan", cmd[5])
    }

    @Test
    fun `mount WebDAV davs is rewritten to https`() = runTest {
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        mounter.mount(
            url = "davs://files.example.com/jordan",
            protocol = NetworkProtocol.WEBDAV,
            username = null,
            password = null,
            descriptorName = "davshome",
        )
        val cmd = launcher.calls[0].first
        assertEquals("https://files.example.com/jordan", cmd[5])
    }

    @Test
    fun `mount SFTP uses mount -t fuse sshfs`() = runTest {
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        val result = mounter.mount(
            url = "sftp://server.example.com/home/jordan",
            protocol = NetworkProtocol.SFTP,
            username = "jordan",
            password = null,
            descriptorName = "sftphome",
        )
        assertTrue(result is NetworkShareMountResult.Mounted)
        val cmd = launcher.calls[0].first
        assertEquals("fuse.sshfs", cmd[2])
        // Username is prepended to the host: jordan@server.example.com.
        assertEquals("jordan@server.example.com/home/jordan", cmd[5])
    }

    @Test
    fun `mount returns Failure when the launcher throws`() = runTest {
        val launcher = ThrowingProcessLauncher(IllegalStateException("spawn failed"))
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        val result = mounter.mount(
            url = "smb://192.168.1.10/share",
            protocol = NetworkProtocol.SMB,
            username = null,
            password = null,
            descriptorName = "fail",
        )
        assertTrue("expected Failure, got $result", result is NetworkShareMountResult.Failure)
        assertTrue(
            "error must mention the spawn failure: ${(result as NetworkShareMountResult.Failure).message}",
            result.message.contains("spawn failed")
        )
    }

    @Test
    fun `mount creates the mount point under scratchDir`() = runTest {
        val launcher = RecordingProcessLauncher(launchedPid = -1)
        val mounter = ProcessLauncherNetworkShareMounter(launcher, tmp.root)
        mounter.mount(
            url = "smb://192.168.1.10/share",
            protocol = NetworkProtocol.SMB,
            username = null,
            password = null,
            descriptorName = "myhome",
        )
        val cwd = launcher.calls[0].third
        assertEquals(tmp.root.absolutePath + "/mnt/myhome", cwd.absolutePath)
        assertNotNull(cwd)
        assertTrue("mount point must exist on disk", cwd.isDirectory)
    }
}
