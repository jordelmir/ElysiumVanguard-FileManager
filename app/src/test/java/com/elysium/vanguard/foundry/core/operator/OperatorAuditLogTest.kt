package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 85 (Foundry / AI Operator) — the JVM
 * tests for [OperatorAuditLog].
 *
 * The tests cover:
 *   - OperatorAction invariants
 *     (timestampMs > 0, stepOrder > 0,
 *     errorCount >= 0, blank reasons
 *     rejected).
 *   - InMemoryOperatorAuditLog (append,
 *     entriesForPlan, entriesForAgent,
 *     entriesByActionKind, size).
 *   - Realistic scenario: the full
 *     lifecycle of a plan (intent issued,
 *     plan created, plan validated, plan
 *     approved, plan execution started,
 *     step started, step completed, plan
 *     execution completed) is logged in
 *     order.
 */
class OperatorAuditLogTest {

    // ============================================================
    // OperatorAction invariants
    // ============================================================

    @Test
    fun `OperatorAction IntentIssued rejects non-positive timestampMs`() {
        try {
            OperatorAction.IntentIssued(
                intentId = IntentId.random(),
                agentId = UserId.random(),
                intentKind = IntentKind.INSTALL_DISTRO,
                timestampMs = 0L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    @Test
    fun `OperatorAction PlanCreated rejects non-positive stepCount`() {
        try {
            OperatorAction.PlanCreated(
                planId = PlanId.random(),
                agentId = UserId.random(),
                stepCount = 0,
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive stepCount",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("stepCount"))
        }
    }

    @Test
    fun `OperatorAction PlanValidated rejects negative errorCount`() {
        try {
            OperatorAction.PlanValidated(
                planId = PlanId.random(),
                isValid = false,
                errorCount = -1,
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "negative errorCount",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("errorCount"))
        }
    }

    @Test
    fun `OperatorAction PlanDenied rejects blank reason`() {
        try {
            OperatorAction.PlanDenied(
                planId = PlanId.random(),
                approverId = UserId.random(),
                reason = "",
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `OperatorAction PlanStepStarted rejects non-positive stepOrder`() {
        try {
            OperatorAction.PlanStepStarted(
                planId = PlanId.random(),
                stepOrder = 0,
                intentId = IntentId.random(),
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive stepOrder",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("stepOrder"))
        }
    }

    @Test
    fun `OperatorAction PlanStepFailed rejects blank reason`() {
        try {
            OperatorAction.PlanStepFailed(
                planId = PlanId.random(),
                stepOrder = 1,
                reason = "",
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `OperatorAction PlanExecutionFailed rejects blank reason`() {
        try {
            OperatorAction.PlanExecutionFailed(
                planId = PlanId.random(),
                reason = "",
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `OperatorAction PlanCancelled rejects blank reason`() {
        try {
            OperatorAction.PlanCancelled(
                planId = PlanId.random(),
                cancellerId = UserId.random(),
                reason = "",
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    // ============================================================
    // InMemoryOperatorAuditLog
    // ============================================================

    @Test
    fun `append adds an entry to the log`() {
        val log = InMemoryOperatorAuditLog()
        val entry = buildEntry(
            action = buildIntentIssued(),
        )
        log.append(entry)
        assertEquals(1, log.size)
        assertEquals(entry, log.entries[0])
    }

    @Test
    fun `append preserves the append order`() {
        val log = InMemoryOperatorAuditLog()
        val entry1 = buildEntry(
            action = buildIntentIssued(),
        )
        val entry2 = buildEntry(
            action = buildPlanCreated(),
        )
        val entry3 = buildEntry(
            action = buildPlanValidated(),
        )
        log.append(entry1)
        log.append(entry2)
        log.append(entry3)
        assertEquals(entry1, log.entries[0])
        assertEquals(entry2, log.entries[1])
        assertEquals(entry3, log.entries[2])
    }

    @Test
    fun `entriesForPlan returns only entries with the plan id`() {
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
        val otherPlanId = PlanId.random()
        log.append(
            buildEntry(
                action = OperatorAction.PlanCreated(
                    planId = planId,
                    agentId = UserId.random(),
                    stepCount = 3,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanValidated(
                    planId = planId,
                    isValid = true,
                    errorCount = 0,
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanCreated(
                    planId = otherPlanId,
                    agentId = UserId.random(),
                    stepCount = 2,
                    timestampMs = 1_700_000_000_002L,
                ),
            ),
        )
        val planEntries = log.entriesForPlan(planId)
        assertEquals(2, planEntries.size)
    }

    @Test
    fun `entriesForPlan returns empty for an unknown plan`() {
        val log = InMemoryOperatorAuditLog()
        log.append(
            buildEntry(
                action = buildPlanCreated(),
            ),
        )
        val entries = log.entriesForPlan(PlanId.random())
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `entriesForAgent returns only entries with the agent id`() {
        val log = InMemoryOperatorAuditLog()
        val agentId = UserId.random()
        val otherAgentId = UserId.random()
        log.append(
            buildEntry(
                action = OperatorAction.IntentIssued(
                    intentId = IntentId.random(),
                    agentId = agentId,
                    intentKind = IntentKind.RUN_DIAGNOSTIC,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanCreated(
                    planId = PlanId.random(),
                    agentId = agentId,
                    stepCount = 3,
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanCreated(
                    planId = PlanId.random(),
                    agentId = otherAgentId,
                    stepCount = 2,
                    timestampMs = 1_700_000_000_002L,
                ),
            ),
        )
        val agentEntries = log.entriesForAgent(agentId)
        assertEquals(2, agentEntries.size)
    }

    @Test
    fun `entriesByActionKind returns only entries of the kind`() {
        val log = InMemoryOperatorAuditLog()
        log.append(
            buildEntry(
                action = buildIntentIssued(),
            ),
        )
        log.append(
            buildEntry(
                action = buildPlanCreated(),
            ),
        )
        log.append(
            buildEntry(
                action = buildIntentIssued(),
            ),
        )
        log.append(
            buildEntry(
                action = buildPlanCreated(),
            ),
        )
        val intentIssuedEntries = log.entriesByActionKind(
            OperatorActionKind.INTENT_ISSUED,
        )
        assertEquals(2, intentIssuedEntries.size)
        val planCreatedEntries = log.entriesByActionKind(
            OperatorActionKind.PLAN_CREATED,
        )
        assertEquals(2, planCreatedEntries.size)
    }

    @Test
    fun `size returns the number of entries`() {
        val log = InMemoryOperatorAuditLog()
        assertEquals(0, log.size)
        log.append(
            buildEntry(
                action = buildIntentIssued(),
            ),
        )
        assertEquals(1, log.size)
        log.append(
            buildEntry(
                action = buildPlanCreated(),
            ),
        )
        assertEquals(2, log.size)
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario the full lifecycle of a plan is logged in order`() {
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
        val agentId = UserId.random()
        val approverId = UserId.random()

        // Step 1: The AI agent issues an
        // intent.
        log.append(
            buildEntry(
                action = OperatorAction.IntentIssued(
                    intentId = IntentId.random(),
                    agentId = agentId,
                    intentKind = IntentKind.INSTALL_DISTRO,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )

        // Step 2: The AI agent creates a
        // plan.
        log.append(
            buildEntry(
                action = OperatorAction.PlanCreated(
                    planId = planId,
                    agentId = agentId,
                    stepCount = 3,
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )

        // Step 3: The plan is validated.
        log.append(
            buildEntry(
                action = OperatorAction.PlanValidated(
                    planId = planId,
                    isValid = true,
                    errorCount = 0,
                    timestampMs = 1_700_000_000_002L,
                ),
            ),
        )

        // Step 4: A human approves the
        // plan.
        log.append(
            buildEntry(
                action = OperatorAction.PlanApproved(
                    planId = planId,
                    approverId = approverId,
                    timestampMs = 1_700_000_000_003L,
                ),
            ),
        )

        // Step 5: The executor starts
        // executing the plan.
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId,
                    timestampMs = 1_700_000_000_004L,
                ),
            ),
        )

        // Step 6: Step 1 starts.
        log.append(
            buildEntry(
                action = OperatorAction.PlanStepStarted(
                    planId = planId,
                    stepOrder = 1,
                    intentId = IntentId.random(),
                    timestampMs = 1_700_000_000_005L,
                ),
            ),
        )

        // Step 7: Step 1 completes.
        log.append(
            buildEntry(
                action = OperatorAction.PlanStepCompleted(
                    planId = planId,
                    stepOrder = 1,
                    exitCode = 0,
                    timestampMs = 1_700_000_000_006L,
                ),
            ),
        )

        // Step 8: The plan completes.
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionCompleted(
                    planId = planId,
                    timestampMs = 1_700_000_000_007L,
                ),
            ),
        )

        // Verify: The log has 8 entries.
        assertEquals(8, log.size)

        // Verify: The plan has 7 entries
        // (IntentIssued has no planId).
        val planEntries = log.entriesForPlan(planId)
        assertEquals(7, planEntries.size)

        // Verify: The agent has 2 entries
        // (IntentIssued + PlanCreated).
        val agentEntries = log.entriesForAgent(agentId)
        assertEquals(2, agentEntries.size)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildEntry(
        action: OperatorAction,
    ): OperatorAuditEntry = OperatorAuditEntry(
        entryId = AuditEntryId.random(),
        action = action,
        signature = Signature("sig-${UUID.randomUUID()}"),
    )

    private fun buildIntentIssued(): OperatorAction.IntentIssued =
        OperatorAction.IntentIssued(
            intentId = IntentId.random(),
            agentId = UserId.random(),
            intentKind = IntentKind.INSTALL_DISTRO,
            timestampMs = 1_700_000_000_000L,
        )

    private fun buildPlanCreated(): OperatorAction.PlanCreated =
        OperatorAction.PlanCreated(
            planId = PlanId.random(),
            agentId = UserId.random(),
            stepCount = 3,
            timestampMs = 1_700_000_000_000L,
        )

    private fun buildPlanValidated(): OperatorAction.PlanValidated =
        OperatorAction.PlanValidated(
            planId = PlanId.random(),
            isValid = true,
            errorCount = 0,
            timestampMs = 1_700_000_000_000L,
        )
}
