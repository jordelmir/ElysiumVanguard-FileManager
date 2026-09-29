package com.elysium.vanguard.core.tasks

import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CRUD + scheduling side effects for user-defined tasks. Every mutation is
 * mirrored to WorkManager through [ScheduledTaskScheduler], so the enabled
 * toggle, edit, and delete flows all "just work".
 */
@Singleton
class ScheduledTaskRepository @Inject constructor(
    private val dao: ScheduledTaskDao,
    private val scheduler: ScheduledTaskScheduler,
) {

    fun observeAll(): Flow<List<ScheduledTaskEntity>> = dao.observeAll()

    suspend fun byId(id: String): ScheduledTaskEntity? = dao.byId(id)

    /**
     * Create (or replace) a task. Returns an error message on invalid
     * input, `null` on success.
     */
    suspend fun create(
        name: String,
        type: TaskType,
        sourcePath: String = "",
        destPath: String = "",
        intervalHours: Int = 0,
        dailyHour: Int = -1,
        enabled: Boolean = true,
    ): String? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Name is required"
        if (!TaskScheduling.isValidTrigger(intervalHours, dailyHour)) {
            return "Pick either an interval (≥ ${TaskScheduling.MIN_INTERVAL_HOURS} h) or a daily hour"
        }
        if (type == TaskType.COPY_DIRECTORY && (sourcePath.isBlank() || destPath.isBlank())) {
            return "Copy tasks need a source and a destination"
        }
        val task = ScheduledTaskEntity(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            type = type.name,
            sourcePath = sourcePath.trim(),
            destPath = destPath.trim(),
            intervalHours = intervalHours,
            dailyHour = dailyHour,
            enabled = enabled,
            createdAt = System.currentTimeMillis(),
        )
        dao.upsert(task)
        scheduler.sync(task)
        return null
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val task = dao.byId(id) ?: return
        val updated = task.copy(enabled = enabled)
        dao.upsert(updated)
        scheduler.sync(updated)
    }

    suspend fun delete(id: String) {
        dao.delete(id)
        scheduler.cancel(id)
    }
}
