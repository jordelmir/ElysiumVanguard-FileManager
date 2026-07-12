package com.elysium.vanguard.features.dualpane

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.ui.theme.LocalAdaptiveMetrics
import com.elysium.vanguard.ui.theme.TitanColors
import java.io.File

/**
 * PHASE 2.10 — Dual-pane file browser.
 *
 * Two independent file lists side by side, each with its own current directory.
 * Built on top of [DualPaneViewModel] which manages both panels' state without
 * sharing directory state.
 *
 * Drag-and-drop between panes is implemented via long-press → tap-copy on the
 * other pane's header. A true Compose drag gesture over two scrollable lists
 * is fragile in 2024's Compose foundation; the button-tap approach is more
 * discoverable and accessible, and we can layer in real DnD later when the
 * upstream APIs stabilize.
 *
 * The default root for each pane is the app's external files dir; the user
 * can navigate up to `/sdcard` (or wherever they have permission) using the
 * up button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualPaneScreen(
    onBack: () -> Unit,
    viewModel: DualPaneViewModel = hiltViewModel()
) {
    val left by viewModel.left.collectAsState()
    val right by viewModel.right.collectAsState()
    val adaptive = LocalAdaptiveMetrics.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = TitanColors.NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Dual Pane", color = TitanColors.NeonCyan)
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
        if (adaptive.shouldStackPrimaryPanes) {
            Column(modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF050810))) {
                Pane(
                    state = left,
                    onUp = { viewModel.goUp(PaneSide.LEFT) },
                    onRefresh = { viewModel.refresh(PaneSide.LEFT) },
                    onOpen = { viewModel.open(PaneSide.LEFT, it) },
                    onCopyHere = { viewModel.copyToOtherPane(PaneSide.LEFT, it) },
                    isSource = true,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                HorizontalDivider(color = Color(0xFF1A2030), thickness = 1.dp)
                Pane(
                    state = right,
                    onUp = { viewModel.goUp(PaneSide.RIGHT) },
                    onRefresh = { viewModel.refresh(PaneSide.RIGHT) },
                    onOpen = { viewModel.open(PaneSide.RIGHT, it) },
                    onCopyHere = { viewModel.copyToOtherPane(PaneSide.RIGHT, it) },
                    isSource = false,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        } else {
            Row(modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF050810))) {
                Pane(
                    state = left,
                    onUp = { viewModel.goUp(PaneSide.LEFT) },
                    onRefresh = { viewModel.refresh(PaneSide.LEFT) },
                    onOpen = { viewModel.open(PaneSide.LEFT, it) },
                    onCopyHere = { viewModel.copyToOtherPane(PaneSide.LEFT, it) },
                    isSource = true,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                VerticalDivider(color = Color(0xFF1A2030), thickness = 1.dp)
                Pane(
                    state = right,
                    onUp = { viewModel.goUp(PaneSide.RIGHT) },
                    onRefresh = { viewModel.refresh(PaneSide.RIGHT) },
                    onOpen = { viewModel.open(PaneSide.RIGHT, it) },
                    onCopyHere = { viewModel.copyToOtherPane(PaneSide.RIGHT, it) },
                    isSource = false,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun Pane(
    state: PaneState,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (File) -> Unit,
    onCopyHere: (File) -> Unit,
    isSource: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Pane header with breadcrumb + actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C111C))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up", tint = TitanColors.NeonCyan)
            }
            Text(
                text = state.currentDir.absolutePath,
                color = if (isSource) TitanColors.NeonCyan else TitanColors.NeonYellow,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TitanColors.NeonCyan)
            }
        }
        HorizontalDivider(color = Color(0xFF1A2030))

        // Pane body.
        if (state.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.loading) "Loading…" else "Empty folder",
                    color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.entries) { entry ->
                    FileRow(
                        file = entry,
                        onClick = { onOpen(entry) },
                        onLongPress = { onCopyHere(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: File,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (file.isDirectory) "📁" else "📄",
            fontSize = 14.sp,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!file.isDirectory) {
                Text(
                    text = formatSize(file.length()),
                    color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        }
        IconButton(onClick = onLongPress) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy to other pane",
                tint = TitanColors.NeonYellow,
                modifier = Modifier.size(16.dp)
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
