package com.elysium.vanguard.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFilterParserTest {

    private val parser = FileFilterParser()

    @Test
    fun `extension filter only matches files`() {
        val f = parser.parse("ext:pdf")
        assertEquals(setOf("pdf"), f.extensions)
        assertEquals(FileFilterParser.TypeFilter.ANY, f.type)
    }

    @Test
    fun `multiple extensions parsed`() {
        val f = parser.parse("ext:pdf,docx,txt")
        assertEquals(setOf("pdf", "docx", "txt"), f.extensions)
    }

    @Test
    fun `size greater than`() {
        val f = parser.parse("size:>5MB")
        assertEquals(5L * 1024 * 1024 + 1, f.minSize)
        assertEquals(null, f.maxSize)
    }

    @Test
    fun `size less than equal`() {
        val f = parser.parse("size:<=100KB")
        assertEquals(100L * 1024, f.maxSize)
    }

    @Test
    fun `type image parses to image`() {
        val f = parser.parse("type:image")
        assertEquals(FileFilterParser.TypeFilter.IMAGE, f.type)
    }

    @Test
    fun `free text becomes nameContains`() {
        val f = parser.parse("hello world")
        assertEquals("hello world", f.nameContains)
    }

    @Test
    fun `name colon prefix overrides free text`() {
        val f = parser.parse("name:report type:doc ext:pdf")
        assertEquals("report", f.nameContains)
        assertEquals(FileFilterParser.TypeFilter.DOC, f.type)
        assertEquals(setOf("pdf"), f.extensions)
    }

    @Test
    fun `regex name filter compiles`() {
        val f = parser.parse("name~:^IMG_[0-9]+\\.jpg$")
        assertTrue(f.nameRegex != null)
        assertTrue(f.nameRegex!!.containsMatchIn("IMG_12345.jpg"))
        assertFalse(f.nameRegex!!.containsMatchIn("photo.jpg"))
    }

    @Test
    fun `invalid regex is silently dropped`() {
        val f = parser.parse("name~:[unclosed")
        // No exception; regex stays null.
        assertEquals(null, f.nameRegex)
    }

    @Test
    fun `last_week filter parses to range`() {
        val before = System.currentTimeMillis()
        val f = parser.parse("modified:last_week")
        val after = System.currentTimeMillis()
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        // The filter window for "last_week" is 7 days back to "now"; accept
        // any timestamp that is at most 7 days old (some slack on the upper
        // bound to allow for the small delta between before/after capture).
        assertNotNull("modifiedAfterMs should be set", f.modifiedAfterMs)
        val afterMs = f.modifiedAfterMs!!
        assertTrue(
            "modifiedAfterMs=$afterMs should be within 7 days of now (between ${after - sevenDays} and $before)",
            afterMs in (after - sevenDays - 100)..(before + 100)
        )
        assertEquals(null, f.modifiedBeforeMs)
    }

    @Test
    fun `absolute date parses both bounds`() {
        val f = parser.parse("modified:2024-03-15")
        assertTrue(f.modifiedAfterMs!! > 0L)
        assertTrue(f.modifiedBeforeMs!! > f.modifiedAfterMs!!)
    }
}