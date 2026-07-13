package com.elysium.vanguard.features.runtime.desktop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppCatalog
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppEntry
import com.elysium.vanguard.core.runtime.distros.gui.GraphicalDesktopCapability
import com.elysium.vanguard.core.runtime.distros.gui.GraphicalDesktopCapabilityDetector
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PHASE 9.6.5 — Linux desktop view-model.
 *
 * Reads a distro's desktop entries and reports the real graphical capability
 * of that rootfs. It never manufactures a bitmap: until Elysium owns a real
 * X11/Wayland/VNC renderer, the workspace routes users to the PTY terminal.
 */
@HiltViewModel
class LinuxDesktopViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val manager: DistroManager
) : AndroidViewModel(application) {

    private val distroId: String =
        savedStateHandle.get<String>(DISTRO_ID_ARG)?.takeIf { it.isNotEmpty() } ?: ""

    val apps = MutableStateFlow<List<LinuxAppEntry>>(emptyList())
    val snapshot = MutableStateFlow<RootfsIntrospectorSnapshot?>(null)

    private val _capability = MutableStateFlow(
        GraphicalDesktopCapability(
            state = GraphicalDesktopCapability.State.ROOTFS_UNAVAILABLE,
            detail = "Checking installed runtime…"
        )
    )
    val capability: StateFlow<GraphicalDesktopCapability> = _capability.asStateFlow()

    private val _rfbSession = MutableStateFlow<RfbSession?>(null)
    internal val rfbSession: StateFlow<RfbSession?> = _rfbSession.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadAll() }
        }
    }

    private fun loadAll() {
        val install = manager.findInstalled(distroId)
        if (install == null || !install.isHealthy) {
            apps.value = emptyList()
            snapshot.value = null
            _capability.value = GraphicalDesktopCapabilityDetector.inspect(null, null)
            return
        }
        var captured: RootfsIntrospectorSnapshot? = null
        manager.introspect(distroId) { snap -> captured = snap }
        snapshot.value = captured
        val catalog = LinuxAppCatalog(install.rootfsDir)
        apps.value = catalog.listApps()
        _capability.value = GraphicalDesktopCapabilityDetector.inspect(
            rootfsDir = install.rootfsDir,
            launcherKind = manager.launcherFor(distroId)?.launcher?.kind
        )
    }

    /** Connect only to an already-running loopback VNC server inside this app sandbox. */
    fun connectLocalVnc() {
        if (_capability.value.state != GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_UNAVAILABLE) return
        val existing = _rfbSession.value
        if (existing != null && existing.state.value !is RfbSession.State.Failed &&
            existing.state.value !is RfbSession.State.Stopped
        ) return
        existing?.stop()
        _rfbSession.value = RfbSession().also(RfbSession::start)
    }

    fun disconnectLocalVnc() {
        _rfbSession.value?.stop()
        _rfbSession.value = null
    }

    override fun onCleared() {
        disconnectLocalVnc()
        super.onCleared()
    }

    companion object {
        const val DISTRO_ID_ARG = "distroId"
    }
}
