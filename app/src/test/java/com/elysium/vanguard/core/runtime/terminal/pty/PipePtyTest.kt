package com.elysium.vanguard.core.runtime.terminal.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.6.10 — Tests for the PTY pipe placeholder.
 *
 * We use a bounded read loop that polls `available()` so the test
 * can't block forever — important because `PipedInputStream.read()`
 * blocks until data or EOF, and we're in a JVM unit test where
 * silence looks like deadlock.
 */
class PipePtyTest {

    @Test
    fun `child writes appear at master output`() {
        val pty = PipePty.create()
        try {
            // Test is the "child" producing output. It writes to
            // slaveOutput (its stdout); parent reads via masterOutput.
            pty.slaveOutput().write("hello from child\n".toByteArray())
            pty.slaveOutput().flush()
            // Close the writer to EOF the reader so collect() returns.
            pty.slaveOutput().close()
            val got = String(collect(pty.masterOutput()), Charsets.UTF_8)
            assertEquals("hello from child\n", got)
        } finally {
            pty.close()
        }
    }

    @Test
    fun `master writes appear at slave output`() {
        val pty = PipePty.create()
        try {
            // Parent writes to masterInput; child reads via slaveInput.
            pty.masterInput().write("hello from parent\n".toByteArray())
            pty.masterInput().flush()
            pty.masterInput().close()
            val got = String(collect(pty.slaveInput()), Charsets.UTF_8)
            assertEquals("hello from parent\n", got)
        } finally {
            pty.close()
        }
    }

    @Test
    fun `close is idempotent`() {
        val pty = PipePty.create()
        pty.close()
        pty.close() // should not throw
        assertTrue(true)
    }

    @Test
    fun `setWindowSize on pipes is a no-op`() {
        val pty = PipePty.create()
        try {
            pty.setWindowSize(rows = 80, cols = 24) // no exception
        } finally {
            pty.close()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `factory returns a pipe-backed PtyPipe`() {
        val pty = PtyFactory.create()
        assertTrue(pty is PipePty)
        pty.close()
    }

    /**
     * Pull bytes from [input] until EOF or until [timeoutMs] elapses.
     * Polls `available()` non-blocking, so the test can't deadlock on
     * a stream that never sees more data.
     */
    private fun collect(input: java.io.InputStream, timeoutMs: Int = 1000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val available = try { input.available() } catch (_: Exception) { 0 }
            if (available <= 0) {
                // Brief sleep to avoid a hot loop when the writer
                // closed but a few bytes are still in flight.
                Thread.sleep(2)
                if (input.available() <= 0) break
                continue
            }
            val n = try { input.read(buf, 0, minOf(buf.size, available)) } catch (_: Exception) { -1 }
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
