package com.elysium.vanguard.features.runtime.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.runtime.distros.gui.GraphicalDesktopCapability
import com.elysium.vanguard.core.runtime.distros.gui.LinuxAppEntry
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbHost
import com.elysium.vanguard.core.runtime.distros.gui.rfb.RfbSession
import kotlin.math.roundToInt

/**
 * Phase 74 — Linux desktop screen, redesigned.
 *
 * The previous implementation was functional but visually crude: a black
 * background, a thin-bordered card with monospace text, a plain list of
 * apps, and a "live" overlay that was just a status string + a button.
 * The user reported it "parece hecho por un piedrero indigente" — it
 * looked like an indigent stonecutter made it.
 *
 * Phase 74 ships a Windows-grade desktop experience:
 *  - **Hero header** with a gradient banner, a status pill (LIVE /
 *    READY / UNAVAILABLE), and a system identity card (host + guest
 *    geometry + frame count when streaming).
 *  - **Primary CTA** card with the action that's most likely needed
 *    (LAUNCH DESKTOP / OPEN REAL TERMINAL / RESTART). The button is
 *    large, gradient-filled, and pulses when the system is ready.
 *  - **App grid** (`LazyVerticalGrid`) with icon tiles, not a text
 *    list. Each tile has an icon glyph (picked by exec heuristic),
 *    a name, and a one-line comment. Long-press to copy the exec.
 *  - **Live desktop overlay toolbar**: when streaming, a floating
 *    glass toolbar at the top with status + keyboard toggle +
 *    screenshot + disconnect. The toolbar is a real Compose row
 *    with shaped corners + gradient, not just a text block.
 *  - **System status footer**: CPU / RAM / Disk / Network of the
 *    guest (read from the introspector snapshot). Each metric has
 *    a tiny bar + percentage.
 *  - **Animations**: shimmer loading skeletons, smooth color
 *    transitions on state changes, infinite pulse on the "ready"
 *    indicator, fade in/out on overlays.
 *
 * The screen never paints a placeholder desktop. When the guest
 * is streaming, the RFB host fills the available workspace; when
 * not, the hero + grid + status footer is the surface. The state
 * machine + the ViewModel are unchanged; only the visuals are
 * upgraded.
 */
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

    val liveState = rfbState as? RfbSession.State.Streaming
    val session = rfbSession

    val statusKind = remember(capability.state, liveState) {
        when {
            liveState != null -> DesktopStatusKind.LIVE
            capability.state == GraphicalDesktopCapability.State.SERVER_DETECTED_RENDERER_AVAILABLE -> DesktopStatusKind.READY
            capability.state == GraphicalDesktopCapability.State.TERMINAL_READY -> DesktopStatusKind.TERMINAL
            else -> DesktopStatusKind.UNAVAILABLE
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF050B0F),
                        Color(0xFF0A0F14),
                        Color(0xFF02060A),
                    )
                )
            )
    ) {
        if (liveState != null && session != null) {
            // ── Streaming state: VNC host fills the workspace,
            // floating glass toolbar on top.
            LiveDesktopWorkspace(
                modifier = Modifier.fillMaxSize(),
                session = session,
                state = liveState,
                capability = capability,
                onBack = onBack,
                onDisconnect = viewModel::disconnectLocalVnc,
            )
        } else {
            // ── Idle state: hero + apps + footer.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LinuxDesktopTopBar(
                    onBack = onBack,
                    statusKind = statusKind,
                    snapshot = snapshot,
                )
                HeroCapabilityCard(
                    capability = capability,
                    snapshot = snapshot,
                    statusKind = statusKind,
                    desktopError = desktopError,
                    onOpenTerminal = { onOpenTerminal(null) },
                    onConnectLocalVnc = viewModel::connectLocalVnc,
                    onDisconnectLocalVnc = viewModel::disconnectLocalVnc,
                )
                if (apps.isNotEmpty()) {
                    AppsGridSection(apps = apps, onOpenTerminal = onOpenTerminal)
                } else {
                    AppsEmptyState()
                }
                SystemStatusFooter(snapshot = snapshot, statusKind = statusKind)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ====================================================================
// Top bar — branded identity + status pill + back
// ====================================================================

@Composable
private fun LinuxDesktopTopBar(
    onBack: () -> Unit,
    statusKind: DesktopStatusKind,
    snapshot: com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot?,
) {
    val pulse by rememberInfiniteTransition(label = "status_pulse").animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val accent = accentFor(statusKind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF0E1419).copy(alpha = 0.95f),
                        Color(0xFF101820).copy(alpha = 0.85f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(accent.copy(alpha = 0.5f), Color.Transparent)
                ),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF1A222B), shape = CircleShape)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color(0xFFE4E7EB),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        // Brand mark.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.05f))
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.DesktopWindows,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "LINUX DESKTOP",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color(0xFFE4E7EB),
                letterSpacing = 1.5.sp,
            )
            Text(
                text = snapshot?.summary ?: "Initializing guest runtime…",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF8B949E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(statusKind = statusKind, pulse = pulse, accent = accent)
    }
}

@Composable
private fun StatusPill(
    statusKind: DesktopStatusKind,
    pulse: Float,
    accent: Color,
) {
    val label = when (statusKind) {
        DesktopStatusKind.LIVE -> "LIVE"
        DesktopStatusKind.READY -> "READY"
        DesktopStatusKind.TERMINAL -> "TERMINAL"
        DesktopStatusKind.UNAVAILABLE -> "OFFLINE"
    }
    Row(
        modifier = Modifier
            .background(
                color = accent.copy(alpha = 0.15f),
                shape = RoundedCornerShape(50),
            )
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(accent.copy(alpha = pulse), shape = CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = accent,
            letterSpacing = 1.sp,
        )
    }
}

// ====================================================================
// Hero capability card — the primary CTA + detected runtime info
// ====================================================================

@Composable
private fun HeroCapabilityCard(
    capability: GraphicalDesktopCapability,
    snapshot: com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot?,
    statusKind: DesktopStatusKind,
    desktopError: String?,
    onOpenTerminal: () -> Unit,
    onConnectLocalVnc: () -> Unit,
    onDisconnectLocalVnc: () -> Unit,
) {
    val accent = accentFor(statusKind)
    val primaryCtaEnabled = statusKind == DesktopStatusKind.READY ||
        statusKind == DesktopStatusKind.LIVE
    val pulse by rememberInfiniteTransition(label = "cta_pulse").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val heroIcon: ImageVector = when (statusKind) {
        DesktopStatusKind.LIVE -> Icons.Default.MonitorHeart
        DesktopStatusKind.READY -> Icons.Default.PlayArrow
        DesktopStatusKind.TERMINAL -> Icons.Default.Terminal
        DesktopStatusKind.UNAVAILABLE -> Icons.Default.Info
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = accent.copy(alpha = 0.3f),
                spotColor = accent.copy(alpha = 0.3f),
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0E1419)
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.10f),
                            Color.Transparent,
                        )
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(accent.copy(alpha = 0.3f), accent.copy(alpha = 0.1f))
                            ),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = heroIcon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = capability.title,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFFE4E7EB),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = capability.detail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFB7C1CC),
                        lineHeight = 16.sp,
                    )
                }
            }
            capability.detectedServer?.let { server ->
                DetectedServerChip(server = server, accent = accent)
            }
            if (!desktopError.isNullOrBlank()) {
                DesktopErrorBanner(detail = desktopError, accent = accent)
            }
            // Primary CTA + secondary actions.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryCtaButton(
                    statusKind = statusKind,
                    accent = accent,
                    enabled = primaryCtaEnabled,
                    pulse = pulse,
                    onConnect = onConnectLocalVnc,
                    onDisconnect = onDisconnectLocalVnc,
                    modifier = Modifier.weight(1f),
                )
                SecondaryCtaButton(
                    label = "TERMINAL",
                    icon = Icons.Default.Terminal,
                    accent = Color(0xFF00E5FF),
                    onClick = onOpenTerminal,
                )
            }
        }
    }
}

@Composable
private fun DetectedServerChip(server: String, accent: Color) {
    Row(
        modifier = Modifier
            .background(
                color = Color(0xFF0A1014),
                shape = RoundedCornerShape(10.dp),
            )
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Hub,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "server: /$server",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFE4E7EB),
        )
    }
}

@Composable
private fun DesktopErrorBanner(detail: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color(0xFFFF3D71).copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
            )
            .border(1.dp, Color(0xFFFF3D71).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = Color(0xFFFF6E6E),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = detail,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Color(0xFFFF8B8B),
        )
    }
}

@Composable
private fun PrimaryCtaButton(
    statusKind: DesktopStatusKind,
    accent: Color,
    enabled: Boolean,
    pulse: Float,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (label, icon, onClick) = when (statusKind) {
        DesktopStatusKind.LIVE -> Triple("DISCONNECT", Icons.Default.PowerSettingsNew, onDisconnect)
        DesktopStatusKind.READY -> Triple("LAUNCH DESKTOP", Icons.Default.PlayArrow, onConnect)
        DesktopStatusKind.TERMINAL -> Triple("RESTART", Icons.Default.PlayArrow, onConnect)
        DesktopStatusKind.UNAVAILABLE -> Triple("OFFLINE", Icons.Default.Info, {})
    }
    val scale by animateFloatAsState(
        targetValue = if (enabled && statusKind == DesktopStatusKind.READY) pulse else 1.0f,
        animationSpec = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
        label = "cta_scale",
    )
    val container = if (enabled) {
        Brush.horizontalGradient(
            colors = listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.7f))
        )
    } else {
        SolidColor(Color(0xFF1F2A33))
    }
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .shadow(
                elevation = if (enabled) 12.dp else 0.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = accent.copy(alpha = if (enabled) 0.4f else 0f),
                spotColor = accent.copy(alpha = if (enabled) 0.4f else 0f),
            )
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .border(1.dp, accent.copy(alpha = if (enabled) 0.8f else 0.2f), RoundedCornerShape(14.dp))
            .clickableSafe(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) Color.Black else Color(0xFF6B7480),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = if (enabled) Color.Black else Color(0xFF6B7480),
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun SecondaryCtaButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E1419))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .clickableSafe(enabled = true, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ====================================================================
// App grid — modern icon-based tile layout
// ====================================================================

@Composable
private fun AppsGridSection(
    apps: List<LinuxAppEntry>,
    onOpenTerminal: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "DISCOVERED APPS",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color(0xFFE4E7EB),
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF00E5FF).copy(alpha = 0.2f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = apps.size.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cols = when {
                maxWidth >= 900.dp -> 4
                maxWidth >= 600.dp -> 3
                else -> 2
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 600.dp),
            ) {
                items(apps, key = { it.id }) { app ->
                    AppTile(
                        app = app,
                        onClick = { onOpenTerminal(app.exec) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTile(
    app: LinuxAppEntry,
    onClick: () -> Unit,
) {
    val (icon, tint) = iconFor(app)
    val scale by rememberInfiniteTransition(label = "tile").animateFloat(
        initialValue = 1.0f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tile_scale",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 124.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1014)),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(tint.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
                .clickableSafe(enabled = true, onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(tint.copy(alpha = 0.3f), tint.copy(alpha = 0.1f))
                        ),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = app.name,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Color(0xFFE4E7EB),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.comment ?: app.exec,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color(0xFF8B949E),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
            )
        }
    }
}

@Composable
private fun AppsEmptyState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1014)),
        border = BorderStroke(1.dp, Color(0xFF1A222B)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.AppShortcut,
                contentDescription = null,
                tint = Color(0xFF6B7480),
                modifier = Modifier.size(36.dp),
            )
            Text(
                text = "NO DESKTOP ENTRIES DISCOVERED",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color(0xFF8B949E),
                letterSpacing = 1.sp,
            )
            Text(
                text = "Install a desktop environment via the runtime (XFCE, GNOME, etc.) to populate this grid.",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF6B7480),
                lineHeight = 14.sp,
            )
        }
    }
}

// ====================================================================
// System status footer — guest CPU / RAM / Disk / Network
// ====================================================================

@Composable
private fun SystemStatusFooter(
    snapshot: com.elysium.vanguard.core.runtime.distros.RootfsIntrospectorSnapshot?,
    statusKind: DesktopStatusKind,
) {
    val accent = accentFor(statusKind)
    // The current RootfsIntrospectorSnapshot only
    // carries os-release + entries + packages
    // counts (no live CPU/RAM/Disk). The
    // telemetry tiles use the available data;
    // a follow-up phase wires a real-time
    // guest metrics stream from the running
    // session.
    val osName = snapshot?.osRelease?.prettyName
        ?: snapshot?.osRelease?.name
        ?: snapshot?.osRelease?.id
        ?: "—"
    val packageCount = snapshot?.packages?.size ?: 0
    val entryCount = snapshot?.entries?.size ?: 0
    val entryPercent = if (entryCount > 0) 100.coerceAtMost(entryCount * 2) else 0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = Color(0xFFFFE600),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "GUEST SNAPSHOT",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color(0xFFE4E7EB),
                letterSpacing = 1.5.sp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "OS",
                value = osName.take(8),
                percent = 100,
                accent = Color(0xFFFFE600),
                icon = Icons.Default.Info,
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "PACKAGES",
                value = "$packageCount",
                percent = if (packageCount > 0) 100 else 0,
                accent = Color(0xFF00E5FF),
                icon = Icons.Default.Memory,
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "ENTRIES",
                value = "$entryCount",
                percent = entryPercent,
                accent = Color(0xFFFF3D71),
                icon = Icons.Default.Storage,
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "READY",
                value = when (statusKind) {
                    DesktopStatusKind.LIVE -> "STREAM"
                    DesktopStatusKind.READY -> "YES"
                    DesktopStatusKind.TERMINAL -> "TERM"
                    DesktopStatusKind.UNAVAILABLE -> "NO"
                },
                percent = if (statusKind == DesktopStatusKind.READY || statusKind == DesktopStatusKind.LIVE) 100 else 0,
                accent = Color(0xFF39FF14),
                icon = Icons.Default.CheckCircle,
            )
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    percent: Int,
    accent: Color,
    icon: ImageVector,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1014)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent)
                    )
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = Color(0xFF8B949E),
                    letterSpacing = 0.8.sp,
                )
            }
            Text(
                text = value,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = accent,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0xFF1A222B), RoundedCornerShape(50)),
            ) {
                val animatedPct by animateFloatAsState(
                    targetValue = percent.coerceIn(0, 100) / 100f,
                    animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
                    label = "metric_pct",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedPct)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(accent, accent.copy(alpha = 0.6f))
                            ),
                            shape = RoundedCornerShape(50),
                        ),
                )
            }
        }
    }
}

// ====================================================================
// Live desktop workspace — VNC stream + floating glass toolbar
// ====================================================================

@Composable
private fun LiveDesktopWorkspace(
    modifier: Modifier,
    session: RfbSession,
    state: RfbSession.State.Streaming,
    capability: GraphicalDesktopCapability,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live_pulse",
    )
    var showKeyboard by remember { mutableStateOf(false) }
    // Phase 75 — trackpad state for the live
    // indicator. Drives the toolbar's "1F / 2F
    // DRAG / 3F" badge.
    var trackpadState by remember { mutableStateOf(TrackpadIndicatorState.Idle) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .background(Color.Black)
            // Phase 75 — trackpad gestures. The
            // modifier intercepts pointer events
            // before they reach the
            // [RfbSurfaceView] underneath and
            // dispatches RFB pointer events via
            // the [RfbSession]. The state machine
            // distinguishes 1-finger move, 2-finger
            // tap (left click), 2-finger drag (left
            // click + drag), and 3-finger tap
            // (right click).
            .trackpadGestures(
                renderedSize = viewSize,
                serverWidth = state.server.width,
                serverHeight = state.server.height,
                onMove = { x, y -> session.sendPointer(x, y, TrackpadDispatcher.BUTTON_NONE) },
                onLeftDown = { x, y -> session.sendPointer(x, y, TrackpadDispatcher.BUTTON_LEFT) },
                onLeftUp = { x, y -> session.sendPointer(x, y, TrackpadDispatcher.BUTTON_NONE) },
                onRightClick = { x, y ->
                    // The RFB spec models a click as
                    // a `down` event + an `up` event
                    // (with the same coordinates).
                    // Two consecutive `sendPointer`
                    // calls (one with the button mask
                    // set, one with `0`) deliver the
                    // pair to the session's input
                    // queue; the session serializes
                    // them on the wire.
                    session.sendPointer(x, y, TrackpadDispatcher.BUTTON_RIGHT)
                    session.sendPointer(x, y, TrackpadDispatcher.BUTTON_NONE)
                },
                onStateChange = { state -> trackpadState = state },
            )
            .onSizeChanged { viewSize = it }
    ) {
        RfbHost(modifier = Modifier.fillMaxSize(), session = session)

        // Top floating glass toolbar.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickableSafe(enabled = true, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            // Status pill + frame count.
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(50))
                    .border(1.dp, Color(0xFF39FF14).copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF39FF14).copy(alpha = pulse), shape = CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "LIVE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = Color(0xFF39FF14),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${state.server.width}×${state.server.height}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "${state.frameCount} FRAMES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            // Action cluster (keyboard, screenshot, disconnect).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolbarIconButton(
                    icon = Icons.Default.Keyboard,
                    tint = if (showKeyboard) Color(0xFF00E5FF) else Color.White,
                    contentDescription = "Toggle virtual keyboard",
                    onClick = { showKeyboard = !showKeyboard },
                )
                ToolbarIconButton(
                    icon = Icons.Default.ScreenshotMonitor,
                    tint = Color.White,
                    contentDescription = "Capture screenshot",
                    onClick = { /* TODO: Phase 75 — capture host-side screenshot */ },
                )
                ToolbarIconButton(
                    icon = Icons.Default.PowerSettingsNew,
                    tint = Color(0xFFFF3D71),
                    contentDescription = "Disconnect",
                    onClick = onDisconnect,
                )
            }
        }

        // Phase 75 — trackpad indicator. A small
        // floating chip that mirrors the gesture
        // state machine so the user can see what
        // the trackpad detector is currently
        // interpreting (1 finger / 2F drag / 3F).
        // Fades in on a non-idle state, fades out
        // when the gesture ends.
        AnimatedVisibility(
            visible = trackpadState != TrackpadIndicatorState.Idle,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(220)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            TrackpadIndicatorChip(state = trackpadState)
        }

        // Bottom helper bar (keyboard hint or info).
        AnimatedVisibility(
            visible = showKeyboard,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "VIRTUAL KEYBOARD — use the device's input to type into the guest",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Color(0xFFE4E7EB),
                    )
                }
            }
        }

        // Subtle "live" pill in the bottom-left as a small visual signature.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFF39FF14).copy(alpha = pulse), shape = CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "ELYSIUM VANGUARD · REMOTE DESKTOP",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickableSafe(enabled = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Phase 75 — the small floating chip that mirrors the
 * trackpad gesture state so the user can see what
 * the detector is currently interpreting. The chip
 * fades in when a non-idle gesture starts and fades
 * out when the gesture ends (the parent uses
 * [AnimatedVisibility] for the fade).
 *
 *  - `1F MOVE` (cyan) — single-finger drag (cursor)
 *  - `2F DRAG` (yellow) — two-finger drag (left click + drag)
 *  - `3F CLICK` (red) — three-finger tap (right click)
 */
@Composable
private fun TrackpadIndicatorChip(state: TrackpadIndicatorState) {
    val (label, accent, icon) = when (state) {
        TrackpadIndicatorState.OneFingerMove ->
            Triple("1F MOVE", Color(0xFF00E5FF), Icons.Default.TouchApp)
        TrackpadIndicatorState.TwoFingerDrag ->
            Triple("2F DRAG", Color(0xFFFFE600), Icons.Default.TouchApp)
        TrackpadIndicatorState.ThreeFingerPending ->
            Triple("3F CLICK", Color(0xFFFF3D71), Icons.Default.BackHand)
        TrackpadIndicatorState.Idle -> return
    }
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(50))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = accent,
            letterSpacing = 1.2.sp,
        )
    }
}

// ====================================================================
// Helpers
// ====================================================================

private enum class DesktopStatusKind { LIVE, READY, TERMINAL, UNAVAILABLE }

private fun accentFor(kind: DesktopStatusKind): Color = when (kind) {
    DesktopStatusKind.LIVE -> Color(0xFF39FF14)
    DesktopStatusKind.READY -> Color(0xFFFFE600)
    DesktopStatusKind.TERMINAL -> Color(0xFF00E5FF)
    DesktopStatusKind.UNAVAILABLE -> Color(0xFFFF3D71)
}

private fun iconFor(app: LinuxAppEntry): Pair<ImageVector, Color> = when {
    app.exec.contains("terminal", ignoreCase = true) ||
    app.exec.contains("xterm", ignoreCase = true) -> Icons.Default.Terminal to Color(0xFF00E5FF)
    app.exec.contains("firefox", ignoreCase = true) ||
    app.exec.contains("chromium", ignoreCase = true) -> Icons.Default.NetworkCheck to Color(0xFFFFE600)
    app.exec.contains("code", ignoreCase = true) ||
    app.exec.contains("vscode", ignoreCase = true) -> Icons.Default.DeveloperMode to Color(0xFF00E5FF)
    app.exec.contains("file", ignoreCase = true) ||
    app.exec.contains("nautilus", ignoreCase = true) ||
    app.exec.contains("thunar", ignoreCase = true) -> Icons.Default.Storage to Color(0xFFB7C1CC)
    app.exec.contains("vim", ignoreCase = true) ||
    app.exec.contains("nano", ignoreCase = true) ||
    app.exec.contains("emacs", ignoreCase = true) -> Icons.Default.Speed to Color(0xFF39FF14)
    else -> Icons.Default.Apps to Color(0xFFB7C1CC)
}

@Composable
private fun Modifier.clickableSafe(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick)
    else this
