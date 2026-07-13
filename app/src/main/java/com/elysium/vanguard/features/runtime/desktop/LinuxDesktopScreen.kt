package com.elysium.vanguard.features.runtime.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.distros.gui.GraphicalDesktopCapability
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppEntry
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbHost
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession

/**
 * PHASE 9.6.5 — Linux desktop screen.
 *
 * The graphical route never paints a placeholder desktop. Its top card shows
 * the real availability of a graphical guest and sends the user to the PTY
 * terminal whenever no actual framebuffer renderer is present.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinuxDesktopScreen(
    onBack: () -> Unit,
    onOpenTerminal: (String?) -> Unit,
    viewModel: LinuxDesktopViewModel = hiltViewModel()
) {
    val apps by viewModel.apps.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val capability by viewModel.capability.collectAsState()
    val rfbSession by viewModel.rfbSession.collectAsState()
    val desktopError by viewModel.desktopError.collectAsState()
    var rfbState by remember(rfbSession) { mutableStateOf(rfbSession?.state?.value ?: RfbSession.State.Stopped) }
    LaunchedEffect(rfbSession) {
        rfbSession?.state?.collect { rfbState = it }
    }

    Scaffold(
        containerColor = Color.Black,
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding: PaddingValues ->
        val liveState = rfbState as? RfbSession.State.Streaming
        val session = rfbSession
        if (liveState != null && session != null) {
            LiveDesktopWorkspace(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                session = session,
                state = liveState,
                onDisconnect = viewModel::disconnectLocalVnc
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WorkspaceCapabilityCard(
                    capability = capability,
                    rfbState = rfbState,
                    launchError = desktopError,
                    onOpenTerminal = { onOpenTerminal(null) },
                    onConnectLocalVnc = viewModel::connectLocalVnc,
                    onDisconnectLocalVnc = viewModel::disconnectLocalVnc
                )
                if (apps.isEmpty()) {
                    Text(
                        text = "no desktop entries discovered in this rootfs",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF8B949E)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(apps, key = { it.id }) { app ->
                            AppRow(app = app, onOpenTerminal = { onOpenTerminal(app.exec) })
                        }
                    }
                }
            }
        }
    }
}

/** A real guest framebuffer owns the available workspace instead of a card slot. */
@Composable
private fun LiveDesktopWorkspace(
    modifier: Modifier,
    session: RfbSession,
    state: RfbSession.State.Streaming,
    onDisconnect: () -> Unit
) {
    Box(modifier = modifier.background(Color.Black)) {
        RfbHost(modifier = Modifier.fillMaxSize(), session = session)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.84f))
                .padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "LIVE · ${state.server.width}×${state.server.height} · ${state.frameCount} frames",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = Color(0xFF39FF14)
            )
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF3D71),
                    contentColor = Color.Black
                )
            ) {
                Text("DISCONNECT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WorkspaceCapabilityCard(
    capability: GraphicalDesktopCapability,
    rfbState: RfbSession.State,
    launchError: String?,
    onOpenTerminal: () -> Unit,
    onConnectLocalVnc: () -> Unit,
    onDisconnectLocalVnc: () -> Unit
) {
    val accent = when (capability.state) {
        GraphicalDesktopCapability.State.ROOTFS_UNAVAILABLE -> Color(0xFFFF3D71)
        GraphicalDesktopCapability.State.TERMINAL_READY -> Color(0xFF00E5FF)
        GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_AVAILABLE -> Color(0xFFFFE600)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "GRAPHICS / CAPABILITY",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = accent
                )
                Text(
                    text = capability.title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = Color(0xFFE4E7EB)
                )
                Text(
                    text = capability.detail,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFB7C1CC)
                )
                capability.detectedServer?.let { server ->
                    Text(
                        text = "server: /$server",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = accent
                    )
                }
                LocalVncStatus(
                    capability = capability,
                    state = rfbState,
                    launchError = launchError,
                    onConnect = onConnectLocalVnc,
                    onDisconnect = onDisconnectLocalVnc
                )
                Button(
                    onClick = onOpenTerminal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                    Text(
                        text = "OPEN REAL TERMINAL",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalVncStatus(
    capability: GraphicalDesktopCapability,
    state: RfbSession.State,
    launchError: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    if (!launchError.isNullOrBlank()) {
        Text(
            text = "desktop unavailable: $launchError",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFFF6E6E)
        )
    }
    when (state) {
        RfbSession.State.Idle -> if (capability.state == GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_AVAILABLE) {
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE600), contentColor = Color.Black)
            ) {
                Text("START AUTHENTICATED DESKTOP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        RfbSession.State.Connecting -> Text(
            text = "starting authenticated local workspace…",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFFFE600)
        )
        is RfbSession.State.Connected -> Text(
            text = "negotiated ${state.server.desktopName}; waiting for framebuffer…",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFF00E5FF)
        )
        is RfbSession.State.Streaming -> {
            Text(
                text = "LIVE · ${state.server.width}×${state.server.height} · ${state.frameCount} frames",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF39FF14)
            )
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3D71), contentColor = Color.Black)
            ) {
                Text("DISCONNECT VNC", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        is RfbSession.State.Failed -> Text(
            text = "desktop connection unavailable: ${state.detail}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFFF6E6E)
        )
        RfbSession.State.Stopped -> if (capability.state == GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_AVAILABLE) {
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE600), contentColor = Color.Black)
            ) {
                Text("RESTART AUTHENTICATED DESKTOP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
private fun AppRow(app: LinuxAppEntry, onOpenTerminal: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = BorderStroke(1.dp, Color(0xFF12343B))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = app.name,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Color(0xFFE4E7EB)
            )
            Text(
                text = "EXEC · ${app.exec}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF00E5FF)
            )
            if (!app.comment.isNullOrBlank()) {
                Text(
                    text = app.comment,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Button(
                onClick = onOpenTerminal,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black
                )
            ) {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Text(
                    text = "RUN IN REAL TERMINAL",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
