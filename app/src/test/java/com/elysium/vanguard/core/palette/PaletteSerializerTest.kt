package com.elysium.vanguard.core.palette

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10.8 — Unit tests for [PaletteSerializer].
 *
 * Round-trip is the contract: a palette serialized to JSON and
 * parsed back must be equal to the original (data class equals).
 * We also test that malformed input returns null, not a crash.
 */
class PaletteSerializerTest {

    @Test
    fun `round-trip preserves all fields of a built-in preset`() {
        PalettePresets.ALL.forEach { original ->
            val json = PaletteSerializer.toJson(original)
            val parsed = PaletteSerializer.fromJson(json)
            assertNotNull("failed to parse ${original.id}", parsed)
            assertEquals(original, parsed)
        }
    }

    @Test
    fun `round-trip preserves a custom palette with extreme values`() {
        val original = ColorPalette(
            id = "extreme",
            name = "Extreme Test",
            primary = ColorSlot(
                base = Color(0xFF112233),
                glow = Color(0xFFAABBCC),
                metallicStart = Color(0xFF000000),
                metallicEnd = Color(0xFFFFFFFF),
                diffused = Color(0xFF445566),
                style = SlotStyle.COMBINED,
                intensity = 1.7f
            ),
            secondary = ColorSlot(base = Color(0x8000FF00), style = SlotStyle.METALLIC, intensity = 0f),
            tertiary = ColorSlot(base = Color(0x40FF0000), style = SlotStyle.DIFFUSED, intensity = 0.1f),
            quaternary = ColorSlot(base = Color(0xC0FF00FF), style = SlotStyle.PHOSPHORESCENT, intensity = 1.2f),
            accent = ColorSlot(base = Color(0xFF123456), style = SlotStyle.NEON, intensity = 0.5f),
            background = Color(0xFF010203),
            surface = Color(0xFF040506),
            onBackground = Color(0xFFAABBCC),
            onSurface = Color(0xFFDDEEFF),
            isDark = true,
            isBuiltIn = false
        )
        val json = PaletteSerializer.toJson(original)
        val parsed = PaletteSerializer.fromJson(json)
        assertEquals(original, parsed)
    }

    @Test
    fun `fromJson returns null on null input`() {
        assertNull(PaletteSerializer.fromJson(null))
    }

    @Test
    fun `fromJson returns null on empty input`() {
        assertNull(PaletteSerializer.fromJson(""))
        assertNull(PaletteSerializer.fromJson("   "))
    }

    @Test
    fun `fromJson returns null on garbage input`() {
        assertNull(PaletteSerializer.fromJson("{not valid json"))
        assertNull(PaletteSerializer.fromJson("this is not json at all"))
    }

    @Test
    fun `fromJsonOrDefault falls back to TITAN on garbage`() {
        val fallback = PaletteSerializer.fromJsonOrDefault("garbage")
        assertEquals(PalettePresets.TITAN_DEFAULT, fallback)
    }

    @Test
    fun `serialized JSON contains the palette id and name`() {
        val json = PaletteSerializer.toJson(PalettePresets.OLED_BLACK)
        assertTrue("id missing from JSON", json.contains("\"id\":\"oled_black\""))
        assertTrue("name missing from JSON", json.contains("\"name\":\"OLED BLACK\""))
    }

    @Test
    fun `unknown style in JSON falls back to NEON`() {
        // We craft a JSON that has a valid palette structure but
        // an unknown style value. The serializer's `SlotStyle.fromKey`
        // returns NEON, so the parsed slot should be NEON.
        val craftedJson = """
            {
              "id": "x",
              "name": "x",
              "isDark": true,
              "isBuiltIn": false,
              "background": 255,
              "surface": 255,
              "onBackground": -1,
              "onSurface": -1,
              "primary": {
                "base": -65536,
                "glow": -65536,
                "metallicStart": 0,
                "metallicEnd": -65536,
                "diffused": 0,
                "style": "BOGUS_STYLE",
                "intensity": 1.0
              },
              "secondary": {
                "base": -16711936,
                "glow": -16711936,
                "metallicStart": 0,
                "metallicEnd": -16711936,
                "diffused": 0,
                "style": "NEON",
                "intensity": 1.0
              },
              "tertiary": {
                "base": -16776961,
                "glow": -16776961,
                "metallicStart": 0,
                "metallicEnd": -16776961,
                "diffused": 0,
                "style": "NEON",
                "intensity": 1.0
              },
              "quaternary": {
                "base": -65281,
                "glow": -65281,
                "metallicStart": 0,
                "metallicEnd": -65281,
                "diffused": 0,
                "style": "NEON",
                "intensity": 1.0
              },
              "accent": {
                "base": -65536,
                "glow": -65536,
                "metallicStart": 0,
                "metallicEnd": -65536,
                "diffused": 0,
                "style": "NEON",
                "intensity": 1.0
              }
            }
        """.trimIndent()
        val parsed = PaletteSerializer.fromJson(craftedJson)
        assertNotNull(parsed)
        assertEquals(SlotStyle.NEON, parsed!!.primary.style)
    }
}
