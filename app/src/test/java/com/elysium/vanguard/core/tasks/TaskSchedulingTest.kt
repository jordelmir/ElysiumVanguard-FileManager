package com.elysium.vanguard.core.tasks

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSchedulingTest {

    @Test
    fun `interval trigger with no daily hour is valid`() {
        assertTrue(TaskScheduling.isValidTrigger(intervalHours = 6, dailyHour = -1))
        assertTrue(TaskScheduling.isValidTrigger(intervalHours = 1, dailyHour = -1))
        assertTrue(TaskScheduling.isValidTrigger(intervalHours = 24, dailyHour = -1))
    }

    @Test
    fun `daily trigger with zero interval is valid`() {
        assertTrue(TaskScheduling.isValidTrigger(intervalHours = 0, dailyHour = 0))
        assertTrue(TaskScheduling.isValidTrigger(intervalHours = 0, dailyHour = 23))
    }

    @Test
    fun `both modes or no mode is invalid`() {
        assertFalse(TaskScheduling.isValidTrigger(intervalHours = 6, dailyHour = 3))   // both
        assertFalse(TaskScheduling.isValidTrigger(intervalHours = 0, dailyHour = -1))  // neither
        assertFalse(TaskScheduling.isValidTrigger(intervalHours = 0, dailyHour = 24))  // hour OOB
        assertFalse(TaskScheduling.isValidTrigger(intervalHours = -1, dailyHour = -1)) // negative
    }

    @Test
    fun `unique work name is stable and prefixed`() {
        assertEquals("scheduled_task_abc-123", TaskScheduling.uniqueWorkName("abc-123"))
    }

    @Test
    fun `daily delay later same day is the gap to that hour`() {
        val utc = TimeZone.getTimeZone("UTC")
        // 2026-01-10 10:00:00 UTC
        val now = utc.run {
            java.util.Calendar.getInstance(this).apply {
                set(2026, 0, 10, 10, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val delay = TaskScheduling.nextDailyDelayMillis(now, hour = 15, timeZone = utc)
        assertEquals(5 * 3_600_000L, delay) // 10:00 → 15:00
    }

    @Test
    fun `daily delay earlier same day rolls to next day`() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = utc.run {
            java.util.Calendar.getInstance(this).apply {
                set(2026, 0, 10, 20, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val delay = TaskScheduling.nextDailyDelayMillis(now, hour = 3, timeZone = utc)
        assertEquals(7 * 3_600_000L, delay) // 20:00 → 03:00 next day
    }

    @Test
    fun `exactly on the hour schedules next day not now`() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = utc.run {
            java.util.Calendar.getInstance(this).apply {
                set(2026, 0, 10, 3, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val delay = TaskScheduling.nextDailyDelayMillis(now, hour = 3, timeZone = utc)
        assertEquals(24 * 3_600_000L, delay)
    }
}
