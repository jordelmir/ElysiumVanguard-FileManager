package com.elysium.vanguard.features.runtime.desktop

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppCatalog
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppEntry
import com.elysium.vanguard.core.runtime.distros.gui.VncSession
import com.elysium.vanguard.core.runtime.distros.gui.VncSessionFactory
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
 * Reads a distro's desktop entries (under usr/share/applications)
 * via [com.elysium.vanguard.core.runtime.distros.gui.LinuxAppCatalog],
 * opens a [VncSession] (stub today, native once libvncclient.so
 * ships), and exposes the frame to the UI as a Bitmap.
 *
 * Phase 9.6.5 — first build; intentionally minimal.
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

    /**
     * Held here so the GC doesn't tear down the frame between
     * captures. Real implementations will own a `libvncclient` handle.
     */
    val session: VncSession = VncSessionFactory.forDistro(
        distroId = distroId,
        displayName = "Linux desktop",
        rootfsDir = null
    )

    private val _frame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val frame: StateFlow<android.graphics.Bitmap?> = _frame.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadAll() }
        }
    }

    fun captureFrame() {
        viewModelScope.launch {
            _frame.value = withContext(Dispatchers.Default) { session.captureFrame() }
        }
    }

    private fun loadAll() {
        val install = manager.findInstalled(distroId)
        if (install == null || !install.isHealthy) {
            apps.value = emptyList()
            snapshot.value = null
            return
        }
        var captured: RootfsIntrospectorSnapshot? = null
        manager.introspect(distroId) { snap -> captured = snap }
        snapshot.value = captured
        val catalog = LinuxAppCatalog(install.rootfsDir)
        apps.value = catalog.listApps()
        captureFrame()
    }

    override fun onCleared() {
        session.close()
        super.onCleared()
    }

    companion object {
        const val DISTRO_ID_ARG = "distroId"
    }
}
