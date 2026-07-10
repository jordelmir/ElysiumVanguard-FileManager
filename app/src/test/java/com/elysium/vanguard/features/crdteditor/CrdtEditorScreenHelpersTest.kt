package com.elysium.vanguard.features.crdteditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.20 — Tests for the deterministic helpers extracted
 * from `CrdtDocumentEditorScreen`. These are the bits of the
 * UI that have non-trivial behavior — they're exercised here
 * in plain JVM tests so the screen's contract is covered
 * even without Compose UI tests.
 */
class CrdtEditorScreenHelpersTest {

    // ---- fileName ----

    @Test
    fun `fileName returns last segment for absolute path`() {
        assertEquals(
            "doc.elysium.word",
            CrdtEditorScreenHelpers.fileName("/foo/bar/doc.elysium.word")
        )
    }

    @Test
    fun `fileName returns input verbatim when there's no slash`() {
        assertEquals(
            "doc.elysium.word",
            CrdtEditorScreenHelpers.fileName("doc.elysium.word")
        )
    }

    @Test
    fun `fileName returns placeholder for blank input`() {
        assertEquals("(no file)", CrdtEditorScreenHelpers.fileName(""))
        assertEquals("(no file)", CrdtEditorScreenHelpers.fileName("   "))
    }

    // ---- EditorResult.label ----

    @Test
    fun `EditorResult label shows Saved for save event`() {
        assertEquals("saved", EditorResult.Saved.label())
    }

    @Test
    fun `EditorResult label shows op count for Synced`() {
        val msg = EditorResult.Synced(7).label()
        assertTrue("got: $msg", msg.contains("7op"))
    }

    @Test
    fun `EditorResult label shows no peer for SyncNoPeer`() {
        assertEquals("no peer", EditorResult.SyncNoPeer.label())
    }

    // ---- BodyEditorDiff.compute ----

    @Test
    fun `BodyEditorDiff emits Chars when next is a strict suffix`() {
        val decision = BodyEditorDiff.compute(prev = "abc", next = "abcd")
        assertTrue(
            "expected Chars, was $decision",
            decision is BodyEditorDiff.Decision.Chars
        )
        assertEquals("d", (decision as BodyEditorDiff.Decision.Chars).appended)
    }

    @Test
    fun `BodyEditorDiff emits Chars for multi-char appends`() {
        val decision = BodyEditorDiff.compute(prev = "abc", next = "abcdef")
        assertTrue(decision is BodyEditorDiff.Decision.Chars)
        assertEquals("def", (decision as BodyEditorDiff.Decision.Chars).appended)
    }

    @Test
    fun `BodyEditorDiff emits Backspace when prev is a strict superstring`() {
        val decision = BodyEditorDiff.compute(prev = "abcd", next = "abc")
        assertEquals(BodyEditorDiff.Decision.Backspace, decision)
    }

    @Test
    fun `BodyEditorDiff emits Ignore when content is unchanged`() {
        assertEquals(
            BodyEditorDiff.Decision.Ignore,
            BodyEditorDiff.compute(prev = "abc", next = "abc")
        )
    }

    @Test
    fun `BodyEditorDiff emits Ignore for mid-string edits`() {
        // User typed in the middle of the string — we treat it
        // as a no-op so the op log stays append-only.
        val decision = BodyEditorDiff.compute(prev = "abc", next = "abXc")
        assertEquals(BodyEditorDiff.Decision.Ignore, decision)
    }

    @Test
    fun `BodyEditorDiff emits Ignore when next is unrelated`() {
        val decision = BodyEditorDiff.compute(prev = "abc", next = "xyz")
        assertEquals(BodyEditorDiff.Decision.Ignore, decision)
    }

    @Test
    fun `BodyEditorDiff emits Backspace for multi-char removal as a single backspace`() {
        // "abcdef" → "abc" is a 3-char removal; the diff emits
        // a single Backspace to keep the op log linear. The
        // composer advances the cursor one keystroke at a time
        // so subsequent ticks will collapse the rest.
        val decision = BodyEditorDiff.compute(prev = "abcdef", next = "abc")
        assertEquals(BodyEditorDiff.Decision.Backspace, decision)
    }

    @Test
    fun `BodyEditorDiff handles empty prev and grows to a non-empty next`() {
        val decision = BodyEditorDiff.compute(prev = "", next = "x")
        assertTrue(decision is BodyEditorDiff.Decision.Chars)
        assertEquals("x", (decision as BodyEditorDiff.Decision.Chars).appended)
    }

    @Test
    fun `BodyEditorDiff handles prev with single char and goes to empty`() {
        val decision = BodyEditorDiff.compute(prev = "x", next = "")
        assertEquals(BodyEditorDiff.Decision.Backspace, decision)
    }
}
