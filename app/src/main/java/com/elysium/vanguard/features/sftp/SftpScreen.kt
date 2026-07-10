package com.elysium.vanguard.features.sftp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 2.4 — SFTP server screen.
 *
 * Reuses the visual language of the HTTP server screen (status card + QR + copyable
 * fields + power button) but adds the password field prominently because SFTP
 * clients can't auto-fill from a URL.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpScreen(
    onBack: () -> Unit,
    viewModel: SftpViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val status by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = TitanColors.NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("SFTP Server", color = TitanColors.NeonCyan)
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(running = state.running, port = state.port, status = status)

            if (state.running) {
                QrCard(
                    qr = state.qr,
                    url = state.url,
                    username = state.username,
                    password = state.password,
                    onCopyUrl = { copy(context, "SFTP URL", state.url) },
                    onCopyPassword = { copy(context, "SFTP Password", state.password) },
                    onRefresh = viewModel::regenerateQr
                )
            }

            Button(
                onClick = {
                    val root = java.io.File("/sdcard")  // best-effort default
                    viewModel.toggle(root)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running) TitanColors.QuantumPink else TitanColors.RadioactiveGreen,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Power, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.running) "Stop SFTP Server" else "Start SFTP Server",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Text(
                text = "Connect from any SSH/SFTP client: FileZilla, Cyberduck, " +
                    "Transmit, terminal `sftp` command, etc. " +
                    "Stop the server to revoke access.",
                color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun StatusCard(running: Boolean, port: Int, status: com.elysium.vanguard.core.sftp.SftpServer.Status) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) Color(0xFF003824) else Color(0xFF0C111C)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (running) TitanColors.RadioactiveGreen else Color.Gray))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (status) {
                        com.elysium.vanguard.core.sftp.SftpServer.Status.RUNNING -> "Running on port $port"
                        com.elysium.vanguard.core.sftp.SftpServer.Status.STARTING -> "Starting…"
                        com.elysium.vanguard.core.sftp.SftpServer.Status.FAILED -> "Failed (port in use?)"
                        com.elysium.vanguard.core.sftp.SftpServer.Status.STOPPED -> "Stopped"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
private fun QrCard(
    qr: android.graphics.Bitmap?,
    url: String,
    username: String,
    password: String,
    onCopyUrl: () -> Unit,
    onCopyPassword: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C111C)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "SFTP QR",
                        modifier = Modifier
                            .size(260.dp)
                            .padding(4.dp)
                    )
                } else {
                    CircularProgressIndicator(color = TitanColors.NeonCyan)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Scan from any SFTP client", color = TitanColors.NeonCyan, fontSize = 13.sp)

            Spacer(Modifier.height(12.dp))
            CopyRow("URL", url, onCopyUrl, TitanColors.NeonCyan)
            Spacer(Modifier.height(8.dp))
            CopyRow("User", username, { /* readonly */ }, TitanColors.NeonCyan, copyEnabled = false)
            Spacer(Modifier.height(8.dp))
            CopyRow("Password", password, onCopyPassword, TitanColors.NeonYellow)
            Spacer(Modifier.height(8.dp))
            Row {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate QR", tint = TitanColors.NeonCyan)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.QrCode2, contentDescription = null, tint = TitanColors.NeonCyan.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun CopyRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    accent: Color,
    copyEnabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = accent.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.width(70.dp))
        Text(
            text = value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF050810), RoundedCornerShape(6.dp))
                .padding(8.dp)
        )
        if (copyEnabled) {
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label", tint = accent)
            }
        }
    }
}

private fun copy(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}