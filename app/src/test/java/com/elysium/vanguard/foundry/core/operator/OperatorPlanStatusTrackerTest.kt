package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 88 (Foundry / AI Operator) — the JVM
 * tests for [OperatorPlanStatusTracker].
 *
 * The tests cover:
 *   - Empty log → Pending.
 *   - PlanCreated entry only → Pending.
 *   - PlanCreated + PlanApproved → Pending.
 *   - PlanCreated + PlanApproved +
 *     PlanExecutionStarted → Running.
 *   - PlanCreated + PlanApproved +
 *     PlanExecutionStarted +
 *     PlanExecutionCompleted → Completed.
 *   - PlanCreated + PlanApproved +
 *     PlanExecutionStarted +
 *     PlanExecutionFailed → Failed.
 *   - PlanDenied → Failed.
 *   - PlanCancelled → Pending.
 *   - Multiple plans in the same log →
 *     the tracker returns the correct
 *     status for each.
 */
class OperatorPlanStatusTrackerTest {

    // ============================================================
    // Empty log + PlanCreated
    // ============================================================

    @Test
    fun `empty log returns Pending for any plan id`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        assertTrue(
            tracker.currentStatus(
                planId = PlanId.random(),
                auditLog = log,
            ) is PlanStatus.Pending,
        )
    }

    @Test
    fun `a log with a PlanCreated entry returns Pending`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
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
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Pending,
        )
    }

    // ============================================================
    // PlanApproved (approved but not started)
    // ============================================================

    @Test
    fun `a log with PlanCreated + PlanApproved returns Pending`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
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
                action = OperatorAction.PlanApproved(
                    planId = planId,
                    approverId = UserId.random(),
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Pending,
        )
    }

    // ============================================================
    // PlanExecutionStarted (running)
    // ============================================================

    @Test
    fun `a log with PlanExecutionStarted returns Running`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
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
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId,
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Running,
        )
    }

    // ============================================================
    // PlanExecutionCompleted
    // ============================================================

    @Test
    fun `a log with PlanExecutionCompleted returns Completed`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
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
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId,
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionCompleted(
                    planId = planId,
                    timestampMs = 1_700_000_000_002L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Completed,
        )
    }

    // ============================================================
    // PlanExecutionFailed
    // ============================================================

    @Test
    fun `a log with PlanExecutionFailed returns Failed with the reason`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionFailed(
                    planId = planId,
                    reason = "step 2 requires approval",
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        val status = tracker.currentStatus(planId, log)
        assertTrue(status is PlanStatus.Failed)
        val failed = status as PlanStatus.Failed
        assertEquals("step 2 requires approval", failed.reason)
    }

    // ============================================================
    // PlanDenied
    // ============================================================

    @Test
    fun `a log with PlanDenied returns Failed with the denied reason`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
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
                action = OperatorAction.PlanDenied(
                    planId = planId,
                    approverId = UserId.random(),
                    reason = "plan too risky",
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        val status = tracker.currentStatus(planId, log)
        assertTrue(status is PlanStatus.Failed)
        val failed = status as PlanStatus.Failed
        assertTrue(failed.reason.contains("plan too risky"))
    }

    // ============================================================
    // PlanCancelled
    // ============================================================

    @Test
    fun `a log with PlanCancelled returns Pending`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
        log.append(
            buildEntry(
                action = OperatorAction.PlanCancelled(
                    planId = planId,
                    cancellerId = UserId.random(),
                    reason = "user changed mind",
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        // The tracker does NOT have a
        // specific case for PlanCancelled
        // (it falls through to the default
        // Pending case). This is the
        // conservative default.
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Pending,
        )
    }

    // ============================================================
    // Multiple plans in the same log
    // ============================================================

    @Test
    fun `tracker returns the correct status for each plan in the same log`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId1 = PlanId.random()
        val planId2 = PlanId.random()

        // Plan 1: completed.
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId1,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionCompleted(
                    planId = planId1,
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )

        // Plan 2: failed.
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId2,
                    timestampMs = 1_700_000_000_002L,
                ),
            ),
        )
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionFailed(
                    planId = planId2,
                    reason = "out of memory",
                    timestampMs = 1_700_000_000_003L,
                ),
            ),
        )

        assertTrue(
            tracker.currentStatus(planId1, log)
                is PlanStatus.Completed,
        )
        val status2 = tracker.currentStatus(planId2, log)
        assertTrue(status2 is PlanStatus.Failed)
        assertEquals(
            "out of memory",
            (status2 as PlanStatus.Failed).reason,
        )
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario the tracker returns the correct status as the plan lifecycle progresses`() {
        val tracker = InMemoryOperatorPlanStatusTracker()
        val log = InMemoryOperatorAuditLog()
        val planId = PlanId.random()
        val agentId = UserId.random()

        // Step 1: The plan is created.
        log.append(
            buildEntry(
                action = OperatorAction.PlanCreated(
                    planId = planId,
                    agentId = agentId,
                    stepCount = 3,
                    timestampMs = 1_700_000_000_000L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Pending,
        )

        // Step 2: The plan is approved.
        log.append(
            buildEntry(
                action = OperatorAction.PlanApproved(
                    planId = planId,
                    approverId = UserId.random(),
                    timestampMs = 1_700_000_000_001L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Pending,
        )

        // Step 3: The plan execution
        // starts.
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionStarted(
                    planId = planId,
                    timestampMs = 1_700_000_000_002L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Running,
        )

        // Step 4: A step starts.
        log.append(
            buildEntry(
                action = OperatorAction.PlanStepStarted(
                    planId = planId,
                    stepOrder = 1,
                    intentId = IntentId.random(),
                    timestampMs = 1_700_000_000_003L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Running,
        )

        // Step 5: The step completes.
        log.append(
            buildEntry(
                action = OperatorAction.PlanStepCompleted(
                    planId = planId,
                    stepOrder = 1,
                    exitCode = 0,
                    timestampMs = 1_700_000_000_004L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Running,
        )

        // Step 6: The plan execution
        // completes.
        log.append(
            buildEntry(
                action = OperatorAction.PlanExecutionCompleted(
                    planId = planId,
                    timestampMs = 1_700_000_000_005L,
                ),
            ),
        )
        assertTrue(
            tracker.currentStatus(planId, log)
                is PlanStatus.Completed,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildEntry(
        action: OperatorAction,
    ): OperatorAuditEntry = OperatorAuditEntry(
        entryId = AuditEntryId.random(),
        action = action,
        signature = com.elysium.vanguard.foundry.core
            .ontology.primitives.Signature(
                "sig-${java.util.UUID.randomUUID()}",
            ),
    )
}
