package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketSigningTest {

    private val key = "phase-1-market-key".toByteArray()
    private val now = Timestamp(1_700_000_000_000L)

    private fun listing(
        id: String = "com.elysium.vanguard:distro:1.0.0",
        name: String = "Test Distro",
        type: MarketListingType = MarketListingType.DISTRO,
    ): MarketListing = MarketListing(
        id = id,
        name = name,
        type = type,
        version = "1.0.0",
        contentHash = ContentHash.of("artifact-$id"),
        signatureKeyId = "publisher:canonical",
        signature = Signature.sign("placeholder", key),
        sizeBytes = 1024L,
        dependencies = emptyList(),
        tags = listOf("test"),
        createdAt = now,
    )

    @Test
    fun `sign produces a listing with a non-empty signature`() {
        val unsigned = listing()
        assertTrue("placeholder signature should not equal a real signature",
            unsigned.signature.value.isNotEmpty())
        val signed = MarketSigning.sign(unsigned, key)
        assertTrue(signed.signature.value.isNotEmpty())
    }

    @Test
    fun `verify returns true for a signed listing under the correct key`() {
        val signed = MarketSigning.sign(listing(), key)
        assertTrue(MarketSigning.verify(signed, key))
    }

    @Test
    fun `verify returns false under the wrong key`() {
        val signed = MarketSigning.sign(listing(), key)
        val wrongKey = "wrong-key".toByteArray()
        assertFalse(MarketSigning.verify(signed, wrongKey))
    }

    @Test
    fun `verify returns false when the listing is tampered`() {
        val signed = MarketSigning.sign(listing(name = "Original Name"), key)
        val tampered = signed.copy(name = "Tampered Name")
        assertFalse(MarketSigning.verify(tampered, key))
    }

    @Test
    fun `verify returns false when the content hash is tampered`() {
        val signed = MarketSigning.sign(listing(), key)
        val tampered = signed.copy(contentHash = ContentHash.of("different-artifact"))
        assertFalse(MarketSigning.verify(tampered, key))
    }

    @Test
    fun `verify returns false when the version is tampered`() {
        val signed = MarketSigning.sign(listing(), key)
        val tampered = signed.copy(version = "2.0.0")
        assertFalse(MarketSigning.verify(tampered, key))
    }

    @Test
    fun `verify returns false when the dependencies are tampered`() {
        val signed = MarketSigning.sign(listing(), key)
        val tampered = signed.copy(dependencies = listOf("evil-dep"))
        assertFalse(MarketSigning.verify(tampered, key))
    }

    @Test
    fun `signing different listings with the same key produces different signatures`() {
        val a = MarketSigning.sign(listing(id = "a"), key)
        val b = MarketSigning.sign(listing(id = "b"), key)
        assertNotEquals(a.signature, b.signature)
    }

    @Test
    fun `signing the same listing twice produces the same signature (deterministic)`() {
        val l = listing()
        val a = MarketSigning.sign(l, key)
        val b = MarketSigning.sign(l, key)
        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `canonical form excludes the signature field`() {
        val unsigned = listing()
        val signed = MarketSigning.sign(unsigned, key)
        // The canonical form should not include the signature;
        // therefore the canonical form of signed and unsigned is equal.
        assertEquals(unsigned.canonicalForm(), signed.canonicalForm())
    }

    @Test
    fun `canonical form includes the type as enum name`() {
        val distro = listing(type = MarketListingType.DISTRO)
        val app = listing(type = MarketListingType.APP)
        assertTrue(distro.canonicalForm().contains("type=DISTRO"))
        assertTrue(app.canonicalForm().contains("type=APP"))
    }
}
