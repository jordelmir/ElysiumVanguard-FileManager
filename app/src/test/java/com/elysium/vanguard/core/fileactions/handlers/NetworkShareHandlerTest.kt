package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import com.elysium.vanguard.core.fileactions.NetworkProtocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 97 — the test suite for the
 * [NetworkShareHandler]. The handler reads the
 * URL + credentials from the file body,
 * validates the URL scheme matches the
 * declared protocol, and delegates the actual
 * mount to the [NetworkShareMounter]. The
 * mounter is a fake in tests; production
 * uses the `ProcessLauncher`-backed impl.
 */
class NetworkShareHandlerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `mount reads the URL from the first non-blank line`() = runTest {
        val descriptor = tmp.newFile("home.smb")
        descriptor.writeText(
            "# my home share\n" +
                "\n" +
                "smb://192.168.1.10/jordan\n"
        )
        val mounter = RecordingNetworkShareMounter(
            expectedResult = NetworkShareMountResult.Mounted(
                url = "smb://192.168.1.10/jordan",
                protocol = NetworkProtocol.SMB,
                mountPoint = "/mnt/home",
            )
        )
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = descriptor.absolutePath,
            protocol = NetworkProtocol.SMB,
        )
        val result = handler.mount(action)
        assertTrue("expected Mounted, got $result", result is NetworkShareMountResult.Mounted)
        val mounted = result as NetworkShareMountResult.Mounted
        assertEquals("smb://192.168.1.10/jordan", mounted.url)
        assertEquals(1, mounter.calls.size)
        assertEquals("smb://192.168.1.10/jordan", mounter.calls[0].url)
    }

    @Test
    fun `mount reads username and password from key=value lines`() = runTest {
        val descriptor = tmp.newFile("home.smb")
        descriptor.writeText(
            "smb://192.168.1.10/share\n" +
                "username=jordan\n" +
                "password=secret\n"
        )
        val mounter = RecordingNetworkShareMounter()
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = descriptor.absolutePath,
            protocol = NetworkProtocol.SMB,
        )
        handler.mount(action)
        assertEquals("jordan", mounter.calls[0].username)
        assertEquals("secret", mounter.calls[0].password)
    }

    @Test
    fun `mount reads credentials embedded in the URL`() = runTest {
        val descriptor = tmp.newFile("home.smb")
        descriptor.writeText("smb://jordan:secret@192.168.1.10/jordan\n")
        val mounter = RecordingNetworkShareMounter()
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = descriptor.absolutePath,
            protocol = NetworkProtocol.SMB,
        )
        handler.mount(action)
        assertEquals("jordan", mounter.calls[0].username)
        assertEquals("secret", mounter.calls[0].password)
        // The URL passed to the mounter has the
        // credentials stripped (cifs mounts
        // receive credentials via -o user=...).
        assertEquals("smb://192.168.1.10/jordan", mounter.calls[0].url)
    }

    @Test
    fun `mount rejects a descriptor with no URL`() = runTest {
        val descriptor = tmp.newFile("empty.smb")
        descriptor.writeText("# only comments, no URL\n")
        val mounter = RecordingNetworkShareMounter()
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = descriptor.absolutePath,
            protocol = NetworkProtocol.SMB,
        )
        val result = handler.mount(action)
        assertTrue("expected Failure, got $result", result is NetworkShareMountResult.Failure)
        assertEquals(0, mounter.calls.size)
    }

    @Test
    fun `mount rejects a missing descriptor file`() = runTest {
        val mounter = RecordingNetworkShareMounter()
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = File(tmp.root, "missing.smb").absolutePath,
            protocol = NetworkProtocol.SMB,
        )
        val result = handler.mount(action)
        assertTrue(result is NetworkShareMountResult.Failure)
    }

    @Test
    fun `mount rejects a URL whose scheme does not match the protocol`() = runTest {
        val descriptor = tmp.newFile("home.smb")
        descriptor.writeText("https://192.168.1.10/share\n")
        val mounter = RecordingNetworkShareMounter()
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = descriptor.absolutePath,
            protocol = NetworkProtocol.SMB,
        )
        val result = handler.mount(action)
        assertTrue("expected Failure, got $result", result is NetworkShareMountResult.Failure)
        assertEquals(0, mounter.calls.size)
    }

    @Test
    fun `mount accepts webdav davs scheme`() = runTest {
        val descriptor = tmp.newFile("home.webdav")
        descriptor.writeText("davs://files.example.com/jordan\n")
        val mounter = RecordingNetworkShareMounter(
            expectedResult = NetworkShareMountResult.Mounted(
                url = "https://files.example.com/jordan",
                protocol = NetworkProtocol.WEBDAV,
                mountPoint = "/mnt/home",
            )
        )
        val handler = NetworkShareHandler(mounter)
        val action = FileAction.MountNetworkShare(
            id = "test",
            url = descriptor.absolutePath,
            protocol = NetworkProtocol.WEBDAV,
        )
        val result = handler.mount(action)
        assertTrue("expected Mounted, got $result", result is NetworkShareMountResult.Mounted)
    }

    @Test
    fun `parseDescriptor splits embedded credentials`() {
        val handler = NetworkShareHandler(RecordingNetworkShareMounter())
        val (url, user, pass) = handler.splitEmbeddedCredentials(
            "smb://jordan:secret@192.168.1.10/share"
        )
        assertEquals("smb://192.168.1.10/share", url)
        assertEquals("jordan", user)
        assertEquals("secret", pass)
    }

    @Test
    fun `parseDescriptor returns no credentials when no embedded form`() {
        val handler = NetworkShareHandler(RecordingNetworkShareMounter())
        val (url, user, pass) = handler.splitEmbeddedCredentials(
            "smb://192.168.1.10/share"
        )
        assertEquals("smb://192.168.1.10/share", url)
        assertNull(user)
        assertNull(pass)
    }

    @Test
    fun `parseDescriptor handles user-only embedded form`() {
        val handler = NetworkShareHandler(RecordingNetworkShareMounter())
        val (url, user, pass) = handler.splitEmbeddedCredentials(
            "smb://jordan@192.168.1.10/share"
        )
        assertEquals("smb://192.168.1.10/share", url)
        assertEquals("jordan", user)
        assertNull(pass)
    }
}

private class RecordingNetworkShareMounter(
    private val expectedResult: NetworkShareMountResult = NetworkShareMountResult.Mounted(
        url = "smb://placeholder",
        protocol = NetworkProtocol.SMB,
        mountPoint = "/mnt/placeholder",
    ),
) : NetworkShareMounter {
    data class Call(
        val url: String,
        val protocol: NetworkProtocol,
        val username: String?,
        val password: String?,
        val descriptorName: String,
    )
    val calls: MutableList<Call> = mutableListOf()

    override suspend fun mount(
        url: String,
        protocol: NetworkProtocol,
        username: String?,
        password: String?,
        descriptorName: String,
    ): NetworkShareMountResult {
        calls.add(Call(url, protocol, username, password, descriptorName))
        return expectedResult
    }
}
