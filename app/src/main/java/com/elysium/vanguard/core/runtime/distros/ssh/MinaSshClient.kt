package com.elysium.vanguard.core.runtime.distros.ssh

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Phase 145 — the production [com.elysium.vanguard.core.runtime.distros.ssh.SshClient]
 * implementation backed by a raw SSH transport.
 *
 * NOTE: This is a simplified implementation that uses a direct TCP socket.
 * For production SSH with key exchange, encryption, and channel multiplexing,
 * the full Apache MINA SSHD client should be wired here. The current
 * implementation provides the structural seam for the terminal to connect
 * to a remote host; the cryptographic transport is a follow-up phase.
 *
 * Thread safety: each [connect] call creates a fresh [MinaSshSession].
 */
class MinaSshClient : com.elysium.vanguard.core.runtime.distros.ssh.SshClient {

    companion object {
        private const val TAG = "MinaSshClient"
    }

    override fun connect(host: SshHost): Result<com.elysium.vanguard.core.runtime.distros.ssh.SshSession> {
        return try {
            val socket = Socket(host.host, host.port)
            socket.soTimeout = 30_000

            Result.success(MinaSshSession(host, socket))
        } catch (e: IOException) {
            when {
                e.message?.contains("Connection refused", ignoreCase = true) == true ->
                    Result.failure(com.elysium.vanguard.core.runtime.distros.ssh.SshError.ConnectionRefused(host.host, host.port))
                e.message?.contains("refused", ignoreCase = true) == true ->
                    Result.failure(com.elysium.vanguard.core.runtime.distros.ssh.SshError.ConnectionRefused(host.host, host.port))
                else ->
                    Result.failure(com.elysium.vanguard.core.runtime.distros.ssh.SshError.Other(host.host, e.message ?: e.javaClass.simpleName))
            }
        } catch (e: Exception) {
            Result.failure(com.elysium.vanguard.core.runtime.distros.ssh.SshError.Other(host.host, e.message ?: e.javaClass.simpleName))
        }
    }
}

/**
 * Phase 145 — the production [com.elysium.vanguard.core.runtime.distros.ssh.SshSession]
 * implementation backed by a TCP socket.
 *
 * NOTE: This is a simplified implementation. For production SSH with
 * proper channel multiplexing, the Apache MINA SSHD ClientChannel
 * should be used instead of raw socket I/O.
 */
class MinaSshSession(
    override val host: SshHost,
    private val socket: Socket,
) : com.elysium.vanguard.core.runtime.distros.ssh.SshSession {

    companion object {
        private const val TAG = "MinaSshSession"
    }

    private var input: OutputStream? = null
    private var output: InputStream? = null
    @Volatile
    private var closed = false

    override fun start(): Result<Unit> {
        return try {
            input = socket.getOutputStream()
            output = socket.getInputStream()
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(com.elysium.vanguard.core.runtime.distros.ssh.SshError.Other(host.host, e.message ?: e.javaClass.simpleName))
        }
    }

    override fun sendLine(line: String) {
        val os = input ?: throw IOException("Session not started")
        val data = if (line.endsWith("\n")) line else "$line\n"
        os.write(data.toByteArray(Charsets.UTF_8))
        os.flush()
    }

    override fun readAvailable(timeoutMs: Long): String {
        val inputStream = output ?: return ""
        return try {
            val available = inputStream.available()
            if (available > 0) {
                val buffer = ByteArray(available)
                inputStream.read(buffer)
                String(buffer, Charsets.UTF_8)
            } else {
                Thread.sleep(minOf(timeoutMs, 50))
                val retryAvailable = inputStream.available()
                if (retryAvailable > 0) {
                    val buffer = ByteArray(retryAvailable)
                    inputStream.read(buffer)
                    String(buffer, Charsets.UTF_8)
                } else {
                    ""
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            input?.close()
        } catch (e: Exception) { Log.w(TAG, "Error closing SSH input stream", e) }
        try {
            output?.close()
        } catch (e: Exception) { Log.w(TAG, "Error closing SSH output stream", e) }
        try {
            socket.close()
        } catch (e: Exception) { Log.w(TAG, "Error closing SSH socket", e) }
    }
}
