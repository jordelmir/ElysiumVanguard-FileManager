package com.elysium.vanguard.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 9.7.10 — Tests for additional disk + container image formats.
 */
class MagicDetectorDiskImageTest {

    private val detector = MagicDetector()

    @Test
    fun `ISO 9660 magic at offset 32768 detects as ISO 9660`() {
        // Build a 32773-byte head with "CD001" at offset 32768.
        val head = ByteArray(32773)
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(head, 32768)
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.ISO_9660, r.kind)
    }

    @Test
    fun `VHD conectix magic at offset 0 detects as VHD`() {
        val head = "conectix".toByteArray() +
            ByteArray(32)
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.VHD, r.kind)
    }

    @Test
    fun `VMDK KDMV magic at offset 0 detects as VMDK`() {
        val head = "KDMV".toByteArray() + ByteArray(32)
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.VMDK, r.kind)
    }
}