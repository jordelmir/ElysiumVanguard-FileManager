package com.elysium.vanguard.core.office

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PHASE 9.16 — Tests for [OfficeImporter].
 *
 * We construct minimal-but-valid `.docx`/`.odt`/`.odp` ZIPs in
 * memory rather than relying on disk fixtures. That keeps the
 * tests self-contained and easy to extend when we add new
 * Office-shaped formats.
 */
class OfficeImporterTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("office-importer-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `non-existent file returns Failure`() {
        val result = OfficeImporter.importToElysium(File(tempDir, "missing.docx"))
        assertTrue(result is OfficeImporter.Result.Failure)
    }

    @Test
    fun `unsupported extension returns Failure`() {
        val f = File(tempDir, "foo.txt").also { it.writeBytes("hi".toByteArray()) }
        val result = OfficeImporter.importToElysium(f)
        assertTrue(result is OfficeImporter.Result.Failure)
        val msg = (result as OfficeImporter.Result.Failure).reason
        assertTrue("got: $msg", msg.contains("Unsupported"))
    }

    @Test
    fun `docx with one paragraph and one text run`() {
        val bytes = buildDocx(
            """
            <?xml version="1.0"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:r><w:t>Hello world</w:t></w:r>
                </w:p>
              </w:body>
            </w:document>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("hello.docx", bytes)
        assertTrue("expected success, was $r", r is OfficeImporter.Result.Success)
        val doc = (r as OfficeImporter.Result.Success).document
        assertEquals(ElysiumDocument.Kind.WORD, doc.kind)
        assertEquals("Hello world", String(doc.body, Charsets.UTF_8))
    }

    @Test
    fun `docx with multiple paragraphs joins with newlines`() {
        val bytes = buildDocx(
            """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>
                <w:p><w:r><w:t>Second</w:t></w:r><w:r><w:t>paragraph</w:t></w:r></w:p>
                <w:p><w:r><w:t>Third line</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("multi.docx", bytes)
        val doc = (r as OfficeImporter.Result.Success).document
        val text = String(doc.body, Charsets.UTF_8)
        assertEquals("First paragraph\nSecond paragraph\nThird line", text)
    }

    @Test
    fun `docx decodes XML entities in text runs`() {
        val bytes = buildDocx(
            """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>A &amp; B &lt; C &gt; D &quot;hi&quot;</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("entities.docx", bytes)
        val doc = (r as OfficeImporter.Result.Success).document
        assertEquals("A & B < C > D \"hi\"", String(doc.body, Charsets.UTF_8))
    }

    @Test
    fun `docx with empty body succeeds but body is empty string`() {
        val bytes = buildDocx(
            """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body/>
            </w:document>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("empty.docx", bytes)
        // Either Success with empty body or Failure with "no paragraphs"
        // is acceptable; we mainly want to ensure no crash.
        when (r) {
            is OfficeImporter.Result.Success -> {
                assertEquals("", String(r.document.body, Charsets.UTF_8))
            }
            is OfficeImporter.Result.Failure -> {
                assertTrue(r.reason.contains("no paragraphs"))
            }
        }
    }

    @Test
    fun `docx missing document xml returns Failure`() {
        // Empty ZIP — no word/document.xml inside.
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.close() }
        }.toByteArray()
        val r = OfficeImporter.importBytesToElysium("bad.docx", bytes)
        assertTrue(r is OfficeImporter.Result.Failure)
        val msg = (r as OfficeImporter.Result.Failure).reason
        assertTrue("got: $msg", msg.contains("missing word/document.xml"))
    }

    @Test
    fun `odt with three paragraphs produces three line body`() {
        val bytes = buildOdt(
            """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">
              <office:body>
                <office:text>
                  <text:p>Hola</text:p>
                  <text:p>mundo</text:p>
                  <text:p>!</text:p>
                </office:text>
              </office:body>
            </office:document-content>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("hola.odt", bytes)
        assertTrue(r is OfficeImporter.Result.Success)
        val doc = (r as OfficeImporter.Result.Success).document
        assertEquals(ElysiumDocument.Kind.WORD, doc.kind)
        assertEquals("Hola\nmundo\n!", String(doc.body, Charsets.UTF_8))
    }

    @Test
    fun `odp with two slides yields a deck with two slides`() {
        val bytes = buildOdp(
            """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                     xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0">
              <office:body>
                <office:presentation>
                  <draw:page>
                    <text:p>Slide one title</text:p>
                    <text:p>Body line one</text:p>
                  </draw:page>
                  <draw:page>
                    <text:p>Slide two title</text:p>
                  </draw:page>
                </office:presentation>
              </office:body>
            </office:document-content>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("talk.odp", bytes)
        assertTrue(r is OfficeImporter.Result.Success)
        val doc = (r as OfficeImporter.Result.Success).document
        assertEquals(ElysiumDocument.Kind.DECK, doc.kind)
        val d = ElysiumDeck.fromJson(doc.body)
        assertEquals(2, d.slides.size)
        assertEquals("Slide one title", d.slides[0].title)
        assertEquals("Body line one", d.slides[0].body)
        assertEquals("Slide two title", d.slides[1].title)
    }

    @Test
    fun `odp with no draw pages produces a single-slide deck fallback`() {
        // An odp with no draw:page would be malformed in real life,
        // but we want the importer to be defensive: produce a
        // single-slide deck so the user sees something.
        val bytes = buildOdp(
            """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                     xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0">
              <office:body>
                <office:presentation>
                  <text:p>Only text, no slides</text:p>
                </office:presentation>
              </office:body>
            </office:document-content>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("nopages.odp", bytes)
        assertTrue(r is OfficeImporter.Result.Success)
        val deck = ElysiumDeck.fromJson((r as OfficeImporter.Result.Success).document.body)
        // The fallback synthesizes a slide; assert we got something
        // usable rather than crashing.
        assertTrue("expected at least 1 slide, got ${deck.slides.size}", deck.slides.isNotEmpty())
    }

    @Test
    fun `Odp with multi-line paragraph body concatenates with newlines`() {
        val bytes = buildOdp(
            """
            <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                     xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                     xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0">
              <office:body>
                <office:presentation>
                  <draw:page>
                    <text:p>Title</text:p>
                    <text:p>Para 1</text:p>
                    <text:p>Para 2</text:p>
                    <text:p>Para 3</text:p>
                  </draw:page>
                </office:presentation>
              </office:body>
            </office:document-content>
            """.trimIndent()
        )
        val r = OfficeImporter.importBytesToElysium("multi.odp", bytes)
        val deck = ElysiumDeck.fromJson((r as OfficeImporter.Result.Success).document.body)
        assertEquals("Title", deck.slides[0].title)
        assertEquals("Para 1\nPara 2\nPara 3", deck.slides[0].body)
    }

    @Test
    fun `importing to a real file round-trips through importToElysium`() {
        val bytes = buildDocx(
            """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>From disk</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.trimIndent()
        )
        val f = File(tempDir, "round.docx").also { it.writeBytes(bytes) }
        val r = OfficeImporter.importToElysium(f)
        assertTrue(r is OfficeImporter.Result.Success)
        val doc = (r as OfficeImporter.Result.Success).document
        assertEquals("From disk", String(doc.body, Charsets.UTF_8))
    }

    // -------- ZIP builders --------

    /**
     * Build a minimal `.docx` ZIP containing the given
     * `word/document.xml` content (and the OOXML content-type
     * registration in `[Content_Types].xml`).
     */
    private fun buildDocx(documentXml: String): ByteArray =
        buildZip(
            "[Content_Types].xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                   <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                     <Default Extension="xml" ContentType="application/xml"/>
                     <Override PartName="/word/document.xml"
                               ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                   </Types>""".trimIndent().toByteArray(),
            "word/document.xml" to documentXml.toByteArray()
        )

    /**
     * Build a minimal `.odt` ZIP containing the given `content.xml`.
     */
    private fun buildOdt(contentXml: String): ByteArray =
        buildZip("content.xml" to contentXml.toByteArray())

    /**
     * Build a minimal `.odp` ZIP containing the given `content.xml`.
     * (ODP zip envelopes are ODF too, so the same content.xml
     * layout works.)
     */
    private fun buildOdp(contentXml: String): ByteArray =
        buildZip("content.xml" to contentXml.toByteArray())

    /**
     * Build a ZIP file from name → bytes pairs. Used by the format
     * builders above so each test can construct its own in-memory
     * fixture.
     */
    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
