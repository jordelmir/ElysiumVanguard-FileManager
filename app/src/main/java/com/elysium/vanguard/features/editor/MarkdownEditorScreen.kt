package com.elysium.vanguard.features.editor

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.editor.MarkdownRenderer
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 2.6 — Markdown editor with live preview.
 *
 * Layout: top half is the source (BasicTextField, monospace, dark), bottom
 * half is the rendered preview (Text, regular weight). A toolbar toggle hides
 * the preview when the user wants a full-screen editor.
 *
 * Why split view by default: zero-friction preview. The user types and sees
 * the result immediately. The toggle is for distraction-free writing or for
 * narrow screens (e.g. landscape phones).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkdownEditorScreen(
    onBack: () -> Unit,
    viewModel: TextEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var previewVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { viewModel.load() }

    val renderer = remember { MarkdownRenderer() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName(viewModel.filePath),
                        color = TitanColors.NeonCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TitanColors.NeonCyan)
                    }
                },
                actions = {
                    IconButton(onClick = { previewVisible = !previewVisible }) {
                        Icon(
                            if (previewVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle preview",
                            tint = TitanColors.NeonCyan
                        )
                    }
                    val ready = state as? TextEditorViewModel.UiState.Ready
                    IconButton(
                        onClick = {
                            viewModel.save { ok ->
                                Toast.makeText(context, if (ok) "Saved" else "Save failed", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = ready?.isModified == true && !ready.isSaving
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save",
                            tint = if (ready?.isModified == true && !ready.isSaving) TitanColors.RadioactiveGreen
                            else TitanColors.NeonCyan.copy(alpha = 0.3f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF050810),
                    titleContentColor = TitanColors.NeonCyan
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .background(Color(0xFF050810))) {
            when (val s = state) {
                TextEditorViewModel.UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TitanColors.NeonCyan)
                    }
                }
                is TextEditorViewModel.UiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.message, color = TitanColors.QuantumPink, fontSize = 16.sp)
                    }
                }
                is TextEditorViewModel.UiState.Ready -> {
                    if (previewVisible) {
                        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                            // Source pane (top).
                            Box(modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF0C111C), RoundedCornerShape(8.dp))
                                .padding(12.dp)) {
                                BasicTextField(
                                    value = s.text,
                                    onValueChange = viewModel::onTextChange,
                                    textStyle = TextStyle(
                                        color = Color(0xFFE6ECF3),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    ),
                                    cursorBrush = SolidColor(TitanColors.NeonCyan),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            // Preview pane (bottom).
                            Box(modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF0C111C), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())) {
                                Text(text = renderer.render(s.text))
                            }
                        }
                    } else {
                        // Full-screen source.
                        BasicTextField(
                            value = s.text,
                            onValueChange = viewModel::onTextChange,
                            textStyle = TextStyle(
                                color = Color(0xFFE6ECF3),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(TitanColors.NeonCyan),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}