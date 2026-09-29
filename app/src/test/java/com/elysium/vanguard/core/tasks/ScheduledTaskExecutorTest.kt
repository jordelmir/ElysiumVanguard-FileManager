package com.elysium.vanguard.core.tasks

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledTaskExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun copyTask(source: File, dest: File) = ScheduledTaskEntity(
        id = "t1",
        name = "Sync",
        type = TaskType.COPY_DIRECTORY.name,
        sourcePath = source.absolutePath,
        destPath = dest.absolutePath,
        intervalHours = 6,
        createdAt = 1L,
    )

    private fun purgeTask() = ScheduledTaskEntity(
        id = "t1",
        name = "Purge",
        type = TaskType.PURGE_TRASH.name,
        intervalHours = 6,
        createdAt = 1L,
    )

    private fun copyExecutor() = ScheduledTaskExecutor(
        purgeTrash = { error("purge must not run") },
        copyDirectory = { s, d -> ScheduledTaskExecutor.copyRecursive(s, d) },
    )

    @Test
    fun `purge task reports purged count`() = runTest {
        val executor = ScheduledTaskExecutor(purgeTrash = { 7 }, copyDirectory = { _, _ -> 0 })
        val result = executor.execute(purgeTask())
        assertTrue(result.success)
        assertTrue(result.detail, result.detail.contains("7"))
    }

    @Test
    fun `purge failure returns unsuccessful result`() = runTest {
        val executor = ScheduledTaskExecutor(purgeTrash = { throw IllegalStateException("db locked") }, copyDirectory = { _, _ -> 0 })
        val result = executor.execute(purgeTask())
        assertFalse(result.success)
        assertTrue(result.detail, result.detail.contains("db locked"))
    }

    @Test
    fun `copy task copies files and reports count`() = runTest {
        val src = tmp.newFolder("src")
        val dst = File(tmp.root, "dst")
        File(src, "a.txt").writeText("A")
        File(src, "sub").mkdir()
        File(src, "sub/b.txt").writeText("B")

        val result = copyExecutor().execute(copyTask(src, dst))

        assertTrue(result.success)
        assertTrue(result.detail, result.detail.contains("2"))
        assertEquals("A", File(dst, "a.txt").readText())
        assertEquals("B", File(dst, "sub/b.txt").readText())
    }

    @Test
    fun `copy task skips files already present at destination`() = runTest {
        val src = tmp.newFolder("src")
        val dst = tmp.newFolder("dst")
        File(src, "a.txt").writeText("NEW")
        File(dst, "a.txt").writeText("OLD")

        val result = copyExecutor().execute(copyTask(src, dst))

        assertTrue(result.success)
        assertTrue(result.detail, result.detail.contains("0"))
        assertEquals("OLD", File(dst, "a.txt").readText())
    }

    @Test
    fun `copy task with missing source fails`() = runTest {
        val missing = File(tmp.root, "nope")
        val result = copyExecutor().execute(copyTask(missing, File(tmp.root, "dst")))
        assertFalse(result.success)
        assertTrue(result.detail, result.detail.contains("Source missing"))
    }

    @Test
    fun `copy task with blank destination fails`() = runTest {
        val src = tmp.newFolder("src")
        val task = copyTask(src, File(tmp.root, "dst")).copy(destPath = "")
        val result = copyExecutor().execute(task)
        assertFalse(result.success)
        assertTrue(result.detail, result.detail.contains("Destination not set"))
    }

    @Test
    fun `unknown task type fails soft`() = runTest {
        val executor = ScheduledTaskExecutor(purgeTrash = { 0 }, copyDirectory = { _, _ -> 0 })
        val task = purgeTask().copy(type = "SOMETHING_ELSE")
        // valueOf throws inside taskType getter → getOrDefault falls back to PURGE_TRASH
        val result = executor.execute(task)
        assertTrue(result.success)
    }
}
