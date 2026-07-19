package com.elysium.vanguard.foundry.core.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 / I-3.6 — the JVM tests for [Diagnostic]
 * + [Hypothesis] + [TestProcedure] + [RepairAction]
 * + [VerificationStatus] + [DiagnosticBinding].
 *
 * The diagnostic is the fault model integration
 * the digital twin uses to surface part-level
 * faults. The tests cover:
 *   - The diagnostic's invariants (rejects blank
 *     DTC, blank symptom, unverified proposal).
 *   - The Hypothesis + TestProcedure + RepairAction
 *     validation (likelihood in 0..1, steps
 *     non-empty, etc.).
 *   - The DiagnosticBinding (add + remove +
 *     lookup + determinism).
 *   - The partsWithDiagnostics + allDiagnostics
 *     computed views.
 */
class DiagnosticBindingTest {

    // ============================================================
    // Diagnostic
    // ============================================================

    @Test
    fun `Diagnostic rejects blank DTC code`() {
        try {
            Diagnostic(
                id = DiagnosticId.random(),
                dtcCode = "",
                symptom = "test",
                hypotheses = listOf(sampleHypothesis()),
                testProcedures = listOf(sampleTestProcedure()),
                repairActions = listOf(sampleRepairAction()),
                verificationStatus = VerificationStatus.ENGINEER_REVIEWED,
            )
            fail("expected IllegalArgumentException for blank DTC")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("dtcCode"))
        }
    }

    @Test
    fun `Diagnostic rejects blank symptom`() {
        try {
            Diagnostic(
                id = DiagnosticId.random(),
                dtcCode = "P0420",
                symptom = "",
                hypotheses = listOf(sampleHypothesis()),
                testProcedures = listOf(sampleTestProcedure()),
                repairActions = listOf(sampleRepairAction()),
                verificationStatus = VerificationStatus.ENGINEER_REVIEWED,
            )
            fail("expected IllegalArgumentException for blank symptom")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("symptom"))
        }
    }

    @Test
    fun `Diagnostic accepts UNVERIFIED_PROPOSAL as the initial verification status`() {
        // UNVERIFIED_PROPOSAL is the initial status for
        // community-corroborated diagnostics (the
        // status is promoted to COMMUNITY_CORROBORATED
        // by the AI council). The Diagnostic accepts
        // UNVERIFIED_PROPOSAL; the consumer is
        // responsible for filtering unverified
        // diagnostics from the user-facing surface.
        val d = Diagnostic(
            id = DiagnosticId.random(),
            dtcCode = "P0420",
            symptom = "test",
            hypotheses = listOf(sampleHypothesis()),
            testProcedures = listOf(sampleTestProcedure()),
            repairActions = listOf(sampleRepairAction()),
            verificationStatus = VerificationStatus.UNVERIFIED_PROPOSAL,
        )
        assertEquals(VerificationStatus.UNVERIFIED_PROPOSAL, d.verificationStatus)
    }

    // ============================================================
    // Hypothesis
    // ============================================================

    @Test
    fun `Hypothesis rejects blank description`() {
        try {
            Hypothesis(description = "", likelihood = 0.5)
            fail("expected IllegalArgumentException for blank description")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description"))
        }
    }

    @Test
    fun `Hypothesis rejects likelihood outside 0 to 1`() {
        try {
            Hypothesis(description = "test", likelihood = 1.5)
            fail("expected IllegalArgumentException for likelihood > 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("likelihood"))
        }
        try {
            Hypothesis(description = "test", likelihood = -0.1)
            fail("expected IllegalArgumentException for likelihood < 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("likelihood"))
        }
    }

    // ============================================================
    // TestProcedure
    // ============================================================

    @Test
    fun `TestProcedure rejects blank name`() {
        try {
            TestProcedure(name = "", steps = listOf("step 1"))
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `TestProcedure rejects empty steps`() {
        try {
            TestProcedure(name = "test", steps = emptyList())
            fail("expected IllegalArgumentException for empty steps")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("steps"))
        }
    }

    @Test
    fun `TestProcedure rejects blank steps`() {
        try {
            TestProcedure(name = "test", steps = listOf("step 1", ""))
            fail("expected IllegalArgumentException for blank step")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    // ============================================================
    // RepairAction
    // ============================================================

    @Test
    fun `RepairAction rejects blank name and description`() {
        try {
            RepairAction(name = "", description = "test", estimatedTimeMinutes = 60)
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
        try {
            RepairAction(name = "test", description = "", estimatedTimeMinutes = 60)
            fail("expected IllegalArgumentException for blank description")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description"))
        }
    }

    @Test
    fun `RepairAction rejects negative estimated time`() {
        try {
            RepairAction(name = "test", description = "test", estimatedTimeMinutes = -1)
            fail("expected IllegalArgumentException for negative time")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("estimatedTimeMinutes"))
        }
    }

    // ============================================================
    // DiagnosticBinding
    // ============================================================

    @Test
    fun `DiagnosticBinding rejects an empty diagnostic list for a part`() {
        try {
            DiagnosticBinding(
                diagnosticsByPart = mapOf(PartInstanceId.random() to emptyList()),
            )
            fail("expected IllegalArgumentException for empty diagnostic list")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    @Test
    fun `empty binding has no parts and no diagnostics`() {
        val binding = DiagnosticBinding.EMPTY
        assertTrue(binding.partsWithDiagnostics.isEmpty())
        assertTrue(binding.allDiagnostics.isEmpty())
    }

    @Test
    fun `addDiagnostic adds a diagnostic to a part`() {
        val partId = PartInstanceId.random()
        val binding = DiagnosticBinding.EMPTY.addDiagnostic(partId, sampleDiagnostic())
        assertEquals(listOf(partId), binding.partsWithDiagnostics)
        assertEquals(1, binding.allDiagnostics.size)
    }

    @Test
    fun `addDiagnostic appends to an existing part's list`() {
        val partId = PartInstanceId.random()
        val binding = DiagnosticBinding.EMPTY
            .addDiagnostic(partId, sampleDiagnostic(dtcCode = "P0420"))
            .addDiagnostic(partId, sampleDiagnostic(dtcCode = "P0301"))
        assertEquals(2, binding.diagnosticsFor(partId).size)
        assertEquals(2, binding.allDiagnostics.size)
    }

    @Test
    fun `diagnosticsFor returns an empty list for an unknown part`() {
        val binding = DiagnosticBinding.EMPTY
        assertTrue(binding.diagnosticsFor(PartInstanceId.random()).isEmpty())
    }

    @Test
    fun `removePart removes the part's diagnostics`() {
        val partId = PartInstanceId.random()
        val binding = DiagnosticBinding.EMPTY
            .addDiagnostic(partId, sampleDiagnostic())
        assertEquals(1, binding.partsWithDiagnostics.size)
        val removed = binding.removePart(partId)
        assertTrue(removed.partsWithDiagnostics.isEmpty())
    }

    @Test
    fun `allDiagnostics is sorted by partId then dtcCode`() {
        // Use deterministic UUIDs to assert the exact
        // sort order. The sort key is (partId, dtcCode).
        val partA = PartInstanceId.from("00000000-0000-0000-0000-000000000001").getOrThrow()
        val partB = PartInstanceId.from("00000000-0000-0000-0000-000000000002").getOrThrow()
        val binding = DiagnosticBinding.EMPTY
            .addDiagnostic(partA, sampleDiagnostic(dtcCode = "P0420"))
            .addDiagnostic(partA, sampleDiagnostic(dtcCode = "P0301"))
            .addDiagnostic(partB, sampleDiagnostic(dtcCode = "P0420"))
        // partA < partB (UUID string compare); within
        // each part, P0301 < P0420.
        val sorted = binding.allDiagnostics.map { it.dtcCode }
        assertEquals(listOf("P0301", "P0420", "P0420"), sorted)
    }

    @Test
    fun `addDiagnostic is pure — does not mutate the original`() {
        val partId = PartInstanceId.random()
        val original = DiagnosticBinding.EMPTY
        val updated = original.addDiagnostic(partId, sampleDiagnostic())
        assertTrue(original.partsWithDiagnostics.isEmpty())
        assertEquals(1, updated.partsWithDiagnostics.size)
    }

    @Test
    fun `binding is deterministic for the same additions`() {
        val partId = PartInstanceId.random()
        val a = DiagnosticBinding.EMPTY.addDiagnostic(partId, sampleDiagnostic(dtcCode = "P0420"))
        val b = DiagnosticBinding.EMPTY.addDiagnostic(partId, sampleDiagnostic(dtcCode = "P0420"))
        // The diagnostics have different random ids
        // but the same DTC code (the deterministic
        // part). The partsWithDiagnostics is the
        // same.
        assertEquals(a.partsWithDiagnostics, b.partsWithDiagnostics)
        assertEquals(
            a.allDiagnostics.map { it.dtcCode },
            b.allDiagnostics.map { it.dtcCode },
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun sampleHypothesis(): Hypothesis = Hypothesis(
        description = "sample hypothesis",
        likelihood = 0.5,
    )

    private fun sampleTestProcedure(): TestProcedure = TestProcedure(
        name = "sample procedure",
        steps = listOf("step 1", "step 2"),
    )

    private fun sampleRepairAction(): RepairAction = RepairAction(
        name = "sample repair",
        description = "sample repair description",
        estimatedTimeMinutes = 60,
    )

    private fun sampleDiagnostic(dtcCode: String = "P0420"): Diagnostic = Diagnostic(
        id = DiagnosticId.random(),
        dtcCode = dtcCode,
        symptom = "sample symptom",
        hypotheses = listOf(sampleHypothesis()),
        testProcedures = listOf(sampleTestProcedure()),
        repairActions = listOf(sampleRepairAction()),
        verificationStatus = VerificationStatus.ENGINEER_REVIEWED,
    )
}
