package com.elysium.vanguard.core.runtime.distros.layer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 12.4 — signer / verifier tests.
 *
 * Each test generates a fresh Ed25519 keypair in the test
 * setup (no fixed keys shipped in source). We verify:
 *
 *   - The sign / verify round-trip works.
 *   - A tampered manifest is rejected.
 *   - A wrong public key is rejected.
 *   - A truncated or extended signature is rejected.
 *   - Public key import / export round-trips (X.509 SPKI).
 *   - Private key import / export round-trips (PKCS#8).
 *   - signToFile / verifyFile round-trip on real disk.
 *   - An unsigned manifest (missing `.sig` file) is rejected.
 */
class ManifestSignerVerifierTest {

    @Test
    fun `sign and verify round-trip`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val manifest = """{"version":1,"layers":[]}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, keyPair.private)
        assertEquals(
            "Ed25519 signatures are always 64 bytes",
            64,
            signature.size
        )
        assertTrue(
            ManifestVerifier.verify(manifest, signature, keyPair.public)
        )
    }

    @Test
    fun `a tampered manifest is rejected`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val original = """{"version":1,"channel":"stable"}""".toByteArray()
        val signature = ManifestSigner.sign(original, keyPair.private)
        // Tamper: change one byte in the manifest.
        val tampered = original.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertFalse(
            "tampered manifest must fail verification",
            ManifestVerifier.verify(tampered, signature, keyPair.public)
        )
    }

    @Test
    fun `a wrong public key is rejected`() {
        val signing = ManifestSigner.generateKeyPair()
        val attacker = ManifestSigner.generateKeyPair()
        val manifest = """{"version":1}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, signing.private)
        assertFalse(
            "manifest signed by a different key must not verify with the wrong public key",
            ManifestVerifier.verify(manifest, signature, attacker.public)
        )
    }

    @Test
    fun `a truncated signature is rejected`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val manifest = """{"version":1}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, keyPair.private)
        val truncated = signature.copyOfRange(0, 32)
        try {
            ManifestVerifier.verify(manifest, truncated, keyPair.public)
            fail("expected IllegalArgumentException for a 32-byte signature")
        } catch (expected: IllegalArgumentException) {
            // The require() guard fires; the signature is too short.
        }
    }

    @Test
    fun `an extended signature is rejected`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val manifest = """{"version":1}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, keyPair.private)
        val extended = signature + ByteArray(32)
        try {
            ManifestVerifier.verify(manifest, extended, keyPair.public)
            fail("expected IllegalArgumentException for a 96-byte signature")
        } catch (expected: IllegalArgumentException) {
            // The size guard fires for any length != 64. The
            // verifier would also reject it, but failing fast on
            // the size mismatch is cleaner.
        }
    }

    @Test
    fun `public key export and import round-trips`() {
        val original = ManifestSigner.generateKeyPair()
        val exported = ManifestSigner.exportPublic(original.public)
        val imported = ManifestSigner.importPublic(exported)
        // A round-tripped key verifies the same signature the
        // original did.
        val manifest = """{"version":1}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, original.private)
        assertTrue(ManifestVerifier.verify(manifest, signature, imported))
    }

    @Test
    fun `private key export and import round-trips`() {
        val original = ManifestSigner.generateKeyPair()
        val exported = ManifestSigner.exportPrivate(original.private)
        val imported = ManifestSigner.importPrivate(exported)
        val manifest = """{"version":1}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, imported)
        assertTrue(ManifestVerifier.verify(manifest, signature, original.public))
    }

    @Test
    fun `exported public key bytes are stable for a given key`() {
        val key = ManifestSigner.generateKeyPair()
        val a = ManifestSigner.exportPublic(key.public)
        val b = ManifestSigner.exportPublic(key.public)
        assertArrayEquals(
            "exporting the same public key twice must yield the same bytes",
            a,
            b
        )
    }

    @Test
    fun `two independently generated keypairs are not equal`() {
        val a = ManifestSigner.generateKeyPair()
        val b = ManifestSigner.generateKeyPair()
        assertNotEquals(
            "different keypairs must produce different public-key bytes",
            ManifestSigner.exportPublic(a.public).toList(),
            ManifestSigner.exportPublic(b.public).toList()
        )
    }

    @Test
    fun `sign to file and verify file round-trip on real disk`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val dir = Files.createTempDirectory("elysium-sig-test").toFile()
        try {
            val manifest = File(dir, "manifest.json")
            manifest.writeText("""{"version":1,"channel":"stable","layers":[]}""")
            ManifestSigner.signToFile(manifest, keyPair.private)

            val signatureFile = File(dir, "manifest.json.sig")
            assertTrue("signature file must exist on disk", signatureFile.isFile)
            assertEquals(64, signatureFile.length())

            assertTrue(
                ManifestVerifier.verifyFile(manifest, keyPair.public)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `verify file rejects a manifest with no signature`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val dir = Files.createTempDirectory("elysium-sig-test-no-sig").toFile()
        try {
            val manifest = File(dir, "manifest.json")
            manifest.writeText("""{"version":1}""")
            // Deliberately do NOT call signToFile.
            try {
                ManifestVerifier.verifyFile(manifest, keyPair.public)
                fail("expected IOException for a manifest with no .sig file")
            } catch (expected: java.io.IOException) {
                // The error message must mention the missing file
                // so the user can see what went wrong.
                assertTrue(
                    "error must mention the missing signature",
                    expected.message?.contains("signature") == true
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `empty manifest bytes are rejected`() {
        val keyPair = ManifestSigner.generateKeyPair()
        try {
            ManifestSigner.sign(ByteArray(0), keyPair.private)
            fail("expected IllegalArgumentException for empty manifest bytes")
        } catch (expected: IllegalArgumentException) {
            // The require() guard fires; an empty manifest is meaningless.
        }
    }

    @Test
    fun `verify an empty manifest bytes is rejected`() {
        val keyPair = ManifestSigner.generateKeyPair()
        val manifest = """{"version":1}""".toByteArray()
        val signature = ManifestSigner.sign(manifest, keyPair.private)
        try {
            ManifestVerifier.verify(ByteArray(0), signature, keyPair.public)
            fail("expected IllegalArgumentException for empty manifest bytes")
        } catch (expected: IllegalArgumentException) {
            // The require() guard fires; an empty manifest is meaningless.
        }
    }
}
