package com.elysium.vanguard.core.runtime.distros.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * PHASE 9.6.13 — Tests for the persistent snippets catalog.
 */
class SnippetsCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty directory reads back empty list`() {
        val catalog = SnippetsCatalog(tmp.root)
        assertEquals(emptyList<UserSnippet>(), catalog.list())
    }

    @Test
    fun `save and list round-trips a single snippet`() {
        val catalog = SnippetsCatalog(tmp.root)
        val snippet = UserSnippet(
            id = "find-cache",
            title = "Locate the largest Maven cache",
            category = "filesystem",
            body = "du -sh ~/.gradle/caches",
            description = "Helps when builds balloon in size"
        )
        catalog.save(snippet)
        val read = catalog.list()
        assertEquals(1, read.size)
        assertEquals(snippet.id, read[0].id)
        assertEquals(snippet.body, read[0].body)
        assertEquals(snippet.category, read[0].category)
    }

    @Test
    fun `saving twice with the same id replaces the snippet`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.save(UserSnippet(
            id = "x",
            title = "old",
            category = "shell",
            body = "old body"
        ))
        catalog.save(UserSnippet(
            id = "x",
            title = "new",
            category = "shell",
            body = "new body"
        ))
        val read = catalog.list()
        assertEquals(1, read.size)
        assertEquals("new body", read[0].body)
        assertEquals("new", read[0].title)
    }

    @Test
    fun `delete removes the snippet`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.save(UserSnippet("a", "A", "shell", "a body"))
        catalog.save(UserSnippet("b", "B", "shell", "b body"))
        catalog.delete("a")
        val read = catalog.list()
        assertEquals(1, read.size)
        assertEquals("b", read[0].id)
    }

    @Test
    fun `multiple snippets persist in order`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.save(UserSnippet("1", "One", "shell", "one"))
        catalog.save(UserSnippet("2", "Two", "shell", "two"))
        catalog.save(UserSnippet("3", "Three", "shell", "three"))
        val read = catalog.list()
        assertEquals(listOf("1", "2", "3"), read.map { it.id })
    }

    @Test
    fun `body with quotes round-trips`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.save(UserSnippet(
            id = "q",
            title = "q",
            category = "shell",
            body = "echo \"hello\" | tr 'a-z' 'A-Z'"
        ))
        val read = catalog.list()
        assertTrue("expected body to round-trip; got='${read[0].body}'", read[0].body.contains("hello"))
    }

    @Test
    fun `body with newline round-trips`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.save(UserSnippet(
            id = "nl",
            title = "nl",
            category = "shell",
            body = "line1\nline2"
        ))
        val read = catalog.list()
        assertTrue(read[0].body.contains('\n'))
    }

    @Test
    fun `deleting the last snippet leaves an empty file`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.save(UserSnippet("x", "X", "shell", "body"))
        catalog.delete("x")
        assertEquals(emptyList<UserSnippet>(), catalog.list())
        assertFalse(catalog.list().any { it.id == "x" })
    }

    @Test
    fun `delete of a non-existent id is a no-op`() {
        val catalog = SnippetsCatalog(tmp.root)
        catalog.delete("ghost")
        // No exception; no file written either.
        assertEquals(emptyList<UserSnippet>(), catalog.list())
    }
}
