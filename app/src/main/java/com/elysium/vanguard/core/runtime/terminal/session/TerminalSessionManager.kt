package com.elysium.vanguard.core.runtime.terminal.session

import com.elysium.vanguard.core.runtime.network.DistroSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local owner of all live terminal sessions.
 *
 * A ViewModel is a UI concern and may disappear when a window folds, rotates,
 * or navigates away. This manager is application scoped instead: it owns the
 * actual PTY session until the user explicitly stops it (from the Runtime UI
 * or the foreground-service notification). Sessions cannot survive Android
 * process death because an OS PTY cannot be serialized; callers therefore
 * attach by id when the process is still alive and create a fresh shell when
 * it is not.
 *
 * Phase 11.4 — the manager now also tracks per-rootfs session counts so the
 * DNS refresh pipeline knows when a guest is alive. The first session on a
 * rootfs registers the rootfs with [DistroSessionRegistry]; the last
 * session on the same rootfs unregisters it. Two sessions on one rootfs
 * keep the registration alive until both close.
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    private val distroSessionRegistry: DistroSessionRegistry
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, TerminalSession>()
    private val trackingJobs = ConcurrentHashMap<String, Job>()
    private val activeRootfsCounts = ConcurrentHashMap<File, AtomicInteger>()
    private val _activeSessionIds = MutableStateFlow<Set<String>>(emptySet())

    /** IDs that still require foreground-process protection. */
    val activeSessionIds: StateFlow<Set<String>> = _activeSessionIds.asStateFlow()

    /** Creates, registers and starts a session exactly once. */
    fun create(config: TerminalSession.Config): TerminalSession = register(TerminalSession(config))

    /** Makes an already-configured distro session manager-owned and starts it. */
    internal fun adopt(session: TerminalSession): TerminalSession = register(session)

    /** Returns an in-memory session for UI reattachment; never reconstructs one silently. */
    fun find(id: String): TerminalSession? = sessions[id]

    /** Bounded, read-only terminal transcript for the Command Core. */
    fun readTail(id: String, maxLines: Int): List<String>? = sessions[id]?.readTail(maxLines)

    /** Safe session metadata: IDs and lifecycle only, never command lines or input bytes. */
    fun summaries(): List<SessionSummary> = sessions.values
        .sortedBy { it.id }
        .map { session ->
            val state = session.state.value
            SessionSummary(
                id = session.id,
                state = when (state) {
                    is TerminalSession.State.NotStarted -> "not_started"
                    is TerminalSession.State.Starting -> "starting"
                    is TerminalSession.State.Running -> "running"
                    is TerminalSession.State.Exited -> "exited"
                    is TerminalSession.State.Error -> "error"
                    is TerminalSession.State.Stopped -> "stopped"
                },
                pid = (state as? TerminalSession.State.Running)?.pid
            )
        }

    /** Starts an already registered session. Safe if it is running or has exited. */
    fun start(id: String): TerminalSession? = sessions[id]?.also { it.start() }

    /** Stops a session and releases its process, buffer, and manager references. */
    fun close(id: String) {
        val session = sessions.remove(id) ?: return
        val rootfs = session.config.rootfsDir
        if (rootfs != null) {
            decrementRootfsCount(rootfs)
        }
        trackingJobs.remove(id)?.cancel()
        session.stop()
        publishActiveSessions()
    }

    private fun register(session: TerminalSession): TerminalSession {
        check(sessions.putIfAbsent(session.id, session) == null) { "duplicate terminal session id" }
        val rootfs = session.config.rootfsDir
        if (rootfs != null) {
            incrementRootfsCount(rootfs)
        }
        trackingJobs[session.id] = managerScope.launch {
            session.state.collect { publishActiveSessions() }
        }
        session.start()
        publishActiveSessions()
        return session
    }

    /**
     * Increment the per-rootfs live-session count. The first session on
     * a rootfs fires [DistroSessionRegistry.onSessionStarted]; subsequent
     * sessions are no-ops as far as the registry is concerned.
     */
    private fun incrementRootfsCount(rootfs: File) {
        val count = activeRootfsCounts.computeIfAbsent(rootfs) { AtomicInteger(0) }
        if (count.incrementAndGet() == 1) {
            distroSessionRegistry.onSessionStarted(rootfs)
        }
    }

    /**
     * Decrement the per-rootfs live-session count. When the count
     * reaches zero, fire [DistroSessionRegistry.onSessionStopped] and
     * drop the entry from the map.
     */
    private fun decrementRootfsCount(rootfs: File) {
        val count = activeRootfsCounts[rootfs] ?: return
        if (count.decrementAndGet() <= 0) {
            activeRootfsCounts.remove(rootfs, count)
            distroSessionRegistry.onSessionStopped(rootfs)
        }
    }

    private fun publishActiveSessions() {
        _activeSessionIds.value = sessions.values
            .asSequence()
            .filter { it.state.value.isForegroundActive() }
            .map { it.id }
            .toSet()
    }

    private fun TerminalSession.State.isForegroundActive(): Boolean =
        this is TerminalSession.State.Starting || this is TerminalSession.State.Running
}

data class SessionSummary(
    val id: String,
    val state: String,
    val pid: Long?
)
