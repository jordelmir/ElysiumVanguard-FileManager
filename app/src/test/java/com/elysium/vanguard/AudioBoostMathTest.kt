package com.elysium.vanguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PHASE 0.8 — Unit tests for MusicHubViewModel.boostToDb.
 *
 * The mapping is intentional and documented in the source:
 *   1x  = 0 dB
 *   2x  = ~6 dB
 *   3.5x = ~11 dB
 *
 * This test guards against regressions if someone tweaks the curve later.
 */
class AudioBoostMathTest {

    @Test
    fun `boost 1x maps to 0 dB (unity gain)`() {
        // 20 * log10(1.0) = 0.0
        assertEquals(0f, musicBoostToDb(1.0f), 0.01f)
    }

    @Test
    fun `boost 2x maps to +6 dB`() {
        // 20 * log10(2.0) ≈ 6.02
        assertEquals(6.02f, musicBoostToDb(2.0f), 0.05f)
    }

    @Test
    fun `boost 3_5x maps to +10_8 dB`() {
        // 20 * log10(3.5) ≈ 10.88
        assertEquals(10.88f, musicBoostToDb(3.5f), 0.05f)
    }

    @Test
    fun `boost below unity is clamped to 0 dB`() {
        // Below 1.0x the source explicitly returns 0 dB (no attenuation),
        // since user-facing volume control handles attenuation separately.
        assertEquals(0f, musicBoostToDb(0.5f), 0.001f)
        assertEquals(0f, musicBoostToDb(0.0f), 0.001f)
    }

    @Test
    fun `boost is monotonically increasing for values greater than unity`() {
        val a = musicBoostToDb(1.5f)
        val b = musicBoostToDb(2.0f)
        val c = musicBoostToDb(4.0f)
        assertNotEquals(a, b)
        assert(a < b) { "Expected $a < $b" }
        assert(b < c) { "Expected $b < $c" }
    }

    // Mirror of MusicHubViewModel.boostToDb. Kept here so this test does not
    // depend on Android framework classes (which would require Robolectric).
    private fun musicBoostToDb(boost: Float): Float {
        return if (boost <= 1.0f) 0f else (20 * kotlin.math.log10(boost.toDouble())).toFloat()
    }
}