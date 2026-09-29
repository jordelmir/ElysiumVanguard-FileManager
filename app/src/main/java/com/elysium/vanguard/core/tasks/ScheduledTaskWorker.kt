package com.elysium.vanguard.core.tasks

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker that runs one scheduled task. The task id arrives in
 * the input data (set by [ScheduledTaskScheduler]). Deleted or disabled
 * tasks resolve quietly; failures retry up to [MAX_ATTEMPTS] times before
 * giving up (a persistently failing job must not retry forever).
 */
@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ScheduledTaskRepository,
    private val executor: ScheduledTaskExecutor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.success()
        val task = repository.byId(taskId) ?: return Result.success() // deleted
        if (!task.enabled) return Result.success()                    // disabled

        val result = executor.execute(task)
        return if (result.success) {
            Result.success()
        } else if (runAttemptCount < MAX_ATTEMPTS - 1) {
            Result.retry()
        } else {
            Result.success() // give up quietly after repeated failures
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val MAX_ATTEMPTS = 3
    }
}
