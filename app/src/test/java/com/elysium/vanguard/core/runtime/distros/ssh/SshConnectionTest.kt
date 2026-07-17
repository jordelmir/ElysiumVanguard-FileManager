package com.elysium.vanguard.core.runtime.distros.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.io.IOException

/**
 * PHASE 9.6.6 — Tests for the SSH host + connection stub.
 *
 * The real SSH client lives in 9.6.6.1; for now we just verify the
 * structural probe and the host data class shape.
 *
 * Phase 9.6.6 — first build; intentionally minimal.
 */
class SshConnectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `probe OK with valid host`() {
        val host = SshHost(
            id = "local-proot",
            displayName = "proot",
            host = "localhost",
            port = 22,
            user = "root"
        )
        val conn = SshConnection(host)
        assertEquals(SshConnection.ProbeResult.OK, conn.probe())
    }

    @Test
    fun `probe fails when user is blank`() {
        val host = SshHost(
            id = "no-user",
            displayName = "n/a",
            host = "x",
            port = 22,
            user = ""
        )
        val conn = SshConnection(host)
        assertEquals(SshConnection.ProbeResult.AUTH_FAILED, conn.probe())
    }

    @Test
    fun `probe fails when port is out of range`() {
        val host = SshHost(
            id = "bad-port",
            displayName = "n/a",
            host = "x",
            port = 0,
            user = "root"
        )
        val conn = SshConnection(host)
        assertEquals(SshConnection.ProbeResult.UNREACHABLE, conn.probe())
    }

    @Test
    fun `probe fails when port is too high`() {
        val host = SshHost(
            id = "absurd-port",
            displayName = "n/a",
            host = "x",
            port = 70000,
            user = "root"
        )
        val conn = SshConnection(host)
        assertEquals(SshConnection.ProbeResult.UNREACHABLE, conn.probe())
    }

    @Test
    fun `openSession throws IOException on bad probe`() {
        val host = SshHost(
            id = "no-user",
            displayName = "n/a",
            host = "x",
            port = 22,
            user = ""
        )
        val conn = SshConnection(host)
        try {
            conn.openSession()
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("AUTH_FAILED"))
        }
    }

    @Test
    fun `openSession is a no-op for OK probe`() {
        val host = SshHost(
            id = "ok",
            displayName = "ok",
            host = "x",
            port = 22,
            user = "root"
        )
        val conn = SshConnection(host)
        conn.openSession() // no exception, no side effect — stub.
    }

    @Test
    fun `private key file is honored when present`() {
        val key = File(tmp.root, "id_rsa")
        key.writeText("fake-private-key-content")
        val host = SshHost(
            id = "with-key",
            displayName = "k",
            host = "x",
            port = 22,
            user = "u",
            privateKeyPath = key
        )
        assertEquals(key, host.privateKeyPath)
        // Default X11 forwarding is true; this test just confirms the field
        // survives host construction.
        assertEquals(true, host.enableX11Forwarding)
    }

    @Test
    fun `X11 forwarding toggle defaults to on`() {
        val host = SshHost(
            id = "k",
            displayName = "k",
            host = "x",
            port = 22,
            user = "u"
        )
        // Default is true on the data class.
        assertEquals(true, host.enableX11Forwarding)
    }

    @Test
    fun `X11Display parses host and display number`() {
        val d = X11Display("localhost:10.0", "deadbeef", 6010)
        assertEquals("localhost", d.hostPart)
        assertEquals(10, d.displayNumber)
        assertEquals(6010, d.forwardingPort)
    }

    @Test
    fun `X11Display rejects malformed display strings`() {
        val d = X11Display("nocolon", "00", 0)
        assertEquals(0, d.displayNumber) // 0 when can't parse
        assertEquals("nocolon", d.hostPart)
    }

    @Test
    fun `X11Forwarding wire format uses MIT-MAGIC-COOKIE-1`() {
        val zeros = "0".repeat(32)
        val display = X11Display("localhost:10.0", zeros, 6010)
        val fwd = X11Forwarding(
            sessionId = "ab",
            display = display,
            cookieHex = zeros
        )
        assertTrue(fwd.wireFormat.startsWith("MIT-MAGIC-COOKIE-1\t"))
    }

    @Test
    fun `X11Forwarding debug tag is lowercase`() {
        val display = X11Display("localhost:10.0", "00", 6010)
        val fwd = X11Forwarding("AB", display, "00")
        assertTrue(fwd.debugTag.startsWith("ab"))
    }

    // --- Phase 49: SshClient + InMemorySshClient ---

    @Test
    fun `InMemorySshClient connect returns a session on success`() {
        val client = InMemorySshClient()
        val host = SshHost(
            id = "local",
            displayName = "Local",
            host = "localhost",
            port = 22,
            user = "root"
        )
        val result = client.connect(host)
        assertTrue(result.isSuccess)
        assertEquals(host, result.getOrThrow().host)
    }

    @Test
    fun `InMemorySshClient start succeeds and unlocks sendLine`() {
        val client = InMemorySshClient()
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        val start = session.start()
        assertTrue(start.isSuccess)
        // sendLine echoes back via the default
        // responder.
        session.sendLine("ls")
        val out = session.readAvailable(timeoutMs = 0)
        assertEquals("ls\n", out)
    }

    @Test
    fun `InMemorySshClient sendLine echoes via the responder`() {
        val client = InMemorySshClient()
        client.responder = { line -> "PONG: $line\n" }
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        session.start()
        session.sendLine("ping")
        val out = session.readAvailable(timeoutMs = 0)
        assertEquals("PONG: ping\n", out)
    }

    @Test
    fun `InMemorySshClient pushOutput injects response without a sendLine`() {
        val client = InMemorySshClient()
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        session.start()
        client.pushOutput("hello from the remote\n")
        val out = session.readAvailable(timeoutMs = 0)
        assertEquals("hello from the remote\n", out)
    }

    @Test
    fun `InMemorySshClient readAvailable returns empty when no output`() {
        val client = InMemorySshClient()
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        session.start()
        val out = session.readAvailable(timeoutMs = 0)
        assertEquals("", out)
    }

    @Test
    fun `InMemorySshClient close is idempotent`() {
        val client = InMemorySshClient()
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        session.start()
        session.close()
        session.close()  // second call must not throw
    }

    @Test
    fun `InMemorySshClient sendLine after close throws IOException`() {
        val client = InMemorySshClient()
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        session.start()
        session.close()
        try {
            session.sendLine("after close")
            fail("expected IOException")
        } catch (expected: IOException) { /* */ }
    }

    @Test
    fun `InMemorySshClient readAvailable drains the buffer (single read consumes all)`() {
        val client = InMemorySshClient()
        val session = client.connect(SshHost(
            id = "local", displayName = "Local", host = "localhost", port = 22, user = "root"
        )).getOrThrow()
        session.start()
        session.sendLine("a")
        session.sendLine("b")
        session.sendLine("c")
        val first = session.readAvailable(timeoutMs = 0)
        assertEquals("a\nb\nc\n", first)
        // Second read returns empty (drained).
        val second = session.readAvailable(timeoutMs = 0)
        assertEquals("", second)
    }
}
