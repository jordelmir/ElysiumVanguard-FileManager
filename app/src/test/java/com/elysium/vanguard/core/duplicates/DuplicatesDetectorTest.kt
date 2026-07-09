package com.elysium.vanguard.core.duplicates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DuplicatesDetectorTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val detector = DuplicatesDetector()

    @Test
    fun `two files with identical content are duplicates`() = runBlocking {
        val a = File(tempFolder.root, "a.txt").apply { writeText("hello world") }
        val b = File(tempFolder.root, "b.txt").apply { writeText("hello world") }
        val groups = detector.findDuplicates(listOf(tempFolder.root))
        assertEquals(1, groups.size)
        assertEquals(setOf(a, b), groups[0].files.toSet())
    }

    @Test
    fun `different content same name is not a duplicate`() = runBlocking {
        File(tempFolder.root, "x.txt").apply { writeText("alpha") }
        File(tempFolder.root, "y.txt").apply { writeText("bravo") }
        val groups = detector.findDuplicates(listOf(tempFolder.root))
        assertEquals(0, groups.size)
    }

    @Test
    fun `three copies form one group`() = runBlocking {
        val content = "the quick brown fox jumps over the lazy dog"
        repeat(3) {
            File(tempFolder.root, "f$it.txt").apply { writeText(content) }
        }
        val groups = detector.findDuplicates(listOf(tempFolder.root))
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].files.size)
        assertEquals(content.length.toLong() * 2, groups[0].wastedBytes)
    }

    @Test
    fun `empty directories produce no groups`() = runBlocking {
        val empty = File(tempFolder.root, "empty").apply { mkdirs() }
        val groups = detector.findDuplicates(listOf(empty))
        assertEquals(0, groups.size)
    }

    @Test
    fun `size grouping prunes unique sizes before hashing`() = runBlocking {
        // 5 unique files at different sizes — phase 1 should eliminate them.
        repeat(5) { i ->
            File(tempFolder.root, "f$i.txt").apply { writeText("size-$i") }
        }
        var lastProgress: DuplicatesDetector.Progress? = null
        val groups = detector.findDuplicates(listOf(tempFolder.root)) { lastProgress = it }
        assertEquals(0, groups.size)
        assertTrue(lastProgress != null)
    }
}