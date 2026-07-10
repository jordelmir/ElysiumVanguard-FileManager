package com.elysium.vanguard.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 9.7.4 — Tests for the subtitle sniffer.
 */
class MagicDetectorSubtitleTest {

    private val detector = MagicDetector()

    @Test
    fun `WEBVTT header detects as WebVTT`() {
        val head = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello world".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.WEBVTT, r.kind)
    }

    @Test
    fun `SRT with classic timestamp arrow detects as SubRip`() {
        val head = "1\n00:00:01,000 --> 00:00:02,000\nHello".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.SRT, r.kind)
    }

    @Test
    fun `ASS file with Script Info header detects as ASS`() {
        val head = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.ASS_WITHOUT_BOM, r.kind)
    }

    @Test
    fun `SSA-style V4 Styles header detects as ASS`() {
        val head = "[V4 Styles]\nFormat: Name, Fontname".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.ASS_WITHOUT_BOM, r.kind)
    }

    @Test
    fun `non-subtitle text is not detected as subtitle`() {
        val head = "The quick brown fox jumps over the lazy dog.\n".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.TEXT_PLAIN, r.kind)
    }

    @Test
    fun `SRT with leading whitespace still detects`() {
        val head = "\n\n3\n00:00:10,500 --> 00:00:12,000\nHi".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.SRT, r.kind)
    }
}