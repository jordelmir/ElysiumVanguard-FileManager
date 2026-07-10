package com.elysium.vanguard.core.editor

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2.6 — Markdown renderer unit tests.
 *
 * What we cover:
 *   - Headings (# … ######)
 *   - Paragraphs (text blocks separated by blank lines)
 *   - Bold, italic, bold+italic inline
 *   - Inline code (backticks)
 *   - Code fences (```)
 *   - Lists (ordered + unordered)
 *   - Blockquotes
 *   - Links [text](url)
 *   - Empty input
 */
class MarkdownRendererTest {

    private val renderer = MarkdownRenderer()

    @Test fun `empty input produces empty output`() {
        val r = renderer.render("")
        assertTrue(r.text.isEmpty() || r.text.isBlank())
    }

    @Test fun `headings at all levels render to text`() {
        val md = "# H1\n## H2\n### H3\n#### H4\n##### H5\n###### H6"
        val r = renderer.render(md)
        for (i in 1..6) {
            assertTrue("H$i missing", r.text.contains("H$i"))
        }
    }

    @Test fun `paragraph splits on blank lines`() {
        val md = "First paragraph.\n\nSecond paragraph."
        val r = renderer.render(md)
        assertTrue(r.text.contains("First paragraph"))
        assertTrue(r.text.contains("Second paragraph"))
        assertTrue(r.text.contains("\n"))
    }

    @Test fun `bold wraps text in span style`() {
        val r = renderer.render("this is **bold** text")
        assertTrue(r.text.contains("bold"))
        // Verify a bold style is applied to the inner text.
        val boldSpans = r.spanStyles.filter { it.item.fontWeight != null }
        assertTrue("expected at least one bold span", boldSpans.isNotEmpty())
        val matchesBoldText = boldSpans.any { span ->
            r.text.substring(span.start, span.end).contains("bold")
        }
        assertTrue("bold span should cover 'bold'", matchesBoldText)
    }

    @Test fun `italic wraps text in span style`() {
        val r = renderer.render("this is *italic* text")
        assertTrue(r.text.contains("italic"))
        val italicSpans = r.spanStyles.filter { it.item.fontStyle != null }
        assertTrue("expected at least one italic span", italicSpans.isNotEmpty())
    }

    @Test fun `inline code with backticks`() {
        val r = renderer.render("use `println()` here")
        assertTrue(r.text.contains("println()"))
    }

    @Test fun `code fence captures multi-line content`() {
        val md = "```\nline1\nline2\nline3\n```"
        val r = renderer.render(md)
        assertTrue(r.text.contains("line1"))
        assertTrue(r.text.contains("line2"))
        assertTrue(r.text.contains("line3"))
    }

    @Test fun `unordered list renders with bullets`() {
        val md = "- alpha\n- beta\n- gamma"
        val r = renderer.render(md)
        assertTrue(r.text.contains("alpha"))
        assertTrue(r.text.contains("beta"))
        assertTrue(r.text.contains("gamma"))
        assertTrue("expected bullet marker", r.text.contains("•"))
    }

    @Test fun `ordered list renders with numbers`() {
        val md = "1. one\n2. two\n3. three"
        val r = renderer.render(md)
        assertTrue(r.text.contains("one"))
        assertTrue(r.text.contains("two"))
        assertTrue(r.text.contains("three"))
        assertTrue("expected '1.' marker", r.text.contains("1."))
    }

    @Test fun `blockquote renders with vertical bar`() {
        val r = renderer.render("> quoted text")
        assertTrue(r.text.contains("│"))
        assertTrue(r.text.contains("quoted text"))
    }

    @Test fun `link renders with brackets and parens preserved`() {
        val r = renderer.render("Visit [site](https://example.com) here")
        assertTrue(r.text.contains("site"))
        assertTrue(r.text.contains("https://example.com"))
    }
}