package com.elysium.vanguard.features.runtime.inspect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot
import com.elysium.vanguard.core.runtime.distros.snapshot.DistroSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PHASE 9.6.3.2 — Inspect view-model.
 *
 * Holds the [RootfsIntrospectorSnapshot] for the currently-displayed
 * distro plus the list of [DistroSnapshot]s that originated from it.
 * All filesystem reads happen on `Dispatchers.IO`.
 *
 * Why not just collect from a long-lived flow: introspecting a rootfs
 * is cheap enough that running it once per screen visit is preferable
 * to stale-cache debugging. If the user complains about latency we'll
 * add a `derivedStateOf` background scan.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
@HiltViewModel
class RuntimeInspectViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val manager: DistroManager
) : AndroidViewModel(application) {

    /** Nav argument carrying the distro id under inspection. */
    private val distroId: String =
        savedStateHandle.get<String>(DISTRO_ID_ARG)?.takeIf { it.isNotEmpty() } ?: ""

    /** Resolves once per VM lifecycle; cheap and avoids UI flicker. */
    val installation = MutableStateFlow<DistroInstallation?>(null)
    val snapshot = MutableStateFlow<RootfsIntrospectorSnapshot?>(null)
    val snapshots = MutableStateFlow<List<DistroSnapshot>>(emptyList())
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { loadAll() }
        }
    }

    fun setTab(tab: Int) {
        if (tab in 0..3) _selectedTab.value = tab
    }

    fun captureSnapshot() {
        if (distroId.isEmpty()) return
        viewModelScope.launch {
            _isBusy.value = true
            try {
                withContext(Dispatchers.IO) {
                    manager.captureSnapshot(distroId)
                    refreshSnapshots()
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun removeSnapshot(snapshotId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                manager.removeSnapshot(snapshotId)
                refreshSnapshots()
            }
        }
    }

    private fun loadAll() {
        val install = manager.findInstalled(distroId)
        installation.value = install
        if (install != null && install.isHealthy) {
            manager.introspect(distroId) { snap ->
                snapshot.value = snap
            }
            refreshSnapshots()
        } else {
            snapshot.value = null
            snapshots.value = emptyList()
        }
    }

    private fun refreshSnapshots() {
        if (distroId.isEmpty()) {
            snapshots.value = emptyList()
            return
        }
        snapshots.value = manager.snapshotsFor(distroId)
    }

    companion object {
        const val DISTRO_ID_ARG = "distroId"
    }
}
