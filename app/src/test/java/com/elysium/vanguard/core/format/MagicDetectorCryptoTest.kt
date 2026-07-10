package com.elysium.vanguard.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PHASE 9.7.7 — Tests for the crypto-format sniffer.
 */
class MagicDetectorCryptoTest {

    private val detector = MagicDetector()

    @Test
    fun `PGP public key armor detects as public key`() {
        val head = "-----BEGIN PGP PUBLIC KEY BLOCK-----\n\nmQENBFxxxxx=".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.PGP_PUBLIC, r.kind)
    }

    @Test
    fun `PGP private key armor detects as private key`() {
        val head = "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nlQHYBGFxxx=".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.PGP_PRIVATE, r.kind)
    }

    @Test
    fun `PGP message armor detects as binary packet`() {
        val head = "-----BEGIN PGP MESSAGE-----\n\nhQEMAxxxxx=".toByteArray()
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.PGP_BINARY, r.kind)
    }

    @Test
    fun `X509 DER ASN1 SEQUENCE header detects as DER cert`() {
        val head = byteArrayOf(0x30.toByte(), 0x82.toByte(), 0x01.toByte(), 0x00.toByte())
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.X509_DER, r.kind)
    }

    @Test
    fun `PKCS12 starts with SEQUENCE 0x82 02 prefix`() {
        val head = byteArrayOf(0x30.toByte(), 0x82.toByte(), 0x02.toByte(), 0x01.toByte())
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.PKCS12, r.kind)
    }

    @Test
    fun `PGP binary packet with high bit set detects as binary packet`() {
        // PGP packet header: byte0=0xC3 (tag 0x43, old format)
        // byte1=0x14 (length 20 bytes, single byte length means top bit NOT set)
        val head = byteArrayOf(0xC3.toByte(), 0x14.toByte(), 0x00.toByte(), 0x00.toByte())
        val r = detector.detectFromHead(head)
        assertEquals(MagicDetector.FileKind.PGP_BINARY, r.kind)
    }
}