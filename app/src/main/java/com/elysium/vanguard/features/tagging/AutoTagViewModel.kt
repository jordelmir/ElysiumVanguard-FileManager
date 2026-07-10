package com.elysium.vanguard.features.tagging

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.FileMetadataDao
import com.elysium.vanguard.core.database.FileMetadataEntity
import com.elysium.vanguard.core.tagging.ImageTagger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * PHASE 3.10 — Auto-tagging ViewModel.
 *
 * Runs ML Kit's image labeler across a batch of URIs, accumulates the union
 * of suggested tags, and applies the user's selection to the metadata DB.
 *
 * Progress is reported in real time so the UI can show "Analyzing 3 of 12…".
 */
@HiltViewModel
class AutoTagViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagger: ImageTagger,
    private val metadataDao: FileMetadataDao
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun tagBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.value = UiState(
            running = true,
            total = uris.size,
            remaining = uris.size,
            imageCount = uris.size,
            lastUris = uris
        )
        viewModelScope.launch {
            val allLabels = mutableSetOf<String>()
            for ((index, uri) in uris.withIndex()) {
                try {
                    val labels = withContext(Dispatchers.IO) { tagger.tag(uri) }
                    allLabels.addAll(labels.map { it.label })
                } catch (_: Exception) {
                    // Skip un-processable images, continue with the rest.
                }
                _state.value = _state.value.copy(
                    remaining = uris.size - index - 1
                )
            }
            _state.value = _state.value.copy(
                running = false,
                suggestions = allLabels.sorted(),
                selected = allLabels.toSet()
            )
        }
    }

    fun toggle(tag: String) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (tag in current) current - tag else current + tag
        )
    }

    fun apply() {
        val tags = _state.value.selected.toList().sorted()
        if (tags.isEmpty()) return
        val csv = tags.joinToString(",")
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                for (uri in _state.value.lastUris) {
                    val existing = metadataDao.getByUri(uri.toString())
                    val merged = if (existing != null) {
                        val existingTags = existing.tags.split(",")
                            .filter { it.isNotEmpty() }
                            .toSet()
                        val combined = (existingTags + tags).joinToString(",")
                        existing.copy(tags = combined, updatedAt = System.currentTimeMillis())
                    } else {
                        FileMetadataEntity(
                            uri = uri.toString(),
                            displayName = File(uri.path ?: "").name.ifEmpty { uri.lastPathSegment ?: "image" },
                            tags = csv,
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis()
                        )
                    }
                    metadataDao.upsert(merged)
                }
                tags.size
            }
            Toast.makeText(context, "Applied $count tag(s)", Toast.LENGTH_SHORT).show()
            _state.value = UiState()  // reset
        }
    }

    data class UiState(
        val running: Boolean = false,
        val total: Int = 0,
        val remaining: Int = 0,
        val imageCount: Int = 0,
        val suggestions: List<String> = emptyList(),
        val selected: Set<String> = emptySet(),
        val lastUris: List<Uri> = emptyList()
    )
}