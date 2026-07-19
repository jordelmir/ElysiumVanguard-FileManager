package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 / I-3.5 — the JVM tests for
 * [RepresentationConfidence].
 *
 * The confidence is the user-facing bundle of the
 * `VehicleRepresentationLevel` + the UI hint the
 * digital twin shows. The tests cover:
 *   - Every level has a confidence with the
 *     right label, color, and gate results.
 *   - UNKNOWN is rejected.
 *   - The marketplace gate is correct
 *     (`OEM_*` + `PARAMETRIC_FUNCTIONAL` pass;
 *     `CONCEPTUAL` + `VISUAL_ONLY` fail).
 *   - The safety gate is correct.
 *   - The lookup is deterministic.
 *   - The reject-blank-label + reject-blank-desc
 *     invariants.
 */
class RepresentationConfidenceTest {

    // ============================================================
    // Per-level confidence
    // ============================================================

    @Test
    fun `OEM_EXACT has GREEN UI color and passes all gates`() {
        val c = RepresentationConfidence.forLevel(RepresentationLevel.OEM_EXACT)
        assertEquals("OEM-Verified", c.displayLabel)
        assertEquals(RepresentationConfidence.UiColor.GREEN, c.uiColor)
        assertTrue("OEM_EXACT is marketplace eligible", c.marketplaceEligible)
        assertTrue("OEM_EXACT passes safety gate", c.safetyGatePasses)
    }

    @Test
    fun `OEM_PARTIAL has BLUE UI color and passes all gates`() {
        val c = RepresentationConfidence.forLevel(RepresentationLevel.OEM_PARTIAL)
        assertEquals("OEM-Partial", c.displayLabel)
        assertEquals(RepresentationConfidence.UiColor.BLUE, c.uiColor)
        assertTrue(c.marketplaceEligible)
        assertTrue(c.safetyGatePasses)
    }

    @Test
    fun `PARAMETRIC_FUNCTIONAL has YELLOW UI color and passes all gates`() {
        val c = RepresentationConfidence.forLevel(RepresentationLevel.PARAMETRIC_FUNCTIONAL)
        assertEquals("Parametric", c.displayLabel)
        assertEquals(RepresentationConfidence.UiColor.YELLOW, c.uiColor)
        assertTrue(c.marketplaceEligible)
        assertTrue(c.safetyGatePasses)
    }

    @Test
    fun `CONCEPTUAL has ORANGE UI color and fails marketplace + safety gates`() {
        val c = RepresentationConfidence.forLevel(RepresentationLevel.CONCEPTUAL)
        assertEquals("Conceptual", c.displayLabel)
        assertEquals(RepresentationConfidence.UiColor.ORANGE, c.uiColor)
        assertFalse("CONCEPTUAL is NOT marketplace eligible", c.marketplaceEligible)
        assertFalse("CONCEPTUAL does NOT pass safety gate", c.safetyGatePasses)
    }

    @Test
    fun `VISUAL_ONLY has RED UI color and fails marketplace + safety gates`() {
        val c = RepresentationConfidence.forLevel(RepresentationLevel.VISUAL_ONLY)
        assertEquals("Visual Only", c.displayLabel)
        assertEquals(RepresentationConfidence.UiColor.RED, c.uiColor)
        assertFalse(c.marketplaceEligible)
        assertFalse(c.safetyGatePasses)
    }

    @Test
    fun `UNKNOWN is rejected by the forLevel lookup`() {
        try {
            RepresentationConfidence.forLevel(RepresentationLevel.UNKNOWN)
            fail("expected IllegalArgumentException for UNKNOWN level")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention UNKNOWN, got: ${e.message}",
                e.message!!.contains("UNKNOWN"),
            )
        }
    }

    @Test
    fun `RepresentationConfidence rejects UNKNOWN in the constructor`() {
        try {
            RepresentationConfidence(
                level = RepresentationLevel.UNKNOWN,
                displayLabel = "test",
                description = "test",
                uiColor = RepresentationConfidence.UiColor.RED,
                marketplaceEligible = false,
                safetyGatePasses = false,
            )
            fail("expected IllegalArgumentException for UNKNOWN level")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("UNKNOWN"))
        }
    }

    @Test
    fun `RepresentationConfidence rejects blank displayLabel`() {
        try {
            RepresentationConfidence(
                level = RepresentationLevel.OEM_EXACT,
                displayLabel = "",
                description = "test",
                uiColor = RepresentationConfidence.UiColor.GREEN,
                marketplaceEligible = true,
                safetyGatePasses = true,
            )
            fail("expected IllegalArgumentException for blank displayLabel")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("displayLabel"))
        }
    }

    @Test
    fun `RepresentationConfidence rejects blank description`() {
        try {
            RepresentationConfidence(
                level = RepresentationLevel.OEM_EXACT,
                displayLabel = "test",
                description = "",
                uiColor = RepresentationConfidence.UiColor.GREEN,
                marketplaceEligible = true,
                safetyGatePasses = true,
            )
            fail("expected IllegalArgumentException for blank description")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description"))
        }
    }

    // ============================================================
    // Gate predicates
    // ============================================================

    @Test
    fun `marketplace gate is correct for every level`() {
        assertTrue(RepresentationConfidence.isMarketplaceEligible(RepresentationLevel.OEM_EXACT))
        assertTrue(RepresentationConfidence.isMarketplaceEligible(RepresentationLevel.OEM_PARTIAL))
        assertTrue(RepresentationConfidence.isMarketplaceEligible(RepresentationLevel.PARAMETRIC_FUNCTIONAL))
        assertFalse(RepresentationConfidence.isMarketplaceEligible(RepresentationLevel.CONCEPTUAL))
        assertFalse(RepresentationConfidence.isMarketplaceEligible(RepresentationLevel.VISUAL_ONLY))
    }

    @Test
    fun `safety gate is correct for every level`() {
        assertTrue(RepresentationConfidence.passesSafetyGate(RepresentationLevel.OEM_EXACT))
        assertTrue(RepresentationConfidence.passesSafetyGate(RepresentationLevel.OEM_PARTIAL))
        assertTrue(RepresentationConfidence.passesSafetyGate(RepresentationLevel.PARAMETRIC_FUNCTIONAL))
        assertFalse(RepresentationConfidence.passesSafetyGate(RepresentationLevel.CONCEPTUAL))
        assertFalse(RepresentationConfidence.passesSafetyGate(RepresentationLevel.VISUAL_ONLY))
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `forLevel is deterministic for the same level`() {
        val a = RepresentationConfidence.forLevel(RepresentationLevel.OEM_EXACT)
        val b = RepresentationConfidence.forLevel(RepresentationLevel.OEM_EXACT)
        assertEquals(a, b)
    }

    @Test
    fun `forLevel differs for different levels`() {
        val a = RepresentationConfidence.forLevel(RepresentationLevel.OEM_EXACT)
        val b = RepresentationConfidence.forLevel(RepresentationLevel.OEM_PARTIAL)
        assertNotEquals(a, b)
    }
}
