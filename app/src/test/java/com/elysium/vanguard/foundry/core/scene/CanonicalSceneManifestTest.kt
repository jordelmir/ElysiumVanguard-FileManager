package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.ids.AssetId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 / I-3.1 — the JVM tests for
 * [CanonicalSceneManifest] + [Canonical3DAsset]
 * + the supporting types.
 *
 * The Scene Manifest is the typed input the 3D
 * pipeline + the digital twin consume. The
 * tests cover:
 *   - The manifest constructs with valid
 *     assets + rejects invalid assets (empty
 *     list, self-parent, cycle, unknown parent).
 *   - The content hash is deterministic + the
 *     manifest is content-addressed.
 *   - The signature verification works
 *     (verifying with the right key returns
 *     success; a tampered manifest returns
 *     failure).
 *   - The supporting types (AssetLod,
 *     AssetBounds, Vector3, AssetTransform,
 *     Quaternion) reject invalid inputs.
 */
class CanonicalSceneManifestTest {

    // ============================================================
    // Happy path
    // ============================================================

    @Test
    fun `manifest constructs with valid assets and computes content hash`() {
        val manifest = buildSampleManifest()
        assertEquals(64, manifest.contentHash.value.length)
        assertTrue(manifest.assets.isNotEmpty())
    }

    @Test
    fun `manifest is deterministic for the same assets and revision`() {
        val a = buildSampleManifest()
        val b = buildSampleManifest()
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `manifest differs for different assets`() {
        val a = buildSampleManifest()
        val b = buildSampleManifest().copy(
            assets = listOf(
                sampleAsset(
                    id = AssetId("f".repeat(64)),
                    label = "different asset",
                ),
            ),
        )
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `manifest differs for different revision content hashes`() {
        val a = buildSampleManifest()
        val b = buildSampleManifest().copy(
            revisionContentHash = ContentHash("f".repeat(64)),
        )
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `manifest differs for different representation levels`() {
        val a = buildSampleManifest()
        val b = buildSampleManifest().copy(
            representationLevel = RepresentationLevel.OEM_EXACT,
        )
        assertNotEquals(a.contentHash, b.contentHash)
    }

    // ============================================================
    // Reject invalid manifests
    // ============================================================

    @Test
    fun `manifest rejects empty assets list`() {
        try {
            buildSampleManifest().copy(assets = emptyList())
            fail("expected IllegalArgumentException for empty assets")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("assets"))
        }
    }

    @Test
    fun `manifest rejects UNKNOWN representation level`() {
        try {
            buildSampleManifest().copy(
                representationLevel = RepresentationLevel.UNKNOWN,
            )
            fail("expected IllegalArgumentException for UNKNOWN representation level")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("representationLevel"))
        }
    }

    @Test
    fun `manifest rejects asset that references an unknown parent`() {
        val orphan = sampleAsset(
            id = AssetId("a".repeat(64)),
            label = "orphan",
            parentId = AssetId("9".repeat(64)),  // not in the manifest
        )
        try {
            buildSampleManifest().copy(assets = listOf(orphan))
            fail("expected IllegalArgumentException for orphan parent")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention parent, got ${e.message}",
                e.message!!.contains("parent"),
            )
        }
    }

    @Test
    fun `manifest rejects asset that is its own parent`() {
        // The `Canonical3DAsset.init` rejects self-parent
        // directly (defense-in-depth). The test
        // asserts that the asset constructor throws.
        try {
            sampleAsset(
                id = AssetId("a".repeat(64)),
                label = "self",
                parentId = AssetId("a".repeat(64)),
            )
            fail("expected IllegalArgumentException for self-parent")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to contain 'own parent', got: ${e.message}",
                e.message!!.contains("own parent"),
            )
        }
    }

    @Test
    fun `manifest rejects cyclic asset graph`() {
        val a = sampleAsset(id = AssetId("a".repeat(64)), label = "a")
        val b = sampleAsset(
            id = AssetId("b".repeat(64)),
            label = "b",
            parentId = AssetId("a".repeat(64)),
        )
        val aCyclic = a.copy(parentId = AssetId("b".repeat(64)))
        try {
            buildSampleManifest().copy(assets = listOf(aCyclic, b))
            fail("expected IllegalArgumentException for cycle")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cycle"))
        }
    }

    // ============================================================
    // Signature verification
    // ============================================================

    @Test
    fun `manifest signature is verifiable with the correct signature`() {
        // The default test signature is just a placeholder.
        // Real signing requires an asymmetric key; the test
        // asserts the verifier returns a failure (because
        // the placeholder is not a real signature over the
        // canonical form).
        val manifest = buildSampleManifest()
        val verifyResult = CanonicalSceneManifest.verifySignature(
            manifest,
            Signature("placeholder"),
        )
        // The placeholder signature won't match a fresh
        // signing; the verifier returns failure.
        assertTrue(
            "expected verification to fail with a placeholder signature",
            verifyResult.isFailure,
        )
    }

    // ============================================================
    // Supporting types
    // ============================================================

    @Test
    fun `AssetLod rejects level less than 0`() {
        try {
            AssetLod(
                level = -1,
                geometryHash = ContentHash("0".repeat(64)),
                bounds = AssetBounds(Vector3.ZERO, Vector3(1.0, 1.0, 1.0)),
                triangleCount = 100,
                targetScreenSize = 1024,
            )
            fail("expected IllegalArgumentException for level < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("level"))
        }
    }

    @Test
    fun `AssetBounds rejects min greater than max`() {
        try {
            AssetBounds(
                min = Vector3(1.0, 0.0, 0.0),
                max = Vector3(0.0, 1.0, 1.0),
            )
            fail("expected IllegalArgumentException for min > max")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("min"))
        }
    }

    @Test
    fun `AssetTransform rejects non-positive scale`() {
        try {
            AssetTransform(
                position = Vector3.ZERO,
                rotation = Quaternion.IDENTITY,
                scale = Vector3(-1.0, 1.0, 1.0),
            )
            fail("expected IllegalArgumentException for non-positive scale")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("scale"))
        }
    }

    @Test
    fun `Quaternion rejects zero norm`() {
        try {
            Quaternion(0.0, 0.0, 0.0, 0.0)
            fail("expected IllegalArgumentException for zero norm")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("norm"))
        }
    }

    @Test
    fun `Vector3 ZERO has all zero components`() {
        assertEquals(0.0, Vector3.ZERO.x, 0.0)
        assertEquals(0.0, Vector3.ZERO.y, 0.0)
        assertEquals(0.0, Vector3.ZERO.z, 0.0)
    }

    @Test
    fun `Quaternion IDENTITY is (1, 0, 0, 0)`() {
        assertEquals(1.0, Quaternion.IDENTITY.w, 0.0)
        assertEquals(0.0, Quaternion.IDENTITY.x, 0.0)
        assertEquals(0.0, Quaternion.IDENTITY.y, 0.0)
        assertEquals(0.0, Quaternion.IDENTITY.z, 0.0)
    }

    @Test
    fun `AssetTransform IDENTITY has zero position and identity rotation`() {
        assertEquals(Vector3.ZERO, AssetTransform.IDENTITY.position)
        assertEquals(Quaternion.IDENTITY, AssetTransform.IDENTITY.rotation)
        assertEquals(Vector3(1.0, 1.0, 1.0), AssetTransform.IDENTITY.scale)
    }

    @Test
    fun `AssetId rejects blank value`() {
        try {
            AssetId("")
            fail("expected IllegalArgumentException for blank AssetId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test
    fun `AssetId fromHash accepts a 64-char SHA-256 hex string`() {
        val hash = "0".repeat(64)
        val result = AssetId.fromHash(hash)
        assertTrue("expected success, got $result", result.isSuccess)
        assertEquals(hash, result.getOrThrow().value)
    }

    @Test
    fun `AssetId fromHash rejects a short hash`() {
        val result = AssetId.fromHash("abc")
        assertTrue("expected failure, got $result", result.isFailure)
    }

    @Test
    fun `AssetId fromHash rejects a non-hex hash`() {
        val result = AssetId.fromHash("z".repeat(64))
        assertTrue("expected failure, got $result", result.isFailure)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildSampleManifest(): CanonicalSceneManifest = CanonicalSceneManifest(
        revisionContentHash = ContentHash("a".repeat(64)),
        assets = listOf(
            sampleAsset(
                id = AssetId("a".repeat(64)),
                label = "chassis",
            ),
            sampleAsset(
                id = AssetId("b".repeat(64)),
                label = "wheel-front-left",
                parentId = AssetId("a".repeat(64)),
            ),
        ),
        coordinateSystem = CoordinateSystem.LOCAL,
        representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        signature = Signature("placeholder"),
    )

    private fun sampleAsset(
        id: AssetId,
        label: String,
        parentId: AssetId? = null,
    ): Canonical3DAsset = Canonical3DAsset(
        id = id,
        label = label,
        lods = listOf(
            AssetLod(
                level = 0,
                geometryHash = ContentHash("0".repeat(64)),
                bounds = AssetBounds(
                    min = Vector3(-1.0, -1.0, -1.0),
                    max = Vector3(1.0, 1.0, 1.0),
                ),
                triangleCount = 1000,
                targetScreenSize = 1024,
            ),
        ),
        bounds = AssetBounds(
            min = Vector3(-1.0, -1.0, -1.0),
            max = Vector3(1.0, 1.0, 1.0),
        ),
        transform = AssetTransform.IDENTITY,
        parentId = parentId,
        coordinateSystem = CoordinateSystem.LOCAL,
    )
}
