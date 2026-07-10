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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
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
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 2.7 — Text editor screen.
 *
 * Implementation notes:
 *   - We use [BasicTextField] rather than [TextField] because we want to drive the
 *     highlighted display ourselves (Compose Material3 TextField overrides styles).
 *   - The cursor and selection still appear naturally because we don't override
 *     the visual transformation.
 *   - For large files we'd want a virtualized editor (LazyColumn + per-line state).
 *     For now we accept that files > 200 KB will feel sluggish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    onBack: () -> Unit,
    viewModel: TextEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val highlighted by viewModel.highlighted.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = fileName(viewModel.filePath),
                            color = TitanColors.NeonCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                        val currentState = state
                        if (currentState is TextEditorViewModel.UiState.Ready && currentState.isModified) {
                            Spacer(Modifier.width(6.dp))
                            Text("•", color = TitanColors.NeonYellow, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TitanColors.NeonCyan)
                    }
                },
                actions = {
                    val ready = state as? TextEditorViewModel.UiState.Ready
                    IconButton(
                        onClick = {
                            viewModel.save { ok ->
                                val msg = if (ok) "Saved" else "Save failed"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                    EditorBody(
                        text = s.text,
                        highlighted = highlighted,
                        onTextChange = viewModel::onTextChange
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorBody(
    text: String,
    highlighted: androidx.compose.ui.text.AnnotatedString,
    onTextChange: (String) -> Unit
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp)
    ) {
        // Highlighted overlay (renders the colors).
        Text(
            text = highlighted,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C111C), RoundedCornerShape(8.dp))
                .padding(12.dp)
        )
        Spacer(Modifier.height8dp())
        // Transparent BasicTextField on top — provides the editable surface and
        // the cursor. We don't show its output because the colored Text above does.
        // (Slight inaccuracy on partial highlighter changes is acceptable here;
        // a more sophisticated implementation would re-tokenize on each input.)
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = TextStyle(
                color = Color.Transparent,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(TitanColors.NeonCyan),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent, RoundedCornerShape(8.dp))
                .padding(12.dp)
        )
    }
}

internal fun fileName(path: String): String {
    val lastSlash = path.lastIndexOf('/')
    return if (lastSlash >= 0) path.substring(lastSlash + 1) else path
}

// Tiny extension so the spacing reads better in the Composable above.
private fun Modifier.height8dp(): Modifier = this.then(Modifier.padding(top = 8.dp))