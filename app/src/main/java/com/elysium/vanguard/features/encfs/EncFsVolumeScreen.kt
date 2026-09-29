package com.elysium.vanguard.features.encfs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.ui.theme.TitanColors
import java.io.File

/**
 * EncFS volume browser — lists the decrypted names of an unlocked volume
 * and lets the user export (decrypt to Downloads), import (encrypt into the
 * volume), delete, and lock it again.
 *
 * Entry points: the file manager navigates here after a successful mount,
 * and an already-mounted volume offers "Open EncFS volume" in the action
 * sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncFsVolumeScreen(
    onBack: () -> Unit,
    viewModel: EncFsVolumeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var password by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.importFiles(uris) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = TitanColors.NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = File(viewModel.volumePath).name,
                            color = TitanColors.NeonCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TitanColors.NeonCyan)
                    }
                },
                actions = {
                    if (state is EncFsVolumeViewModel.VolumeState.Unlocked) {
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Upload, contentDescription = "Import files", tint = TitanColors.NeonCyan)
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TitanColors.NeonCyan)
                        }
                        IconButton(onClick = { viewModel.unmount(); onBack() }) {
                            Icon(Icons.Default.LockOpen, contentDescription = "Lock volume", tint = TitanColors.NeonYellow)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF050810),
                    titleContentColor = TitanColors.NeonCyan,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Color(0xFF050810)),
        ) {
            when (val s = state) {
                EncFsVolumeViewModel.VolumeState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TitanColors.NeonCyan,
                    )
                }

                is EncFsVolumeViewModel.VolumeState.Locked -> LockedContent(
                    state = s,
                    password = password,
                    onPasswordChange = { password = it },
                    onUnlock = { viewModel.unlock(password) },
                )

                is EncFsVolumeViewModel.VolumeState.Unlocked -> UnlockedContent(
                    state = s,
                    onExport = { viewModel.exportEntry(it) },
                    onDelete = { viewModel.deleteEntry(it) },
                )
            }
        }
    }
}

@Composable
private fun LockedContent(
    state: EncFsVolumeViewModel.VolumeState.Locked,
    password: String,
    onPasswordChange: (String) -> Unit,
    onUnlock: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = TitanColors.NeonCyan,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "EncFS volume locked",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.volumePath,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TitanColors.NeonCyan,
                unfocusedBorderColor = TitanColors.NeonCyan.copy(alpha = 0.4f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = err,
                color = TitanColors.NeonRed,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onUnlock,
            enabled = !state.checking && password.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = TitanColors.NeonCyan,
                contentColor = Color.Black,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.checking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.Black,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("UNLOCK", fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun UnlockedContent(
    state: EncFsVolumeViewModel.VolumeState.Unlocked,
    onExport: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.busy || state.message != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C111C))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = TitanColors.NeonCyan,
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = state.message ?: "Working…",
                    color = if (state.busy) Color.White.copy(alpha = 0.7f) else TitanColors.NeonCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (state.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Volume is empty — use ↑ to import files",
                    color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.entries, key = { it.encryptedName }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = TitanColors.NeonCyan.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.plainName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatSize(entry.sizeBytes),
                                color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (!state.busy) {
                            IconButton(onClick = { onExport(entry.encryptedName) }) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "Export ${entry.plainName}",
                                    tint = TitanColors.NeonCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(onClick = { onDelete(entry.encryptedName) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete ${entry.plainName}",
                                    tint = TitanColors.NeonRed,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}
