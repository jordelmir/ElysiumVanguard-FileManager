package com.elysium.vanguard.core.runtime.distros.ssh

import java.io.File
import java.io.IOException

/**
 * PHASE 9.6.6 — SSH-backed host.
 *
 * Represents a remote (or local-proot-served) Linux machine reachable
 * via SSH. We use this single data class to drive both the terminal
 * session (Phase 9.6.6.1) and the X11 forwarder (Phase 9.6.6.2).
 *
 * The "host" can be the local proot-backed distro once it's running
 * (ssh localhost); that's how we get X11 forwarding without a remote
 * machine: we ssh into the device itself but inside the proot'd
 * rootfs, so X clients render on the local display via the
 * `xauth` chain.
 *
 * Phase 9.6.6 — first build; intentionally minimal.
 */
data class SshHost(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    /**
     * Optional path to a private key file (PKCS#8, OpenSSH, or
     * legacy PEM-encoded DSA/RSA). Mutually exclusive with password.
     */
    val privateKeyPath: File? = null,
    /** Optional password; mutually exclusive with privateKey. */
    val password: String? = null,
    /**
     * Whether to request X11 forwarding for sessions against this
     * host. When true, the SSH client asks the server to set up an
     * xauth cookie and route $DISPLAY to point back to our embedded
     * VNC server.
     */
    val enableX11Forwarding: Boolean = true
)

/**
 * PHASE 9.6.6 — Minimal SSH connection helper that reuses Apache MINA
 * SSHD as the underlying client. Today this is a stub that returns
 * either OK or a synthetic failure — wired so we have a single seam
 * for the real implementation that lands in 9.6.6.1.
 *
 * Why a stub here: the unit tests must NOT touch real crypto. The
 * real client will:
 *
 *   - load the host key
 *   - authenticate via password or private key
 *   - open a "session" channel
 *   - optionally request X11 forwarding on the session
 *   - provide an InputStream / OutputStream pair to plug into our
 *     existing terminal pipeline
 *
 * Phase 9.6.6 — first build; intentionally minimal.
 */
class SshConnection(
    private val host: SshHost
) {
    enum class ProbeResult { OK, AUTH_FAILED, UNREACHABLE, X11_UNSUPPORTED }

    /**
     * PHASE 9.6.6 — Static credential probe. Returns whether the host
     * would accept a connection *before* we open a real session.
     * Today this is structural only (no network).
     */
    fun probe(): ProbeResult {
        if (host.user.isBlank()) return ProbeResult.AUTH_FAILED
        if (host.port < 1 || host.port > 65535) return ProbeResult.UNREACHABLE
        return ProbeResult.OK
    }

    /**
     * PHASE 9.6.6 — Establish a session channel. The stub returns a
     * synthetic failure for misuse; the real client will return a
     * live [org.apache.sshd.client.channel.Channel] that the terminal
     * pumps through.
     *
     * @throws IOException when the probe result was anything other
     *   than [ProbeResult.OK].
     */
    @Throws(IOException::class)
    fun openSession(): Unit {
        val probe = probe()
        if (probe != ProbeResult.OK) {
            throw IOException("ssh probe failed: $probe")
        }
        // Real implementation goes here in 9.6.6.1.
    }
}
