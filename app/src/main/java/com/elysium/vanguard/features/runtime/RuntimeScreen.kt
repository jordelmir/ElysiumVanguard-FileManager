package com.elysium.vanguard.features.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.elysium.vanguard.core.runtime.distros.Distro
import com.elysium.vanguard.core.runtime.distros.DistroCatalog
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.DistroInstallProgress
import com.elysium.vanguard.core.runtime.distros.EffectiveCatalogRow
import com.elysium.vanguard.core.runtime.distros.displayByteSize
import com.elysium.vanguard.ui.theme.LocalAdaptiveMetrics

/**
 * PHASE 9.6.2 — Sovereign runtime catalog screen.
 *
 * Single tab that lists the distro catalog plus any locally installed
 * rootfs (with a chip per distro showing on-disk size and an Open button
 * when healthy). Tapping install kicks off `DistroManager.installBlocking`
 * on `Dispatchers.IO`; an error becomes a banner.
 *
 * Phase 9.6.2 — first build; intentionally minimal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuntimeScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDistro: (String) -> Unit = {},
    onInspectDistro: (String) -> Unit = {},
    onCustomRootfs: () -> Unit = {},
    onOpenDesktop: (String) -> Unit = {},
    viewModel: RuntimeViewModel = hiltViewModel()
) {
    val adaptive = LocalAdaptiveMetrics.current
    val installed by viewModel.installed.collectAsState()
    val installing by viewModel.installing.collectAsState()
    val errors by viewModel.errors.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val effectiveCatalog by viewModel.effectiveCatalog.collectAsState()
    val healthyCount = installed.count { it.isHealthy }
    val failedCount = installed.size - healthyCount

    Scaffold(
        containerColor = Color(0xFF0B0D10),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Sovereign Runtime",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = Color(0xFFE4E7EB)
                        )
                        Text(
                            text = if (failedCount > 0) {
                                "Catalog · $healthyCount ready · $failedCount repair"
                            } else {
                                "Catalog · $healthyCount ready"
                            },
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
                    IconButton(onClick = onOpenTerminal) {
                        Icon(
                            Icons.Default.Terminal,
                            contentDescription = "Open local shell",
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
        ) {
            Text(
                text = "Total catalog size: ${(DistroCatalog.totalCatalogSizeBytes).displayByteSize()}",
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF8B949E),
                modifier = Modifier.padding(adaptive.screenPadding)
            )

            // PHASE 9.6.3.2 — "Add custom rootfs" tile.
            CustomRootfsTile(onClick = onCustomRootfs)

            if (errors.isNotEmpty()) {
                BannerError(errors.values.first())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(adaptive.screenPadding),
                verticalArrangement = Arrangement.spacedBy(adaptive.sectionSpacing)
            ) {
                if (effectiveCatalog.any { !it.isCustom }) {
                    item { SectionHeader("Catalog") }
                    items(
                        effectiveCatalog.filter { !it.isCustom },
                        key = { it.distro.id }
                    ) { row ->
                        EffectiveCatalogRowView(
                            row = row,
                            isInstalling = installing.contains(row.distro.id),
                            progress = progress[row.distro.id],
                            onInstall = { viewModel.installBlocking(row.distro.id) },
                            onRemove = { viewModel.remove(row.distro.id) },
                            onOpenTerminal = { onOpenDistro(row.distro.id) },
                            onInspect = { onInspectDistro(row.distro.id) },
                            onOpenDesktop = { onOpenDesktop(row.distro.id) }
                        )
                    }
                }
                if (effectiveCatalog.any { it.isCustom }) {
                    item { SectionHeader("Custom") }
                    items(
                        effectiveCatalog.filter { it.isCustom },
                        key = { it.distro.id }
                    ) { row ->
                        EffectiveCatalogRowView(
                            row = row,
                            isInstalling = installing.contains(row.distro.id),
                            progress = progress[row.distro.id],
                            onInstall = { /* already installed; no-op */ },
                            onRemove = { viewModel.remove(row.distro.id) },
                            onOpenTerminal = { onOpenDistro(row.distro.id) },
                            onInspect = { onInspectDistro(row.distro.id) },
                            onOpenDesktop = { onOpenDesktop(row.distro.id) }
                        )
                    }
                }
                if (effectiveCatalog.isEmpty()) {
                    item {
                        Text(
                            text = "no distros yet — install from the catalog or add a custom URL",
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8B949E)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DistroCatalogRow(
    distro: Distro,
    installed: DistroInstallation?,
    isInstalling: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpenTerminal: () -> Unit,
    onInspect: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F1115)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.displayName,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE4E7EB),
                    fontSize = 15.sp
                )
                Text(
                    text = distro.version,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E)
                )
                Text(
                    text = "${distro.packageManager} · ~${distro.approxSizeBytes.displayByteSize()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(top = 4.dp)
                )
                installed?.let {
                    Text(
                        text = "on disk ${it.sizeOnDiskBytes.displayByteSize()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (it.isHealthy) Color(0xFF98C379) else Color(0xFFFF6E6E),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    isInstalling -> CircularProgressIndicator(
                        color = Color(0xFF61AFEF),
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    installed == null -> Button(onClick = onInstall) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("Install", fontFamily = FontFamily.Monospace)
                    }
                    installed.isHealthy -> {
                        Button(onClick = onOpenTerminal) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Open", fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = "inspect",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF61AFEF),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickableNoRipple(onInspect)
                        )
                        Text(
                            text = "tap to remove",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF8B949E),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickableNoRipple(onRemove)
                        )
                    }
                    else -> FailedInstallActions(
                        reason = installed.failureReason,
                        onRetry = onInstall,
                        onRemove = onRemove
                    )
                }
            }
        }
    }
}

@Composable
private fun BannerError(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF4C1F22))
        ) {
            Text(
                text = message,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFD0D0),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Tiny compat helper for clickable text. Compose 1.6 splits API; we
 * keep our own implementation so the runtime screen does not grow with
 * every Compose update.
 */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/**
 * PHASE 9.6.3.2 — Header tile that opens the custom rootfs URL
 * installer. Placed above the catalog so users see "I can also paste a
 * URL" without scrolling.
 */
@Composable
private fun CustomRootfsTile(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickableCustom(onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2A1F))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Add custom rootfs",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE4E7EB),
                fontSize = 13.sp
            )
            Text(
                text = "paste a tar.gz / tar.xz / tar URL",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Color(0xFF8B949E),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Local clickable Modifier wrapper. Use [clickableCustom] everywhere we
 * want a flat click; card-elevation-style ripple is omitted for the
 * consistent dark-mode look.
 */
@Composable
private fun Modifier.clickableCustom(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable { onClick() })

/**
 * PHASE 9.6.3.3 — Section headers ("Catalog" / "Custom") inside the
 * runtime grid. Plain monospace text, no card background.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        color = Color(0xFF61AFEF),
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * PHASE 9.6.3.3 — Row renderer that takes an [EffectiveCatalogRow]
 * instead of a raw [Distro]. Same shape as the original
 * [DistroCatalogRow]; just keeps the catalog state and rendering
 * cleanly separated.
 */
@Composable
private fun EffectiveCatalogRowView(
    row: EffectiveCatalogRow,
    isInstalling: Boolean,
    progress: DistroInstallProgress?,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onOpenTerminal: () -> Unit,
    onInspect: () -> Unit,
    onOpenDesktop: () -> Unit = {}
) {
    val distro = row.distro
    val installed = row.installation
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F1115)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (distro.displayName + if (row.isCustom) "  ·custom" else ""),
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE4E7EB),
                    fontSize = 15.sp
                )
                Text(
                    text = distro.version,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E)
                )
                Text(
                    text = "${distro.packageManager} · ~${distro.approxSizeBytes.displayByteSize()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF8B949E),
                    modifier = Modifier.padding(top = 4.dp)
                )
                installed?.let {
                    Text(
                        text = "on disk ${it.sizeOnDiskBytes.displayByteSize()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (it.isHealthy) Color(0xFF98C379) else Color(0xFFFF6E6E),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    isInstalling -> Column(horizontalAlignment = Alignment.End) {
                        CircularProgressIndicator(
                            color = Color(0xFF61AFEF),
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = progress?.displayLabel ?: "preparing",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = Color(0xFF61AFEF),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    !row.isInstalled -> Button(onClick = onInstall) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("Install", fontFamily = FontFamily.Monospace)
                    }
                    row.isHealthy -> {
                        Button(onClick = onOpenTerminal) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text("Open", fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            text = "desktop",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF61AFEF),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickableNoRipple(onOpenDesktop)
                        )
                        Text(
                            text = "inspect",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF61AFEF),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickableNoRipple(onInspect)
                        )
                        Text(
                            text = "tap to remove",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF8B949E),
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickableNoRipple(onRemove)
                        )
                    }
                    else -> FailedInstallActions(
                        reason = installed?.failureReason,
                        onRetry = onInstall,
                        onRemove = onRemove
                    )
                }
            }
        }
    }
}

@Composable
private fun FailedInstallActions(
    reason: String?,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = "install failed",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color(0xFFFF6E6E)
        )
        reason?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message.take(160),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = Color(0xFFFFA3A3),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Retry", fontFamily = FontFamily.Monospace)
        }
        Text(
            text = "remove broken data",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = Color(0xFF8B949E),
            modifier = Modifier
                .padding(top = 6.dp)
                .clickableNoRipple(onRemove)
        )
    }
}
