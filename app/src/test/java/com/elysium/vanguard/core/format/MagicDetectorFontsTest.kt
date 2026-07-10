package com.elysium.vanguard.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 9.7.9 — Tests for additional font formats.
 */
class MagicDetectorFontsTest {

    private val detector = MagicDetector()

    @Test
    fun `TTC magic ttcf detects as TrueType Collection`() {
        val head = "ttcf\u0000\u0001\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.TTC, r.kind)
    }

    @Test
    fun `TTF magic still detects as TTF`() {
        val head = byteArrayOf(0x00, 0x01, 0x00, 0x00,
            0x00, 0x0A, 0x00, 0x80.toByte())
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.TTF, r.kind)
    }

    @Test
    fun `OTF OTTO magic still detects as OTF`() {
        val head = "OTTO\u0000\u0006\u0000\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.OTF, r.kind)
    }

    @Test
    fun `WOFF2 magic still detects as WOFF2`() {
        val head = "wOF2\u0000\u0001\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.WOFF2, r.kind)
    }
}