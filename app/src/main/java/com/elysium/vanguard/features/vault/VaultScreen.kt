package com.elysium.vanguard.features.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.database.VaultEntity
import com.elysium.vanguard.ui.theme.TitanColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PHASE 2.1 — Vault screen.
 *
 * Two visual states:
 * - Locked: a centered shield icon with "Unlock Vault" call-to-action. The lock is
 *   currently a UX gate; biometric binding is wired but optional.
 * - Unlocked: a list of vault items with per-row Decrypt / Purge actions, plus a
 *   header summary (count + total encrypted size) and a "Lock" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpenFile: (VaultEntity, java.io.File) -> Unit,
    onNavigateToMetadata: (key: String, name: String) -> Unit = { _, _ -> },
    viewModel: VaultViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeInfo()
        }
    }
    LaunchedEffect(state.lastDecrypted) {
        state.lastDecrypted?.let { preview ->
            onOpenFile(preview.entry, preview.tempFile)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Vault",
                            color = TitanColors.NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (state.isUnlocked) {
                                "${state.items.size} items · ${formatSize(state.totalBytes)} · AES-256-GCM"
                            } else {
                                "Locked"
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (state.isUnlocked) {
                        IconButton(onClick = { viewModel.lock() }) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Lock vault",
                                tint = TitanColors.QuantumPink
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { padding ->
        if (state.isUnlocked) {
            UnlockedContent(
                state = state,
                viewModel = viewModel,
                onNavigateToMetadata = onNavigateToMetadata,
                modifier = Modifier.padding(padding)
            )
        } else {
            LockedContent(
                onUnlock = { viewModel.unlock() },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun LockedContent(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = null,
                tint = TitanColors.NeonCyan,
                modifier = Modifier.size(96.dp)
            )
            Text(
                "Vault is locked",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Text(
                "AES-256-GCM with per-file data encryption keys.\nMaster key in Android Keystore.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TitanColors.NeonCyan)
            ) {
                Icon(Icons.Filled.LockOpen, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Unlock Vault", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun UnlockedContent(
    state: VaultViewModel.VaultState,
    viewModel: VaultViewModel,
    onNavigateToMetadata: (key: String, name: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.items.isEmpty() && !state.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(72.dp)
                )
                Text(
                    "Vault is empty",
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Encrypt files from the file manager to see them here.",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(state.items, key = { it.id }) { entry ->
            VaultItemRow(
                entry = entry,
                onDecrypt = { viewModel.decryptToCache(entry) },
                onPurge = { viewModel.purgeEntry(entry) },
                onMetadata = { onNavigateToMetadata("vault://${entry.id}", entry.originalName) }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        }
    }
}

@Composable
private fun VaultItemRow(
    entry: VaultEntity,
    onDecrypt: () -> Unit,
    onPurge: () -> Unit,
    onMetadata: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            tint = TitanColors.NeonCyan,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.originalName,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${formatSize(entry.originalSize)} → ${formatSize(entry.vaultSize)} · ${formatDate(entry.encryptedAt)}",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
        IconButton(onClick = onMetadata) {
            Icon(Icons.Filled.Sell, contentDescription = "Tags & Notes", tint = TitanColors.RadioactiveGreen)
        }
        IconButton(onClick = onDecrypt) {
            Icon(Icons.Filled.Visibility, contentDescription = "Decrypt", tint = TitanColors.NeonCyan)
        }
        IconButton(onClick = onPurge) {
            Icon(Icons.Filled.DeleteForever, contentDescription = "Purge", tint = TitanColors.QuantumPink)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))