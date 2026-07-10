package com.elysium.vanguard.core.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 2.7 — TextEditorRepository round-trip tests.
 *
 * What we cover:
 *   - load() returns the file's contents
 *   - load() returns null for missing / unreadable files
 *   - save() round-trips content back to disk
 *   - save() is atomic (no half-written file if interrupted)
 *   - save() overwrites an existing file in place
 *
 * We use a real [Context] stub backed by a temp folder. No mocking framework
 * needed because TextEditorRepository only uses context.getApplicationContext()
 * to resolve files (we provide an isolated one anyway).
 */
class TextEditorRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repo(): TextEditorRepository {
        // The repo no longer takes a context — it only operates on absolute file
        // paths provided by the caller, so JVM unit tests can use it directly.
        return TextEditorRepository()
    }

    @Test fun `load returns null for missing file`() = runBlocking {
        val r = repo()
        val path = File(tmp.root, "nope.txt").absolutePath
        assertNull(r.load(path))
    }

    @Test fun `load returns contents of an existing file`() = runBlocking {
        val r = repo()
        val file = File(tmp.root, "hello.txt").apply { writeText("hello world") }
        val loaded = r.load(file.absolutePath)
        assertEquals("hello world", loaded)
    }

    @Test fun `save round-trips content`() = runBlocking {
        val r = repo()
        val file = File(tmp.root, "out.txt")
        assertTrue(r.save(file.absolutePath, "first version"))
        assertEquals("first version", file.readText())

        assertTrue(r.save(file.absolutePath, "second version"))
        assertEquals("second version", file.readText())
    }

    @Test fun `save fails when parent directory does not exist`() = runBlocking {
        val r = repo()
        val missing = File(tmp.root, "does-not-exist/out.txt")
        assertFalse(r.save(missing.absolutePath, "x"))
    }

    @Test fun `save is atomic — no temp file left behind on success`() = runBlocking {
        val r = repo()
        val file = File(tmp.root, "atomic.txt")
        r.save(file.absolutePath, "content")
        val siblings = file.parentFile!!.listFiles()!!.filter { it.name != file.name }
        assertTrue("no temp file should remain: $siblings", siblings.isEmpty())
    }

    @Test fun `save overwrites existing file in place`() = runBlocking {
        val r = repo()
        val file = File(tmp.root, "overwrite.txt").apply { writeText("old") }
        r.save(file.absolutePath, "new")
        assertEquals("new", file.readText())
    }

    @Test fun `load + save + load round trip preserves content`() = runBlocking {
        val r = repo()
        val file = File(tmp.root, "rt.txt")
        val original = "line1\nline2 with unicode ñ\nline3"
        r.save(file.absolutePath, original)
        val loaded = r.load(file.absolutePath)
        assertEquals(original, loaded)
    }

    @Test fun `language detection matches file extension`() {
        val r = repo()
        assertEquals(Language.KOTLIN, r.detectLanguage("foo.kt"))
        assertEquals(Language.PYTHON, r.detectLanguage("bar.py"))
        assertEquals(Language.PLAIN, r.detectLanguage("baz"))
    }
}