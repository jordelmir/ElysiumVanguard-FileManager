package com.elysium.vanguard.foundry.core.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 3 / I-3.4 — the JVM tests for
 * [PartSelection] + [PartSelector].
 *
 * The selection is the read-side state for the
 * digital twin's user-facing surface. The
 * tests cover:
 *   - The empty selection (the default state).
 *   - The select / deselect / toggleIsolation
 *     transitions.
 *   - The invalid state (isolated=true with
 *     no selectedId) is rejected.
 *   - The visible / hidden instance set
 *     computation.
 *   - The resolveSelection + focusedInstance
 *     helpers.
 *   - Determinism (the selection is a pure
 *     function of the inputs).
 */
class PartSelectionTest {

    // ============================================================
    // PartSelection
    // ============================================================

    @Test
    fun `empty selection has no selected id and is not isolated`() {
        val selection = PartSelection.EMPTY
        assertTrue(selection.isEmpty)
        assertFalse(selection.isIsolated)
        assertNull(selection.selectedId)
        assertFalse(selection.isolated)
    }

    @Test
    fun `select transitions the selection to the part`() {
        val id = PartInstanceId.random()
        val selection = PartSelection.EMPTY.select(id)
        assertEquals(id, selection.selectedId)
        assertFalse("a new selection is not isolated", selection.isolated)
    }

    @Test
    fun `select the same part is a no-op`() {
        val id = PartInstanceId.random()
        val a = PartSelection.EMPTY.select(id)
        val b = a.select(id)
        assertEquals(a, b)
    }

    @Test
    fun `select a different part resets isolation`() {
        val a = PartInstanceId.random()
        val b = PartInstanceId.random()
        val isolated = PartSelection(selectedId = a, isolated = true)
        val switched = isolated.select(b)
        assertEquals(b, switched.selectedId)
        assertFalse("switching parts resets isolation", switched.isolated)
    }

    @Test
    fun `deselect clears the selection`() {
        val id = PartInstanceId.random()
        val selection = PartSelection(selectedId = id, isolated = true)
        val deselected = selection.deselect()
        assertTrue(deselected.isEmpty)
    }

    @Test
    fun `toggleIsolation turns isolation on`() {
        val id = PartInstanceId.random()
        val selection = PartSelection(selectedId = id, isolated = false)
        val isolated = selection.toggleIsolation()
        assertTrue(isolated.isIsolated)
    }

    @Test
    fun `toggleIsolation turns isolation off`() {
        val id = PartInstanceId.random()
        val selection = PartSelection(selectedId = id, isolated = true)
        val notIsolated = selection.toggleIsolation()
        assertFalse(notIsolated.isIsolated)
    }

    @Test
    fun `toggleIsolation on empty selection is a no-op`() {
        val selection = PartSelection.EMPTY.toggleIsolation()
        assertTrue(selection.isEmpty)
    }

    @Test
    fun `selection rejects isolated=true with no selectedId`() {
        try {
            PartSelection(selectedId = null, isolated = true)
            fail("expected IllegalArgumentException for isolated=true with no selectedId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("isolated"))
        }
    }

    // ============================================================
    // PartSelector: visible / hidden sets
    // ============================================================

    @Test
    fun `visible instances with empty selection is the full graph`() {
        val graph = buildSampleGraph()
        val visible = PartSelector.visibleInstances(graph, PartSelection.EMPTY)
        assertEquals(graph.size, visible.size)
    }

    @Test
    fun `visible instances with non-isolated selection is the full graph`() {
        val graph = buildSampleGraph()
        val id = graph.roots.first().id
        val selection = PartSelection(selectedId = id, isolated = false)
        val visible = PartSelector.visibleInstances(graph, selection)
        assertEquals(graph.size, visible.size)
    }

    @Test
    fun `visible instances with isolated selection is the selected part and descendants`() {
        val graph = buildSampleGraph()
        // Select the chassis; the wheel is a descendant.
        val chassis = graph.roots.first { it.displayLabel == "chassis" }
        val selection = PartSelection(selectedId = chassis.id, isolated = true)
        val visible = PartSelector.visibleInstances(graph, selection)
        // The chassis + 1 wheel are visible; the other root is hidden.
        assertEquals(2, visible.size)
        assertTrue(
            "expected chassis in visible",
            visible.any { it.id == chassis.id },
        )
    }

    @Test
    fun `hidden instances with isolated selection is the complement`() {
        val graph = buildSampleGraph()
        val chassis = graph.roots.first { it.displayLabel == "chassis" }
        val selection = PartSelection(selectedId = chassis.id, isolated = true)
        val hidden = PartSelector.hiddenInstances(graph, selection)
        // The other root + descendants outside the chassis subtree are hidden.
        assertEquals(1, hidden.size)
    }

    @Test
    fun `visible instances with stale selection (id not in graph) returns full graph`() {
        val graph = buildSampleGraph()
        val staleId = PartInstanceId(UUID.randomUUID())
        val selection = PartSelection(selectedId = staleId, isolated = true)
        val visible = PartSelector.visibleInstances(graph, selection)
        assertEquals(graph.size, visible.size)
    }

    // ============================================================
    // PartSelector: resolveSelection + focusedInstance
    // ============================================================

    @Test
    fun `resolveSelection returns the selected instance`() {
        val graph = buildSampleGraph()
        val chassis = graph.roots.first()
        val selection = PartSelection(selectedId = chassis.id)
        val resolved = PartSelector.resolveSelection(graph, selection)
        assertNotNull(resolved)
        assertEquals(chassis.id, resolved!!.id)
    }

    @Test
    fun `resolveSelection with empty selection returns null`() {
        val graph = buildSampleGraph()
        val resolved = PartSelector.resolveSelection(graph, PartSelection.EMPTY)
        assertNull(resolved)
    }

    @Test
    fun `resolveSelection with stale selection returns null`() {
        val graph = buildSampleGraph()
        val staleId = PartInstanceId(UUID.randomUUID())
        val resolved = PartSelector.resolveSelection(
            graph,
            PartSelection(selectedId = staleId),
        )
        assertNull(resolved)
    }

    @Test
    fun `focusedInstance with selection returns the selected instance`() {
        val graph = buildSampleGraph()
        val chassis = graph.roots.first { it.displayLabel == "chassis" }
        val focused = PartSelector.focusedInstance(
            graph,
            PartSelection(selectedId = chassis.id),
        )
        assertNotNull(focused)
        assertEquals(chassis.id, focused!!.id)
    }

    @Test
    fun `focusedInstance with empty selection returns the first root`() {
        val graph = buildSampleGraph()
        val focused = PartSelector.focusedInstance(graph, PartSelection.EMPTY)
        assertNotNull(focused)
        // The first root in the graph (the order
        // depends on the test fixture).
        assertTrue(
            "expected focused to be a root",
            graph.roots.any { it.id == focused!!.id },
        )
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `visible instances are sorted by depth then label`() {
        val graph = buildSampleGraph()
        val visible = PartSelector.visibleInstances(graph, PartSelection.EMPTY)
        // The visible list is sorted by (depth, label).
        for (i in 0 until visible.size - 1) {
            val a = graph.depthOf(visible[i].id)
            val b = graph.depthOf(visible[i + 1].id)
            assertTrue(
                "expected depth[$i] ($a) <= depth[${i + 1}] ($b)",
                a <= b,
            )
        }
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildSampleGraph(): PartInstanceGraph {
        val chassisId = PartInstanceId.random()
        val wheelId = PartInstanceId.random()
        val engineId = PartInstanceId.random()
        val chassis = PartInstance(
            id = chassisId,
            assetId = com.elysium.vanguard.foundry.core.ontology.ids.AssetId("a".repeat(64)),
            displayLabel = "chassis",
        )
        val wheel = PartInstance(
            id = wheelId,
            assetId = com.elysium.vanguard.foundry.core.ontology.ids.AssetId("b".repeat(64)),
            parentInstanceId = chassisId,
            displayLabel = "wheel",
        )
        val engine = PartInstance(
            id = engineId,
            assetId = com.elysium.vanguard.foundry.core.ontology.ids.AssetId("c".repeat(64)),
            displayLabel = "engine",
        )
        return PartInstanceGraph(
            instances = listOf(chassis, wheel, engine),
            representationLevel = com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        )
    }
}
