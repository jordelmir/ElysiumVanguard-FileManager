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
            withContext(Dispatchers.IO) {
                // PHASE 96 — defensive try/catch around the
                // initial load. The previous version surfaced
                // uncaught exceptions to the default
                // `viewModelScope` handler which closed the
                // screen ("al tocar inspect se cierra"). The
                // introspector reads `var/lib/dpkg/status`
                // and walks the rootfs — both can throw on
                // partial installs / missing files. We catch
                // every exception, set the state to a safe
                // empty value, and let the user see the
                // "—" + "loading…" UI rather than a black
                // screen.
                try {
                    loadAll()
                } catch (e: Exception) {
                    installation.value = null
                    snapshot.value = null
                    snapshots.value = emptyList()
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
            try {
                withContext(Dispatchers.IO) {
                    manager.captureSnapshot(distroId)
                    refreshSnapshots()
                }
            } catch (_: Exception) {
                // PHASE 96 — don't crash the screen on a
                // snapshot failure. The UI shows the
                // progress spinner + dismisses on done.
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun removeSnapshot(snapshotId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    manager.removeSnapshot(snapshotId)
                } catch (_: Exception) {
                    // PHASE 96 — defensive: a failed remove
                    // shouldn't crash the screen.
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
            } catch (_: Exception) {
                // PHASE 96 — the introspector can throw on
                // a partial install (missing os-release,
                // permission errors on rootfs walk, etc).
                // Set the snapshot to a safe empty value
                // and continue; the user still sees the
                // installation header + a "no snapshot"
                // placeholder.
                snapshot.value = null
            }
            try {
                refreshSnapshots()
            } catch (_: Exception) {
                snapshots.value = emptyList()
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
