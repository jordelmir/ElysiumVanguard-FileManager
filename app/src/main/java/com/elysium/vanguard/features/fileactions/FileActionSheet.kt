package com.elysium.vanguard.features.fileactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DiscFull
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.elysium.vanguard.core.fileactions.DiskImageFormat
import com.elysium.vanguard.core.fileactions.FileAction
import com.elysium.vanguard.core.fileactions.NetworkProtocol

/**
 * Phase 93 — the **file action sheet**, the
 * bottom sheet UI the File Manager shows when
 * the user long-presses a file.
 *
 * The sheet is a thin presentation layer over
 * the [FileActionResolver]. The caller passes
 * a list of [FileAction]s (already resolved);
 * the sheet renders each as a row with an icon
 * + label + description. Tapping a row fires
 * the corresponding callback.
 *
 * **Why a sheet, not a dialog?** The sheet
 * keeps the user oriented (the file is still
 * visible in the background) and matches the
 * Material 3 pattern. The dialog variant
 * exists for full-screen flows.
 */
@Composable
fun FileActionSheet(
    fileName: String,
    actions: List<FileAction>,
    onActionClick: (FileAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header: file name + close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = "${actions.size} action${if (actions.size == 1) "" else "s"} available",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // Actions list
        if (actions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No contextual actions for this file",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(actions, key = { it.id }) { action ->
                    FileActionRow(
                        action = action,
                        onClick = { onActionClick(action) },
                    )
                }
            }
        }
    }
}

/**
 * A single action row: icon + label + description.
 * The icon is derived from the action's runtime +
 * kind (apt / dnf / pacman / AppImage / Windows /
 * ISO / Git / SMB / WebDAV / USB-OTG).
 */
@Composable
private fun FileActionRow(
    action: FileAction,
    onClick: () -> Unit,
) {
    val (icon, tint) = iconFor(action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = action.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
            )
        }
    }
}

/**
 * The (icon, tint) pair for an action. The
 * mapping is intentionally small (6 icons cover
 * every action in Phase 93); a future phase
 * can add a per-action icon override.
 */
private fun iconFor(action: FileAction): Pair<ImageVector, Color> = when (action) {
    is FileAction.InstallDebPackage,
    is FileAction.InstallRpmPackage,
    is FileAction.InstallPacmanPackage -> Icons.Filled.Archive to Color(0xFFE57373)
    is FileAction.RunAppImage -> Icons.Filled.PlayArrow to Color(0xFF81C784)
    is FileAction.RunWindowsBinary -> Icons.Filled.Code to Color(0xFF64B5F6)
    // Phase 103 — `.msi` is an installer
    // (Windows Installer service), not a
    // Wine-runnable binary. Distinct icon
    // (Install) + a darker blue tint so the
    // sheet shows the difference at a glance.
    is FileAction.InstallWindowsMsi -> Icons.Filled.InstallDesktop to Color(0xFF1976D2)
    is FileAction.MountDiskImage -> Icons.Filled.Storage to Color(0xFFFFB74D)
    is FileAction.BootVmFromImage -> Icons.Filled.DiscFull to Color(0xFFBA68C8)
    is FileAction.GitClone -> Icons.Filled.Download to Color(0xFF4DB6AC)
    is FileAction.MountNetworkShare -> when (action.protocol) {
        NetworkProtocol.SMB -> Icons.Filled.Storage to Color(0xFFFF8A65)
        NetworkProtocol.WEBDAV -> Icons.Filled.Folder to Color(0xFF7986CB)
        NetworkProtocol.SFTP -> Icons.Filled.Folder to Color(0xFF7986CB)
    }
    is FileAction.InspectUsbOtgDevice -> Icons.Filled.Usb to Color(0xFFA1887F)
}
