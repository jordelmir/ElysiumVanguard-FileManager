package com.elysium.vanguard.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 9.7.8 — Tests for the scientific + WebAssembly sniffer.
 */
class MagicDetectorScientificTest {

    private val detector = MagicDetector()

    @Test
    fun `HDF5 magic detects as HDF5`() {
        val head = byteArrayOf(0x89.toByte(), 0x48, 0x44, 0x46,
            0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.HDF5, r.kind)
    }

    @Test
    fun `NetCDF3 classic CDF magic detects as NetCDF3`() {
        val head = "CDF\u0001\u0000\u0000\u0000".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.NETCDF3, r.kind)
    }

    @Test
    fun `WASM zero-asm magic detects as WebAssembly`() {
        val head = byteArrayOf(0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00)
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.WASM, r.kind)
    }

    @Test
    fun `FITS SIMPLE header still detects as FITS`() {
        val head = "SIMPLE  =                    T".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.FITS, r.kind)
    }
}