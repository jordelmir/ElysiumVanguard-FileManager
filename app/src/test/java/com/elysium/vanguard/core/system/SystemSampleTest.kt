package com.elysium.vanguard.core.system

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 114 — the test suite for the
 * [SystemSample] data class. The math
 * lives in the data class (the
 * `memoryPercent` property); the tests
 * cover the edge cases.
 */
class SystemSampleTest {

    @Test
    fun `memoryPercent returns used over total rounded down`() {
        val sample = SystemSample(
            atMs = 0L,
            cpuPercent = 50,
            memoryUsedMb = 512,
            memoryTotalMb = 1024,
            temperatureCelsius = 45.0,
            uptimeSeconds = 60L,
        )
        assertEquals(50, sample.memoryPercent)
    }

    @Test
    fun `memoryPercent returns 0 when memoryTotalMb is 0`() {
        // Avoids divide-by-zero.
        val sample = SystemSample(
            atMs = 0L,
            cpuPercent = 50,
            memoryUsedMb = 0,
            memoryTotalMb = 0,
            temperatureCelsius = null,
            uptimeSeconds = 0L,
        )
        assertEquals(0, sample.memoryPercent)
    }

    @Test
    fun `memoryPercent rounds down for fractional values`() {
        // 333 / 1000 = 33.3% → 33% (truncated)
        val sample = SystemSample(
            atMs = 0L,
            cpuPercent = 0,
            memoryUsedMb = 333,
            memoryTotalMb = 1000,
            temperatureCelsius = null,
            uptimeSeconds = 0L,
        )
        assertEquals(33, sample.memoryPercent)
    }

    @Test
    fun `memoryPercent at 100 percent is preserved`() {
        val sample = SystemSample(
            atMs = 0L,
            cpuPercent = 0,
            memoryUsedMb = 1024,
            memoryTotalMb = 1024,
            temperatureCelsius = null,
            uptimeSeconds = 0L,
        )
        assertEquals(100, sample.memoryPercent)
    }

    @Test
    fun `memoryUsedMb can exceed memoryTotalMb without crashing`() {
        // Pathological: used > total
        // (the platform can report this
        // briefly during allocation).
        // The percent is clamped by the
        // (used * 100) / total formula
        // (returns > 100).
        val sample = SystemSample(
            atMs = 0L,
            cpuPercent = 0,
            memoryUsedMb = 2000,
            memoryTotalMb = 1000,
            temperatureCelsius = null,
            uptimeSeconds = 0L,
        )
        assertEquals(200, sample.memoryPercent)
    }
}
