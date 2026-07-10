package com.elysium.vanguard.features.runtime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.distros.EffectiveCatalogRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * PHASE 9.6.3 — Owns the Hilt-managed [DistroManager] and exposes its
 * state to the runtime UI.
 *
 * The manager is provided by [com.elysium.vanguard.core.runtime.distros.DistroModule];
 * injecting it (instead of building our own, as Phase 9.6.2 did) lets
 * the terminal screen share the same install list as the runtime screen
 * — installing on one is observed on the other without any explicit
 * signaling.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
@HiltViewModel
class RuntimeViewModel @Inject constructor(
    application: Application,
    private val manager: DistroManager
) : AndroidViewModel(application) {

    val installed: StateFlow<List<DistroInstallation>> = manager.installed
    val installing: StateFlow<Set<String>> = manager.installing
    val errors: StateFlow<Map<String, String>> = manager.errors

    /**
     * PHASE 9.6.3.3 — Effective catalog: catalog-official + custom
     * installed on disk. Recomputed whenever [installed] changes (i.e.
     * after every install / remove). The screen reads this directly.
     */
    private val _effectiveCatalog = MutableStateFlow<List<EffectiveCatalogRow>>(emptyList())
    val effectiveCatalog: StateFlow<List<EffectiveCatalogRow>> = _effectiveCatalog.asStateFlow()

    private val threadPool = Executors.newFixedThreadPool(2)
    private val cancellation = AtomicBoolean(false)

    init {
        // Refresh the effective catalog any time the installed list
        // emits a new value. Cheap because the list is small.
        kotlinx.coroutines.flow.combine(manager.installed, manager.installing) { _, _ ->
            manager.effectiveCatalog()
        }.let { flow ->
            viewModelScope.launch {
                flow.collect { _effectiveCatalog.value = it }
            }
        }
        // Seed once in case combine() above never fires (shouldn't,
        // but a defensive seed makes the screen paint on first frame).
        _effectiveCatalog.value = manager.effectiveCatalog()
    }

    fun installBlocking(id: String) {
        if (installing.value.contains(id)) return
        cancellation.set(false)
        threadPool.submit {
            if (cancellation.get()) return@submit
            manager.installBlocking(id)
        }
    }

    fun remove(id: String): Boolean {
        val ok = manager.remove(id)
        return ok
    }

    override fun onCleared() {
        cancellation.set(true)
        threadPool.shutdown()
        threadPool.awaitTermination(2, TimeUnit.SECONDS)
        super.onCleared()
    }
}

/**
 * PHASE 9.6.3 — Default [DistroBaseDirResolver]. Kept as a no-arg
 * convenience so callers that don't want to inject the Hilt-managed
 * manager can still get the canonical base dir.
 */
object DistroBaseDirResolver {
    fun baseDirFor(application: Application): java.io.File {
        val dir = java.io.File(application.filesDir, "distros")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
