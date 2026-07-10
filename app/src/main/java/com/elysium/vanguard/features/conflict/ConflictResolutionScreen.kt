package com.elysium.vanguard.features.conflict

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.conflict.Conflict
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 1.10 — Conflict resolution screen.
 *
 * Shows a list of detected conflicts. Each row has three buttons (or two for
 * duplicates) representing the user's choice. When the user has resolved every
 * row, the "Apply" button at the bottom commits the choices to disk.
 *
 * The screen is reachable from any batch operation that hit a name collision.
 * The caller passes the conflicting file list via SavedStateHandle (Hilt's
 * [SavedStateHandle] reads from the nav args).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    onBack: () -> Unit,
    onApplied: (Int) -> Unit,
    viewModel: ConflictResolutionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = TitanColors.NeonYellow)
                        Spacer(Modifier.width(8.dp))
                        Text("Resolve Conflicts", color = TitanColors.NeonCyan)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TitanColors.NeonCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF050810),
                    titleContentColor = TitanColors.NeonCyan
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF050810))
                .padding(16.dp)
        ) {
            Text(
                text = "${state.conflicts.size} conflict${if (state.conflicts.size == 1) "" else "s"} detected. " +
                    "Choose how to handle each one.",
                color = TitanColors.NeonCyan,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.conflicts, key = { it.sourcePath }) { conflict ->
                    ConflictRow(
                        conflict = conflict,
                        resolution = state.resolutions[conflict.sourcePath] ?: Conflict.Resolution.PENDING,
                        onResolution = { res -> viewModel.setResolution(conflict.sourcePath, res) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val applied = viewModel.apply()
                    onApplied(applied)
                },
                enabled = state.canApply,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TitanColors.RadioactiveGreen,
                    contentColor = Color.Black,
                    disabledContainerColor = TitanColors.NeonCyan.copy(alpha = 0.2f),
                    disabledContentColor = TitanColors.NeonCyan.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Apply Resolutions", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ConflictRow(
    conflict: Conflict,
    resolution: Conflict.Resolution,
    onResolution: (Conflict.Resolution) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C111C)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (conflict.kind == Conflict.Kind.DUPLICATE) Icons.Default.ContentCopy
                    else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (conflict.kind == Conflict.Kind.DUPLICATE) TitanColors.NeonYellow
                    else TitanColors.QuantumPink,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = conflict.sourceName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }
            Text(
                text = if (conflict.kind == Conflict.Kind.DUPLICATE)
                    "Identical file already exists in destination."
                else
                    "A file with the same name already exists in destination.",
                color = TitanColors.NeonCyan.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ResolutionButton(
                    label = "Keep Source",
                    selected = resolution == Conflict.Resolution.KEEP_SOURCE,
                    onClick = { onResolution(Conflict.Resolution.KEEP_SOURCE) },
                    modifier = Modifier.weight(1f)
                )
                ResolutionButton(
                    label = "Keep Existing",
                    selected = resolution == Conflict.Resolution.KEEP_DESTINATION,
                    onClick = { onResolution(Conflict.Resolution.KEEP_DESTINATION) },
                    modifier = Modifier.weight(1f)
                )
                // "Keep both" is meaningless for true duplicates (same bytes).
                if (conflict.kind == Conflict.Kind.NAME) {
                    ResolutionButton(
                        label = "Keep Both",
                        selected = resolution == Conflict.Resolution.KEEP_BOTH,
                        onClick = { onResolution(Conflict.Resolution.KEEP_BOTH) },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    ResolutionButton(
                        label = "Skip",
                        selected = resolution == Conflict.Resolution.SKIP,
                        onClick = { onResolution(Conflict.Resolution.SKIP) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResolutionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) TitanColors.NeonCyan else Color(0xFF1A2030)
    val textColor = if (selected) Color.Black else TitanColors.NeonCyan
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = textColor
        ),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}