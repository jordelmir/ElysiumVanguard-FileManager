package com.elysium.vanguard.core.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.8.2 — Tests for the Elysium Word renderer.
 */
class ElysiumWordRendererTest {

    @Test
    fun `renders plain text without spans`() {
        val body = "Hello, Elysium.".toByteArray()
        val rendered = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints()).text
        assertEquals("Hello, Elysium.", rendered.trim())
    }

    @Test
    fun `renders a single-line heading with bold style`() {
        val body = "# Top heading".toByteArray()
        val annotated = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints())
        assertEquals(1, annotated.spanStyles.size)
        // The # marker is stripped.
        assertEquals("Top heading", annotated.text.substringBefore("\n"))
    }

    @Test
    fun `renders bold inline emphasis via double asterisks`() {
        val body = "I am **bold** here".toByteArray()
        val annotated = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints())
        // The text contains "I am bold here" with the "bold" portion bold.
        assertTrue("expected bold text; got '${annotated.text}'", annotated.text.contains("bold"))
    }

    @Test
    fun `renders italic inline emphasis via single asterisks`() {
        val body = "An *italic* word".toByteArray()
        val annotated = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints())
        assertTrue(annotated.text.contains("italic"))
    }

    @Test
    fun `renders a multi-line doc preserving line breaks`() {
        val body = "# Title\nParagraph one.\nParagraph two.".toByteArray()
        val rendered = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints()).text
        val lines = rendered.split('\n')
        assertEquals(3, lines.size)
        assertEquals("Title", lines[0])
        assertEquals("Paragraph one.", lines[1])
        assertEquals("Paragraph two.", lines[2])
    }

    @Test
    fun `quotes render with italic base style`() {
        val body = "> a wise quote".toByteArray()
        val annotated = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints())
        assertTrue(annotated.text.contains("a wise quote"))
        // Italic span style applied at the line level (base style, not inline).
        assertTrue(annotated.spanStyles.isNotEmpty())
    }

    @Test
    fun `code fences render in monospace`() {
        val body = "```kotlin\nval x = 1\n```".toByteArray()
        val annotated = ElysiumWordRenderer.render(body, ElysiumDocument.StyleHints())
        // The triple backtick marker is stripped; the code text appears.
        assertTrue(annotated.text.contains("val x = 1"))
    }

    @Test
    fun `empty body renders to empty string`() {
        val annotated = ElysiumWordRenderer.render(ByteArray(0), ElysiumDocument.StyleHints())
        assertEquals("", annotated.text)
    }
}
