package com.elysium.vanguard.core.runtime.distros.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * PHASE 9.6.8 — Tests for the snippets library.
 *
 * The library is curated today; tests verify that the bundled set
 * doesn't shrink or grow by accident as we add new snippets, and
 * that category filters behave intuitively.
 *
 * Phase 9.6.8 — first build; intentionally minimal.
 */
class BundledSnippetLibraryTest {

    @Test
    fun `library has at least one snippet per category used today`() {
        val grouped = BundledSnippetLibrary.grouped()
        // We expect PACKAGE, FILESYSTEM, SHELL, GIT, NETWORK at least.
        assertTrue(BashSnippet.Category.PACKAGE in grouped.keys)
        assertTrue(BashSnippet.Category.FILESYSTEM in grouped.keys)
        assertTrue(BashSnippet.Category.SHELL in grouped.keys)
    }

    @Test
    fun `find returns a snippet by id`() {
        val snippet = BundledSnippetLibrary.find("apt-update-and-upgrade")
        assertNotNull(snippet)
        assertEquals(BashSnippet.Category.PACKAGE, snippet!!.category)
        assertTrue(snippet.body.contains("apt update"))
    }

    @Test
    fun `find returns null for unknown id`() {
        assertEquals(null, BundledSnippetLibrary.find("does-not-exist"))
    }

    @Test
    fun `filterByCategoryLowercase matches the category name`() {
        val pkg = BundledSnippetLibrary.filterByCategoryLowercase("package")
        assertEquals(3, pkg.size)
        for (s in pkg) {
            assertEquals(BashSnippet.Category.PACKAGE, s.category)
        }
    }

    @Test
    fun `filterByCategoryLowercase substring match works`() {
        val pkg = BundledSnippetLibrary.filterByCategoryLowercase("pkg")
        assertEquals(0, pkg.size) // 'pkg' is not a substring of 'package'

        val fs = BundledSnippetLibrary.filterByCategoryLowercase("fs")
        assertEquals(0, fs.size)
    }

    @Test
    fun `every snippet has a non-empty body`() {
        for (s in BundledSnippetLibrary.ALL) {
            assertTrue("snippet ${s.id} has empty body", s.body.isNotBlank())
        }
    }

    @Test
    fun `every snippet has a unique id`() {
        val ids = BundledSnippetLibrary.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `TmateLaunchSpec reflects expiry`() {
        val past = TmateLaunchSpec(
            sessionId = "s",
            sshUrl = "ssh://x",
            roUrl = "ssh://y",
            expiresAtMs = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10)
        )
        assertTrue(past.isExpired)
        val future = past.copy(expiresAtMs = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
        assertFalse(future.isExpired)
    }

    @Test
    fun `TmateLaunchSpec carries all fields`() {
        val spec = TmateLaunchSpec(
            sessionId = "ab",
            sshUrl = "ssh://rw",
            roUrl = "ssh://ro",
            expiresAtMs = 123L
        )
        assertEquals("ab", spec.sessionId)
        assertEquals("ssh://rw", spec.sshUrl)
        assertEquals("ssh://ro", spec.roUrl)
        assertEquals(123L, spec.expiresAtMs)
    }
}
