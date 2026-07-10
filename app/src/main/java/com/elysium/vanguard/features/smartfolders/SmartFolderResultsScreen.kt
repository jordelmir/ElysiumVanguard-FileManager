package com.elysium.vanguard.features.smartfolders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elysium.vanguard.core.database.SmartFolderEntity
import com.elysium.vanguard.core.smartfolders.SmartFolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * PHASE 2.13 — Smart folder results screen.
 *
 * Re-evaluates the saved query every time the user taps Refresh (or the first
 * time the screen is opened). Results are not cached because the filesystem is
 * the source of truth and we want fresh data.
 */
@HiltViewModel
class SmartFolderResultsViewModel @Inject constructor(
    private val repository: SmartFolderRepository,
    savedState: SavedStateHandle
) : ViewModel() {

    data class State(
        val folderId: Long = -1L,
        val folderName: String = "",
        val query: String = "",
        val rootPath: String = "",
        val isLoading: Boolean = true,
        val results: List<File> = emptyList(),
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val id = savedState.get<String>("id")?.toLongOrNull() ?: -1L
        _state.update { it.copy(folderId = id) }
        viewModelScope.launch {
            val folder = repository.get(id)
            if (folder == null) {
                _state.update { it.copy(isLoading = false, errorMessage = "Smart folder not found") }
                return@launch
            }
            _state.update {
                it.copy(
                    folderName = folder.name,
                    query = folder.query,
                    rootPath = folder.rootPath
                )
            }
            evaluate(folder)
        }
    }

    fun refresh() {
        val id = _state.value.folderId
        viewModelScope.launch {
            val folder = repository.get(id) ?: return@launch
            evaluate(folder)
        }
    }

    fun consumeError() = _state.update { it.copy(errorMessage = null) }

    private suspend fun evaluate(folder: SmartFolderEntity) {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val results = repository.evaluate(folder)
            _state.update { it.copy(isLoading = false, results = results) }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, errorMessage = "Evaluation failed: ${e.message}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartFolderResultsScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    viewModel: SmartFolderResultsViewModel = hiltViewModel()
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
                        Text(
                            state.folderName.ifEmpty { "Smart folder" },
                            color = Color(0xFF66FFB2),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${state.results.size} matches · ${state.query}",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color(0xFF66FFB2))
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
                CircularProgressIndicator(color = Color(0xFF66FFB2))
            }
            return@Scaffold
        }

        if (state.results.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No matches", color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                    Text(
                        "In ${state.rootPath} with: ${state.query}",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(state.results, key = { it.absolutePath }) { file ->
                ResultRow(file = file, onClick = { onOpenFile(file.absolutePath) })
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }
    }
}

@Composable
private fun ResultRow(file: File, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatSize(file.length())} · ${file.parent ?: "?"}",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}