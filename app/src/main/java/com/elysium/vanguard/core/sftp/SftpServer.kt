package com.elysium.vanguard.core.sftp

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * PHASE 2.4 — SFTP server backed by Apache MINA SSHD.
 *
 * Scope:
 *   - SFTP subsystem only (no exec/shell, no port forwarding). The user wants
 *     file transfer, not a remote shell into the phone.
 *   - Password auth (no public key) — simpler UX, the password is rotated per
 *     server start and shown in the QR.
 *   - Read/write to a single root directory via [VirtualFileSystemFactory].
 *
 * What we deliberately skip:
 *   - SCP. SFTP covers everything SCP does; the server still answers to the
 *     `scp` binary if the user points it at a single file.
 *   - Shell. SFTP clients (FileZilla, Cyberduck, terminal sftp) only need the
 *     `sftp-server` subsystem; enabling a shell would expose a remote process
 *     on the phone which is a security risk we don't need.
 *
 * Server keys:
 *   - Generated on first start, persisted to the app's files dir.
 *   - ED25519 preferred (small, fast, modern).
 *   - First-time client connections will get the standard "host key not
 *     recognized" warning; the user accepts manually.
 */
class SftpServer(
    private val config: SftpConfig,
    private val hostKeyDir: File
) {

    private val _status = AtomicReference(Status.STOPPED)
    private var sshd: SshServer? = null

    enum class Status { STOPPED, STARTING, RUNNING, FAILED }

    data class Snapshot(
        val status: Status,
        val port: Int,
        val boundAddress: String,
        val activeSessions: Int,
        val totalSessions: Long,
        val rootPath: String
    )

    private val sessionCounter = AtomicLong(0)
    private val activeSessionsCounter = AtomicLong(0)

    fun snapshot(): Snapshot = Snapshot(
        status = _status.get(),
        port = config.port,
        boundAddress = config.bindAddress,
        activeSessions = activeSessionsCounter.get().toInt(),
        totalSessions = sessionCounter.get(),
        rootPath = when (val r = config.root) {
            is SftpConfig.RootSpec.Filesystem -> r.dir.absolutePath
            is SftpConfig.RootSpec.SafTree -> r.treeUri.toString()
        }
    )

    fun start() {
        if (_status.get() != Status.STOPPED) return
        _status.set(Status.STARTING)
        try {
            val server = SshServer.setUpDefaultServer().apply {
                port = config.port
                host = config.bindAddress
                keyPairProvider = SimpleGeneratorHostKeyProvider(
                    File(hostKeyDir, "hostkey.ser").apply { parentFile?.mkdirs() }.toPath()
                )
                passwordAuthenticator = PasswordAuthenticator { user, pass, _ ->
                    user == config.username && pass == config.password
                }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory().apply {
                    val root = when (val r = config.root) {
                        is SftpConfig.RootSpec.Filesystem -> r.dir.toPath().toAbsolutePath()
                        is SftpConfig.RootSpec.SafTree -> error("SAF not supported yet; pick a filesystem root")
                    }
                    setDefaultHomeDir(root)
                }
                // Track session lifecycle for the UI.
                addSessionListener(object : SessionListener {
                    override fun sessionCreated(session: Session?) {
                        sessionCounter.incrementAndGet()
                        activeSessionsCounter.incrementAndGet()
                    }
                    override fun sessionClosed(session: Session?) {
                        activeSessionsCounter.decrementAndGet()
                    }
                    override fun sessionException(
                        session: Session?,
                        throwable: Throwable?
                    ) { /* log later if needed */ }
                })
            }
            server.start()
            sshd = server
            _status.set(Status.RUNNING)
        } catch (e: IOException) {
            _status.set(Status.FAILED)
            sshd = null
        } catch (e: Exception) {
            _status.set(Status.FAILED)
            sshd = null
        }
    }

    fun stop() {
        val s = sshd ?: return
        try { s.stop() } catch (_: Exception) {}
        sshd = null
        activeSessionsCounter.set(0)
        _status.set(Status.STOPPED)
    }
}