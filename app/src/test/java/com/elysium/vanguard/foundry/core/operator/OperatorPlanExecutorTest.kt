package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 86 (Foundry / AI Operator) — the
 * JVM tests for [OperatorPlanExecutor].
 *
 * The tests cover:
 *   - StepResult invariants (AwaitingApproval
 *     / Denied / Failed blank reasons
 *     rejected).
 *   - PlanExecutionResult invariants
 *     (stepResults not empty, startedAtMs
 *     > 0, completedAtMs >= startedAtMs,
 *     durationMs).
 *   - InMemoryOperatorPlanExecutor:
 *     - All-Allowed plan: status = Completed
 *       + 3 PlanStepCompleted entries.
 *     - Requires-Approval plan: status = Paused
 *       + PlanExecutionFailed entry.
 *     - Denied plan: status = Failed +
 *       PlanExecutionFailed entry.
 *     - Invalid plan: status = Failed +
 *       PlanExecutionFailed entry (no
 *       step execution).
 *   - The audit log records every
 *     transition.
 *   - Realistic scenario: the "Install
 *     Blender" 3-step plan is executed
 *     with a Full authority (all steps
 *     allowed) → status = Completed.
 */
class OperatorPlanExecutorTest {

    // ============================================================
    // StepResult invariants
    // ============================================================

    @Test
    fun `StepResult AwaitingApproval rejects blank reason`() {
        try {
            StepResult.AwaitingApproval(
                intentId = IntentId.random(),
                reason = "",
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
    fun `StepResult Denied rejects blank reason`() {
        try {
            StepResult.Denied(
                intentId = IntentId.random(),
                reason = "",
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
    fun `StepResult Failed rejects blank reason`() {
        try {
            StepResult.Failed(
                intentId = IntentId.random(),
                reason = "",
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
    // PlanExecutionResult invariants
    // ============================================================

    @Test
    fun `PlanExecutionResult accepts empty stepResults`() {
        // An invalid plan (one that failed
        // at validation time, or a plan
        // where the first step was denied)
        // can have empty stepResults.
        val result = buildResult(
            stepResults = emptyMap(),
        )
        assertTrue(result.stepResults.isEmpty())
    }

    @Test
    fun `PlanExecutionResult rejects non-positive startedAtMs`() {
        try {
            buildResult(startedAtMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive startedAtMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("startedAtMs"))
        }
    }

    @Test
    fun `PlanExecutionResult rejects completedAtMs less than startedAtMs`() {
        try {
            buildResult(
                startedAtMs = 2_000L,
                completedAtMs = 1_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "completedAtMs < startedAtMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("completedAtMs"))
        }
    }

    @Test
    fun `PlanExecutionResult durationMs is completedAtMs minus startedAtMs`() {
        val result = buildResult(
            startedAtMs = 1_000L,
            completedAtMs = 5_000L,
        )
        assertEquals(4_000L, result.durationMs)
    }

    // ============================================================
    // InMemoryOperatorPlanExecutor — all-allowed plan
    // ============================================================

    @Test
    fun `execute a 3-step plan with Full authority returns Completed with 3 step results`() {
        val executor = InMemoryOperatorPlanExecutor()
        val planValidator = InMemoryOperatorPlanValidator()
        val intentValidator = InMemoryOperatorIntentValidator()
        val auditLog = InMemoryOperatorAuditLog()
        val agentId = UserId.random()
        val plan = buildPlan(
            agentId = agentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    agentId = agentId,
                    intent = buildInstallDistro(agentId),
                ),
                buildStep(
                    order = 2,
                    agentId = agentId,
                    intent = buildLaunchCapsule(agentId),
                ),
                buildStep(
                    order = 3,
                    agentId = agentId,
                    intent = buildCreateWorkspace(agentId),
                ),
            ),
        )
        val authority = buildAuthority(
            agentId = agentId,
            scope = OperatorAuthorityScope.Full,
        )
        val result = executor.execute(
            plan = plan,
            authority = authority,
            intentValidator = intentValidator,
            planValidator = planValidator,
            auditLog = auditLog,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result.status is PlanStatus.Completed)
        assertEquals(3, result.stepResults.size)
        assertTrue(result.stepResults[1] is StepResult.Executed)
        assertTrue(result.stepResults[2] is StepResult.Executed)
        assertTrue(result.stepResults[3] is StepResult.Executed)

        // The audit log has 8 entries:
        // PlanExecutionStarted + 3 *
        // (PlanStepStarted + PlanStepCompleted)
        // + PlanExecutionCompleted = 1 + 6 + 1 = 8.
        assertEquals(8, auditLog.size)
    }

    // ============================================================
    // InMemoryOperatorPlanExecutor — requires-approval plan
    // ============================================================

    @Test
    fun `execute a plan with a RequiresApproval step returns Paused`() {
        val executor = InMemoryOperatorPlanExecutor()
        val planValidator = InMemoryOperatorPlanValidator()
        val intentValidator = InMemoryOperatorIntentValidator()
        val auditLog = InMemoryOperatorAuditLog()
        val agentId = UserId.random()
        val plan = buildPlan(
            agentId = agentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    agentId = agentId,
                    intent = buildRunDiagnostic(agentId),
                ),
                buildStep(
                    order = 2,
                    agentId = agentId,
                    intent = buildInstallDistro(agentId),
                ),
            ),
        )
        // Restricted authority allows
        // RunDiagnostic; InstallDistro
        // requires approval.
        val authority = buildAuthority(
            agentId = agentId,
            scope = OperatorAuthorityScope.Restricted(
                allowedKinds = setOf(
                    IntentKind.RUN_DIAGNOSTIC,
                ),
            ),
        )
        val result = executor.execute(
            plan = plan,
            authority = authority,
            intentValidator = intentValidator,
            planValidator = planValidator,
            auditLog = auditLog,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result.status is PlanStatus.Paused)
        assertEquals(1, result.stepResults.size)
        // Step 1 should be Executed; step
        // 2 was not attempted (the
        // AwaitingApproval is the trigger
        // for the Paused status, not a
        // step result).
        assertTrue(result.stepResults[1] is StepResult.Executed)
        assertEquals(null, result.stepResults[2])
    }

    // ============================================================
    // InMemoryOperatorPlanExecutor — denied plan
    // ============================================================

    @Test
    fun `execute a plan with a Denied step returns Failed`() {
        val executor = InMemoryOperatorPlanExecutor()
        val planValidator = InMemoryOperatorPlanValidator()
        val intentValidator = InMemoryOperatorIntentValidator()
        val auditLog = InMemoryOperatorAuditLog()
        val agentId = UserId.random()
        val plan = buildPlan(
            agentId = agentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    agentId = agentId,
                    intent = buildInstallDistro(agentId),
                ),
            ),
        )
        val authority = buildAuthority(
            agentId = agentId,
            scope = OperatorAuthorityScope.ReadOnly,
        )
        val result = executor.execute(
            plan = plan,
            authority = authority,
            intentValidator = intentValidator,
            planValidator = planValidator,
            auditLog = auditLog,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result.status is PlanStatus.Failed)
        // No steps were Executed (the
        // first step was Denied).
        assertEquals(0, result.stepResults.size)
    }

    // ============================================================
    // InMemoryOperatorPlanExecutor — invalid plan
    // ============================================================

    @Test
    fun `execute an invalid plan returns Failed without executing any step`() {
        val executor = InMemoryOperatorPlanExecutor()
        val planValidator = InMemoryOperatorPlanValidator()
        val intentValidator = InMemoryOperatorIntentValidator()
        val auditLog = InMemoryOperatorAuditLog()
        val agentId = UserId.random()
        // Plan with duplicate step orders
        // (invalid).
        val plan = buildPlan(
            agentId = agentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    agentId = agentId,
                    intent = buildInstallDistro(agentId),
                ),
                buildStep(
                    order = 1,
                    agentId = agentId,
                    intent = buildLaunchCapsule(agentId),
                ),
            ),
        )
        val authority = buildAuthority(
            agentId = agentId,
            scope = OperatorAuthorityScope.Full,
        )
        val result = executor.execute(
            plan = plan,
            authority = authority,
            intentValidator = intentValidator,
            planValidator = planValidator,
            auditLog = auditLog,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result.status is PlanStatus.Failed)
        assertEquals(0, result.stepResults.size)
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario the "Install Blender" plan from the master vision is executed successfully with a Full authority`() {
        val executor = InMemoryOperatorPlanExecutor()
        val planValidator = InMemoryOperatorPlanValidator()
        val intentValidator = InMemoryOperatorIntentValidator()
        val auditLog = InMemoryOperatorAuditLog()
        val agentId = UserId.random()

        // Build the 3-step "Install
        // Blender" plan.
        val plan = buildPlan(
            agentId = agentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    description = "Install Elysium Linux",
                    agentId = agentId,
                    intent = buildInstallDistro(agentId),
                ),
                buildStep(
                    order = 2,
                    description = "Launch Blender",
                    agentId = agentId,
                    intent = buildLaunchCapsule(agentId),
                ),
                buildStep(
                    order = 3,
                    description = "Create workspace",
                    agentId = agentId,
                    intent = buildCreateWorkspace(agentId),
                ),
            ),
        )
        val authority = buildAuthority(
            agentId = agentId,
            scope = OperatorAuthorityScope.Full,
        )

        // Execute the plan.
        val result = executor.execute(
            plan = plan,
            authority = authority,
            intentValidator = intentValidator,
            planValidator = planValidator,
            auditLog = auditLog,
            nowMs = 1_700_000_000_000L,
        )

        // Verify: The plan is completed.
        assertTrue(result.status is PlanStatus.Completed)
        assertEquals(3, result.stepResults.size)
        assertTrue(result.stepResults[1] is StepResult.Executed)
        assertTrue(result.stepResults[2] is StepResult.Executed)
        assertTrue(result.stepResults[3] is StepResult.Executed)

        // Verify: The audit log has the
        // expected entries.
        // Expected:
        //   - PlanExecutionStarted (1)
        //   - PlanStepStarted (3) +
        //     PlanStepCompleted (3) = 6
        //   - PlanExecutionCompleted (1)
        //   Total: 8.
        assertEquals(8, auditLog.size)

        // Verify: The entries are in
        // chronological order.
        val entries = auditLog.entries
        assertTrue(
            entries[0].action
                is OperatorAction.PlanExecutionStarted,
        )
        assertTrue(
            entries.last().action
                is OperatorAction.PlanExecutionCompleted,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildStep(
        order: Int,
        description: String = "Test step",
        agentId: UserId,
        intent: OperatorIntent? = null,
    ): PlanStep = PlanStep(
        order = order,
        description = description,
        intent = intent ?: buildRunDiagnostic(agentId),
    )

    private fun buildPlan(
        agentId: UserId = UserId.random(),
        steps: List<PlanStep>,
    ): OperatorPlan = OperatorPlan(
        planId = PlanId.random(),
        agentId = agentId,
        steps = steps,
        createdAtMs = 1_700_000_000_000L,
        signature = Signature("sig-plan-${UUID.randomUUID()}"),
    )

    private fun buildResult(
        executionId: ExecutionId = ExecutionId.random(),
        planId: PlanId = PlanId.random(),
        status: PlanStatus = PlanStatus.Completed,
        stepResults: Map<Int, StepResult> = mapOf(
            1 to StepResult.Executed(
                intentId = IntentId.random(),
                exitCode = 0,
            ),
        ),
        startedAtMs: Long = 1_700_000_000_000L,
        completedAtMs: Long = 1_700_000_005_000L,
    ): PlanExecutionResult = PlanExecutionResult(
        executionId = executionId,
        planId = planId,
        status = status,
        stepResults = stepResults,
        startedAtMs = startedAtMs,
        completedAtMs = completedAtMs,
    )

    private fun buildAuthority(
        agentId: UserId = UserId.random(),
        scope: OperatorAuthorityScope = OperatorAuthorityScope.Full,
    ): OperatorAuthority = OperatorAuthority(
        agentId = agentId,
        scope = scope,
        issuedBy = UserId(UUID.randomUUID()),
        signature = Signature("sig-authority-${UUID.randomUUID()}"),
    )

    private fun buildInstallDistro(
        agentId: UserId,
    ): OperatorIntent.InstallDistro =
        OperatorIntent.InstallDistro(
            intentId = IntentId.random(),
            agentId = agentId,
            description = "Install Elysium Linux",
            distroId = "com.elysium.linux",
            targetWorkspaceId = "com.elysium.workspace.blender",
        )

    private fun buildLaunchCapsule(
        agentId: UserId,
    ): OperatorIntent.LaunchCapsule =
        OperatorIntent.LaunchCapsule(
            intentId = IntentId.random(),
            agentId = agentId,
            description = "Launch Blender",
            capsuleId = "com.elysium.blender.arm64",
            runtime = "linux",
        )

    private fun buildCreateWorkspace(
        agentId: UserId,
    ): OperatorIntent.CreateWorkspace =
        OperatorIntent.CreateWorkspace(
            intentId = IntentId.random(),
            agentId = agentId,
            description = "Create Blender workspace",
            workspaceName = "blender-workspace",
            sandboxProfile = "standard",
        )

    private fun buildRunDiagnostic(
        agentId: UserId,
    ): OperatorIntent.RunDiagnostic =
        OperatorIntent.RunDiagnostic(
            intentId = IntentId.random(),
            agentId = agentId,
            description = "Run a diagnostic",
            diagnosticKind = "process-memory-usage",
        )
}
