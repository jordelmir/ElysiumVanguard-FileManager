package com.elysium.vanguard.features.runtime.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import com.elysium.vanguard.core.runtime.distros.custom.CustomRootfsKind
import com.elysium.vanguard.core.runtime.distros.custom.UrlProbe

/**
 * PHASE 9.6.3.2 — Custom rootfs install screen.
 *
 * UI flow:
 *
 *   1. User pastes a URL.
 *   2. Taps Validate → HEAD probe runs; we show size / kind / ETag.
 *   3. If acceptable, Install button enables.
 *   4. Tap Install → download + extract; progress shown.
 *   5. On success, "Installed · tap to inspect" hint.
 *
 * Phase 9.6.3.2 — first build; intentionally minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeCustomScreen(
    onBack: () -> Unit,
    viewModel: RuntimeCustomViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val url by viewModel.url.collectAsState()
    val bytesRead by viewModel.bytesRead.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Add custom rootfs",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color(0xFFE4E7EB)
                        )
                        Text(
                            text = "paste a tar.gz / tar.xz / tar URL",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF8B949E)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFFE4E7EB)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F1115)
                )
            )
        }
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = viewModel::onUrlChanged,
                label = {
                    Text(
                        "URL",
                        fontFamily = FontFamily.Monospace
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(state, viewModel, bytesRead)
        }
    }
}

/**
 * Format a byte count as "60 MB" / "1.2 GB" / etc.
 */
private fun formatBytes(value: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var i = 0
    while (size >= 1024.0 && i < units.lastIndex) {
        size /= 1024.0
        i += 1
    }
    return "${size.toInt()} ${units[i]}"
}

@Composable
private fun Row(state: RuntimeCustomViewModel.State, viewModel: RuntimeCustomViewModel, bytesRead: Long) {
    when (state) {
        RuntimeCustomViewModel.State.Idle -> {
            Button(
                onClick = viewModel::probe,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF61AFEF),
                    contentColor = Color(0xFF0B0D10)
                )
            ) {
                Text(
                    "Validate",
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        is RuntimeCustomViewModel.State.Probed -> {
            ProbeCard(state.probe, viewModel)
        }
        is RuntimeCustomViewModel.State.Installing -> {
            val s = state as RuntimeCustomViewModel.State.Installing
            val total = s.totalBytes ?: 0L
            val fraction: Float? = if (total > 0L) {
                (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF61AFEF))
                Text(
                    text = "downloading + extracting…",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF8B949E)
                )
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF98C379),
                        trackColor = Color(0xFF1F2A1F)
                    )
                    val pct = (fraction * 100).toInt()
                    val read = formatBytes(bytesRead)
                    val totalStr = formatBytes(total)
                    Text(
                        text = "$read / $totalStr ($pct%)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E)
                    )
                } else {
                    Text(
                        text = "${formatBytes(bytesRead)} downloaded so far",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E)
                    )
                }
            }
        }
        is RuntimeCustomViewModel.State.Installed -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A1F))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF98C379),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = "Installed at ${state.rootfsDir.absolutePath}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFE4E7EB)
                    )
                    Text(
                        text = "id: ${state.distroId}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Button(
                        onClick = viewModel::reset,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Add another", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        is RuntimeCustomViewModel.State.Error -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4C1F22))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFD0D0),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = state.message,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFFFFD0D0)
                    )
                    Button(
                        onClick = viewModel::reset,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Reset", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeCard(probe: UrlProbe, viewModel: RuntimeCustomViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ProbeLine("status", if (probe.reachable) "200 OK" else "${probe.statusCode}")
            ProbeLine("size", probe.contentLengthBytes?.let { "${it / 1024 / 1024} MB" } ?: "unknown")
            ProbeLine("kind", probe.suggestedKind.name)
            ProbeLine("content-type", probe.contentType ?: "—")
            ProbeLine("etag", probe.etagOrLastModified ?: "—")
            Button(
                onClick = viewModel::install,
                enabled = probe.isAcceptable,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (probe.isAcceptable) Color(0xFF98C379) else Color(0xFF5A5A5A),
                    contentColor = Color(0xFF0B0D10)
                )
            ) {
                Text(
                    if (probe.isAcceptable) "Install" else "Cannot install (probe failed)",
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun ProbeLine(label: String, value: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFF8B949E)
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Color(0xFFE4E7EB)
        )
    }
}
