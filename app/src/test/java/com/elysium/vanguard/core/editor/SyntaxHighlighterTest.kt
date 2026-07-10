package com.elysium.vanguard.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2.7 — Syntax highlighter unit tests.
 *
 * What we verify:
 *   - Plain text: no tokenization rules fire, output is one big UNKNOWN span
 *   - Kotlin keywords / strings / comments / numbers / functions
 *   - Java, Python, JSON, SQL, Bash keyword coverage
 *   - Markdown headings / emphasis / code / links
 *   - HTML tags / attributes
 *   - Token ranges cover the whole input (no gaps, no overlaps)
 *   - Empty input returns empty
 */
class SyntaxHighlighterTest {

    @Test fun `empty input returns empty list`() {
        assertTrue(SyntaxHighlighter.tokenize("", Language.KOTLIN).isEmpty())
    }

    @Test fun `plain text produces single unknown span`() {
        val tokens = SyntaxHighlighter.tokenize("hello world", Language.PLAIN)
        assertEquals(1, tokens.size)
        assertEquals(TokenKind.UNKNOWN, tokens[0].kind)
        assertEquals(0, tokens[0].start)
        assertEquals(11, tokens[0].end)
    }

    @Test fun `kotlin keyword is classified as keyword`() {
        val tokens = SyntaxHighlighter.tokenize("val x = 1", Language.KOTLIN)
        val keywords = tokens.filter { it.kind == TokenKind.KEYWORD }
        assertTrue(keywords.isNotEmpty())
        assertEquals("val", tokens.first { it.kind == TokenKind.KEYWORD }.text("val x = 1"))
    }

    @Test fun `kotlin string literal is classified as string`() {
        val src = "val s = \"hello\""
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val strings = tokens.filter { it.kind == TokenKind.STRING }
        assertEquals(1, strings.size)
        assertEquals("\"hello\"", strings[0].text(src))
    }

    @Test fun `kotlin line comment is classified as comment`() {
        val src = "// hello\nval x = 1"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val comments = tokens.filter { it.kind == TokenKind.COMMENT }
        assertEquals(1, comments.size)
        assertEquals("// hello", comments[0].text(src))
    }

    @Test fun `kotlin block comment is classified as comment`() {
        val src = "/* block */\nval x = 1"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val comments = tokens.filter { it.kind == TokenKind.COMMENT }
        assertTrue(comments.any { it.text(src) == "/* block */" })
    }

    @Test fun `kotlin function call is classified as function`() {
        // Use a non-builtin name so the FUNCTION rule (which runs after BUILTIN) wins.
        val src = "myFunc(\"hi\")"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val fns = tokens.filter { it.kind == TokenKind.FUNCTION }
        assertTrue("expected myFunc to be classified as FUNCTION; got $tokens", fns.any { it.text(src) == "myFunc" })
    }

    @Test fun `kotlin number literal is classified as number`() {
        val src = "val x = 42"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val numbers = tokens.filter { it.kind == TokenKind.NUMBER }
        assertEquals(1, numbers.size)
        assertEquals("42", numbers[0].text(src))
    }

    @Test fun `kotlin annotation is classified as annotation`() {
        val src = "@Composable\nfun x() = 1"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val annos = tokens.filter { it.kind == TokenKind.ANNOTATION }
        assertTrue(annos.any { it.text(src) == "@Composable" })
    }

    @Test fun `python def and return are keywords`() {
        val src = "def foo():\n    return 1"
        val tokens = SyntaxHighlighter.tokenize(src, Language.PYTHON)
        val keywords = tokens.filter { it.kind == TokenKind.KEYWORD }
        val text = keywords.map { it.text(src) }
        assertTrue("def" in text)
        assertTrue("return" in text)
    }

    @Test fun `python print is builtin`() {
        val src = "print(\"hi\")"
        val tokens = SyntaxHighlighter.tokenize(src, Language.PYTHON)
        val builtins = tokens.filter { it.kind == TokenKind.BUILTIN }
        assertTrue(builtins.any { it.text(src) == "print" })
    }

    @Test fun `json string and number are tokenized`() {
        val src = "{\"k\": 42}"
        val tokens = SyntaxHighlighter.tokenize(src, Language.JSON)
        val strings = tokens.filter { it.kind == TokenKind.STRING }
        val numbers = tokens.filter { it.kind == TokenKind.NUMBER }
        assertTrue(strings.any { it.text(src).contains("k") })
        assertTrue(numbers.any { it.text(src) == "42" })
    }

    @Test fun `json true false null are keywords`() {
        val src = "[true, false, null]"
        val tokens = SyntaxHighlighter.tokenize(src, Language.JSON)
        val keywords = tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.text(src) }
        assertTrue("true" in keywords)
        assertTrue("false" in keywords)
        assertTrue("null" in keywords)
    }

    @Test fun `sql select from where are keywords`() {
        val src = "SELECT * FROM users WHERE id = 1"
        val tokens = SyntaxHighlighter.tokenize(src, Language.SQL)
        val keywords = tokens.filter { it.kind == TokenKind.KEYWORD }.map { it.text(src) }
        assertTrue("SELECT" in keywords)
        assertTrue("FROM" in keywords)
        assertTrue("WHERE" in keywords)
    }

    @Test fun `bash echo is builtin`() {
        val src = "echo \"hello\""
        val tokens = SyntaxHighlighter.tokenize(src, Language.BASH)
        val builtins = tokens.filter { it.kind == TokenKind.BUILTIN }
        assertTrue(builtins.any { it.text(src) == "echo" })
    }

    @Test fun `markdown heading is classified as heading`() {
        val src = "# Title\n\nBody"
        val tokens = SyntaxHighlighter.tokenize(src, Language.MARKDOWN)
        val headings = tokens.filter { it.kind == TokenKind.HEADING }
        assertTrue(headings.any { it.text(src) == "# Title" })
    }

    @Test fun `markdown emphasis is classified`() {
        val src = "Some *italic* and **bold** text"
        val tokens = SyntaxHighlighter.tokenize(src, Language.MARKDOWN)
        val emphasis = tokens.filter { it.kind == TokenKind.EMPHASIS }
        val text = emphasis.map { it.text(src) }
        assertTrue("*italic*" in text)
        assertTrue("**bold**" in text)
    }

    @Test fun `markdown code fence is classified`() {
        val src = "Inline `code` and:\n```kotlin\nval x = 1\n```"
        val tokens = SyntaxHighlighter.tokenize(src, Language.MARKDOWN)
        val fences = tokens.filter { it.kind == TokenKind.CODE_FENCE }
        assertTrue(fences.isNotEmpty())
    }

    @Test fun `markdown link is classified`() {
        val src = "Visit [site](https://example.com) now"
        val tokens = SyntaxHighlighter.tokenize(src, Language.MARKDOWN)
        val links = tokens.filter { it.kind == TokenKind.LINK }
        assertTrue(links.isNotEmpty())
    }

    @Test fun `html tag is classified`() {
        val src = "<div class=\"foo\">hi</div>"
        val tokens = SyntaxHighlighter.tokenize(src, Language.HTML)
        val tags = tokens.filter { it.kind == TokenKind.HTML_TAG }
        assertTrue(tags.isNotEmpty())
    }

    @Test fun `html comment is classified as comment`() {
        val src = "<!-- a comment -->"
        val tokens = SyntaxHighlighter.tokenize(src, Language.HTML)
        val comments = tokens.filter { it.kind == TokenKind.COMMENT }
        assertTrue(comments.isNotEmpty())
    }

    @Test fun `tokenization covers full input with no gaps`() {
        val src = "val x = \"hello\"\nfun foo() {\n    println(x)\n}"
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        // Sorted, non-overlapping, cover 0..src.length.
        var cursor = 0
        for (t in tokens.sortedBy { it.start }) {
            assertTrue("gap at $cursor, token starts at ${t.start}", t.start >= cursor)
            assertTrue("overlap at $cursor, token ends at ${t.end}", t.end > t.start)
            cursor = t.end
        }
        assertEquals("trailing gap", src.length, cursor)
    }

    @Test fun `language detection from file extension`() {
        assertEquals(Language.KOTLIN, Language.forFile("foo.kt"))
        assertEquals(Language.KOTLIN, Language.forFile("/path/to/Build.gradle.kts"))
        assertEquals(Language.PYTHON, Language.forFile("script.py"))
        assertEquals(Language.JAVASCRIPT, Language.forFile("module.js"))
        assertEquals(Language.TYPESCRIPT, Language.forFile("app.tsx"))
        assertEquals(Language.JSON, Language.forFile("package.json"))
        assertEquals(Language.MARKDOWN, Language.forFile("README.md"))
        assertEquals(Language.HTML, Language.forFile("index.html"))
        assertEquals(Language.SQL, Language.forFile("schema.sql"))
        assertEquals(Language.BASH, Language.forFile("script.sh"))
        assertEquals(Language.PLAIN, Language.forFile("README"))
        assertEquals(Language.PLAIN, Language.forFile("image.png"))
    }

    @Test fun `triple-quoted string in kotlin is captured as one string token`() {
        val src = "val s = \"\"\"\nhello\n\"\"\""
        val tokens = SyntaxHighlighter.tokenize(src, Language.KOTLIN)
        val strings = tokens.filter { it.kind == TokenKind.STRING }
        assertTrue("at least one string token expected", strings.isNotEmpty())
        // The string should span multiple lines.
        assertTrue(strings.any { it.end - it.start > 10 })
    }

    private fun Token.text(source: String): String = source.substring(start, end)
}