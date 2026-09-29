package com.elysium.vanguard.core.tasks

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager glue: turns a [ScheduledTaskEntity] into a unique periodic
 * work request (or cancels it). Interval tasks run every N hours; daily
 * tasks run every day with an initial delay to the next HH:00.
 */
@Singleton
class ScheduledTaskScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Create or update the schedule for [task]; cancels when disabled/invalid. */
    fun sync(task: ScheduledTaskEntity) {
        val workManager = WorkManager.getInstance(context)
        val uniqueName = TaskScheduling.uniqueWorkName(task.id)
        if (!task.enabled || !TaskScheduling.isValidTrigger(task.intervalHours, task.dailyHour)) {
            workManager.cancelUniqueWork(uniqueName)
            return
        }
        val inputData = workDataOf(ScheduledTaskWorker.KEY_TASK_ID to task.id)
        val request = if (task.intervalHours > 0) {
            PeriodicWorkRequestBuilder<ScheduledTaskWorker>(task.intervalHours.toLong(), TimeUnit.HOURS)
                .setInputData(inputData)
                .build()
        } else {
            PeriodicWorkRequestBuilder<ScheduledTaskWorker>(1, TimeUnit.DAYS)
                .setInputData(inputData)
                .setInitialDelay(
                    TaskScheduling.nextDailyDelayMillis(System.currentTimeMillis(), task.dailyHour),
                    TimeUnit.MILLISECONDS,
                )
                .build()
        }
        workManager.enqueueUniquePeriodicWork(uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(TaskScheduling.uniqueWorkName(taskId))
    }
}
