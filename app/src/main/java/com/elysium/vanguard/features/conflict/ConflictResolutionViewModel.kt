package com.elysium.vanguard.features.conflict

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.conflict.Conflict
import com.elysium.vanguard.core.conflict.ConflictDetector
import com.elysium.vanguard.core.conflict.ConflictResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.io.File
import javax.inject.Inject

/**
 * PHASE 1.10 — Conflict resolution state holder.
 *
 * Two-phase lifecycle:
 *   1. On init, the ViewModel reads `sources` (CSV of source paths) and `dest`
 *      (destination directory) from SavedStateHandle, runs the detector, and
 *      publishes the list of conflicts.
 *   2. The user picks a resolution per row. The "Apply" button commits.
 *
 * The apply step runs on Dispatchers.IO. It returns the number of successfully
 * applied operations so the caller can pop back with a toast/snackbar.
 */
@HiltViewModel
class ConflictResolutionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val sources: List<String> = savedStateHandle.get<String>("sources")
        ?.split("|")
        ?.filter { it.isNotEmpty() }
        ?.map { URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) }
        ?: emptyList()

    private val destination: String = URLDecoder.decode(
        savedStateHandle["dest"] ?: "",
        StandardCharsets.UTF_8.toString()
    )

    private val detector = ConflictDetector()
    private val resolver = ConflictResolver()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val conflicts = withContext(Dispatchers.IO) {
                if (destination.isBlank()) emptyList()
                else detector.detect(sources, File(destination))
            }
            _state.value = UiState(conflicts = conflicts)
        }
    }

    fun setResolution(sourcePath: String, resolution: Conflict.Resolution) {
        val updated = _state.value.resolutions.toMutableMap()
        updated[sourcePath] = resolution
        _state.value = _state.value.copy(resolutions = updated)
    }

    /** Commit resolutions to disk. Returns the count of successful operations. */
    fun apply(): Int {
        val current = _state.value
        if (!current.canApply) return 0
        val outcomes = resolver.apply(current.conflicts, current.resolutions)
        return outcomes.count { it.success }
    }

    data class UiState(
        val conflicts: List<Conflict> = emptyList(),
        val resolutions: Map<String, Conflict.Resolution> = emptyMap()
    ) {
        val canApply: Boolean
            get() = conflicts.isNotEmpty() && resolutions.size == conflicts.size &&
                resolutions.values.none { it == Conflict.Resolution.PENDING }
    }
}