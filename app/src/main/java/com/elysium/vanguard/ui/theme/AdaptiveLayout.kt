package com.elysium.vanguard.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AdaptiveWidthClass {
    Compact,
    Medium,
    Expanded
}

enum class AdaptiveHeightClass {
    Compact,
    Medium,
    Expanded
}

/**
 * App-wide layout metrics derived from the available Compose bounds.
 *
 * Compact: narrow phones.
 * Medium: large phones / foldables such as Honor Magic V2 in practical app widths.
 * Expanded: tablets, desktop-like windows, and unfolded wide canvases.
 */
@Stable
data class AdaptiveMetrics(
    val widthClass: AdaptiveWidthClass,
    val heightClass: AdaptiveHeightClass,
    val screenPadding: Dp,
    val sectionSpacing: Dp,
    val gridSpacing: Dp,
    val dashboardCardMinWidth: Dp,
    val fileCardMinWidth: Dp,
    val mediaThumbMinWidth: Dp,
    val albumCardMinWidth: Dp,
    val listContentPadding: Dp,
    val metricGaugeSize: Dp,
    val quickTileHeight: Dp,
    val portalAspectRatio: Float,
    val maxReadableWidth: Dp
) {
    val isCompact: Boolean get() = widthClass == AdaptiveWidthClass.Compact
    val isMedium: Boolean get() = widthClass == AdaptiveWidthClass.Medium
    val isExpanded: Boolean get() = widthClass == AdaptiveWidthClass.Expanded
    val shouldStackPrimaryPanes: Boolean get() = isCompact
}

fun adaptiveMetricsFor(maxWidth: Dp, maxHeight: Dp): AdaptiveMetrics {
    val widthClass = when {
        maxWidth < 600.dp -> AdaptiveWidthClass.Compact
        maxWidth < 840.dp -> AdaptiveWidthClass.Medium
        else -> AdaptiveWidthClass.Expanded
    }
    val heightClass = when {
        maxHeight < 480.dp -> AdaptiveHeightClass.Compact
        maxHeight < 900.dp -> AdaptiveHeightClass.Medium
        else -> AdaptiveHeightClass.Expanded
    }

    return when (widthClass) {
        AdaptiveWidthClass.Compact -> AdaptiveMetrics(
            widthClass = widthClass,
            heightClass = heightClass,
            screenPadding = if (maxWidth < 380.dp) 10.dp else 12.dp,
            sectionSpacing = 12.dp,
            gridSpacing = 12.dp,
            dashboardCardMinWidth = 136.dp,
            fileCardMinWidth = 104.dp,
            mediaThumbMinWidth = 86.dp,
            albumCardMinWidth = 140.dp,
            listContentPadding = 12.dp,
            metricGaugeSize = 64.dp,
            quickTileHeight = 64.dp,
            portalAspectRatio = 0.78f,
            maxReadableWidth = 560.dp
        )
        AdaptiveWidthClass.Medium -> AdaptiveMetrics(
            widthClass = widthClass,
            heightClass = heightClass,
            screenPadding = 20.dp,
            sectionSpacing = 16.dp,
            gridSpacing = 16.dp,
            dashboardCardMinWidth = 180.dp,
            fileCardMinWidth = 126.dp,
            mediaThumbMinWidth = 110.dp,
            albumCardMinWidth = 180.dp,
            listContentPadding = 16.dp,
            metricGaugeSize = 72.dp,
            quickTileHeight = 72.dp,
            portalAspectRatio = 0.90f,
            maxReadableWidth = 760.dp
        )
        AdaptiveWidthClass.Expanded -> AdaptiveMetrics(
            widthClass = widthClass,
            heightClass = heightClass,
            screenPadding = 28.dp,
            sectionSpacing = 20.dp,
            gridSpacing = 20.dp,
            dashboardCardMinWidth = 220.dp,
            fileCardMinWidth = 148.dp,
            mediaThumbMinWidth = 138.dp,
            albumCardMinWidth = 230.dp,
            listContentPadding = 20.dp,
            metricGaugeSize = 82.dp,
            quickTileHeight = 80.dp,
            portalAspectRatio = 1.02f,
            maxReadableWidth = 1120.dp
        )
    }
}

val LocalAdaptiveMetrics = staticCompositionLocalOf {
    adaptiveMetricsFor(360.dp, 800.dp)
}
