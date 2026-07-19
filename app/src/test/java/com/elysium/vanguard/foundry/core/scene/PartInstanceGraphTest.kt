package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.ids.AssetId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 3 / I-3.2 — the JVM tests for
 * [PartInstance] + [PartInstanceGraph].
 *
 * The graph is the runtime representation of the
 * digital twin. The tests cover:
 *   - The graph builds from a manifest +
 *     assigns fresh ids to each instance.
 *   - The graph rejects empty instances +
 *     UNKNOWN representation level + cyclic
 *     graphs + orphan parents.
 *   - The graph's read operations (roots,
 *     childrenOf, descendantsOf, ancestorsOf,
 *     findById, findByAssetId) return the
 *     expected results.
 *   - The content hash is deterministic for
 *     the same instances.
 *   - The part instance rejects self-parent.
 */
class PartInstanceGraphTest {

    // ============================================================
    // PartInstance
    // ============================================================

    @Test
    fun `part instance rejects blank display label`() {
        try {
            PartInstance(
                id = PartInstanceId.random(),
                assetId = AssetId("a".repeat(64)),
                displayLabel = "",
            )
            fail("expected IllegalArgumentException for blank label")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("displayLabel"))
        }
    }

    @Test
    fun `part instance rejects self-parent`() {
        val id = PartInstanceId.random()
        try {
            PartInstance(
                id = id,
                assetId = AssetId("a".repeat(64)),
                parentInstanceId = id,
                displayLabel = "self",
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
    fun `part instance accepts empty artifact and repair lists by default`() {
        val instance = PartInstance(
            id = PartInstanceId.random(),
            assetId = AssetId("a".repeat(64)),
            displayLabel = "test",
        )
        assertTrue(instance.engineeringArtifactRefs.isEmpty())
        assertTrue(instance.repairActions.isEmpty())
    }

    // ============================================================
    // PartInstanceGraph: build from manifest
    // ============================================================

    @Test
    fun `graph builds from manifest with one instance per asset`() {
        val manifest = buildSampleManifest()
        val graph = PartInstanceGraph.fromManifest(manifest)
        assertEquals(manifest.assets.size, graph.size)
        assertEquals(2, graph.roots.size)
    }

    @Test
    fun `graph preserves the parent-child relationships from the manifest`() {
        val manifest = buildSampleManifest()
        val graph = PartInstanceGraph.fromManifest(manifest)
        // The chassis is a root; the wheel is a child.
        val chassis = graph.findByAssetId(AssetId("a".repeat(64))).first()
        val wheel = graph.findByAssetId(AssetId("b".repeat(64))).first()
        assertNull("chassis has no parent", chassis.parentInstanceId)
        assertEquals("wheel's parent is chassis", chassis.id, wheel.parentInstanceId)
    }

    @Test
    fun `graph fromManifest uses a deterministic id factory when provided`() {
        val manifest = buildSampleManifest()
        val assetToId = mutableMapOf<AssetId, PartInstanceId>()
        val factory = { assetId: AssetId ->
            val id = PartInstanceId.random()
            assetToId[assetId] = id
            id
        }
        val graph1 = PartInstanceGraph.fromManifest(manifest, factory)
        // The graph1 is built; the factory side effect
        // captured the id mappings.
        val firstAssetId = AssetId("a".repeat(64))
        val firstChassisId = assetToId[firstAssetId]
        assertNotNull("expected factory to be called for chassis", firstChassisId)
        assertEquals(firstChassisId, graph1.findByAssetId(firstAssetId).first().id)
    }

    // ============================================================
    // PartInstanceGraph: reject invalid graphs
    // ============================================================

    @Test
    fun `graph rejects empty instances list`() {
        try {
            PartInstanceGraph(
                instances = emptyList(),
                representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
            )
            fail("expected IllegalArgumentException for empty instances")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("instances"))
        }
    }

    @Test
    fun `graph rejects UNKNOWN representation level`() {
        try {
            PartInstanceGraph(
                instances = listOf(sampleInstance("a", parent = null)),
                representationLevel = RepresentationLevel.UNKNOWN,
            )
            fail("expected IllegalArgumentException for UNKNOWN level")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("representationLevel"))
        }
    }

    @Test
    fun `graph rejects an instance with an unknown parent`() {
        val orphan = PartInstance(
            id = PartInstanceId.random(),
            assetId = AssetId("a".repeat(64)),
            parentInstanceId = PartInstanceId.random(),  // not in graph
            displayLabel = "orphan",
        )
        try {
            PartInstanceGraph(
                instances = listOf(orphan),
                representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
            )
            fail("expected IllegalArgumentException for orphan parent")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention parent, got: ${e.message}",
                e.message!!.contains("parent"),
            )
        }
    }

    @Test
    fun `graph rejects a cyclic instance graph`() {
        val aId = PartInstanceId.random()
        val bId = PartInstanceId.random()
        val a = PartInstance(
            id = aId,
            assetId = AssetId("a".repeat(64)),
            parentInstanceId = bId,
            displayLabel = "a",
        )
        val b = PartInstance(
            id = bId,
            assetId = AssetId("b".repeat(64)),
            parentInstanceId = aId,
            displayLabel = "b",
        )
        try {
            PartInstanceGraph(
                instances = listOf(a, b),
                representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
            )
            fail("expected IllegalArgumentException for cycle")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cycle"))
        }
    }

    // ============================================================
    // PartInstanceGraph: read operations
    // ============================================================

    @Test
    fun `graph roots are the instances with no parent`() {
        val graph = PartInstanceGraph.fromManifest(buildSampleManifest())
        // The manifest has 2 roots (chassis + an extra root).
        assertEquals(2, graph.roots.size)
        // The chassis is one of the roots.
        val chassisAssetId = AssetId("a".repeat(64))
        val chassisInstance = graph.findByAssetId(chassisAssetId).first()
        assertTrue(
            "expected chassis in roots",
            graph.roots.any { it.id == chassisInstance.id },
        )
    }

    @Test
    fun `graph childrenOf returns direct children`() {
        val graph = PartInstanceGraph.fromManifest(buildSampleManifest())
        val chassis = graph.findByAssetId(AssetId("a".repeat(64))).first()
        val children = graph.childrenOf(chassis.id)
        // The chassis has 1 child (the wheel).
        assertEquals(1, children.size)
        assertEquals(AssetId("b".repeat(64)), children.first().assetId)
    }

    @Test
    fun `graph descendantsOf returns all descendants recursively`() {
        // Build a 3-level graph: chassis > wheel > bolt
        val chassis = sampleInstance("chassis", parent = null)
        val wheel = sampleInstance("wheel", parent = chassis.id)
        val bolt = sampleInstance("bolt", parent = wheel.id)
        val graph = PartInstanceGraph(
            instances = listOf(chassis, wheel, bolt),
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        val descendants = graph.descendantsOf(chassis.id)
        assertEquals(2, descendants.size)
        // The descendants are ordered by BFS.
        assertEquals(wheel.id, descendants[0].id)
        assertEquals(bolt.id, descendants[1].id)
    }

    @Test
    fun `graph ancestorsOf returns the chain of ancestors`() {
        val chassis = sampleInstance("chassis", parent = null)
        val wheel = sampleInstance("wheel", parent = chassis.id)
        val bolt = sampleInstance("bolt", parent = wheel.id)
        val graph = PartInstanceGraph(
            instances = listOf(chassis, wheel, bolt),
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        val ancestors = graph.ancestorsOf(bolt.id)
        assertEquals(2, ancestors.size)
        // The ancestors are ordered from immediate parent to root.
        assertEquals(wheel.id, ancestors[0].id)
        assertEquals(chassis.id, ancestors[1].id)
    }

    @Test
    fun `graph depthOf returns the count of ancestors`() {
        val chassis = sampleInstance("chassis", parent = null)
        val wheel = sampleInstance("wheel", parent = chassis.id)
        val bolt = sampleInstance("bolt", parent = wheel.id)
        val graph = PartInstanceGraph(
            instances = listOf(chassis, wheel, bolt),
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        assertEquals(0, graph.depthOf(chassis.id))
        assertEquals(1, graph.depthOf(wheel.id))
        assertEquals(2, graph.depthOf(bolt.id))
    }

    @Test
    fun `graph findById returns null for unknown id`() {
        val graph = PartInstanceGraph.fromManifest(buildSampleManifest())
        val unknown = PartInstanceId.random()
        assertNull(graph.findById(unknown))
    }

    @Test
    fun `graph findByAssetId returns empty list for unknown asset`() {
        val graph = PartInstanceGraph.fromManifest(buildSampleManifest())
        val unknown = AssetId("9".repeat(64))
        assertTrue(graph.findByAssetId(unknown).isEmpty())
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `graph content hash is deterministic for the same instances`() {
        // Build a valid graph (root + child) so the
        // canonical form is comparable.
        val rootId = PartInstanceId.random()
        val childId = PartInstanceId.random()
        val instances = listOf(
            PartInstance(
                id = rootId,
                assetId = AssetId("a".repeat(64)),
                parentInstanceId = null,
                displayLabel = "root",
            ),
            PartInstance(
                id = childId,
                assetId = AssetId("b".repeat(64)),
                parentInstanceId = rootId,
                displayLabel = "child",
            ),
        )
        val a1 = PartInstanceGraph(
            instances = instances,
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        val a2 = PartInstanceGraph(
            instances = instances,
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        assertEquals(a1.canonicalForm(), a2.canonicalForm())
        assertEquals(a1.contentHash, a2.contentHash)
    }

    @Test
    fun `graph content hash differs for different instances`() {
        val a1 = PartInstanceGraph(
            instances = listOf(sampleInstance("a", parent = null)),
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        val a2 = PartInstanceGraph(
            instances = listOf(
                sampleInstance("a", parent = null),
                sampleInstance("b", parent = null),
            ),
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
        assertTrue(
            "expected different content hashes for different graphs",
            a1.contentHash != a2.contentHash,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildSampleManifest(): CanonicalSceneManifest =
        CanonicalSceneManifest(
            revisionContentHash = ContentHash("a".repeat(64)),
            assets = listOf(
                sampleAsset(AssetId("a".repeat(64)), "chassis", parent = null),
                sampleAsset(AssetId("b".repeat(64)), "wheel", parent = AssetId("a".repeat(64))),
                // Second root for the roots test.
                sampleAsset(AssetId("c".repeat(64)), "engine", parent = null),
            ),
            coordinateSystem = CoordinateSystem.LOCAL,
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
            signature = Signature("placeholder"),
        )

    private fun sampleAsset(
        id: AssetId,
        label: String,
        parent: AssetId?,
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
        parentId = parent,
        coordinateSystem = CoordinateSystem.LOCAL,
    )

    private fun sampleInstance(
        label: String,
        parent: PartInstanceId?,
    ): PartInstance = PartInstance(
        id = PartInstanceId.random(),
        assetId = AssetId(label.hashCode().toLong().toString(16).padStart(64, '0').take(64)),
        parentInstanceId = parent,
        displayLabel = label,
    )
}
