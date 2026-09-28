package com.elysium.vanguard.core.runtime.wine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * PHASE 144 — the visual Wine + Box64 status surface.
 *
 * The body is a self-contained Composable that shows the
 * state of the [ElysiumWineStackDetector] + the [WineStack]:
 *   - Whether `wine` is installed (and where)
 *   - Whether `box64` is installed (and where)
 *   - Whether x86-64 Windows apps can run (requires box64)
 *   - Whether x86 (32-bit) Windows apps can run (requires box86 — Phase 144 ships without; the field is reserved)
 *   - The current Elysium prefix root (the per-app directory under `<filesDir>/wine-prefixes/`)
 *
 * The body is read-only. A future increment adds a
 * "Re-detect" action that forces a fresh probe; Phase 144
 * reads the Hilt-provided detector once on first composition
 * via [rememberElysiumWineStackDetector].
 *
 * The body is reachable today by:
 *   - Dropping it into any Composable that takes a body
 *     lambda (e.g. a debug screen, the Help body, the
 *     Programs catalog).
 *   - Future: wiring it into the desktop's Programs catalog
 *     under a new icon key (e.g. `wine_status`).
 *
 * Until `libwine.so` + `libbox64.so` are cross-compiled for
 * ARM64 (Phase 145+), the body shows "Wine not installed" +
 * a one-line install hint. The body is the visible tip of
 * the foundation: the typed pieces are wired through Hilt
 * and tested; the binaries are the last mile.
 */
@Composable
fun WineStatusBody(
    modifier: Modifier = Modifier,
) {
    val detector = rememberElysiumWineStackDetector()
    val stack = detector.stack
    val wine = detector.wineLocation
    val box64 = detector.box64Location

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Wine + Box64",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Windows compatibility layer status",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // --- Wine row ---
        StatusRow(
            label = "Wine",
            detected = wine != null,
            detail = wine?.displayPath ?: "not installed",
        )
        Spacer(modifier = Modifier.height(8.dp))

        // --- Box64 row ---
        StatusRow(
            label = "Box64",
            detected = box64 != null,
            detail = box64?.displayPath ?: "not installed (x86-64 Windows apps will not run)",
        )
        Spacer(modifier = Modifier.height(8.dp))

        // --- Box86 row (reserved) ---
        StatusRow(
            label = "Box86",
            detected = false, // Phase 144 ships box64 only
            detail = "not installed (32-bit x86 Windows apps require a future increment)",
        )
        Spacer(modifier = Modifier.height(16.dp))

        // --- Capability summary ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Capabilities",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "• x86-64 Windows apps: ${if (stack?.supportsX86_64 == true) "✓ ready" else "✗ needs Box64"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• x86 (32-bit) Windows apps: ${if (stack?.supportsX86 == true) "✓ ready" else "✗ needs Box86 (future)"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "• ARM64EC Windows apps: ✗ not supported (no native ARM64EC runtime on Android)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Install hint when nothing is installed ---
        if (stack == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Install Wine + Box64",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The cleanest path on a Snapdragon device is via Termux:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "pkg i wine box64",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Alternatively, drop libwine.so and libbox64.so under <filesDir>/wine/. A future Elysium increment bundles both in the APK.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Summary at the bottom ---
        Text(
            text = "Detector: " + detector.describeForUi(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A single status row. The row shows a colored dot + a
 * label + a detail string. The dot is green for detected,
 * red for not detected.
 */
@Composable
private fun StatusRow(
    label: String,
    detected: Boolean,
    detail: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (detected) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (detected) Color(0xFF50FA7B) else Color(0xFFFF5555),
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
