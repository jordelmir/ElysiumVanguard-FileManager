package com.elysium.vanguard.features.ocr

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.ocr.OcrEngine
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
 * PHASE 3.11 — OCR ViewModel.
 *
 * State machine:
 *   IDLE → RUNNING (on image pick) → READY (with text) or ERROR
 *   READY → RUNNING (on next image pick)
 *
 * Saving writes to `<files-dir>/ocr/<timestamp>.txt`. The user can then move
 * the file via the regular file manager.
 */
@HiltViewModel
class OcrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: OcrEngine
) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun runOcr(uri: Uri) {
        _state.value = UiState(running = true)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { engine.extract(uri) }
                _state.value = UiState(text = result.fullText, running = false)
            } catch (e: Exception) {
                _state.value = UiState(
                    running = false,
                    error = e.message ?: "OCR failed"
                )
            }
        }
    }

    fun saveResult() {
        val text = _state.value.text
        if (text.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "ocr").apply { mkdirs() }
                val target = File(dir, "ocr-${System.currentTimeMillis()}.txt")
                try {
                    target.writeText(text)
                    target.absolutePath
                } catch (_: Exception) { null }
            }
            if (ok != null) {
                _state.value = _state.value.copy(lastSavedPath = ok)
                Toast.makeText(context, "Saved to $ok", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class UiState(
        val running: Boolean = false,
        val text: String = "",
        val error: String? = null,
        val lastSavedPath: String? = null
    )
}