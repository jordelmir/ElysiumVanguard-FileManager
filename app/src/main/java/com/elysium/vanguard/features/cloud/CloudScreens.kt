package com.elysium.vanguard.features.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import com.elysium.vanguard.core.cloud.*
import com.elysium.vanguard.ui.theme.TitanColors
import java.util.Locale

/**
 * Cloud storage accounts screen - manages all cloud connections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountsScreen(
    viewModel: CloudStorageViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val connections by viewModel.connections.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CLOUD STORAGE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TitanColors.CarbonGray)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { /* Show add connection dialog */ },
                icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                text = { Text("ADD ACCOUNT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                containerColor = TitanColors.NeonCyan,
                contentColor = Color.Black
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (connections.isEmpty()) {
                EmptyCloudState(onAddAccount = { /* Show add connection dialog */ })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(connections) { connection ->
                        CloudAccountCard(
                            connection = connection,
                            onClick = { viewModel.selectConnection(connection) },
                            onDisconnect = { viewModel.removeConnection(connection.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cloud file browser screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBrowserScreen(
    viewModel: CloudStorageViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val connection = viewModel.currentConnection.collectAsState().value
    val files = viewModel.currentFiles.collectAsState().value
    val isLoading = viewModel.isLoading.collectAsState().value
    val error = viewModel.error.collectAsState().value
    val transferProgress = viewModel.transferProgress.collectAsState().value
    val quota = viewModel.quota.collectAsState().value

    connection?.let { conn ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(conn.provider.displayName, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            Text(viewModel.currentFolderId.collectAsState().value, fontFamily = FontFamily.Monospace, color = TitanColors.NeonCyan.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { viewModel.goToParent() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                    actions = {
                        IconButton(onClick = { viewModel.createFolder("New Folder") }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", tint = Color.White) }
                        IconButton(onClick = { /* Show upload dialog */ }) { Icon(Icons.Default.CloudUpload, contentDescription = "Upload", tint = Color.White) }
                        IconButton(onClick = { viewModel.syncConnection(conn.id) }) { Icon(Icons.Default.Sync, contentDescription = "Sync", tint = Color.White) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = TitanColors.CarbonGray)
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Quota bar
                quota?.let { q ->
                    StorageQuotaBar(quota = q)
                }

                // Error message
                error?.let { e ->
                    Text(text = e, color = TitanColors.NeonRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 8.dp))
                }

                // Transfer progress
                transferProgress?.let { tp ->
                    TransferProgressBar(progress = tp)
                }

                // File list
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TitanColors.NeonCyan)
                    }
                } else if (files.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TitanColors.NeonCyan.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("EMPTY FOLDER", color = TitanColors.NeonCyan.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(files) { file ->
                            CloudFileRow(
                                file = file,
                                onClick = { if (file.isFolder) viewModel.navigateInto(file) },
                                onLongClick = { /* Show context menu */ }
                            )
                        }
                    }
                }
            }
        }
    } ?: Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CLOUD BROWSER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TitanColors.CarbonGray)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("SELECT A CLOUD ACCOUNT", color = TitanColors.NeonCyan.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Individual cloud file row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudFileRow(
    file: CloudFile,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isFolder = file.isFolder
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = TitanColors.CarbonGray,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, TitanColors.NeonCyan.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isFolder) Icons.Default.Folder else getFileIcon(file.mimeType),
                contentDescription = null,
                tint = if (isFolder) TitanColors.NeonCyan else TitanColors.NeonYellow,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatSize(file.size)} • ${formatDate(file.modifiedTime)}",
                    color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (file.isShared) {
                Icon(Icons.Default.Share, contentDescription = "Shared", tint = TitanColors.RadioactiveGreen, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudAccountCard(
    connection: CloudConnection,
    onClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDisconnect
            ),
        color = TitanColors.CarbonGray,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, TitanColors.NeonCyan.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getProviderIcon(connection.provider),
                contentDescription = null,
                tint = TitanColors.NeonCyan,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = connection.config.displayName ?: connection.provider.displayName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${connection.provider.displayName} • Last used: ${formatRelativeTime(connection.lastUsedAt)}",
                    color = TitanColors.NeonCyan.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TitanColors.NeonCyan.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun EmptyCloudState(onAddAccount: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = TitanColors.NeonCyan.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("NO CLOUD ACCOUNTS", color = TitanColors.NeonCyan.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Add your first cloud storage account to access files anywhere", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAddAccount,
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.NeonCyan, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("ADD ACCOUNT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StorageQuotaBar(quota: StorageQuota) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Text("STORAGE", color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("${formatSize(quota.usedBytes)} / ${formatSize(quota.totalBytes)} (${quota.usagePercent.toInt()}%)", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = (quota.usagePercent / 100f).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = when {
                quota.usagePercent > 90 -> TitanColors.NeonRed
                quota.usagePercent > 70 -> TitanColors.NeonYellow
                else -> TitanColors.RadioactiveGreen
            },
            trackColor = Color.White.copy(alpha = 0.1f)
        )
    }
}

@Composable
fun TransferProgressBar(progress: CloudTransferProgress) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (progress.isUpload) "UPLOADING" else "DOWNLOADING", color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("${progress.progressPercent.toInt()}%", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = (progress.progressPercent / 100f).coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = TitanColors.NeonCyan,
            trackColor = Color.White.copy(alpha = 0.1f)
        )
        Text("${progress.completedFiles}/${progress.totalFiles} files • ${progress.formattedProgress}", color = TitanColors.NeonCyan.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

private fun formatDate(date: java.util.Date): String = java.text.SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(date)

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        else -> java.text.SimpleDateFormat("MMM dd", Locale.getDefault()).format(java.util.Date(timestamp))
    }
}

private fun getProviderIcon(provider: CloudProvider): androidx.compose.ui.graphics.vector.ImageVector = when (provider) {
    CloudProvider.GOOGLE_DRIVE -> Icons.Default.Cloud
    CloudProvider.ONEDRIVE, CloudProvider.ONEDRIVE_BUSINESS -> Icons.Default.Cloud
    CloudProvider.DROPBOX -> Icons.Default.Cloud
    CloudProvider.BOX -> Icons.Default.Cloud
    CloudProvider.MEGA -> Icons.Default.Cloud
    CloudProvider.YANDEX_DISK -> Icons.Default.Cloud
    CloudProvider.NEXTCLOUD -> Icons.Default.Cloud
    CloudProvider.WEBDAV -> Icons.Default.Storage
    CloudProvider.SFTP -> Icons.Default.Security
    CloudProvider.FTP -> Icons.Default.Storage
    CloudProvider.SMB -> Icons.Default.Computer
    CloudProvider.LOCAL_SERVER -> Icons.Default.Router
    else -> Icons.Default.Cloud
}

private fun getFileIcon(mimeType: String?): androidx.compose.ui.graphics.vector.ImageVector {
    val mime = mimeType ?: "application/octet-stream"
    return when {
        mime.startsWith("image/") -> Icons.Default.Image
        mime.startsWith("video/") -> Icons.Default.Videocam
        mime.startsWith("audio/") -> Icons.Default.AudioFile
        mime.startsWith("text/") -> Icons.Default.Description
        mime == "application/pdf" -> Icons.Default.PictureAsPdf
        mime.contains("zip") || mime.contains("compressed") || mime.contains("archive") -> Icons.Default.Archive
        mime.startsWith("application/") -> Icons.Default.InsertDriveFile
        else -> Icons.Default.InsertDriveFile
    }
}