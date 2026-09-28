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
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    loadAll()
                } catch (e: Exception) {
                    installation.value = null
                    snapshot.value = null
                    snapshots.value = emptyList()
                    _errorMessage.value = "Failed to load: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    fun setTab(tab: Int) {
        if (tab in 0..3) _selectedTab.value = tab
    }

    fun captureSnapshot() {
        if (distroId.isEmpty()) return
        viewModelScope.launch {
            _isBusy.value = true
            _errorMessage.value = null
            try {
                withContext(Dispatchers.IO) {
                    manager.captureSnapshot(distroId)
                    refreshSnapshots()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Snapshot failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun removeSnapshot(snapshotId: String) {
        viewModelScope.launch {
            _errorMessage.value = null
            withContext(Dispatchers.IO) {
                try {
                    manager.removeSnapshot(snapshotId)
                } catch (e: Exception) {
                    _errorMessage.value = "Remove failed: ${e.message ?: e.javaClass.simpleName}"
                }
                refreshSnapshots()
            }
        }
    }

    private fun loadAll() {
        val install = manager.findInstalled(distroId)
        installation.value = install
        if (install != null && install.isHealthy) {
            try {
                manager.introspect(distroId) { snap ->
                    snapshot.value = snap
                }
            } catch (e: Exception) {
                snapshot.value = null
                _errorMessage.value = "Introspection failed: ${e.message ?: e.javaClass.simpleName}"
            }
            try {
                refreshSnapshots()
            } catch (e: Exception) {
                snapshots.value = emptyList()
                _errorMessage.value = "Snapshot list failed: ${e.message ?: e.javaClass.simpleName}"
            }
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
