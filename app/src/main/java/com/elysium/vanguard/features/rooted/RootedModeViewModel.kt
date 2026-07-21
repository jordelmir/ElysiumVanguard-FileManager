package com.elysium.vanguard.features.rooted

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.launcher.CgroupSpec
import com.elysium.vanguard.core.runtime.distros.launcher.NamespaceSpec
import com.elysium.vanguard.core.runtime.distros.launcher.RootStatus
import com.elysium.vanguard.core.runtime.distros.launcher.RootedModeProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PHASE 102 — the **rooted mode settings** view model.
 *
 * Owns the UI state for the
 * [RootedModeScreen]. Three things:
 *
 *   1. The current [RootStatus] from the
 *      [RootedModeProbe] (refreshed on every
 *      screen open + on `onRecheck()`).
 *   2. The user's "rooted mode enabled" toggle
 *      (persisted via the [RootedModePrefs]
 *      injected narrow interface).
 *   3. The user's namespace + cgroup config
 *      (persisted the same way).
 *
 * The view model never executes anything
 * dangerous — toggling the switch just changes
 * the in-memory + persisted state; the
 * [com.elysium.vanguard.core.runtime.distros.launcher.NamespacedDistroLauncher]
 * is what actually reads these values on the
 * next launch.
 */
@HiltViewModel
class RootedModeViewModel @Inject constructor(
    private val probe: RootedModeProbe,
    private val prefs: RootedModePrefs,
) : ViewModel() {

    private val _state = MutableStateFlow(RootedModeUiState())
    val state: StateFlow<RootedModeUiState> = _state.asStateFlow()

    init {
        // Load persisted prefs synchronously (they're tiny).
        val enabled = prefs.isRootedModeEnabled()
        val ns = prefs.namespaceSpec()
        val cg = prefs.cgroupSpec()
        _state.update {
            it.copy(
                rootedModeEnabled = enabled,
                namespaceSpec = ns,
                cgroupSpec = cg,
            )
        }
        refreshStatus()
    }

    /**
     * Re-probe the device's root status. The probe
     * caches for 5s, so spamming this button doesn't
     * spawn a `su` call.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            // The probe is cheap (cached) but we still
            // hop to IO to avoid blocking the main thread
            // on a first-time `su` call.
            val status = withContext(Dispatchers.IO) { probe.probe() }
            _state.update { it.copy(status = status) }
        }
    }

    fun onRootedModeToggle(enabled: Boolean) {
        prefs.setRootedModeEnabled(enabled)
        _state.update { it.copy(rootedModeEnabled = enabled) }
    }

    fun onUserNamespaceToggle(enabled: Boolean) {
        val newSpec = _state.value.namespaceSpec.copy(user = enabled)
        prefs.setNamespaceSpec(newSpec)
        _state.update { it.copy(namespaceSpec = newSpec) }
    }

    fun onCgroupSpecChange(spec: CgroupSpec) {
        prefs.setCgroupSpec(spec)
        _state.update { it.copy(cgroupSpec = spec) }
    }

    fun onResetCgroup() {
        onCgroupSpecChange(CgroupSpec.NONE)
    }

    fun onApplyBackgroundCgroup() {
        onCgroupSpecChange(CgroupSpec.BACKGROUND)
    }
}

/**
 * The view state.
 */
data class RootedModeUiState(
    val status: RootStatus? = null,
    val rootedModeEnabled: Boolean = false,
    val namespaceSpec: NamespaceSpec = NamespaceSpec.FULL_SANDBOX,
    val cgroupSpec: CgroupSpec = CgroupSpec.NONE,
)

/**
 * Narrow interface for the **persisted** Rooted Mode
 * settings. Production binds a SharedPreferences-backed
 * impl via Hilt; tests stub it with a 5-line
 * in-memory impl.
 *
 * **Why a narrow interface and not SharedPreferences
 * directly**: the view model is otherwise JVM-testable
 * and we want to keep that property. SharedPreferences
 * is an Android-only type that drags in Context.
 */
interface RootedModePrefs {
    fun isRootedModeEnabled(): Boolean
    fun setRootedModeEnabled(enabled: Boolean)

    fun namespaceSpec(): NamespaceSpec
    fun setNamespaceSpec(spec: NamespaceSpec)

    fun cgroupSpec(): CgroupSpec
    fun setCgroupSpec(spec: CgroupSpec)
}
