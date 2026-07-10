package com.elysium.vanguard.core.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.8.4 — Tests for the deck document + HTML export.
 */
class ElysiumDeckTest {

    @Test
    fun `round trip through JSON preserves slides`() {
        val deck = ElysiumDeck(
            title = "Onward",
            slides = listOf(
                Slide(title = "Welcome", body = "Hello, sovereigns."),
                Slide(title = "What's next", body = "All the things."),
                Slide(title = "Thanks", body = "End.", notes = "remember to wave")
            )
        )
        val parsed = ElysiumDeck.fromJson(deck.toJson())
        assertEquals(deck.title, parsed.title)
        assertEquals(deck.slides.size, parsed.slides.size)
        assertEquals("Welcome", parsed.slides[0].title)
        assertEquals("Hello, sovereigns.", parsed.slides[0].body)
        assertEquals("remember to wave", parsed.slides[2].notes)
    }

    @Test
    fun `slide IDs are slugified`() {
        val slide = Slide(title = "Onward Roadmap", body = "...")
        // Spaces collapse to dashes; case is preserved.
        assertEquals("onward-roadmap", slide.id)
    }

    @Test
    fun `slide IDs default consistently when title has only symbols`() {
        val slide = Slide(title = "!!!", body = "...")
        // The slug is non-empty but stable; we don't care about its
        // exact form here — what matters is no exception.
        assertTrue(slide.id.isNotEmpty())
    }

    @Test
    fun `deck requires at least one slide`() {
        try {
            ElysiumDeck(title = "x", slides = emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("slide"))
        }
    }

    @Test
    fun `HTML export embeds the title and a slide per element`() {
        val deck = ElysiumDeck(
            title = "Brief",
            slides = listOf(
                Slide("Title1", "first"),
                Slide("Title2", "second")
            )
        )
        val html = DeckHtmlExporter.export(deck)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("Brief"))
        assertTrue(html.contains("Title1"))
        assertTrue(html.contains("Title2"))
        assertTrue(html.contains("first"))
        assertTrue(html.contains("second"))
    }

    @Test
    fun `HTML export escapes lt and gt`() {
        val deck = ElysiumDeck(
            title = "Bracket< test",
            slides = listOf(Slide("a", "b > c"))
        )
        val html = DeckHtmlExporter.export(deck)
        assertTrue("expected '<test>' to escape to '&lt;'", html.contains("Bracket&lt; test"))
        assertTrue("expected 'b > c' to escape to '&gt;'", html.contains("b &gt; c"))
    }

    @Test
    fun `HTML export includes theme tag in footer`() {
        val deck = ElysiumDeck("d", listOf(Slide("a", "b")))
        val html = DeckHtmlExporter.export(deck, theme = "dusk")
        assertTrue(html.contains("dusk"))
    }

    @Test
    fun `fromJson falls back when slides array is missing`() {
        val deck = ElysiumDeck.fromJson(
            """{"title":"empty"}""".toByteArray()
        )
        // The fallback is a single placeholder slide so the deck is
        // renderable. Title comes from the explicit "title" field
        // (no fallback is applied when the field is present).
        assertEquals(1, deck.slides.size)
        assertEquals("empty", deck.title)
    }
}
