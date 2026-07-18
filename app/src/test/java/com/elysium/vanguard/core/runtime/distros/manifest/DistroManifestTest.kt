package com.elysium.vanguard.core.runtime.distros.manifest

import com.elysium.vanguard.core.runtime.distros.layer.ManifestSigner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair

/**
 * Phase 51 — tests for [DistroManifest] +
 * [DistroManifestCodec] + the codec's
 * invariants.
 *
 * The manifest is the contract between the
 * build-pipeline side (which has the offline
 * signing key) and the device (which has the
 * public key shipped in the APK). The tests
 * pin:
 *
 *   - The [DistroManifest] init-block
 *     invariants (id / version / sha256 not
 *     blank, sha256 is 64 lowercase hex
 *     chars, sizeBytes > 0, signedAtMs > 0,
 *     signature is 64 bytes).
 *   - The codec's round-trip: encode a
 *     manifest body, decode it back, the
 *     result equals the original (modulo the
 *     signature, which is supplied
 *     separately).
 *   - The codec rejects a malformed JSON
 *     body (missing fields, non-positive
 *     values).
 *   - The signature is a separate file: the
 *     encoded body is the same regardless of
 *     the signature.
 */
class DistroManifestTest {

    private fun makeKeyPair(): KeyPair = ManifestSigner.generateKeyPair()

    private fun makeManifest(
        id: String = "debian-12",
        version: String = "12.4",
        sha256: String = "9".repeat(64),
        sizeBytes: Long = 1024L,
        signedAtMs: Long = 1_700_000_000_000L,
        signature: ByteArray = ByteArray(64) { 0x42 },
        bodyBytes: ByteArray = """{"id":"debian-12","version":"12.4","sha256":"9"}""".toByteArray()
    ) = DistroManifest(
        id = id,
        version = version,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        signedAtMs = signedAtMs,
        signature = signature,
        bodyBytes = bodyBytes
    )

    // --- init-block invariants ---

    @Test
    fun `DistroManifest rejects a blank id`() {
        try {
            makeManifest(id = "")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a blank version`() {
        try {
            makeManifest(version = "")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a blank sha256`() {
        try {
            makeManifest(sha256 = "")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a sha256 that is not 64 chars`() {
        try {
            makeManifest(sha256 = "abc")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a sha256 with non-hex characters`() {
        try {
            makeManifest(sha256 = "Z".repeat(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a sha256 with uppercase hex characters`() {
        try {
            // The runtime contract is lowercase
            // hex; uppercase would be ambiguous
            // and is rejected.
            makeManifest(sha256 = "A".repeat(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a non-positive sizeBytes`() {
        try {
            makeManifest(sizeBytes = 0L)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
        try {
            makeManifest(sizeBytes = -1L)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a non-positive signedAtMs`() {
        try {
            makeManifest(signedAtMs = 0L)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects a signature that is not 64 bytes`() {
        try {
            makeManifest(signature = ByteArray(32))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
        try {
            makeManifest(signature = ByteArray(65))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `DistroManifest rejects empty bodyBytes`() {
        try {
            makeManifest(bodyBytes = ByteArray(0))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    // --- equals / hashCode ---

    @Test
    fun `DistroManifest equals uses content equality on the signature bytes`() {
        val keyPair = makeKeyPair()
        val manifestBytes = """{"id":"debian-12","version":"12.4"}""".toByteArray()
        val signature = ManifestSigner.sign(manifestBytes, keyPair.private)
        val a = DistroManifest(
            id = "debian-12",
            version = "12.4",
            sha256 = "a".repeat(64),
            sizeBytes = 100L,
            signedAtMs = 1_700_000_000_000L,
            signature = signature,
            bodyBytes = manifestBytes
        )
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `DistroManifest does not equal a manifest with a different signature`() {
        val a = makeManifest()
        val b = a.copy(signature = ByteArray(64) { 0x99.toByte() })
        assertNotEquals(a, b)
    }

    // --- codec round-trip ---

    @Test
    fun `codec round-trip preserves every field including the signature bytes`() {
        val keyPair = makeKeyPair()
        val body = """{"id":"debian-12","version":"12.4","sha256":"abcdef0123456789","sizeBytes":268435456,"signedAtMs":1700000000000}"""
        // Build a manifest whose body is `body`
        // and whose signature is over `body`.
        // Then encode the body from the manifest
        // (canonicalised by JSONObject) and
        // verify the decoded body matches the
        // manifest's body bytes — note that
        // JSONObject may re-serialise with
        // different whitespace / key order, so
        // the round-trip uses the same canonical
        // body for both.
        val canonical = DistroManifestCodec.encodeBody(
            DistroManifest(
                id = "debian-12",
                version = "12.4",
                sha256 = "abcdef0123456789".repeat(4),
                sizeBytes = 268_435_456L,
                signedAtMs = 1_700_000_000_000L,
                signature = ByteArray(64),
                bodyBytes = body.toByteArray()
            )
        )
        val original = DistroManifest(
            id = "debian-12",
            version = "12.4",
            sha256 = "abcdef0123456789".repeat(4),
            sizeBytes = 268_435_456L,
            signedAtMs = 1_700_000_000_000L,
            signature = ManifestSigner.sign(canonical.toByteArray(), keyPair.private),
            bodyBytes = canonical.toByteArray()
        )
        val decoded = DistroManifestCodec.decode(canonical, original.signature)
        assertEquals(original, decoded)
        assertArrayEquals(original.signature, decoded.signature)
    }

    @Test
    fun `encoded body is JSON without the signature field`() {
        val manifest = makeManifest()
        val encoded = DistroManifestCodec.encodeBody(manifest)
        assertTrue(
            "encoded body should not include the signature: $encoded",
            !encoded.contains("signature")
        )
    }

    @Test
    fun `decode rejects a JSON body missing id`() {
        val body = """{"version":"12.4","sha256":"${"a".repeat(64)}","sizeBytes":100,"signedAtMs":1000}"""
        try {
            DistroManifestCodec.decode(body, ByteArray(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `decode rejects a JSON body missing sha256`() {
        val body = """{"id":"debian-12","version":"12.4","sizeBytes":100,"signedAtMs":1000}"""
        try {
            DistroManifestCodec.decode(body, ByteArray(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `decode rejects a non-positive sizeBytes`() {
        val body = """{"id":"debian-12","version":"12.4","sha256":"${"a".repeat(64)}","sizeBytes":0,"signedAtMs":1000}"""
        try {
            DistroManifestCodec.decode(body, ByteArray(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `decode rejects a non-positive signedAtMs`() {
        val body = """{"id":"debian-12","version":"12.4","sha256":"${"a".repeat(64)}","sizeBytes":100,"signedAtMs":0}"""
        try {
            DistroManifestCodec.decode(body, ByteArray(64))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }
}
