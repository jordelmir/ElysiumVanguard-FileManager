package com.elysium.vanguard.core.tasks

import java.io.File

/**
 * Executes a [ScheduledTaskEntity]'s work. The trash purge and directory
 * copy are injected as lambdas so the whole class is JVM-testable; the Hilt
 * graph supplies the real [com.elysium.vanguard.core.trash.TrashRepository]
 * (see [TasksModule]).
 */
class ScheduledTaskExecutor(
    private val purgeTrash: suspend () -> Int,
    private val copyDirectory: suspend (File, File) -> Int,
) {

    data class RunResult(val success: Boolean, val detail: String)

    suspend fun execute(task: ScheduledTaskEntity): RunResult = when (task.taskType) {
        TaskType.PURGE_TRASH -> {
            try {
                val purged = purgeTrash()
                RunResult(success = true, detail = "Purged $purged item(s)")
            } catch (e: Exception) {
                RunResult(success = false, detail = "Purge failed: ${e.message}")
            }
        }

        TaskType.COPY_DIRECTORY -> {
            val source = File(task.sourcePath)
            when {
                task.destPath.isBlank() ->
                    RunResult(success = false, detail = "Destination not set")
                !source.isDirectory ->
                    RunResult(success = false, detail = "Source missing: ${task.sourcePath}")
                else -> {
                    try {
                        val copied = copyDirectory(source, File(task.destPath))
                        RunResult(success = true, detail = "Copied $copied file(s)")
                    } catch (e: Exception) {
                        RunResult(success = false, detail = "Copy failed: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Recursive copy that is safe to run repeatedly: directories are
         * created as needed and files that already exist at the destination
         * are skipped (no overwrite). Returns the number of files copied.
         */
        fun copyRecursive(source: File, dest: File): Int {
            var copied = 0
            if (!dest.exists() && !dest.mkdirs()) {
                throw IllegalStateException("Cannot create ${dest.absolutePath}")
            }
            val children = source.listFiles() ?: return 0
            for (child in children) {
                val target = File(dest, child.name)
                if (child.isDirectory) {
                    copied += copyRecursive(child, target)
                } else if (!target.exists()) {
                    child.copyTo(target, overwrite = false)
                    copied++
                }
            }
            return copied
        }
    }
}
