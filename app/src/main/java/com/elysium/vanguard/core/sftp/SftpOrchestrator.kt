package com.elysium.vanguard.core.sftp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PHASE 2.4 — SFTP server orchestrator.
 *
 * Same lifecycle pattern as the HTTP server: a single application-scoped
 * instance owns the server socket, auth credentials, and root directory.
 * The UI talks to the orchestrator and never to the underlying MINA server
 * directly — that's how we keep the ViewModel testable.
 */
@Singleton
class SftpOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var current: SftpServer? = null
    private var currentConfig: SftpConfig? = null

    private val _state = MutableStateFlow(SftpServer.Status.STOPPED)
    val state: StateFlow<SftpServer.Status> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow<ServerSnapshot?>(null)
    val snapshot: StateFlow<ServerSnapshot?> = _snapshot.asStateFlow()

    /** Quick accessor for the most recent config (for password / URL). */
    fun activeConfig(): SftpConfig? = currentConfig

    fun start(config: SftpConfig): Boolean {
        // Always rebuild on start so the password rotates.
        stop()
        val server = SftpServer(config, hostKeyDir = File(context.filesDir, "sftp-hostkey"))
        server.start()
        val s = server.snapshot()
        if (s.status != SftpServer.Status.RUNNING) {
            _state.value = SftpServer.Status.FAILED
            return false
        }
        current = server
        currentConfig = config
        _state.value = SftpServer.Status.RUNNING
        _snapshot.value = ServerSnapshot(
            status = s.status,
            port = s.port,
            url = connectionUrl(s, config),
            root = s.rootPath,
            password = config.password,
            username = config.username
        )
        return true
    }

    fun stop() {
        current?.stop()
        current = null
        currentConfig = null
        _state.value = SftpServer.Status.STOPPED
        _snapshot.value = null
    }

    fun refreshSnapshot() {
        val s = current?.snapshot() ?: return
        val cfg = currentConfig ?: return
        _snapshot.value = ServerSnapshot(
            status = s.status,
            port = s.port,
            url = connectionUrl(s, cfg),
            root = s.rootPath,
            password = cfg.password,
            username = cfg.username
        )
    }

    private fun connectionUrl(s: SftpServer.Snapshot, cfg: SftpConfig): String {
        val ip = lanIp() ?: return "sftp://${cfg.username}@<phone-ip>:${s.port}/"
        return "sftp://${cfg.username}@$ip:${s.port}/"
    }

    private fun lanIp(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { nic ->
                nic.inetAddresses?.toList()?.filter {
                    !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress
                } ?: emptyList()
            }?.firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }

    data class ServerSnapshot(
        val status: SftpServer.Status,
        val port: Int,
        val url: String,
        val root: String,
        val password: String,
        val username: String
    )
}