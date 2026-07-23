package com.elysium.vanguard.features.dashboard

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.ui.components.MatrixRain
import com.elysium.vanguard.ui.components.NeonGlowIcon
import com.elysium.vanguard.ui.components.TitanLogo
import com.elysium.vanguard.ui.components.TitanLogoStyle
import com.elysium.vanguard.ui.components.SovereignLifeWrapper
import com.elysium.vanguard.ui.components.SovereignCard
import com.elysium.vanguard.ui.components.OrbitalIcon
import com.elysium.vanguard.ui.components.AnimatedCounter
import com.elysium.vanguard.ui.components.GlassPillBadge
import com.elysium.vanguard.ui.theme.TitanColors
import com.elysium.vanguard.ui.theme.GlobalColors
import com.elysium.vanguard.ui.theme.LocalAdaptiveMetrics
import com.elysium.vanguard.ui.theme.holographicGlass
import com.elysium.vanguard.ui.theme.pulsingNeonBorder
import com.elysium.vanguard.ui.theme.neonGlass
import com.elysium.vanguard.ui.theme.uniformNeonSurfaceBrush
import com.elysium.vanguard.ui.theme.uniformNeonSurfaceColor
import com.elysium.vanguard.ui.theme.premiumGlass
import com.elysium.vanguard.ui.theme.SectionColorManager
import com.elysium.vanguard.ui.components.ColorCustomizerIcon
import com.elysium.vanguard.ui.components.ColorSelectionDialog
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    onNavigateToStorage: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToMusic: () -> Unit,
    onNavigateToRuntime: (() -> Unit)? = null,
    onNavigateToWorkspaces: (() -> Unit)? = null,
    onNavigateToTerminal: (() -> Unit)? = null,
    onNavigateToWord: (() -> Unit)? = null,
    onNavigateToSheet: (() -> Unit)? = null,
    onNavigateToColors: (() -> Unit)? = null,
    onNavigateToCommandCore: (() -> Unit)? = null,
    onNavigateToLocalAgent: (() -> Unit)? = null,
    onNavigateToDesktop: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showColorDialog by remember { mutableStateOf(false) }
    val accentColor = SectionColorManager.dashboardAccent
    val adaptive = LocalAdaptiveMetrics.current

    // PHASE 10.9 — Global theme. Read the 4 brand colors once at
    // composition time. Used to drive every card/tile/border in
    // the dashboard. When the user picks a new palette on the
    // COLORS screen, these values flip and the whole dashboard
    // re-renders in the new colors.
    val gPrimary = GlobalColors.primary
    val gSecondary = GlobalColors.secondary
    val gTertiary = GlobalColors.tertiary
    val gQuaternary = GlobalColors.quaternary

    /** One opaque, module-derived fill: no transparent color over black. */
    fun moduleSurface(color: Color): Brush = uniformNeonSurfaceBrush(color, 0.24f)

    // ── LIVE DEVICE METRICS ──
    val storageInfo = remember { getStorageInfo() }
    val ramInfo = remember { getRamInfo(context) }
    val batteryLevel = remember { getBatteryLevel(context) }

    val portalItems = listOf(
        PortalItem("FILE SYSTEM", "MANAGE · COMPRESS · EXPLORE", Icons.Default.Storage, gPrimary, onNavigateToStorage,
            moduleSurface(gPrimary)),
        PortalItem("MEDIA VAULT", "PHOTOS · VIDEOS · ALBUMS", Icons.Default.Image, gSecondary, onNavigateToGallery,
            moduleSurface(gSecondary)),
        PortalItem("AUDIO HUB", "MUSIC · PLAYLISTS · BASS", Icons.Default.MusicNote, gTertiary, onNavigateToMusic,
            moduleSurface(gTertiary)),
        // PHASE 9.6.2 — Sovereign Runtime catalog now opens a
        // dedicated screen that lets the user pick which distro to
        // install. The Terminal tile stays for one-tap access to the
        // local Android shell while rootfs setup is still pending.
        PortalItem(
            title = "RUNTIME",
            subtitle = "INSTALL · OPEN · MANAGE",
            icon = Icons.Default.Terminal,
            neonColor = gQuaternary,
            onClick = onNavigateToRuntime ?: {},
            gradientBg = moduleSurface(gQuaternary)
        ),
        // PHASE 42 — Sovereign runtime home (workspaces, sessions,
        // status bar). Distinct from the catalog tile above; the
        // catalog is for "I want to install a new distro" and the
        // home is for "I want to start / stop my existing
        // sessions".
        PortalItem(
            title = "WORKSPACES",
            subtitle = "SESSIONS · STATUS · CONTROL",
            icon = Icons.Default.Computer,
            neonColor = gPrimary,
            onClick = onNavigateToWorkspaces ?: {},
            gradientBg = moduleSurface(gPrimary)
        ),
        // PHASE 10.5 — Elysium Word. The full Word clone: font,
        // typography, spacing, alignment, lists, headings, etc.
        PortalItem(
            title = "WORD",
            subtitle = "WRITE · FORMAT · EXPORT",
            icon = Icons.Default.Article,
            neonColor = gPrimary,
            onClick = onNavigateToWord ?: {},
            gradientBg = moduleSurface(gPrimary)
        ),
        // PHASE 10.6 — Elysium Sheet. The full Excel clone: cells,
        // formulas (32 functions), formatting, multiple sheets.
        PortalItem(
            title = "SHEET",
            subtitle = "FORMULAS · CHARTS · EXPORT",
            icon = Icons.Default.GridOn,
            neonColor = gSecondary,
            onClick = onNavigateToSheet ?: {},
            gradientBg = moduleSurface(gSecondary)
        ),
        // PHASE 10.8 — Color customization. Live palette editor:
        // primary/secondary/tertiary/quaternary + accent, with
        // NEON / PHOSPHORESCENT / METALLIC / COMBINED / DIFFUSED
        // styles, 8 built-in presets (TITAN / OLED BLACK / etc.),
        // and persistence.
        PortalItem(
            title = "COLORS",
            subtitle = "PRIMARY · ACCENT · PRESETS",
            icon = Icons.Default.Palette,
            neonColor = gTertiary,
            onClick = onNavigateToColors ?: {},
            gradientBg = moduleSurface(gTertiary)
        ),
        PortalItem(
            title = "COMMAND CORE",
            subtitle = "PLAN · REVIEW · APPROVE",
            icon = Icons.Default.AutoAwesome,
            neonColor = TitanColors.NeonCyan,
            onClick = onNavigateToCommandCore ?: {},
            gradientBg = moduleSurface(TitanColors.NeonCyan)
        ),
        // PHASE 73 — the rule-based local agent
        // (parallel to the HTTP-gateway Command
        // Core; the two systems coexist and the
        // user picks which one to talk to).
        PortalItem(
            title = "LOCAL AGENT",
            subtitle = "RULE-BASED · ON-DEVICE",
            icon = Icons.Default.Psychology,
            neonColor = TitanColors.NeonRed,
            onClick = onNavigateToLocalAgent ?: {},
            gradientBg = moduleSurface(TitanColors.NeonRed)
        ),
        // PHASE 79 — the Universal Desktop Shell. Windows-11-style
        // windowing surface for the sovereign runtime: Terminal,
        // Files, Notes, Settings as draggable windows on a shared
        // desktop, with a real taskbar at the bottom.
        PortalItem(
            title = "DESKTOP",
            subtitle = "WINDOWS · DOCK · MULTITASK",
            icon = Icons.Default.DesktopWindows,
            neonColor = TitanColors.NeonCyan,
            onClick = onNavigateToDesktop ?: {},
            gradientBg = moduleSurface(TitanColors.NeonCyan)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TitanColors.AbsoluteBlack)
    ) {
        // ── MATRIX RAIN BACKGROUND ──
        MatrixRain(
            color = TitanColors.RadioactiveGreen.copy(alpha = 0.5f),
            speed = 70L,
            trailLength = 16,
            alpha = 0.25f,
            isMulticolor = SectionColorManager.isMulticolor
        )

        // ── COLOR CUSTOMIZER ──
        ColorCustomizerIcon(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 16.dp, end = 16.dp),
            onClick = { showColorDialog = true }
        )

        if (showColorDialog) {
            ColorSelectionDialog(
                sectionName = "DASHBOARD",
                onColorSelected = { SectionColorManager.dashboardAccent = it },
                onDismiss = { showColorDialog = false }
            )
        }

        // ── CONTENT ──
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = adaptive.dashboardCardMinWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = adaptive.screenPadding,
                top = adaptive.screenPadding + 20.dp,
                end = adaptive.screenPadding,
                bottom = adaptive.screenPadding + 4.dp
            ),
            verticalArrangement = Arrangement.spacedBy(adaptive.gridSpacing),
            horizontalArrangement = Arrangement.spacedBy(adaptive.gridSpacing)
        ) {
            // ══════════ HERO HEADER ══════════
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SovereignLifeWrapper {
                        TitanLogo(style = TitanLogoStyle.HERO, size = 100.dp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "ELYSIUM VANGUARD",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 6.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                    Text(
                        text = "NEURAL COMMAND CENTER",
                        color = gPrimary.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // PHASE 10.7 — Quick action ribbon. Three glass tiles
            // for the highest-traffic "make something" actions:
            // new Word document, new Sheet workbook, install a
            // Linux distro. We surface them above the main portal
            // grid so the user doesn't have to scroll to find
            // them.
            item(span = { GridItemSpan(maxLineSpan) }) {
                QuickActionRibbon(
                    onNewWord = { onNavigateToWord?.invoke() },
                    onNewSheet = { onNavigateToSheet?.invoke() },
                    onRuntime = { onNavigateToRuntime?.invoke() }
                )
            }

            // ══════════ LIVE METRICS STRIP ══════════
            item(span = { GridItemSpan(maxLineSpan) }) {
                SovereignCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    glassAlpha = 0.0f, // PHASE 10.9 — no dark background; the holographic border + gauges carry the visual
                    glowRadius = 26.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(uniformNeonSurfaceColor(gPrimary, 0.16f))
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        LiveMetricGauge(
                            label = "STORAGE",
                            value = storageInfo.usedPercent,
                            color = when {
                                storageInfo.usedPercent > 85 -> TitanColors.NeonRed
                                storageInfo.usedPercent > 60 -> TitanColors.NeonOrange
                                else -> accentColor
                            },
                            detail = "${storageInfo.usedGb}/${storageInfo.totalGb} GB"
                        )
                        LiveMetricGauge(
                            label = "MEMORY",
                            value = ramInfo.usedPercent,
                            color = when {
                                ramInfo.usedPercent > 80 -> TitanColors.NeonRed
                                ramInfo.usedPercent > 50 -> TitanColors.NeonOrange
                                else -> TitanColors.RadioactiveGreen
                            },
                            detail = "${ramInfo.usedGb}/${ramInfo.totalGb} GB"
                        )
                        LiveMetricGauge(
                            label = "BATTERY",
                            value = batteryLevel,
                            color = when {
                                batteryLevel < 20 -> TitanColors.NeonRed
                                batteryLevel < 50 -> TitanColors.NeonYellow
                                else -> TitanColors.RadioactiveGreen
                            },
                            detail = "$batteryLevel%"
                        )
                    }
                    } // close the colored-fill Box wrapper
                }
            }

            // ══════════ SECTION HEADER ══════════
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(16.dp)
                            .background(accentColor, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "OPERATIONAL NODES",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ══════════ PORTAL CARDS ══════════
            items(portalItems) { item ->
                PortalCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    neonColor = item.neonColor,
                    gradientBg = item.gradientBg,
                    onClick = item.onClick
                )
            }

            // ══════════ STATUS STRIP ══════════
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .pulsingNeonBorder(cornerRadius = 16.dp, glowColor = gPrimary, glassAlpha = 0.1f)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(label = "CORE", value = "STABLE", color = gPrimary)
                    StatusChip(label = "SHIELD", value = "ACTIVE", color = TitanColors.RadioactiveGreen)
                    StatusChip(label = "THREATS", value = "ZERO", color = TitanColors.NeonRed)
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// COMPONENTS
// ══════════════════════════════════════════════════════════════

private data class PortalItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val neonColor: Color,
    val onClick: () -> Unit,
    val gradientBg: Brush
)

/**
 * LIVE METRIC GAUGE — Circular arc gauge with animated fill.
 */
@Composable
private fun LiveMetricGauge(
    label: String,
    value: Int,
    color: Color,
    detail: String
) {
    val adaptive = LocalAdaptiveMetrics.current
    val animatedSweep by animateFloatAsState(
        targetValue = (value / 100f) * 270f,
        animationSpec = tween(1500, easing = FastOutSlowInEasing),
        label = "sweep"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(adaptive.metricGaugeSize), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 5.dp.toPx()
                // Background arc
                drawArc(
                    color = Color.White.copy(alpha = 0.08f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                // Filled arc
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            AnimatedCounter(
                targetValue = value,
                color = color,
                fontSize = if (adaptive.isCompact) 14.sp else 16.sp,
                fontWeight = FontWeight.Black,
                suffix = "%"
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
        Text(detail, color = Color.White.copy(alpha = 0.3f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
    }
}

/**
 * PORTAL CARD — Premium card with orbital icon and gradient background.
 *
 * PHASE 10.9 — The inner fill is now a flat tinted color (no
 * `Color.Transparent` stop). The previous vertical gradient that
 * faded to transparent at the bottom was making the lower half
 * of every card show the matrix-rain text behind it, which read
 * as "black inside the module". Now the whole card is tinted
 * with the brand color so the color is visible across the
 * entire surface, not just the top edge.
 */
@Composable
private fun PortalCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    neonColor: Color,
    gradientBg: Brush,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val adaptive = LocalAdaptiveMetrics.current
    // PHASE 10.10 — The clickable is on the INNER Box (not the
    // outer SovereignCard modifier) because the SovereignCard
    // wraps its content in SovereignLifeWrapper which applies a
    // 3D rotation effect. The rotation moves the visible card
    // slightly, but the clickable area stays at the original
    // position. Tapping the card visibly fires the click
    // (the focus state changes) but the rotation makes the
    // hit area not match the visible area, so the click
    // appears to be ignored. Putting the clickable on the
    // inner Box (which IS inside the rotation) makes the
    // hit area track the visible card.
    SovereignCard(
        modifier = modifier
            .aspectRatio(adaptive.portalAspectRatio),
        cornerRadius = 24.dp,
        glassAlpha = 0.0f,
        glowRadius = 26.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBg)
                .clickable { onClick() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Orbital icon
                OrbitalIcon(
                    icon = icon,
                    color = neonColor,
                    iconSize = 28.dp,
                    ringSize = 52.dp,
                    ringColor = neonColor.copy(alpha = 0.4f)
                )

                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = neonColor.copy(alpha = 0.6f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        lineHeight = 12.sp
                    )
                }

                // Enter arrow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ENTER",
                        color = neonColor.copy(alpha = 0.4f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = neonColor.copy(alpha = 0.4f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * STATUS CHIP — Compact status indicator with label and value.
 */
@Composable
private fun StatusChip(label: String, value: String, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_$label")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color.copy(alpha = glowAlpha), CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(label, color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Text(value, color = color.copy(alpha = glowAlpha), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}

// ══════════════════════════════════════════════════════════════
// DEVICE INFO HELPERS
// ══════════════════════════════════════════════════════════════

private data class StorageInfo(val totalGb: String, val usedGb: String, val usedPercent: Int)
private data class RamInfo(val totalGb: String, val usedGb: String, val usedPercent: Int)

private fun getStorageInfo(): StorageInfo {
    return try {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val usedBytes = totalBytes - freeBytes
        val totalGb = "%.1f".format(totalBytes / 1_073_741_824.0)
        val usedGb = "%.1f".format(usedBytes / 1_073_741_824.0)
        val percent = ((usedBytes.toDouble() / totalBytes) * 100).roundToInt()
        StorageInfo(totalGb, usedGb, percent)
    } catch (_: Exception) {
        StorageInfo("0", "0", 0)
    }
}

private fun getRamInfo(context: Context): RamInfo {
    return try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalGb = "%.1f".format(memInfo.totalMem / 1_073_741_824.0)
        val usedGb = "%.1f".format((memInfo.totalMem - memInfo.availMem) / 1_073_741_824.0)
        val percent = (((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem) * 100).roundToInt()
        RamInfo(totalGb, usedGb, percent)
    } catch (_: Exception) {
        RamInfo("0", "0", 0)
    }
}

private fun getBatteryLevel(context: Context): Int {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
        100
    }
}

// ══════════════════════════════════════════════════════════════
// PHASE 10.7 — Quick action ribbon
// ══════════════════════════════════════════════════════════════

/**
 * Compact row of three glass tiles for "create something new"
 * actions. Each tile pulses its accent color softly so the
 * user perceives them as alive. The whole ribbon is wrapped in
 * a `pulsingNeonBorder` so it never feels static. Colors come
 * from the [GlobalColors] theme (PHASE 10.9) so the whole
 * ribbon follows the user's palette pick.
 */
@Composable
private fun QuickActionRibbon(
    onNewWord: () -> Unit,
    onNewSheet: () -> Unit,
    onRuntime: () -> Unit
) {
    val adaptive = LocalAdaptiveMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .pulsingNeonBorder(cornerRadius = 18.dp, glowColor = GlobalColors.primary, glassAlpha = 0.0f)
            .padding(horizontal = adaptive.screenPadding / 2, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(if (adaptive.isCompact) 6.dp else 8.dp)
    ) {
        QuickActionTile(
            label = "WORD",
            icon = Icons.Default.Article,
            accent = GlobalColors.primary,
            modifier = Modifier.weight(1f),
            onClick = onNewWord
        )
        QuickActionTile(
            label = "SHEET",
            icon = Icons.Default.GridOn,
            accent = GlobalColors.secondary,
            modifier = Modifier.weight(1f),
            onClick = onNewSheet
        )
        QuickActionTile(
            label = "RUNTIME",
            icon = Icons.Default.Terminal,
            accent = GlobalColors.tertiary,
            modifier = Modifier.weight(1f),
            onClick = onRuntime
        )
    }
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val adaptive = LocalAdaptiveMetrics.current
    val infinite = rememberInfiniteTransition(label = "tile_$label")
    val glow by infinite.animateFloat(
        initialValue = 0.5f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )
    Box(
        modifier = modifier
            .height(adaptive.quickTileHeight)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(14.dp),
                spotColor = accent.copy(alpha = 0.85f * glow),
                ambientColor = accent.copy(alpha = 0.55f * glow),
                clip = false
            )
            .clip(RoundedCornerShape(14.dp))
            .background(uniformNeonSurfaceColor(accent, 0.24f))
            .border(1.2.dp, accent.copy(alpha = 0.85f * glow), RoundedCornerShape(14.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = label,
                tint = accent.copy(alpha = glow),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
        }
    }
}
