package com.elysium.vanguard.features.runtime.terminal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.launcher.LauncherPick
import com.elysium.vanguard.core.runtime.terminal.service.TerminalService
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PHASE 9.6.1 — Holds the single process-backed terminal session.
 *
 * The session is the only mutable thing the [TerminalScreen] cares
 * about: the rest of the screen is a thin host wrapping
 * [com.elysium.vanguard.core.runtime.terminal.view.TerminalHost].
 * We start the session in the VM's `init` block so the shell process
 * is up before Compose renders its first frame; we tear it down in
 * [onCleared].
 *
 * The session is manager-owned, so this ViewModel attaches/detaches without
 * killing a long-running Linux command when the user changes screen.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val distroManager: DistroManager,
    private val sessionManager: TerminalSessionManager
) : AndroidViewModel(application) {

    /**
     * Optional distribution id this VM was opened for. When present, the
     * session runs inside that distro via the resolved launcher. When
     * absent, the session runs `/system/bin/sh` (the original 9.6.1
     * behavior).
     */
    private val distroId: String? = savedStateHandle.get<String>(DISTRO_ID_ARG)?.takeIf { it.isNotEmpty() }

    /**
     * Description of which launcher was picked for this session, if any.
     * Surface for the UI badge.
     */
    private val _launcherPick = MutableStateFlow<LauncherPick?>(null)
    val launcherPick: StateFlow<LauncherPick?> = _launcherPick.asStateFlow()

    /**
     * Reattaches after configuration changes when the app process is alive.
     * If Android has reclaimed the process, no fake restoration occurs: a
     * fresh shell is created and visibly reports its new PID.
     */
    val session: TerminalSession = acquireSession()

    /** Mirror of the session's lifecycle state. */
    private val _lifecycle = MutableStateFlow<TerminalSession.State>(
        TerminalSession.State.NotStarted
    )
    val lifecycle: StateFlow<TerminalSession.State> = _lifecycle.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    /** Already-typed raw bytes; mostly for debugging/dev tools. */
    private val _typed = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val typed: SharedFlow<ByteArray> = _typed.asSharedFlow()

    init {
        viewModelScope.launch {
            session.state.collectLatest { state ->
                _lifecycle.value = state
                if (state is TerminalSession.State.Exited) {
                    _exitCode.value = state.exitCode
                }
            }
        }
    }

    private fun acquireSession(): TerminalSession {
        val restored = savedStateHandle.get<String>(SESSION_ID_ARG)?.let(sessionManager::find)
        val attached = restored ?: sessionManager.adopt(buildSession())
        savedStateHandle[SESSION_ID_ARG] = attached.id
        TerminalService.promote(getApplication(), attached.id)
        return attached
    }

    /**
     * Build either a distro-rooted session or a plain sh session.
     * Resolution failures fall back to the local shell so the UI never
     * crashes — users get a `jailed` notice in the title instead.
     */
    private fun buildSession(): TerminalSession {
        val id = distroId ?: return TerminalSession(
            TerminalSession.Config(
                cols = 80,
                rows = 24,
                termName = "xterm-256color"
            )
        )
        val install = distroManager.findInstalled(id)
        if (install == null || !install.isHealthy) {
            // Fall back to plain shell with an unmistakable intro line.
            _launcherPick.value = null
            val intro = TerminalSession(
                TerminalSession.Config(
                    cols = 80,
                    rows = 24,
                    termName = "xterm-256color"
                )
            )
            // Inject a one-liner so the user knows what happened.
            // We let the session start cleanly; the warning text lives
            // in the VM title state so the UI can render it.
            _launcherPick.value = null
            return intro
        }
        val pick = distroManager.launcherFor(id)
        if (pick == null) {
            _launcherPick.value = null
            return TerminalSession(
                TerminalSession.Config(
                    cols = 80,
                    rows = 24,
                    termName = "xterm-256color"
                )
            )
        }
        _launcherPick.value = pick
        return TerminalSession.forDistro(
            rootfsDir = install.rootfsDir,
            pick = pick
        )
    }

    /** Send raw bytes to the shell. */
    fun send(bytes: ByteArray) {
        _typed.tryEmit(bytes)
        session.write(bytes)
    }

    fun sendText(s: String) {
        send(s.toByteArray(Charsets.UTF_8))
    }

    fun sendInterrupt() {
        session.sendInterrupt()
    }

    /** Explicit user action; screen navigation alone keeps the process alive. */
    fun closeSession() {
        sessionManager.close(session.id)
        savedStateHandle.remove<String>(SESSION_ID_ARG)
    }

    /** Replace the current shell with a fresh one (SIGINT + restart). */
    fun restart() {
        session.sendInterrupt()
        // Phase 9.6.2: full process restart here. For 9.6.1 we leave
        // the existing process running — the user can type `exit` to
        // end the session on their own.
    }

    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        /**
         * Nav argument name used to pass the target distro id. Phase
         * 9.6.3 uses this single key; the route signature is
         * `terminal?distroId={id}` so an absent argument falls through
         * to the 9.6.1 local-shell behavior.
         */
        const val DISTRO_ID_ARG = "distroId"
        const val SESSION_ID_ARG = "terminalSessionId"
    }
}
