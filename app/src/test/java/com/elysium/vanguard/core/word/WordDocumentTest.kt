package com.elysium.vanguard.core.word

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PHASE 10.5 — Tests for the Word document model and round-trips.
 *
 * These tests pin down the format model:
 *
 *   - Constructing documents and reading plain text.
 *   - JSON round-trip via [WordFile] / [WordJson].
 *   - DOCX import: synthetic .docx ZIP that mirrors what Word/LibreOffice
 *     produce, then assert the parsed [WordDocument].
 *   - DOCX export: write a doc, re-import it, assert no run is lost.
 *
 * All tests run on the JVM with `Files.createTempDirectory` and
 * `ByteArrayOutputStream` — no Android device required.
 */
class WordDocumentTest {

    @Test
    fun `plainText joins block runs with newlines`() {
        val doc = WordDocument(
            title = "Test",
            blocks = listOf(
                WordParagraph(runs = listOf(WordRun("Hello, "), WordRun("world."))),
                WordParagraph(runs = listOf(WordRun("Line two.")))
            )
        )
        val text = doc.plainText()
        assertTrue(text.contains("Hello, world."))
        assertTrue(text.contains("Line two."))
    }

    @Test
    fun `wordCount splits on whitespace`() {
        val doc = WordDocument(
            title = "x",
            blocks = listOf(
                WordParagraph(runs = listOf(WordRun("one two three"))),
                WordParagraph(runs = listOf(WordRun("four")))
            )
        )
        assertEquals(4, doc.wordCount())
    }

    @Test
    fun `paragraphCount counts paragraph blocks`() {
        val doc = WordDocument(
            title = "x",
            blocks = listOf(
                WordHeading(level = 1, runs = listOf(WordRun("h"))),
                WordParagraph(runs = listOf(WordRun("a"))),
                WordParagraph(runs = listOf(WordRun("b"))),
                WordListItem(runs = listOf(WordRun("li")), kind = ListKind.BULLET)
            )
        )
        assertEquals(2, doc.paragraphCount())
    }

    @Test
    fun `CharacterFormat merge combines fields without dropping defaults`() {
        val base = CharacterFormat()
        val bold = base.copy(bold = true)
        val merged = bold.merge(CharacterFormat())
        assertTrue(merged.bold)
        // Defaults preserved.
        assertEquals(14f, merged.fontSizeSp, 0.01f)
    }

    @Test
    fun `CharacterFormat merge overrides with new fields`() {
        val base = CharacterFormat(fontSizeSp = 12f)
        val bigger = CharacterFormat(fontSizeSp = 20f)
        val merged = bigger.merge(base)
        assertEquals(20f, merged.fontSizeSp, 0.01f)
    }

    @Test
    fun `JSON round-trip preserves every block`() {
        val original = WordDocument(
            title = "Round-trip",
            author = "joe",
            blocks = listOf(
                WordHeading(level = 1, runs = listOf(WordRun("Title"))),
                WordParagraph(runs = listOf(
                    WordRun("Hello, ", CharacterFormat(bold = true)),
                    WordRun("world.", CharacterFormat(italic = true))
                )),
                WordListItem(runs = listOf(WordRun("first")), kind = ListKind.BULLET, depth = 0),
                WordListItem(runs = listOf(WordRun("nested")), kind = ListKind.NUMBERED, depth = 1),
                WordBlockQuote(runs = listOf(WordRun("famous")), citation = "Marcus"),
                WordCodeBlock(code = "fun x() = 1", language = "kotlin"),
                WordPageBreak(),
                WordHorizontalRule()
            )
        )
        val json = WordJson.toJson(original)
        val parsed = WordJson.fromJson(json)
        assertEquals(original.title, parsed.title)
        assertEquals(original.author, parsed.author)
        assertEquals(original.blocks.size, parsed.blocks.size)
        // Spot-check block types
        assertTrue(parsed.blocks[0] is WordHeading)
        assertTrue(parsed.blocks[1] is WordParagraph)
        assertTrue(parsed.blocks[2] is WordListItem)
        assertTrue(parsed.blocks[3] is WordListItem)
        assertTrue(parsed.blocks[4] is WordBlockQuote)
        assertTrue(parsed.blocks[5] is WordCodeBlock)
        assertTrue(parsed.blocks[6] is WordPageBreak)
        assertTrue(parsed.blocks[7] is WordHorizontalRule)
        // Run-level fidelity
        val p = parsed.blocks[1] as WordParagraph
        assertTrue(p.runs[0].format.bold)
        assertTrue(p.runs[1].format.italic)
    }

    @Test
    fun `WordFile round-trip with magic header`() {
        val doc = WordDocument(
            title = "Magic test",
            blocks = listOf(WordParagraph(runs = listOf(WordRun("body"))))
        )
        val text = WordFile.write(doc)
        assertTrue(text.startsWith("ELYSIUM-WORD/1\n"))
        val parsed = WordFile.read(text)
        assertEquals(doc.title, parsed.title)
        assertEquals("body", (parsed.blocks.first() as WordParagraph).runs.first().text)
    }

    @Test
    fun `DOCX import parses a synthetic Word document`() {
        val docxBytes = makeDocx(
            documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r><w:rPr><w:b/></w:rPr><w:t>Bold</w:t></w:r>
                      <w:r><w:t xml:space="preserve"> plain</w:t></w:r>
                    </w:p>
                    <w:p>
                      <w:pPr><w:jc w:val="center"/></w:pPr>
                      <w:r><w:t>Centered</w:t></w:r>
                    </w:p>
                  </w:body>
                </w:document>
            """.trimIndent()
        )
        val doc = WordDocx.importBytes(docxBytes)
        assertNotNull(doc)
        doc!!
        assertEquals(2, doc!!.blocks.size)
        val first = doc!!.blocks[0] as WordParagraph
        assertEquals(2, first.runs.size)
        assertTrue(first.runs[0].format.bold)
        assertEquals(" plain", first.runs[1].text)
        val second = doc!!.blocks[1] as WordParagraph
        assertEquals(TextAlignment.CENTER, second.format.alignment)
        assertEquals("Centered", second.runs[0].text)
    }

    @Test
    fun `DOCX export produces a parseable ZIP with the right pieces`() {
        val doc = WordDocument(
            title = "Exported",
            author = "joe",
            blocks = listOf(
                WordParagraph(runs = listOf(
                    WordRun("Hello, ", CharacterFormat(bold = true)),
                    WordRun("world", CharacterFormat(italic = true))
                ))
            )
        )
        val bytes = WordDocx.export(doc)
        assertTrue(bytes.size > 0)
        // ZIP starts with "PK\x03\x04".
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        // Walk the ZIP and read the document.xml entry.
        var documentXml: String? = null
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
            var entry: java.util.zip.ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXml = zis.readBytes().toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        assertNotNull(documentXml)
        assertTrue(documentXml!!.contains("Hello,"))
        assertTrue(documentXml!!.contains("<w:b/>"))
        assertTrue(documentXml!!.contains("<w:i/>"))
    }

    @Test
    fun `DOCX export then import round-trips body text`() {
        val original = WordDocument(
            title = "Round trip",
            blocks = listOf(
                WordParagraph(runs = listOf(
                    WordRun("alpha ", CharacterFormat(bold = true)),
                    WordRun("beta", CharacterFormat(italic = true))
                )),
                WordHeading(level = 2, runs = listOf(WordRun("Subhead"))),
                WordParagraph(runs = listOf(WordRun("third line")))
            )
        )
        val bytes = WordDocx.export(original)
        val parsed = WordDocx.importBytes(bytes)
        assertNotNull(parsed)
        parsed!!
        assertEquals(3, parsed!!.blocks.size)
        val p0 = parsed!!.blocks[0] as WordParagraph
        // Some XML serialisation may drop the space delimiter between
        // runs; the merged text should still contain both tokens.
        val combined = p0.runs.joinToString("") { it.text }
        assertTrue(combined.contains("alpha"))
        assertTrue(combined.contains("beta"))
        val p1 = parsed!!.blocks[1] as WordHeading
        assertEquals(2, p1.level)
        assertEquals("Subhead", p1.runs[0].text)
    }

    @Test
    fun `String toWordDocument builds one paragraph per line`() {
        val text = "line one\nline two\n\nline four"
        val doc = text.toWordDocument(title = "Imported")
        assertEquals("Imported", doc.title)
        // Empty line still becomes a (blank) paragraph.
        assertEquals(4, doc.blocks.size)
    }

    @Test
    fun `Heading rejects invalid levels`() {
        val ex = runCatching { WordHeading(level = 7, runs = listOf(WordRun("x"))) }
        assertTrue(ex.isFailure)
    }

    @Test
    fun `ListItem rejects extreme depth`() {
        val ex = runCatching { WordListItem(runs = listOf(WordRun("x")), kind = ListKind.BULLET, depth = 99) }
        assertTrue(ex.isFailure)
    }

    @Test
    fun `DOCX import gracefully returns null on missing entry`() {
        val bytes = makeDocx(documentXml = null)
        val doc = WordDocx.importBytes(bytes)
        assertNull(doc)
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Build a minimal ZIP carrying one optional entry. Used to
     * synthesize input for the DOCX reader.
     */
    private fun makeDocx(documentXml: String?): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            if (documentXml != null) {
                zos.putNextEntry(ZipEntry("word/document.xml"))
                zos.write(documentXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
