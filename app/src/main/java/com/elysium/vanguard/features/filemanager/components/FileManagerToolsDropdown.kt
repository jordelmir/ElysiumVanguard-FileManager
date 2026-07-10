package com.elysium.vanguard.features.filemanager.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 8.8 — Tools dropdown menu.
 *
 * Extracted from the monolithic FileManagerScreen. The screen has accumulated
 * 10+ navigation entries over multiple phases; centralizing them in a
 * dedicated composable keeps the screen file readable and makes the menu
 * a single testable unit.
 *
 * Why a list of `ToolItem` data class instead of inline DropdownMenuItem
 * calls: it makes the "what does this menu do?" answer one read of the
 * top of the file. Adding a new tool is a one-line change in [tools].
 */
@Composable
fun FileManagerToolsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onVault: () -> Unit,
    onTrash: () -> Unit,
    onSearch: () -> Unit,
    onDuplicates: () -> Unit,
    onAnalyzer: () -> Unit,
    onLocalServer: () -> Unit,
    onSftp: () -> Unit,
    onDualPane: () -> Unit,
    onOcr: () -> Unit,
    onAutoTag: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onAiSearch: () -> Unit
) {
    val tools = listOf(
        ToolItem("Vault", Icons.Default.Lock, TitanColors.QuantumPink, onVault),
        ToolItem("Trash", Icons.Default.Delete, TitanColors.QuantumPink, onTrash),
        ToolItem("Search", Icons.Default.Search, TitanColors.RadioactiveGreen, onSearch),
        ToolItem("Duplicates", Icons.Default.FileCopy, TitanColors.NeonYellow, onDuplicates),
        ToolItem("Storage Analyzer", Icons.Default.Storage, TitanColors.ElectricBlue, onAnalyzer),
        ToolItem("Local Server", Icons.Default.QrCode2, TitanColors.NeonCyan, onLocalServer),
        ToolItem("SFTP Server", Icons.Default.Lock, TitanColors.NeonYellow, onSftp),
        ToolItem("Dual Pane", Icons.Default.ViewColumn, TitanColors.NeonCyan, onDualPane),
        ToolItem("OCR (Text Recognition)", Icons.Default.DocumentScanner, TitanColors.RadioactiveGreen, onOcr),
        ToolItem("Auto-Tag Photos", Icons.Default.AutoAwesome, TitanColors.NeonYellow, onAutoTag),
        ToolItem("Rename", Icons.Default.Edit, TitanColors.NeonCyan, onRename),
        ToolItem("Share", Icons.Default.Share, TitanColors.NeonCyan, onShare),
        ToolItem("AI Search", Icons.Default.AutoAwesome, TitanColors.NeonYellow, onAiSearch)
    )

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        tools.forEach { tool ->
            ToolItemRow(tool, onDismiss)
        }
    }
}

private data class ToolItem(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val onClick: () -> Unit
)

@Composable
private fun ToolItemRow(
    tool: ToolItem,
    onDismiss: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(tool.label) },
        leadingIcon = {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                tint = tool.accent
            )
        },
        onClick = {
            onDismiss()
            tool.onClick()
        }
    )
}