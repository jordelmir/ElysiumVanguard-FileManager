package com.elysium.vanguard.features.crdteditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 9.15 — Compose editor screen for CRDT-backed Elysium
 * documents.
 *
 * Renders three fields — title, author, body — and a status row
 * showing sync state. Edits are propagated to the ViewModel as
 * intents; the ViewModel is the source of truth and we mirror
 * its state back into the Compose-managed local buffers so the
 * cursor stays where the user left it.
 *
 * We model body editing as append-and-backspace rather than
 * mid-string cursor manipulation. The CRDT sequence is happy
 * with either, but the append-only path keeps user input and
 * op-log entries aligned 1:1 — every backspace corresponds to
 * exactly one CRDT delete op, every keypress to exactly one
 * insert op.
 *
 * Phase 9.15 — first build; intentionally minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrdtDocumentEditorScreen(
    onBack: () -> Unit,
    viewModel: CrdtDocumentEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName(viewModel.filePath),
                            color = TitanColors.NeonCyan,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "CRDT editor · sync-ready",
                            color = TitanColors.NeonYellow,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TitanColors.NeonCyan
                        )
                    }
                },
                actions = {
                    val ready = state as? EditorState.Ready
                    IconButton(
                        onClick = { viewModel.dispatch(EditorIntent.Save) },
                        enabled = ready?.isDirty == true
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = "Save",
                            tint = if (ready?.isDirty == true)
                                TitanColors.NeonYellow
                            else
                                TitanColors.NeonCyan.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.dispatch(EditorIntent.Sync) },
                        enabled = ready != null
                    ) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Sync",
                            tint = TitanColors.NeonCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (val s = state) {
                EditorState.Empty -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = TitanColors.NeonCyan)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Opening CRDT session…",
                            color = TitanColors.NeonCyan
                        )
                    }
                }
                is EditorState.Ready -> {
                    MetadataFields(
                        title = s.title,
                        author = s.author,
                        onTitleChange = { viewModel.dispatch(EditorIntent.SetTitle(it)) },
                        onAuthorChange = { viewModel.dispatch(EditorIntent.SetAuthor(it)) }
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = TitanColors.NeonCyan.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    BodyEditor(
                        body = s.body,
                        onCharTyped = { viewModel.dispatch(EditorIntent.AppendChar(it)) },
                        onBackspace = { viewModel.dispatch(EditorIntent.Backspace()) }
                    )
                    Spacer(Modifier.height(16.dp))
                    StatusRow(
                        isDirty = s.isDirty,
                        lastSavedHlc = s.lastSavedHlc?.serialize() ?: "—",
                        nodeId = s.nodeId,
                        bodyChars = s.body.length,
                        lastResult = s.lastResult?.label() ?: "—"
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataFields(
    title: String,
    author: String,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit
) {
    val neonCyan = TitanColors.NeonCyan
    val neonYellow = TitanColors.NeonYellow
    val textFieldStyle = TextStyle(
        color = Color.White,
        fontSize = 14.sp,
        fontFamily = FontFamily.Monospace
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Title",
            color = neonCyan,
            style = MaterialTheme.typography.labelSmall
        )
        BasicTextField(
            value = title,
            onValueChange = onTitleChange,
            textStyle = textFieldStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(neonYellow),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Author",
            color = neonCyan,
            style = MaterialTheme.typography.labelSmall
        )
        BasicTextField(
            value = author,
            onValueChange = onAuthorChange,
            textStyle = textFieldStyle.copy(fontSize = 14.sp),
            cursorBrush = SolidColor(neonYellow),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun BodyEditor(
    body: String,
    onCharTyped: (String) -> Unit,
    onBackspace: () -> Unit
) {
    val neonCyan = TitanColors.NeonCyan
    val neonYellow = TitanColors.NeonYellow
    var localBuffer by remember(body) { mutableStateOf(body) }
    BasicTextField(
        value = localBuffer,
        onValueChange = { next ->
            // Decide using the pure helper (Phase 9.20) so the
            // append/backspace/ignore semantics are unit-tested
            // independently of Compose.
            when (val decision = BodyEditorDiff.compute(localBuffer, next)) {
                is BodyEditorDiff.Decision.Chars -> {
                    for (c in decision.appended) onCharTyped(c.toString())
                    localBuffer = next
                }
                BodyEditorDiff.Decision.Backspace -> {
                    if (localBuffer.isNotEmpty()) onBackspace()
                    localBuffer = next
                }
                BodyEditorDiff.Decision.Ignore -> { /* mid-string edit ignored */ }
            }
        },
        textStyle = TextStyle(
            color = Color.White,
            fontSize = 15.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 22.sp
        ),
        cursorBrush = SolidColor(neonYellow),
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(
                color = Color.Black.copy(alpha = 0.25f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "$neonCyan chars: ${body.length}",
        color = neonCyan.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall
    )
}

@Composable
private fun StatusRow(
    isDirty: Boolean,
    lastSavedHlc: String,
    nodeId: String,
    bodyChars: Int,
    lastResult: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = TitanColors.NeonCyan.copy(alpha = 0.08f),
                shape = RoundedCornerShape(6.dp)
            )
            .padding(12.dp)
    ) {
        StatusLine(label = "dirty", value = if (isDirty) "yes" else "no")
        StatusLine(label = "lastSavedHlc", value = lastSavedHlc)
        StatusLine(label = "nodeId", value = nodeId)
        StatusLine(label = "bodyChars", value = bodyChars.toString())
        StatusLine(label = "lastResult", value = lastResult)
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label ",
            color = TitanColors.NeonYellow,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ErrorPane(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Editor error",
            color = TitanColors.NeonYellow,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(text = message, color = Color.White)
    }
}

private fun fileName(path: String): String = CrdtEditorScreenHelpers.fileName(path)

private fun EditorResult?.label(): String = when (this) {
    null -> "—"
    else -> this.label()
}
