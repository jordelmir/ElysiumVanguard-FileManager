package com.elysium.vanguard.features.systemmonitor

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.core.system.SystemSample

/**
 * PHASE 114 — the **system monitor screen**.
 * The screen shows the current CPU,
 * memory, temperature, and uptime at a
 * glance, plus a small sparkline-style
 * chart of the last 60 samples.
 *
 * The screen is a pure Compose hierarchy:
 * the ViewModel polls the provider + the
 * screen observes the rolling history.
 * The chart is a hand-rolled bar chart
 * (no external chart library) — each
 * sample is a vertical bar; the height
 * is proportional to the CPU%.
 */
@Composable
fun SystemMonitorScreen(viewModel: SystemMonitorViewModel = hiltViewModel()) {
    val history by viewModel.history.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.startPolling()
    }
    val current = viewModel.currentSample
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0E1A),
                        Color(0xFF1A1240).copy(alpha = 0.7f),
                        Color(0xFF0A0E1A).copy(alpha = 0.95f),
                    ),
                ),
            )
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Title.
            Text(
                text = "System Monitor",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
            )
            // The current values card.
            current?.let { sample ->
                CurrentValuesCard(sample = sample)
            } ?: Text(
                text = "Collecting first sample...",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodyMedium,
            )
            // The rolling chart card.
            RollingChartCard(history = history)
        }
    }
}

@Composable
private fun CurrentValuesCard(sample: SystemSample) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ValueColumn(
            label = "CPU",
            value = "${sample.cpuPercent}%",
            tint = colorForPercent(sample.cpuPercent),
        )
        ValueColumn(
            label = "Memory",
            value = "${sample.memoryUsedMb} / ${sample.memoryTotalMb} MB",
            subValue = "${sample.memoryPercent}%",
            tint = colorForPercent(sample.memoryPercent),
        )
        ValueColumn(
            label = "Temp",
            value = sample.temperatureCelsius?.let { "%.1f°C".format(it) } ?: "—",
            tint = colorForTemperature(sample.temperatureCelsius),
        )
        ValueColumn(
            label = "Uptime",
            value = formatUptime(sample.uptimeSeconds),
        )
    }
}

@Composable
private fun ValueColumn(
    label: String,
    value: String,
    subValue: String? = null,
    tint: Color = Color.White,
) {
    Column {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = tint,
            style = MaterialTheme.typography.headlineSmall,
        )
        if (subValue != null) {
            Text(
                text = subValue,
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RollingChartCard(history: List<SystemSample>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(20.dp),
    ) {
        Text(
            text = "CPU % — last ${history.size} samples",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            history.forEach { sample ->
                val percent = sample.cpuPercent.coerceIn(0, 100)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((percent * 0.8).dp.coerceAtLeast(2.dp))
                        .background(colorForPercent(percent)),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Min ${history.minOfOrNull { it.cpuPercent } ?: 0}%  ·  " +
                "Max ${history.maxOfOrNull { it.cpuPercent } ?: 0}%  ·  " +
                "Avg ${if (history.isEmpty()) 0 else history.sumOf { it.cpuPercent } / history.size}%",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * The color ramp for a percentage
 * value. Low = green, medium = amber,
 * high = red. Used for the CPU + memory
 * display + the bar chart.
 */
private fun colorForPercent(percent: Int): Color = when {
    percent < 50 -> Color(0xFF81C784)
    percent < 80 -> Color(0xFFFFB74D)
    else -> Color(0xFFE57373)
}

/**
 * The color ramp for the temperature
 * in °C. Low = blue, medium = green,
 * high = amber, very high = red.
 */
private fun colorForTemperature(tempC: Double?): Color {
    if (tempC == null) return Color.White.copy(alpha = 0.6f)
    return when {
        tempC < 40 -> Color(0xFF64B5F6)
        tempC < 60 -> Color(0xFF81C784)
        tempC < 80 -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
}

/**
 * Format the uptime as "Xh Ym" or
 * "Xm Ys" or "Ys" depending on the
 * magnitude. Avoids the user seeing
 * "3675 seconds" — they see "1h 1m"
 * instead.
 */
private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}
