package com.elysium.vanguard.core.runtime.distros.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the RootfsSigner contract and its concrete
 * implementations. The Android Keystore factory is exercised for
 * the 'available' probe and the 'fallback to HMAC' path; the
 * actual Keystore key generation is Android-only and lives in
 * the instrumented test suite.
 */
class RootfsSignerTest {

    @Test
    fun `HmacRootfsSigner produces a deterministic base64 signature`() {
        val signer = HmacRootfsSigner(keyAlias = "elysium-test")
        val data = "the quick brown fox".toByteArray(Charsets.UTF_8)
        val a = signer.sign(data)
        val b = signer.sign(data)
        assertEquals(a, b)
        assertEquals(44, a.length) // 32 bytes -> 44 base64 chars
        assertEquals("HmacSHA256", signer.algorithm)
    }

    @Test
    fun `HmacRootfsSigner verify accepts a fresh signature`() {
        val signer = HmacRootfsSigner(keyAlias = "elysium-test")
        val data = "verify me".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(data)
        assertTrue(signer.verify(data, sig))
    }

    @Test
    fun `HmacRootfsSigner verify rejects a tampered signature`() {
        val signer = HmacRootfsSigner(keyAlias = "elysium-test")
        val data = "verify me".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(data)
        val tampered = sig.replaceFirst('a', 'b')
        assertFalse(signer.verify(data, tampered))
    }

    @Test
    fun `HmacRootfsSigner verify rejects tampered data`() {
        val signer = HmacRootfsSigner(keyAlias = "elysium-test")
        val sig = signer.sign("alpha".toByteArray())
        assertFalse(signer.verify("beta".toByteArray(), sig))
    }

    @Test
    fun `HmacRootfsSigner has no public key`() {
        val signer = HmacRootfsSigner(keyAlias = "elysium-test")
        assertNull(signer.publicKeyBase64())
    }

    @Test
    fun `RsaRootfsSigner roundtrips data through sign and verify`() {
        val keyPair = RsaRootfsSigner.generateKeyPair()
        val signer = RsaRootfsSigner(keyPair)
        val data = "roundtrip".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(data)
        assertEquals("SHA256withRSA", signer.algorithm)
        assertTrue(signer.verify(data, sig))
        assertNotNull(signer.publicKeyBase64())
    }

    @Test
    fun `RsaRootfsSigner verify fails with a different public key`() {
        val signer = RsaRootfsSigner(RsaRootfsSigner.generateKeyPair())
        val otherSigner = RsaRootfsSigner(RsaRootfsSigner.generateKeyPair())
        val data = "roundtrip".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(data)
        assertFalse(otherSigner.verify(data, sig))
    }

    @Test
    fun `RsaRootfsSigner verify rejects tampered data`() {
        val signer = RsaRootfsSigner(RsaRootfsSigner.generateKeyPair())
        val sig = signer.sign("alpha".toByteArray())
        assertFalse(signer.verify("beta".toByteArray(), sig))
    }

    @Test
    fun `RsaRootfsSigner public key is base64 X509 SPKI`() {
        val signer = RsaRootfsSigner(RsaRootfsSigner.generateKeyPair())
        val pub = signer.publicKeyBase64()
        assertNotNull(pub)
        // RSA-2048 SPKI is 294 bytes -> 392 base64 chars.
        assertTrue(pub!!.length in 380..400)
    }

    @Test
    fun `AndroidKeystoreRootfsSigner reports available=false on JVM`() {
        // The Keystore provider only exists on Android. On JVM the
        // probe must return false and the factory must fall back to
        // HMAC.
        assertFalse(AndroidKeystoreRootfsSigner.available())
        val signer = AndroidKeystoreRootfsSigner.create(preferKeystore = true)
        // Fallback: HMAC.
        assertEquals("HmacSHA256", signer.algorithm)
    }

    @Test
    fun `AndroidKeystoreRootfsSigner preferKeystore=false returns HMAC directly`() {
        val signer = AndroidKeystoreRootfsSigner.create(preferKeystore = false)
        assertEquals("HmacSHA256", signer.algorithm)
    }
}
