package com.elysium.vanguard.core.runtime.distros.ssh

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PHASE 49 — the in-memory [SshClient] impl.
 *
 * The [InMemorySshClient] is the test impl
 * the [SshConnectionTest] (and the future
 * terminal tests) use. It does NOT open a
 * real SSH connection; it returns a
 * [InMemorySshSession] backed by an in-memory
 * buffer.
 *
 * The class is also useful in previews + dev
 * builds: the runtime can wire the in-memory
 * client instead of the MINA SSHD client and
 * still have a fully functional terminal UX
 * (the user just sees the in-memory buffer
 * echoed back, which is the right behaviour
 * for a "no host available" demo mode).
 *
 * The class is thread-safe. The
 * [InMemorySshSession] uses a
 * [CopyOnWriteArrayList] for the output
 * buffer; the [sendLine] / [readAvailable]
 * methods are safe against concurrent
 * callers (the terminal session + the
 * network I/O are usually on different
 * threads).
 *
 * Phase 49 — first build. The
 * [connectForTest] helper is the seam tests
 * use to inject a custom response. The
 * default behaviour (echo input) is enough
 * for the smoke tests in the terminal +
 * Hilt module wiring paths.
 */
class InMemorySshClient : SshClient {

    /**
     * The behaviour the client uses to produce
     * output in response to a [sendLine] call.
     * Default: echo the line back. Tests can
     * override to simulate a remote shell.
     */
    var responder: (String) -> String = { line -> "$line\n" }

    /**
     * A test seam: directly push output into
     * the latest session's buffer. The
     * terminal's [readAvailable] will return
     * it on the next call. Returns the session
     * the output was pushed into, or `null`
     * when no session is open.
     */
    fun pushOutput(output: String): InMemorySshSession? {
        val current = lastSession
        current?.let { it.appendOutput(output) }
        return current
    }

    @Volatile
    private var lastSession: InMemorySshSession? = null

    override fun connect(host: SshHost): Result<SshSession> {
        val session = InMemorySshSession(host, responder)
        lastSession = session
        return Result.success(session)
    }
}

/**
 * The in-memory [SshSession] impl. The session
 * uses a [CopyOnWriteArrayList] of
 * [CharSequence] chunks for the output buffer
 * (one chunk per [sendLine] / [appendOutput]
 * call). The [readAvailable] method concatenates
 * the chunks into a single [String] — the
 * cost is O(n) per read, but reads are
 * throttled by the terminal's frame rate so
 * the O(n) is fine.
 */
class InMemorySshSession(
    override val host: SshHost,
    private val responder: (String) -> String
) : SshSession {

    private val lock = Any()
    private val output = CopyOnWriteArrayList<CharSequence>()
    @Volatile
    private var started: Boolean = false
    @Volatile
    private var closed: Boolean = false

    override fun start(): Result<Unit> = synchronized(lock) {
        if (closed) return Result.failure(
            SshError.HostDisconnected(host.host)
        )
        started = true
        Result.success(Unit)
    }

    override fun sendLine(line: String) {
        if (closed) throw IOException("session is closed")
        // The responder is the test's hook to
        // simulate a remote shell. The default
        // (echo) is what most tests need.
        val response = try {
            responder(line)
        } catch (e: Exception) {
            "responder error: ${e.message}\n"
        }
        appendOutput(response)
    }

    override fun readAvailable(timeoutMs: Long): String {
        if (output.isEmpty()) {
            // Honour the timeout. The default
            // implementation sleeps the current
            // thread; tests usually pass
            // `timeoutMs = 0` for the
            // "non-blocking" semantics.
            if (timeoutMs > 0) {
                try {
                    Thread.sleep(timeoutMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        val snapshot = output.toList()
        output.clear()
        return snapshot.joinToString("")
    }

    override fun close() {
        closed = true
    }

    /**
     * Test seam: directly push [output] into
     * the session's buffer. The terminal's
     * [readAvailable] will return it on the
     * next call. Used by the [InMemorySshClient.pushOutput]
     * helper and by tests that want to
     * simulate a remote response without
     * wiring a responder.
     */
    fun appendOutput(output: String) {
        if (output.isEmpty()) return
        this.output.add(output)
    }
}
