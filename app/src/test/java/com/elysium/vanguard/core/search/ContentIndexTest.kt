package com.elysium.vanguard.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 1.5 — ContentIndex unit tests.
 *
 * What we cover:
 *   - Index a file with known content, search finds it
 *   - Search returns multiple hits ranked by relevance
 *   - Empty / whitespace queries return no results
 *   - Missing tokens in the index return no results
 *   - Stop words are dropped from queries
 *   - Binary files are skipped
 *   - unindexFile removes all postings
 *   - Re-indexing a file replaces its previous postings (de-dupe)
 *   - clear() resets the index
 */
class ContentIndexTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeFile(name: String, content: String): File =
        File(tmp.root, name).apply { writeText(content) }

    @Test fun `index a file and find it by content search`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "the quick brown fox jumps over the lazy dog")
        assertTrue(idx.indexFile(f))
        val hits = idx.search("fox")
        assertEquals(1, hits.size)
        assertEquals(f.absolutePath, hits[0].filePath)
    }

    @Test fun `search returns no results for empty query`() {
        val idx = ContentIndex()
        writeFile("note.txt", "hello world")
        assertTrue(idx.indexFile(File(tmp.root, "note.txt")))
        assertTrue(idx.search("").isEmpty())
        assertTrue(idx.search("   ").isEmpty())
    }

    @Test fun `search returns no results when no file contains the term`() {
        val idx = ContentIndex()
        writeFile("a.txt", "alpha beta gamma")
        idx.indexFile(File(tmp.root, "a.txt"))
        assertTrue(idx.search("xyzzy").isEmpty())
    }

    @Test fun `search ranks multiple hits by relevance`() {
        val idx = ContentIndex()
        // File A mentions "kotlin" once, file B mentions it five times.
        val a = writeFile("a.txt", "kotlin is a programming language")
        val b = writeFile("b.txt", "kotlin kotlin kotlin kotlin kotlin rocks")
        idx.indexFile(a); idx.indexFile(b)
        val hits = idx.search("kotlin")
        assertEquals(2, hits.size)
        assertEquals("b.txt", hits[0].displayName)  // higher score first
    }

    @Test fun `stop words are not indexed`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "the the the cat sat on the mat")
        idx.indexFile(f)
        // "the" is a stop word; should not be in the index.
        val hits = idx.search("the")
        assertTrue(hits.isEmpty())
    }

    @Test fun `binary file is skipped on index`() {
        val idx = ContentIndex()
        val f = File(tmp.root, "binary.dat").apply {
            // NUL bytes in the first 8 KB = strong binary signal.
            writeBytes(ByteArray(2048) { 0 })
        }
        assertFalse(idx.indexFile(f))
        assertEquals(0, idx.indexedFileCount())
    }

    @Test fun `oversize file is skipped`() {
        val idx = ContentIndex()
        val f = File(tmp.root, "big.txt")
        // Create a 6 MB file with valid UTF-8 (but our cap is 5 MB).
        f.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            repeat(100) { out.write(chunk) }
        }
        assertFalse(idx.indexFile(f))
    }

    @Test fun `unindexFile removes all of its postings`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "alpha beta gamma")
        idx.indexFile(f)
        assertEquals(1, idx.indexedFileCount())
        idx.unindexFile(f.absolutePath)
        assertEquals(0, idx.indexedFileCount())
        assertTrue(idx.search("alpha").isEmpty())
    }

    @Test fun `re-indexing replaces prior postings`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "alpha content")
        idx.indexFile(f)
        // Rewrite with different content and re-index.
        f.writeText("omega content")
        idx.indexFile(f)
        // "alpha" should no longer match; "omega" should.
        assertTrue(idx.search("alpha").isEmpty())
        assertEquals(1, idx.search("omega").size)
    }

    @Test fun `clear resets the index`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "alpha beta")
        idx.indexFile(f)
        assertTrue(idx.indexedFileCount() > 0)
        idx.clear()
        assertEquals(0, idx.indexedFileCount())
        assertTrue(idx.search("alpha").isEmpty())
    }

    @Test fun `isIndexed reflects state`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "alpha")
        assertFalse(idx.isIndexed(f.absolutePath))
        idx.indexFile(f)
        assertTrue(idx.isIndexed(f.absolutePath))
    }

    @Test fun `multi-term query only returns files containing every term`() {
        val idx = ContentIndex()
        writeFile("a.txt", "alpha beta")
        writeFile("b.txt", "alpha gamma")
        writeFile("c.txt", "alpha beta gamma")
        for (f in tmp.root.listFiles().orEmpty()) idx.indexFile(f)
        val hits = idx.search("alpha beta")
        // Only a.txt and c.txt contain both terms.
        assertEquals(2, hits.size)
        val names = hits.map { it.displayName }.toSet()
        assertTrue("a.txt" in names)
        assertTrue("c.txt" in names)
        assertFalse("b.txt" in names)
    }

    @Test fun `snippet contains the matched term`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "The rain in spain stays mainly in the plain")
        idx.indexFile(f)
        val hits = idx.search("spain")
        assertEquals(1, hits.size)
        assertNotNull(hits[0].snippet)
        assertTrue("snippet should contain the term", hits[0].snippet!!.contains("spain", ignoreCase = true))
    }

    @Test fun `index a file with mixed case tokens and search case insensitive`() {
        val idx = ContentIndex()
        val f = writeFile("note.txt", "Kotlin Programming Language")
        idx.indexFile(f)
        // The indexer lowercases; queries should match regardless of case.
        assertEquals(1, idx.search("kotlin").size)
        assertEquals(1, idx.search("KOTLIN").size)
    }
}