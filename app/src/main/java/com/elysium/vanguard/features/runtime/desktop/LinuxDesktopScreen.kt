package com.elysium.vanguard.features.runtime.desktop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppEntry

/**
 * PHASE 9.6.5 — Linux desktop screen.
 *
 * Two regions:
 *
 *   - **Top**: a Canvas drawing the VNC-style frame (stub today).
 *     Tapping the refresh icon re-captures; in a real implementation
 *     we would push the libvncclient handle's framebuffer here.
 *   - **Bottom**: list of `LinuxAppEntry` rows — apps discovered
 *     inside the distro (no launch wired yet, just the catalog).
 *
 * Phase 9.6.5 — first build; intentionally minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxDesktopScreen(
    onBack: () -> Unit,
    onOpenApp: (String, String) -> Unit = { _, _ -> },
    viewModel: LinuxDesktopViewModel = hiltViewModel()
) {
    val frame by viewModel.frame.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Linux desktop",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color(0xFFE4E7EB)
                        )
                        Text(
                            text = snapshot?.summary ?: "loading…",
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
                actions = {
                    IconButton(onClick = { viewModel.captureFrame() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Capture frame",
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FrameCard(frame = frame)
            if (apps.isEmpty()) {
                Text(
                    text = "no GUI apps discovered yet (need proot to actually launch them)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(apps, key = { it.id }) { app ->
                        AppRow(app = app, onTap = { onOpenApp(app.id, app.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameCard(frame: Bitmap?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0D10)),
            contentAlignment = Alignment.Center
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = "Desktop frame (stub)",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF61AFEF))
                    Text(
                        text = "capturing first frame…",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: LinuxAppEntry, onTap: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1115))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = app.name,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color(0xFFE4E7EB)
            )
            Text(
                text = "exec: ${app.exec}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF61AFEF),
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!app.comment.isNullOrBlank()) {
                Text(
                    text = app.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
