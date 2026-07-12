package com.elysium.vanguard.features.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elysium.vanguard.ui.theme.GlobalColors
import com.elysium.vanguard.ui.theme.TitanColors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PHASE 10.8 — HSV-based color picker shown in a dialog.
 *
 * Three channels, two inputs:
 *
 *  - A 2D saturation/value pad (S on x, V on y, hue fixed).
 *  - A hue slider below it (0..360).
 *
 * Tapping or dragging the 2D pad updates S+V at once. The hue
 * slider changes the hue. A live swatch on the right previews the
 * current color. The dialog reports the picked color via
 * [onColorSelected] when the user taps APPLY.
 *
 * HSV math is in [hsvFromColor] and [colorFromHsv] — pure JVM
 * functions, easy to unit-test independently of Compose.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit,
    title: String = "PICK COLOR"
) {
    val hsv = remember(initialColor) {
        val out = FloatArray(3)
        hsvFromColor(initialColor, out)
        out
    }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }

    val currentColor = remember(h, s, v) { colorFromHsv(h, s, v) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlobalColors.primary.copy(alpha = 0.12f),
        title = {
            Text(
                title,
                color = TitanColors.AbsoluteWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SatValuePad(
                        hue = h,
                        saturation = s,
                        value = v,
                        onChange = { newS, newV ->
                            s = newS
                            v = newV
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(180.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(currentColor)
                            .border(1.dp, TitanColors.AbsoluteWhite.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "#%08X".format(currentColor.toArgb()),
                    color = TitanColors.AbsoluteWhite.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(8.dp))
                HueSlider(
                    value = h,
                    onChange = { h = it }
                )
                Text(
                    "HUE  ${h.toInt()}°",
                    color = TitanColors.AbsoluteWhite.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onColorSelected(currentColor)
                onDismiss()
            }) {
                Text("APPLY", color = TitanColors.NeonCyan, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = TitanColors.AbsoluteWhite.copy(alpha = 0.7f))
            }
        }
    )
}

/**
 * The 2D saturation/value pad. Renders the hue color underneath,
 * a white-to-transparent horizontal gradient on top (saturation),
 * and a transparent-to-black vertical gradient over that (value).
 * Tap or drag inside the box to set S+V.
 */
@Composable
private fun SatValuePad(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.hsv(hue, 1f, 1f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos -> setSatValueAt(pos, size.width.toFloat(), size.height.toFloat(), onChange) },
                    onDrag = { change, _ ->
                        setSatValueAt(change.position, size.width.toFloat(), size.height.toFloat(), onChange)
                        change.consume()
                    }
                )
            }
    ) {
        // Saturation ramp (white → transparent) on top of the hue
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.White, Color.White.copy(alpha = 0f))
                    )
                )
        )
        // Value ramp (transparent → black) on top of that
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0f), Color.Black)
                    )
                )
        )
        // Selection marker
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            val cx = (size.width * saturation).coerceIn(0f, size.width)
            val cy = size.height - (size.height * value).coerceIn(0f, size.height)
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/** Helper: convert a tap position to (S, V) and notify the caller. */
private fun setSatValueAt(
    pos: Offset,
    width: Float,
    height: Float,
    onChange: (Float, Float) -> Unit
) {
    if (width <= 0f || height <= 0f) return
    val sNew = (pos.x / width).coerceIn(0f, 1f)
    val vNew = (1f - pos.y / height).coerceIn(0f, 1f)
    onChange(sNew, vNew)
}

/** Hue slider — horizontal rainbow gradient with a white thumb. */
@Composable
private fun HueSlider(
    value: Float,
    onChange: (Float) -> Unit
) {
    val sweep = remember {
        Brush.horizontalGradient(
            listOf(
                Color.hsv(0f, 1f, 1f),
                Color.hsv(60f, 1f, 1f),
                Color.hsv(120f, 1f, 1f),
                Color.hsv(180f, 1f, 1f),
                Color.hsv(240f, 1f, 1f),
                Color.hsv(300f, 1f, 1f),
                Color.hsv(360f, 1f, 1f)
            )
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(sweep)
    ) {
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Color math (pure JVM, easy to unit test) ──────────────────

/**
 * Convert a Compose [Color] to HSV. Output goes into [out] as
 * three floats: h (0..360), s (0..1), v (0..1). The output array
 * is reused across calls to avoid allocations in slider drag.
 */
fun hsvFromColor(color: Color, out: FloatArray) {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val v = maxC
    val s = if (maxC == 0f) 0f else delta / maxC
    val h = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    out[0] = if (h < 0f) h + 360f else h
    out[1] = s
    out[2] = v
}

/** Convert HSV floats back to a Compose [Color]. */
fun colorFromHsv(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val hh = (h % 360f) / 60f
    val x = c * (1f - abs(hh % 2f - 1f))
    val (r1, g1, b1) = when (hh.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        5 -> Triple(c, 0f, x)
        else -> Triple(0f, 0f, 0f)
    }
    val m = v - c
    return Color(red = r1 + m, green = g1 + m, blue = b1 + m, alpha = 1f)
}
