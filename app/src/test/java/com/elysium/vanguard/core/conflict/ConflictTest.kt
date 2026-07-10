package com.elysium.vanguard.core.conflict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 1.10 — ConflictDetector + ConflictResolver tests.
 *
 * What we cover:
 *   - Detection classifies NAME vs DUPLICATE correctly
 *   - Detection returns empty when no name collisions
 *   - Resolver applies KEEP_DESTINATION as no-op
 *   - Resolver applies KEEP_SOURCE by overwriting
 *   - Resolver applies KEEP_BOTH by renaming with "(1)" suffix
 *   - Resolver picks a non-colliding "keep both" name when "(1)" exists
 *   - Resolver applies SKIP without touching files
 */
class ConflictTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun detector() = ConflictDetector()
    private fun resolver() = ConflictResolver()

    @Test fun `no name collision yields empty list`() {
        val src = File(tmp.root, "a.txt").apply { writeText("aaa") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val result = detector().detect(listOf(src.absolutePath), dest)
        assertTrue(result.isEmpty())
    }

    @Test fun `name collision with different content is NAME conflict`() {
        val src = File(tmp.root, "a.txt").apply { writeText("aaaa") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "a.txt").writeText("bbbb")
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        assertEquals(1, conflicts.size)
        assertEquals(Conflict.Kind.NAME, conflicts[0].kind)
    }

    @Test fun `name collision with identical content is DUPLICATE`() {
        val src = File(tmp.root, "a.txt").apply { writeText("same content") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "a.txt").writeText("same content")
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        assertEquals(1, conflicts.size)
        assertEquals(Conflict.Kind.DUPLICATE, conflicts[0].kind)
    }

    @Test fun `name collision with same size but different content is NAME`() {
        val src = File(tmp.root, "a.txt").apply { writeText("AAAA") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "a.txt").writeText("BBBB")
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        assertEquals(Conflict.Kind.NAME, conflicts[0].kind)
    }

    @Test fun `resolver KEEP_DESTINATION leaves both files untouched`() {
        val src = File(tmp.root, "a.txt").apply { writeText("new") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val existing = File(dest, "a.txt").apply { writeText("old") }
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        val outcomes = resolver().apply(conflicts, mapOf(src.absolutePath to Conflict.Resolution.KEEP_DESTINATION))
        assertTrue(outcomes[0].success)
        assertEquals("old", existing.readText())
        assertEquals("new", src.readText())
    }

    @Test fun `resolver KEEP_SOURCE overwrites destination`() {
        val src = File(tmp.root, "a.txt").apply { writeText("new") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val existing = File(dest, "a.txt").apply { writeText("old") }
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        val outcomes = resolver().apply(conflicts, mapOf(src.absolutePath to Conflict.Resolution.KEEP_SOURCE))
        assertTrue(outcomes[0].success)
        assertEquals("new", existing.readText())
        // Source consumed (it was moved/replaced into dest).
        assertFalse("source should be gone", src.exists())
    }

    @Test fun `resolver KEEP_BOTH renames source with numeric suffix`() {
        val src = File(tmp.root, "a.txt").apply { writeText("new") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "a.txt").writeText("old")
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        val outcomes = resolver().apply(conflicts, mapOf(src.absolutePath to Conflict.Resolution.KEEP_BOTH))
        assertTrue(outcomes[0].success)
        val renamed = File(dest, "a (1).txt")
        assertTrue("renamed file should exist at $renamed", renamed.exists())
        assertEquals("new", renamed.readText())
        assertTrue("original destination should still hold old content",
            File(dest, "a.txt").readText() == "old")
    }

    @Test fun `resolver KEEP_BOTH picks the next free suffix when one is taken`() {
        val src = File(tmp.root, "a.txt").apply { writeText("new") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "a.txt").writeText("old")
        File(dest, "a (1).txt").writeText("taken1")
        File(dest, "a (2).txt").writeText("taken2")
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        val outcomes = resolver().apply(conflicts, mapOf(src.absolutePath to Conflict.Resolution.KEEP_BOTH))
        assertTrue(outcomes[0].success)
        val renamed = File(dest, "a (3).txt")
        assertTrue(renamed.exists())
        assertEquals("new", renamed.readText())
    }

    @Test fun `resolver SKIP does not touch any file`() {
        val src = File(tmp.root, "a.txt").apply { writeText("new") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        val existing = File(dest, "a.txt").apply { writeText("old") }
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        val outcomes = resolver().apply(conflicts, mapOf(src.absolutePath to Conflict.Resolution.SKIP))
        assertTrue(outcomes[0].success)
        assertEquals("old", existing.readText())
        assertEquals("new", src.readText())
    }

    @Test fun `resolver PENDING without resolution returns error outcome`() {
        val src = File(tmp.root, "a.txt").apply { writeText("new") }
        val dest = File(tmp.root, "dest").apply { mkdirs() }
        File(dest, "a.txt").writeText("old")
        val conflicts = detector().detect(listOf(src.absolutePath), dest)
        val outcomes = resolver().apply(conflicts, emptyMap())
        assertFalse(outcomes[0].success)
        assertTrue(outcomes[0].error!!.contains("No resolution"))
    }
}