package com.elysium.vanguard.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 9.7.6 — Tests for the lossless audio + extra video containers.
 */
class MagicDetectorLosslessTest {

    private val detector = MagicDetector()

    @Test
    fun `Monkey's Audio MAC prefix detects as APE`() {
        val head = "MAC \u0019\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.MAC, r.kind)
    }

    @Test
    fun `WavPack wvpk prefix detects as WavePack`() {
        val head = "wvpk\u0000\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.WAVEPACK, r.kind)
    }

    @Test
    fun `TTA1 prefix detects as TTA`() {
        val head = "TTA1\u0001\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.TTA, r.kind)
    }

    @Test
    fun `EBML header detects as Matroska`() {
        val head = byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte(),
            0x93.toByte(), 0x42.toByte(), 0x82.toByte(), 0x88.toByte())
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.MKV, r.kind)
    }

    @Test
    fun `FLV magic detects as Flash Video`() {
        val head = "FLV\u0001\u0005\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.FLV, r.kind)
    }
}