package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * PHASE 10.8 — JSON serializer for [ColorPalette].
 *
 * We use a small DTO layer (rather than Gson type adapters) for three
 * reasons:
 *
 *  1. **Schema stability.** The DTOs are the on-disk format. If we
 *     ever change [ColorSlot] internally (e.g. add a `pattern`
 *     field), the DTO can stay backward-compatible — old JSON
 *     without the field parses with a default, new JSON with the
 *     field round-trips fully.
 *  2. **Human-readable JSON.** ARGB longs read fine in a debugger
 *     and in `adb shell` dumps; a custom adapter that emits
 *     `{"value":-16711681}` would be opaque.
 *  3. **No annotation noise.** We don't need to sprinkle `@SerializedName`
 *     or `@Keep` over the production model.
 *
 * Colors are stored as signed 32-bit ARGB longs (Color.toArgb()).
 * [Color] in Compose uses [Int] ARGB, but Gson parses long literals
 * losslessly so we widen to [Long] for stability across JVMs.
 */
object PaletteSerializer {

    private val gson: Gson = Gson()

    // ── DTOs ────────────────────────────────────────────────────────

    private data class PaletteDto(
        val id: String,
        val name: String,
        val isDark: Boolean,
        val isBuiltIn: Boolean,
        val background: Long,
        val surface: Long,
        val onBackground: Long,
        val onSurface: Long,
        val primary: SlotDto,
        val secondary: SlotDto,
        val tertiary: SlotDto,
        val quaternary: SlotDto,
        val accent: SlotDto
    )

    private data class SlotDto(
        val base: Long,
        val glow: Long,
        val metallicStart: Long,
        val metallicEnd: Long,
        val diffused: Long,
        val style: String,
        val intensity: Float
    )

    // ── API ─────────────────────────────────────────────────────────

    /** Serialize a [ColorPalette] to a JSON string. */
    fun toJson(palette: ColorPalette): String {
        return gson.toJson(palette.toDto())
    }

    /**
     * Parse a JSON string into a [ColorPalette]. Returns `null` on
     * parse failure — the caller is expected to fall back to
     * [PalettePresets.Default] in that case.
     */
    fun fromJson(json: String?): ColorPalette? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, PaletteDto::class.java).toDomain()
        } catch (e: JsonSyntaxException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
    }

    /**
     * Convenience: parse a JSON string or return the default palette
     * if parsing fails. Used by [PaletteStore.loadCurrent] on the
     * cold-start path so a corrupt preferences blob never crashes
     * the app.
     */
    fun fromJsonOrDefault(json: String?): ColorPalette {
        return fromJson(json) ?: PalettePresets.Default
    }

    // ── Mappers ────────────────────────────────────────────────────

    private fun ColorPalette.toDto(): PaletteDto = PaletteDto(
        id = id,
        name = name,
        isDark = isDark,
        isBuiltIn = isBuiltIn,
        background = background.toArgbLong(),
        surface = surface.toArgbLong(),
        onBackground = onBackground.toArgbLong(),
        onSurface = onSurface.toArgbLong(),
        primary = primary.toDto(),
        secondary = secondary.toDto(),
        tertiary = tertiary.toDto(),
        quaternary = quaternary.toDto(),
        accent = accent.toDto()
    )

    private fun PaletteDto.toDomain(): ColorPalette = ColorPalette(
        id = id,
        name = name,
        isDark = isDark,
        isBuiltIn = isBuiltIn,
        background = background.toComposeColor(),
        surface = surface.toComposeColor(),
        onBackground = onBackground.toComposeColor(),
        onSurface = onSurface.toComposeColor(),
        primary = primary.toDomain(),
        secondary = secondary.toDomain(),
        tertiary = tertiary.toDomain(),
        quaternary = quaternary.toDomain(),
        accent = accent.toDomain()
    )

    private fun ColorSlot.toDto(): SlotDto = SlotDto(
        base = base.toArgbLong(),
        glow = glow.toArgbLong(),
        metallicStart = metallicStart.toArgbLong(),
        metallicEnd = metallicEnd.toArgbLong(),
        diffused = diffused.toArgbLong(),
        style = style.name,
        intensity = intensity
    )

    private fun SlotDto.toDomain(): ColorSlot = ColorSlot(
        base = base.toComposeColor(),
        glow = glow.toComposeColor(),
        metallicStart = metallicStart.toComposeColor(),
        metallicEnd = metallicEnd.toComposeColor(),
        diffused = diffused.toComposeColor(),
        style = SlotStyle.fromKey(style),
        intensity = intensity
    )

    // ── Color ↔ long conversion ─────────────────────────────────────

    /**
     * Convert a Compose [Color] to its 32-bit ARGB int, widened to
     * [Long] for stable Gson serialization. Uses [Color.toArgb]
     * because [Color.value] is a ULong with a color-space marker in
     * the high bits; reading `value` directly gives a different
     * number for the same perceived color depending on the color
     * space.
     */
    private fun Color.toArgbLong(): Long = this.toArgb().toLong() and 0xFFFFFFFFL

    /**
     * Reconstruct a Compose [Color] from a 32-bit ARGB [Long].
     * `Color(Int)` is the documented way to build a color from
     * ARGB; it embeds the sRGB color space marker for us.
     */
    private fun Long.toComposeColor(): Color = Color(this.toInt())
}
