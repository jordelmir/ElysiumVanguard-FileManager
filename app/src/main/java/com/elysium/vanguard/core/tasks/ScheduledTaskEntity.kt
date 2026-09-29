package com.elysium.vanguard.core.tasks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What a scheduled task runs when it fires.
 */
enum class TaskType {
    /** Empty the trash (purge everything currently in it). */
    PURGE_TRASH,

    /** Recursively copy a directory tree to a destination (missing files only — idempotent sync). */
    COPY_DIRECTORY,
}

/**
 * A user-defined recurring job (Phase: scheduled tasks / auto-tasks).
 *
 * Trigger — exactly one of the two modes must be active (validated by
 * [TaskScheduling.isValidTrigger]):
 * - **Interval**: [intervalHours] > 0 and [dailyHour] == -1.
 * - **Daily**: [dailyHour] in 0..23 and [intervalHours] == 0.
 */
@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** [TaskType.name] — stored as text so unknown values fail soft. */
    val type: String,
    @ColumnInfo(name = "source_path") val sourcePath: String = "",
    @ColumnInfo(name = "dest_path") val destPath: String = "",
    @ColumnInfo(name = "interval_hours") val intervalHours: Int = 0,
    @ColumnInfo(name = "daily_hour") val dailyHour: Int = -1,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
) {
    val taskType: TaskType
        get() = runCatching { TaskType.valueOf(type) }.getOrDefault(TaskType.PURGE_TRASH)

    /** Human trigger summary for the UI list. */
    val triggerSummary: String
        get() = when {
            intervalHours > 0 -> "Every $intervalHours h"
            dailyHour in 0..23 -> String.format("Daily at %02d:00", dailyHour)
            else -> "Invalid trigger"
        }
}
