package com.elysium.vanguard.features.dualpane

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject

/**
 * PHASE 2.10 — Dual-pane state holder.
 *
 * Each pane tracks its own [PaneState]. "Open" on a folder navigates; on a
 * file, no-op (we don't open files from the dual pane — the user can switch
 * back to single-pane for that). "Copy here" sends a file from one pane to
 * the other via [java.nio.file.Files.copy].
 *
 * Conflict resolution lives separately (Phase 1.10). The dual pane calls
 * [ConflictResolver] when it detects a name collision; for now we just
 * overwrite + toast on conflict to keep the demo loop simple.
 */
@HiltViewModel
class DualPaneViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val defaultRoot: File = context.getExternalFilesDir(null) ?: context.filesDir

    private val _left = MutableStateFlow(PaneState())
    val left: StateFlow<PaneState> = _left.asStateFlow()

    private val _right = MutableStateFlow(PaneState())
    val right: StateFlow<PaneState> = _right.asStateFlow()

    // Tab stacks per pane: each tab owns its directory; only the active
    // tab's directory is mirrored into the pane's PaneState above.
    private val _leftTabs = MutableStateFlow(PaneTabs.initial(defaultRoot))
    val leftTabs: StateFlow<PaneTabsState> = _leftTabs.asStateFlow()

    private val _rightTabs = MutableStateFlow(PaneTabs.initial(defaultRoot))
    val rightTabs: StateFlow<PaneTabsState> = _rightTabs.asStateFlow()

    init {
        // Default both panes to the app's external files dir.
        open(PaneSide.LEFT, defaultRoot)
        open(PaneSide.RIGHT, defaultRoot)
    }

    fun openTab(side: PaneSide, dir: File) {
        val flow = tabsOf(side)
        flow.value = PaneTabs.openTab(flow.value, dir)
        loadDir(side, dir)
    }

    fun closeTab(side: PaneSide, index: Int) {
        val flow = tabsOf(side)
        val next = PaneTabs.closeTab(flow.value, index) ?: return
        flow.value = next
        loadDir(side, next.active.dir)
    }

    fun switchTab(side: PaneSide, index: Int) {
        val flow = tabsOf(side)
        val next = PaneTabs.switchTo(flow.value, index)
        flow.value = next
        loadDir(side, next.active.dir)
    }

    fun refresh(side: PaneSide) {
        val current = stateOf(side).value
        loadDir(side, current.currentDir)
    }

    fun open(side: PaneSide, file: File) {
        if (file.isDirectory) {
            loadDir(side, file)
        } else {
            // File tap: no-op in dual pane. The user switches to single-pane
            // for media/document viewing.
        }
    }

    fun goUp(side: PaneSide) {
        val current = stateOf(side).value.currentDir
        val parent = current.parentFile ?: return
        if (parent.canRead()) loadDir(side, parent)
    }

    fun copyToOtherPane(targetSide: PaneSide, file: File) {
        if (file.isDirectory) {
            Toast.makeText(context, "Folder copy not supported in dual pane", Toast.LENGTH_SHORT).show()
            return
        }
        val destination = stateOf(targetSide).value.currentDir
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val target = File(destination, file.name)
                    if (target.exists()) {
                        // For now, just skip + notify. Conflict resolution is a
                        // separate flow.
                        return@withContext CopyResult.SKIP
                    }
                    file.copyTo(target)
                    CopyResult.OK
                } catch (_: FileNotFoundException) {
                    CopyResult.ERROR
                } catch (_: SecurityException) {
                    CopyResult.ERROR
                } catch (_: Exception) {
                    CopyResult.ERROR
                }
            }
            val msg = when (ok) {
                CopyResult.OK -> "Copied to ${destination.name}"
                CopyResult.SKIP -> "Already exists in destination"
                CopyResult.ERROR -> "Copy failed"
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            if (ok == CopyResult.OK) {
                refresh(targetSide)
            }
        }
    }

    private fun stateOf(side: PaneSide): MutableStateFlow<PaneState> = when (side) {
        PaneSide.LEFT -> _left
        PaneSide.RIGHT -> _right
    }

    private fun tabsOf(side: PaneSide): MutableStateFlow<PaneTabsState> = when (side) {
        PaneSide.LEFT -> _leftTabs
        PaneSide.RIGHT -> _rightTabs
    }

    private fun loadDir(side: PaneSide, dir: File) {
        val flow = stateOf(side)
        // Navigation applies to the active tab of this pane.
        val tabs = tabsOf(side)
        tabs.value = PaneTabs.updateActiveDir(tabs.value, dir)
        flow.value = flow.value.copy(currentDir = dir, loading = true)
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                dir.listFiles()?.sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                ) ?: emptyList()
            }
            flow.value = flow.value.copy(entries = entries, loading = false)
        }
    }

    private enum class CopyResult { OK, SKIP, ERROR }
}

enum class PaneSide { LEFT, RIGHT }

data class PaneState(
    val currentDir: File = File("/"),
    val entries: List<File> = emptyList(),
    val loading: Boolean = false
)