package com.elysium.vanguard.features.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.elysium.vanguard.core.server.LocalServerOrchestrator
import com.elysium.vanguard.ui.theme.TitanColors

/**
 * PHASE 2.3 — UI for the local file server.
 *
 * Three states:
 *   STOPPED: big "Start" button, no QR
 *   RUNNING: QR + URL + token + stats, with "Stop" button
 *   FAILED: error message + retry
 *
 * The QR re-renders whenever the server starts OR the user taps refresh. We don't
 * regenerate on every recomposition — that would be wasteful.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalServerScreen(
    onBack: () -> Unit,
    viewModel: LocalServerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val qr by viewModel.qrBitmap.collectAsState()
    val error by viewModel.lastError.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCode2, contentDescription = null, tint = TitanColors.NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Local Transfer", color = TitanColors.NeonCyan)
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ServerStatusCard(state = state, stats = stats, error = error)

            AnimatedVisibility(
                visible = state == LocalServerOrchestrator.State.RUNNING,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200))
            ) {
                QrCard(
                    url = viewModel.url,
                    token = viewModel.authToken,
                    qr = qr,
                    onCopyUrl = { viewModel.url?.let { copyToClipboard(context, "Transfer URL", it) } },
                    onCopyToken = { copyToClipboard(context, "Auth Token", viewModel.authToken) },
                    onShareUrl = {
                        viewModel.url?.let {
                            shareText(context, "Elysium Vanguard Transfer", it)
                        }
                    },
                    onRefresh = { viewModel.regenerateQr() }
                )
            }

            PowerButton(
                state = state,
                onClick = { viewModel.toggle() }
            )

            // Educational footer so first-time users get what they're looking at.
            Text(
                text = "When running, this device serves files over your local Wi-Fi. " +
                    "Anyone with the URL and auth token can browse, download, and upload. " +
                    "Stop the server when you're done to revoke access.",
                color = TitanColors.NeonCyan.copy(alpha = 0.5f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun ServerStatusCard(
    state: LocalServerOrchestrator.State,
    stats: LocalServerOrchestrator.Stats,
    error: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                LocalServerOrchestrator.State.RUNNING -> Color(0xFF003824)
                LocalServerOrchestrator.State.FAILED -> Color(0xFF3A0014)
                else -> Color(0xFF0C111C)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (state) {
                        LocalServerOrchestrator.State.STOPPED -> "Server stopped"
                        LocalServerOrchestrator.State.STARTING -> "Starting…"
                        LocalServerOrchestrator.State.RUNNING -> "Running on port ${stats.boundPort}"
                        LocalServerOrchestrator.State.FAILED -> "Failed to start"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
            if (state == LocalServerOrchestrator.State.RUNNING) {
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatItem("Active", stats.activeConnections.toString())
                    StatItem("Total", stats.totalRequests.toString())
                    StatItem("Port", stats.boundPort.toString())
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    color = TitanColors.QuantumPink,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun StatusDot(state: LocalServerOrchestrator.State) {
    val color = when (state) {
        LocalServerOrchestrator.State.RUNNING -> TitanColors.RadioactiveGreen
        LocalServerOrchestrator.State.STARTING -> TitanColors.NeonYellow
        LocalServerOrchestrator.State.FAILED -> TitanColors.QuantumPink
        LocalServerOrchestrator.State.STOPPED -> Color.Gray
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(label, color = TitanColors.NeonCyan.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun QrCard(
    url: String?,
    token: String,
    qr: android.graphics.Bitmap?,
    onCopyUrl: () -> Unit,
    onCopyToken: () -> Unit,
    onShareUrl: () -> Unit,
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
                        contentDescription = "QR code",
                        modifier = Modifier
                            .size(260.dp)
                            .padding(4.dp)
                    )
                } else {
                    CircularProgressIndicator(color = TitanColors.NeonCyan)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Scan from another device",
                color = TitanColors.NeonCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))

            // URL row with copy + share.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = url ?: "—",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF050810), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onCopyUrl) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = TitanColors.NeonCyan)
                }
                IconButton(onClick = onShareUrl) {
                    Icon(Icons.Default.Share, contentDescription = "Share", tint = TitanColors.NeonCyan)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate QR", tint = TitanColors.NeonCyan)
                }
            }

            Spacer(Modifier.height(12.dp))
            // Token row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Token: ",
                    color = TitanColors.NeonCyan.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    text = token.take(8) + "…" + token.takeLast(4),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF050810), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
                IconButton(onClick = onCopyToken) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy token", tint = TitanColors.NeonCyan)
                }
            }
        }
    }
}

@Composable
private fun PowerButton(
    state: LocalServerOrchestrator.State,
    onClick: () -> Unit
) {
    val running = state == LocalServerOrchestrator.State.RUNNING
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) TitanColors.QuantumPink else TitanColors.RadioactiveGreen,
            contentColor = Color.Black
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(Icons.Default.Power, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (running) "Stop Server" else "Start Server",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun shareText(context: Context, title: String, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, title))
}