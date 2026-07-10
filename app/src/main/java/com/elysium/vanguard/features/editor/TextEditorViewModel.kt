package com.elysium.vanguard.features.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.editor.HighlightedString
import com.elysium.vanguard.core.editor.Language
import com.elysium.vanguard.core.editor.SyntaxHighlighter
import com.elysium.vanguard.core.editor.TextEditorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * PHASE 2.7 — Drives the text editor screen.
 *
 * State machine:
 *   LOADING → READY (with optional modified flag) → SAVING → READY
 *
 * Highlighting is computed off the main thread on every text change. For files
 * up to ~50 KB this is imperceptible; for larger files we'd want a debounce.
 */
@HiltViewModel
class TextEditorViewModel @Inject constructor(
    private val repository: TextEditorRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The file path this editor was opened for. URL-decoded because Compose
     *  Navigation keeps the path argument URL-encoded in SavedStateHandle. */
    val filePath: String
        get() = URLDecoder.decode(
            savedStateHandle["path"] ?: "",
            StandardCharsets.UTF_8.toString()
        )

    /** Detected language, set when the file loads. */
    val language: StateFlow<Language> = MutableStateFlow(Language.PLAIN)

    fun load() {
        if (filePath.isBlank()) {
            _state.value = UiState.Error("No file path")
            return
        }
        viewModelScope.launch {
            _state.value = UiState.Loading
            val loaded = repository.load(filePath)
            if (loaded == null) {
                _state.value = UiState.Error("Could not read file")
            } else {
                val lang = repository.detectLanguage(filePath)
                _language.value = lang
                recomputeHighlight(loaded, lang)
                _state.value = UiState.Ready(loaded, isModified = false)
            }
        }
    }

    fun onTextChange(newText: String) {
        val current = _state.value
        if (current !is UiState.Ready) return
        _state.value = current.copy(text = newText, isModified = true)
        recomputeHighlight(newText, _language.value)
    }

    fun save(onComplete: (Boolean) -> Unit) {
        val current = _state.value as? UiState.Ready ?: return onComplete(false)
        viewModelScope.launch {
            _state.value = current.copy(isSaving = true)
            val ok = repository.save(filePath, current.text)
            if (ok) {
                _state.value = (_state.value as? UiState.Ready)?.copy(isModified = false, isSaving = false)
                    ?: _state.value
            } else {
                _state.value = (_state.value as? UiState.Ready)?.copy(isSaving = false)
                    ?: _state.value
            }
            onComplete(ok)
        }
    }

    private val _highlighted = MutableStateFlow<AnnotatedString>(AnnotatedString(""))
    val highlighted: StateFlow<AnnotatedString> = _highlighted.asStateFlow()

    private val _language = MutableStateFlow(Language.PLAIN)

    private fun recomputeHighlight(text: String, lang: Language) {
        viewModelScope.launch {
            val tokens = withContext(Dispatchers.Default) {
                SyntaxHighlighter.tokenize(text, lang)
            }
            _highlighted.value = withContext(Dispatchers.Default) {
                HighlightedString.build(text, tokens)
            }
        }
    }

    sealed class UiState {
        object Loading : UiState()
        data class Ready(
            val text: String,
            val isModified: Boolean,
            val isSaving: Boolean = false
        ) : UiState()
        data class Error(val message: String) : UiState()
    }
}