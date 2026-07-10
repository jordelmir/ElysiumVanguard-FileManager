package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.office.ElysiumDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.7 — Tests for the CRDT-backed Elysium document.
 */
class CrdtElysiumDocumentTest {

    private fun makeWordDoc(title: String, body: String): ElysiumDocument =
        ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = body.toByteArray(Charsets.UTF_8)
        )

    @Test
    fun `fromElysiumDocument extracts metadata and body`() {
        val original = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = "# Notes\n\nHello world".toByteArray(Charsets.UTF_8)
        )
        val crdt = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        assertEquals("Notes", crdt.metadata.get("title"))
        assertEquals("Hello world", crdt.body.asString())
    }

    @Test
    fun `toElysiumDocument round-trips back to a similar shape`() {
        val original = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = "# Notes\n\nHello world".toByteArray(Charsets.UTF_8)
        )
        val crdt = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        val rendered = crdt.toElysiumDocument()
        // The rendered body should contain the title as "# Notes"
        // and the body "Hello world".
        val text = rendered.body.toString(Charsets.UTF_8)
        assertTrue(text.contains("# Notes"))
        assertTrue(text.contains("Hello world"))
    }

    @Test
    fun `two CRDT docs converge after merge`() {
        val original = makeWordDoc("Doc", "")
        val a = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        val b = CrdtElysiumDocument.fromElysiumDocument(original, "bob")
        // Alice edits the title; bob edits the body.
        a.metadata.apply(CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "alice"), "title", "Alice's Doc"))
        a.body.apply(CrdtSeqOp.Insert(HybridLogicalClock(1001, 0, "alice"), "A"))
        b.metadata.apply(CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "bob"), "title", "Bob's Doc"))
        b.body.apply(CrdtSeqOp.Insert(HybridLogicalClock(1001, 0, "bob"), "B"))
        // Merge a <- b.
        a.merge(b)
        // The title should reflect the higher HLC's edit. Both
        // titles have HLC (1000,0); tiebreaker is nodeId, so "bob"
        // wins ("b" > "a").
        assertEquals("Bob's Doc", a.metadata.get("title"))
        // Both inserts survive.
        assertTrue(a.body.asString().contains("A"))
        assertTrue(a.body.asString().contains("B"))
    }

    @Test
    fun `metadata deletion propagates through merge`() {
        val original = makeWordDoc("Doc", "")
        val a = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        val b = CrdtElysiumDocument.fromElysiumDocument(original, "bob")
        // Alice deletes the title.
        a.metadata.apply(CrdtOp.DeleteProperty(HybridLogicalClock(2000, 0, "alice"), "title"))
        a.merge(b)
        assertEquals(null, a.metadata.get("title"))
    }

    @Test
    fun `body character-level edits merge correctly`() {
        val original = makeWordDoc("Doc", "Hi")
        val a = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        val b = CrdtElysiumDocument.fromElysiumDocument(original, "bob")
        // Alice appends " world"; bob appends "!".
        a.body.apply(CrdtSeqOp.Insert(HybridLogicalClock(2000, 0, "alice"), " world"))
        b.body.apply(CrdtSeqOp.Insert(HybridLogicalClock(2001, 0, "bob"), "!"))
        a.merge(b)
        val text = a.body.asString()
        assertTrue("merged body should contain 'Hi': $text", text.contains("Hi"))
        assertTrue("merged body should contain ' world': $text", text.contains(" world"))
        assertTrue("merged body should contain '!': $text", text.contains("!"))
    }

    @Test
    fun `fromElysiumDocument handles documents with no title`() {
        val original = makeWordDoc("", "Just a body")
        val crdt = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        assertEquals(null, crdt.metadata.get("title"))
        assertEquals("Just a body", crdt.body.asString())
    }

    @Test
    fun `merge refuses to combine different document kinds`() {
        val word = CrdtElysiumDocument.fromElysiumDocument(
            makeWordDoc("W", "x"), "alice"
        )
        val sheet = CrdtElysiumDocument.fromElysiumDocument(
            ElysiumDocument(
                kind = ElysiumDocument.Kind.SHEET,
                style = ElysiumDocument.StyleHints(),
                body = "a,b\nc,d".toByteArray(Charsets.UTF_8)
            ),
            "bob"
        )
        try {
            word.merge(sheet)
            assertTrue("expected IllegalStateException", false)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("kind"))
        }
    }

    @Test
    fun `metadata defaults are preserved on round-trip`() {
        val original = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(
                font = "sans-serif",
                fontSizePt = 16,
                theme = "midnight"
            ),
            body = "# Title\n\nBody text".toByteArray(Charsets.UTF_8)
        )
        val crdt = CrdtElysiumDocument.fromElysiumDocument(original, "alice")
        val rendered = crdt.toElysiumDocument()
        assertEquals("sans-serif", rendered.style.font)
        assertEquals(16, rendered.style.fontSizePt)
        assertEquals("midnight", rendered.style.theme)
    }
}