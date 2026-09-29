package com.elysium.vanguard.core.tasks

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure trigger math + naming for scheduled tasks — no Android types, so
 * every rule is unit-testable on the JVM.
 */
object TaskScheduling {

    /** WorkManager enforces a 15-minute floor; we only expose whole hours. */
    const val MIN_INTERVAL_HOURS = 1

    /**
     * A valid trigger has exactly one active mode: interval hours > 0 with
     * dailyHour == -1, or a daily hour in 0..23 with intervalHours == 0.
     */
    fun isValidTrigger(intervalHours: Int, dailyHour: Int): Boolean =
        (intervalHours >= MIN_INTERVAL_HOURS && dailyHour == -1) ||
            (intervalHours == 0 && dailyHour in 0..23)

    /** Unique WorkManager work name for one task. */
    fun uniqueWorkName(taskId: String): String = "scheduled_task_$taskId"

    /**
     * Millis until the next occurrence of [hour]:00:00 local time after
     * [nowMillis]. When [nowMillis] is exactly on the occurrence the next
     * day's occurrence is returned (a task never fires "now" via initial
     * delay — the periodic schedule owns that).
     */
    fun nextDailyDelayMillis(nowMillis: Long, hour: Int, timeZone: TimeZone = TimeZone.getDefault()): Long {
        require(hour in 0..23) { "hour must be 0..23, was $hour" }
        val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        val target = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        return target.timeInMillis - nowMillis
    }
}
