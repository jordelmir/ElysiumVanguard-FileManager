package com.elysium.vanguard.core.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.8.1 — Tests for the `.elysium.*` document format.
 */
class ElysiumDocumentTest {

    @Test
    fun `round trip preserves body for a Word document`() {
        val original = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(
                font = "Inter",
                fontSizePt = 14,
                theme = "sovereign-light"
            ),
            body = "Hello, Elysium.".toByteArray()
        )
        val bytes = original.toBytes()
        val parsed = ElysiumDocument.fromBytes(bytes)
        assertEquals(original.kind, parsed.kind)
        assertEquals(original.style.font, parsed.style.font)
        assertEquals(original.style.fontSizePt, parsed.style.fontSizePt)
        assertEquals(original.style.theme, parsed.style.theme)
        assertEquals(original.body.toList(), parsed.body.toList())
    }

    @Test
    fun `round trip preserves body for a Sheet document`() {
        val csv = """
            name,age,city
            ada,36,london
            jor,32,sanjose
        """.trimIndent().toByteArray()
        val doc = ElysiumDocument(
            kind = ElysiumDocument.Kind.SHEET,
            style = ElysiumDocument.StyleHints(),
            body = csv
        )
        val parsed = ElysiumDocument.fromBytes(doc.toBytes())
        assertEquals(ElysiumDocument.Kind.SHEET, parsed.kind)
        assertEquals(csv.toList(), parsed.body.toList())
    }

    @Test
    fun `round trip preserves body for a Deck document`() {
        val slidesJson = """[{"title":"Intro","body":"Welcome"}]""".toByteArray()
        val doc = ElysiumDocument(
            kind = ElysiumDocument.Kind.DECK,
            style = ElysiumDocument.StyleHints(fontSizePt = 24),
            body = slidesJson
        )
        val parsed = ElysiumDocument.fromBytes(doc.toBytes())
        assertEquals(ElysiumDocument.Kind.DECK, parsed.kind)
        assertEquals(slidesJson.toList(), parsed.body.toList())
        assertEquals(24, parsed.style.fontSizePt)
    }

    @Test
    fun `extracted ZIP contains manifest, style, and payload`() {
        val body = "My note.".toByteArray()
        val doc = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = body
        )
        val tmp = Files.createTempFile("elysium-doc", ".elysium.word")
        try {
            doc.writeTo(tmp.toFile())
            val zis = java.util.zip.ZipInputStream(tmp.toFile().inputStream())
            val names = ArrayList<String>()
            while (true) {
                val e = zis.nextEntry ?: break
                names += e.name
                zis.closeEntry()
            }
            zis.close()
            assertTrue("manifest.json should be in zip", names.contains("manifest.json"))
            assertTrue("style.json should be in zip", names.contains("style.json"))
            assertTrue("body.txt should be in zip", names.contains("body.txt"))
        } finally {
            tmp.toFile().delete()
        }
    }

    @Test
    fun `style JSON carries extras when present`() {
        val style = ElysiumDocument.StyleHints(
            extras = mapOf("lineHeight" to "1.5", "accentColor" to "#61AFEF")
        )
        val json = style.toJson()
        assertTrue(json.contains("\"extras\":{"))
        assertTrue(json.contains("\"lineHeight\":\"1.5\""))
        assertTrue(json.contains("\"accentColor\":\"#61AFEF\""))
    }

    @Test
    fun `style JSON omits extras block when empty`() {
        val json = ElysiumDocument.StyleHints().toJson()
        assertTrue(!json.contains("\"extras\":"))
    }

    @Test
    fun `kind extensions match the Phase plan`() {
        assertEquals(".elysium.word", ElysiumDocument.Kind.WORD.extension)
        assertEquals(".elysium.sheet", ElysiumDocument.Kind.SHEET.extension)
        assertEquals(".elysium.deck", ElysiumDocument.Kind.DECK.extension)
    }

    @Test
    fun `unzip and rezip is round-trip safe`() {
        val original = ElysiumDocument(
            kind = ElysiumDocument.Kind.DECK,
            style = ElysiumDocument.StyleHints(
                font = "JetBrains Mono",
                fontSizePt = 32,
                theme = "sovereign-terminal",
                extras = mapOf("align" to "left", "bold" to "true")
            ),
            body = """[{"title":"hi","body":"again"}]""".toByteArray()
        )
        val tmp = Files.createTempFile("elysium-deck", ".elysium.deck").toFile()
        try {
            original.writeTo(tmp)
            // Re-unzip and re-zip using stock java tools
            val extracted = java.util.zip.ZipInputStream(tmp.inputStream()).use { zis ->
                val entries = HashMap<String, ByteArray>()
                while (true) {
                    val e = zis.nextEntry ?: break
                    val bytes = zis.readBytes()
                    entries[e.name] = bytes
                    zis.closeEntry()
                }
                entries
            }
            val roundtripBytes = java.io.ByteArrayOutputStream().use { baos ->
                java.util.zip.ZipOutputStream(baos).use { zos ->
                    for ((name, data) in extracted) {
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        zos.write(data)
                        zos.closeEntry()
                    }
                }
                baos.toByteArray()
            }
            val parsed = ElysiumDocument.fromBytes(roundtripBytes)
            assertEquals(original.kind, parsed.kind)
            assertEquals(original.body.toList(), parsed.body.toList())
        } finally {
            tmp.delete()
        }
    }
}
