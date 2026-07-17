package com.elysium.vanguard.core.runtime.distros.ssh

/**
 * PHASE 49 — the SSH client interface.
 *
 * The [SshClient] is the runtime's seam for
 * "connect to a remote host over SSH". It is
 * the SSH counterpart to the
 * [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
 * seam: a narrow interface that the
 * [com.elysium.vanguard.features.runtime.terminal.TerminalViewModel]
 * consumes, with the production impl
 * (MINA SSHD-backed) wired in via Hilt and a
 * fake impl for the unit tests.
 *
 * The interface is intentionally small: the
 * terminal needs to open a session, send a
 * line of input, read whatever output the
 * remote produced, and close the session. The
 * X11 forwarding path is a separate concern
 * (the [X11Forwarder]); the [SshClient] is
 * just the terminal transport.
 *
 * Phase 49 — first build. The
 * [SshClient.connect] method returns an
 * [SshSession] (a thin, type-safe wrapper
 * around an open SSH channel) on success or
 * a typed failure on error. The production
 * impl backed by Apache MINA SSHD is a
 * follow-up phase.
 */
interface SshClient {

    /**
     * Open a new SSH session to [host]. Returns a
     * [Result.success] with the [SshSession] on
     * success, or a [Result.failure] with a
     * typed [SshError] on error.
     *
     * The session is *not* started until
     * [SshSession.start] is called. The two
     * phases (connect / start) are split so a
     * test or a future "preview" path can hold
     * a connected session without consuming
     * a remote PTY until the user explicitly
     * requests it.
     */
    fun connect(host: SshHost): Result<SshSession>
}

/**
 * A connected SSH session. The session is
 * open (the SSH handshake completed and a
 * channel is reserved) but not yet started
 * (no PTY is allocated, no shell is spawned).
 * Call [start] to allocate the PTY + shell;
 * call [close] to release the channel.
 *
 * The session is [AutoCloseable] so a
 * `use { }` block in a caller releases the
 * channel on every code path.
 */
interface SshSession : AutoCloseable {
    /**
     * The host this session is bound to. Useful
     * for logging / observability — the session
     * does NOT carry the host implicitly.
     */
    val host: SshHost

    /**
     * Start the session: allocate a PTY and
     * spawn the remote shell. Returns
     * `Result.success(Unit)` on success or a
     * `Result.failure(SshError)` if the PTY
     * allocation or shell spawn failed (the
     * channel is closed on failure; the caller
     * does not need to call [close]).
     */
    fun start(): Result<Unit>

    /**
     * Send [line] to the remote shell as a
     * single line of input (a newline is
     * appended if [line] does not end with
     * one). Returns the number of bytes
     * written, or throws [IOException] on a
     * network error.
     */
    fun sendLine(line: String)

    /**
     * Read whatever the remote produced since
     * the last [readAvailable] call (or since
     * the session started). Returns an empty
     * string when no output is available.
     * Blocks up to [timeoutMs] for output; the
     * implementation may return earlier if
     * partial output is available.
     */
    fun readAvailable(timeoutMs: Long): String

    /**
     * Close the SSH channel. Idempotent: a
     * second call is a no-op.
     */
    override fun close()
}

/**
 * Typed errors the [SshClient.connect] path
 * returns. The caller branches on the kind
 * (e.g. "Authentication failed" → show a
 * snackbar with "wrong password / key"; vs
 * "Connection refused" → "host is not
 * reachable").
 */
sealed class SshError(message: String) : RuntimeException(message) {

    /** The host refused the TCP connection
     *  (host is down, port is closed, firewall
     *  blocked, etc.). */
    data class ConnectionRefused(val host: String, val port: Int) :
        SshError("Connection refused: $host:$port")

    /** The host accepted the TCP connection but
     *  the SSH handshake failed (version
     *  mismatch, key exchange failure, etc.). */
    data class HandshakeFailed(val host: String, val reason: String) :
        SshError("SSH handshake failed: $host ($reason)")

    /** The host accepted the handshake but
     *  rejected the credentials (wrong
     *  password, wrong key, etc.). */
    data class AuthenticationFailed(val host: String, val user: String) :
        SshError("Authentication failed: $user@$host")

    /** The remote host closed the connection
     *  before the session was usable. */
    data class HostDisconnected(val host: String) :
        SshError("Host disconnected: $host")

    /** Any other SSH error. The original
     *  exception's message is preserved. */
    data class Other(val host: String, val causeMessage: String) :
        SshError("SSH error: $host ($causeMessage)")
}
