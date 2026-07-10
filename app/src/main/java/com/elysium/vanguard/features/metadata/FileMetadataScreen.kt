package com.elysium.vanguard.features.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.FileMetadataEntity
import com.elysium.vanguard.core.metadata.FileMetadataRepository
import com.elysium.vanguard.ui.theme.TitanColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PHASE 2.12 — Per-file metadata editor (tags + color + note).
 *
 * Reached from any file context menu. The route passes the file URI and display
 * name as nav args; the screen loads the current metadata (if any), lets the
 * user edit it, and saves on close.
 */
@HiltViewModel
class FileMetadataViewModel @Inject constructor(
    private val repository: FileMetadataRepository,
    savedState: SavedStateHandle
) : ViewModel() {

    data class State(
        val key: String = "",
        val displayName: String = "",
        val tags: List<String> = emptyList(),
        val tagInput: String = "",
        val colorHex: String? = null,
        val note: String = "",
        val isLoading: Boolean = true,
        val savedAt: Long? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        // Nav args are passed via the SavedStateHandle as "key" and "name".
        val key = savedState.get<String>("key").orEmpty()
        val name = savedState.get<String>("name").orEmpty()
        if (key.isNotEmpty()) {
            viewModelScope.launch {
                val existing = repository.get(key)
                _state.update {
                    it.copy(
                        key = key,
                        displayName = name,
                        tags = repository.parseTags(existing?.tags),
                        colorHex = existing?.colorHex,
                        note = existing?.note.orEmpty(),
                        isLoading = false
                    )
                }
            }
        } else {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun setTagInput(value: String) {
        _state.update { it.copy(tagInput = value) }
    }

    fun commitTagInput() {
        val s = _state.value
        val input = s.tagInput.trim()
        if (input.isEmpty()) return
        val parts = input.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        val updated = (s.tags + parts).distinct()
        _state.update { it.copy(tags = updated, tagInput = "") }
    }

    fun removeTag(tag: String) {
        _state.update { it.copy(tags = it.tags - tag) }
    }

    fun setColor(hex: String?) {
        _state.update { it.copy(colorHex = hex) }
    }

    fun setNote(value: String) {
        _state.update { it.copy(note = value) }
    }

    fun save() {
        val s = _state.value
        if (s.key.isEmpty()) return
        viewModelScope.launch {
            repository.save(
                key = s.key,
                displayName = s.displayName,
                tags = s.tags,
                colorHex = s.colorHex,
                note = s.note
            )
            _state.update { it.copy(savedAt = System.currentTimeMillis()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileMetadataScreen(
    onBack: () -> Unit,
    viewModel: FileMetadataViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.savedAt) {
        if (state.savedAt != null) {
            snackbarHostState.showSnackbar("Saved")
            // Don't auto-navigate back; user may want to keep editing.
            // The savedAt flag is consumed by ViewModel on the next save.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tags & Notes", color = TitanColors.NeonCyan, fontWeight = FontWeight.Bold)
                        Text(
                            state.displayName.ifEmpty { "—" },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) {
                        Text("Save", color = TitanColors.RadioactiveGreen, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TitanColors.NeonCyan)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── TAGS ──
            item {
                SectionLabel(icon = Icons.Filled.Sell, title = "Tags")
            }
            item {
                OutlinedTextField(
                    value = state.tagInput,
                    onValueChange = viewModel::setTagInput,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type a tag and press +", color = Color.White.copy(alpha = 0.4f)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.commitTagInput() }),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.commitTagInput() }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add tag", tint = TitanColors.NeonCyan)
                        }
                    },
                    singleLine = true,
                    colors = darkFieldColors()
                )
            }
            item {
                if (state.tags.isEmpty()) {
                    Text(
                        "No tags yet. Try: work, urgent, contract, archive.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 12.sp
                    )
                } else {
                    TagChipsRow(tags = state.tags, onRemove = viewModel::removeTag)
                }
            }

            // ── COLOR ──
            item {
                SectionLabel(icon = Icons.Filled.Palette, title = "Color")
            }
            item {
                ColorSwatches(
                    selectedHex = state.colorHex,
                    onSelect = viewModel::setColor
                )
            }

            // ── NOTE ──
            item {
                @Suppress("DEPRECATION")
SectionLabel(icon = Icons.Filled.Note, title = "Note")
            }
            item {
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    placeholder = { Text("Add a private note about this file…", color = Color.White.copy(alpha = 0.4f)) },
                    colors = darkFieldColors()
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = TitanColors.NeonCyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipsRow(tags: List<String>, onRemove: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = false,
                onClick = { onRemove(tag) },
                label = { Text(tag) },
                trailingIcon = { Text("×", color = TitanColors.QuantumPink) },
                colors = InputChipDefaults.inputChipColors(
                    containerColor = TitanColors.NeonCyan.copy(alpha = 0.15f),
                    labelColor = Color.White
                )
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatches(selectedHex: String?, onSelect: (String?) -> Unit) {
    val swatches = listOf(
        null to "None",
        "#FF6B6B" to "Coral",
        "#FFD93D" to "Sun",
        "#6BCB77" to "Mint",
        "#4D96FF" to "Sky",
        "#A66CFF" to "Violet",
        "#FF6BD6" to "Pink",
        "#FF9F45" to "Tangerine",
        "#9D9D9D" to "Slate"
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        swatches.forEach { (hex, _) ->
            val color = hex?.let { parseHexColor(it) } ?: Color.Transparent
            val isSelected = hex == selectedHex
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(color, CircleShape)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) TitanColors.NeonCyan else Color.White.copy(alpha = 0.2f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) },
                contentAlignment = Alignment.Center
            ) {
                if (hex == null) {
                    Text("∅", color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun parseHexColor(hex: String): Color {
    val cleaned = hex.removePrefix("#")
    val rgb = cleaned.toLong(16)
    return when (cleaned.length) {
        6 -> Color(0xFF000000 or rgb)
        8 -> Color(rgb)
        else -> Color.Gray
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = TitanColors.NeonCyan,
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    focusedContainerColor = Color.White.copy(alpha = 0.04f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.02f),
    cursorColor = TitanColors.NeonCyan
)