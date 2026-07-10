package com.elysium.vanguard.features.smartfolders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.SmartFolderEntity
import com.elysium.vanguard.core.smartfolders.SmartFolderRepository
import com.elysium.vanguard.ui.theme.TitanColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * PHASE 2.13 — Smart folder screen.
 *
 * Two views in one screen:
 * - List view: saved smart folders, tap to open results.
 * - Editor view (modal-ish): name + query + root path + Save.
 */
@HiltViewModel
class SmartFolderViewModel @Inject constructor(
    private val repository: SmartFolderRepository
) : ViewModel() {

    data class State(
        val folders: List<SmartFolderEntity> = emptyList(),
        val showEditor: Boolean = false,
        val draftName: String = "",
        val draftQuery: String = "",
        val draftRootPath: String = "/storage/emulated/0/Download",
        val isSaving: Boolean = false,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { folders ->
                _state.update { it.copy(folders = folders) }
            }
        }
    }

    fun showEditor() = _state.update { it.copy(showEditor = true, errorMessage = null) }
    fun hideEditor() = _state.update { it.copy(showEditor = false) }

    fun setDraftName(value: String) = _state.update { it.copy(draftName = value) }
    fun setDraftQuery(value: String) = _state.update { it.copy(draftQuery = value) }
    fun setDraftRootPath(value: String) = _state.update { it.copy(draftRootPath = value) }

    fun saveDraft() {
        val s = _state.value
        if (s.draftName.isBlank() || s.draftQuery.isBlank() || s.draftRootPath.isBlank()) {
            _state.update { it.copy(errorMessage = "Name, query and root path are all required.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            try {
                repository.create(s.draftName, s.draftQuery, s.draftRootPath)
                _state.update {
                    State(folders = it.folders, showEditor = false)  // reset
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, errorMessage = "Save failed: ${e.message}") }
            }
        }
    }

    fun delete(folder: SmartFolderEntity) {
        viewModelScope.launch {
            repository.delete(folder)
        }
    }

    fun consumeError() = _state.update { it.copy(errorMessage = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFoldersScreen(
    onBack: () -> Unit,
    onOpenFolder: (SmartFolderEntity) -> Unit,
    viewModel: SmartFolderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Smart Folders", color = TitanColors.NeonCyan, fontWeight = FontWeight.Bold)
                        Text(
                            "${state.folders.size} saved",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
        floatingActionButton = {
            if (!state.showEditor) {
                FloatingActionButton(
                    onClick = { viewModel.showEditor() },
                    containerColor = TitanColors.NeonCyan,
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New smart folder")
                }
            }
        }
    ) { padding ->
        if (state.showEditor) {
            SmartFolderEditor(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.padding(padding)
            )
        } else {
            SmartFolderList(
                folders = state.folders,
                onOpen = onOpenFolder,
                onDelete = viewModel::delete,
                onCreate = viewModel::showEditor,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun SmartFolderList(
    folders: List<SmartFolderEntity>,
    onOpen: (SmartFolderEntity) -> Unit,
    onDelete: (SmartFolderEntity) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (folders.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text("No smart folders yet", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                Text(
                    "Save a search like \"ext:pdf size:>1MB\" to reuse it.",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(folders, key = { it.id }) { folder ->
            SmartFolderRow(
                folder = folder,
                onOpen = { onOpen(folder) },
                onDelete = { onDelete(folder) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        }
    }
}

@Composable
private fun SmartFolderRow(
    folder: SmartFolderEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = TitanColors.NeonCyan, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(folder.query, fontSize = 11.sp, color = TitanColors.NeonCyan)
            Text(
                "${folder.rootPath} · ${folder.lastEvaluatedAt?.let { "checked ${formatDate(it)}" } ?: "never opened"}",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.4f)
            )
        }
        IconButton(onClick = onOpen) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Open", tint = TitanColors.RadioactiveGreen)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = TitanColors.QuantumPink)
        }
    }
}

@Composable
private fun SmartFolderEditor(
    state: SmartFolderViewModel.State,
    viewModel: SmartFolderViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("New smart folder", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Saved searches that re-run on the live filesystem every time you open them.",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.5f)
        )

        OutlinedTextField(
            value = state.draftName,
            onValueChange = viewModel::setDraftName,
            label = { Text("Name") },
            placeholder = { Text("Big PDFs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = darkFieldColors()
        )

        OutlinedTextField(
            value = state.draftQuery,
            onValueChange = viewModel::setDraftQuery,
            label = { Text("Query (FileFilterParser DSL)") },
            placeholder = { Text("ext:pdf size:>1MB modified:last_week") },
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            colors = darkFieldColors()
        )

        OutlinedTextField(
            value = state.draftRootPath,
            onValueChange = viewModel::setDraftRootPath,
            label = { Text("Root path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = darkFieldColors()
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.hideEditor() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", color = Color.White)
            }
            Button(
                onClick = { viewModel.saveDraft() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.NeonCyan),
                enabled = !state.isSaving
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Filter DSL reference", color = TitanColors.NeonCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(
            """
            name:foo         name contains "foo"
            name~:^foo$      name matches regex
            ext:pdf,doc      extension is pdf or doc
            size:>5MB        size greater than 5 megabytes
            size:<1KB        size less than 1 kilobyte
            modified:today   modified today
            modified:last_week
            type:image       type is image / video / audio / doc / file / dir
            """.trimIndent(),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
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

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))