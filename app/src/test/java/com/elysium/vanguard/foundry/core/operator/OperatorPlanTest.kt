package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 84 (Foundry / AI Operator) — the JVM
 * tests for [OperatorPlan].
 *
 * The tests cover:
 *   - PlanStep invariants (order > 0,
 *     description not blank).
 *   - OperatorPlan invariants (steps not
 *     empty, createdAtMs > 0, stepByOrder,
 *     firstStep, stepCount, sortedOrders).
 *   - PlanStatus invariants (Failed /
 *     Paused reason not blank).
 *   - PlanValidationResult invariants
 *     (Invalid errors not empty).
 *   - InMemoryOperatorPlanValidator
 *     (valid plan, duplicate orders,
 *     non-contiguous orders, mismatched
 *     agentId, isValid helper).
 *   - Realistic scenario: a 3-step plan
 *     (install distro, launch capsule,
 *     create snapshot — the "Install
 *     Blender" scenario from the master
 *     vision).
 */
class OperatorPlanTest {

    // ============================================================
    // PlanStep invariants
    // ============================================================

    @Test
    fun `PlanStep accepts a well-formed configuration`() {
        val step = buildStep()
        assertEquals(1, step.order)
    }

    @Test
    fun `PlanStep rejects non-positive order`() {
        try {
            buildStep(order = 0)
            fail("expected IllegalArgumentException for order <= 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("order"))
        }
    }

    @Test
    fun `PlanStep rejects blank description`() {
        try {
            buildStep(description = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank description",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description"))
        }
    }

    // ============================================================
    // OperatorPlan invariants
    // ============================================================

    @Test
    fun `OperatorPlan accepts a well-formed configuration`() {
        val plan = buildPlan()
        assertEquals(1, plan.stepCount)
    }

    @Test
    fun `OperatorPlan rejects empty steps`() {
        try {
            buildPlan(steps = emptyList())
            fail(
                "expected IllegalArgumentException for " +
                    "empty steps",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("steps"))
        }
    }

    @Test
    fun `OperatorPlan rejects non-positive createdAtMs`() {
        try {
            buildPlan(createdAtMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive createdAtMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("createdAtMs"))
        }
    }

    @Test
    fun `OperatorPlan stepByOrder returns the step for the order`() {
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 1, description = "Step 1"),
                buildStep(order = 2, description = "Step 2"),
                buildStep(order = 3, description = "Step 3"),
            ),
        )
        val step2 = plan.stepByOrder(2)
        assertNotNull(step2)
        assertEquals("Step 2", step2!!.description)
    }

    @Test
    fun `OperatorPlan stepByOrder returns null for an unknown order`() {
        val plan = buildPlan()
        val found = plan.stepByOrder(99)
        assertNull(found)
    }

    @Test
    fun `OperatorPlan firstStep returns the step with the lowest order`() {
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 3, description = "Step 3"),
                buildStep(order = 1, description = "Step 1"),
                buildStep(order = 2, description = "Step 2"),
            ),
        )
        assertEquals(1, plan.firstStep.order)
        assertEquals("Step 1", plan.firstStep.description)
    }

    @Test
    fun `OperatorPlan stepCount returns the number of steps`() {
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 1),
                buildStep(order = 2),
                buildStep(order = 3),
            ),
        )
        assertEquals(3, plan.stepCount)
    }

    @Test
    fun `OperatorPlan sortedOrders returns the orders in sorted order`() {
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 3),
                buildStep(order = 1),
                buildStep(order = 2),
            ),
        )
        assertEquals(listOf(1, 2, 3), plan.sortedOrders)
    }

    // ============================================================
    // PlanStatus invariants
    // ============================================================

    @Test
    fun `PlanStatus Failed rejects blank reason`() {
        try {
            PlanStatus.Failed(reason = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `PlanStatus Paused rejects blank reason`() {
        try {
            PlanStatus.Paused(reason = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    // ============================================================
    // PlanValidationResult invariants
    // ============================================================

    @Test
    fun `PlanValidationResult Invalid rejects empty errors`() {
        try {
            PlanValidationResult.Invalid(errors = emptyList())
            fail(
                "expected IllegalArgumentException for " +
                    "empty errors",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("errors"))
        }
    }

    // ============================================================
    // InMemoryOperatorPlanValidator
    // ============================================================

    @Test
    fun `validator returns Valid for a well-formed plan`() {
        val validator = InMemoryOperatorPlanValidator()
        val planAgentId = UserId.random()
        val plan = buildPlan(
            agentId = planAgentId,
            steps = listOf(
                buildStep(order = 1, agentId = planAgentId),
                buildStep(order = 2, agentId = planAgentId),
                buildStep(order = 3, agentId = planAgentId),
            ),
        )
        val result = validator.validate(plan)
        assertTrue(result is PlanValidationResult.Valid)
        assertTrue(validator.isValid(plan))
    }

    @Test
    fun `validator reports duplicate step orders`() {
        val validator = InMemoryOperatorPlanValidator()
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 1),
                buildStep(order = 2),
                buildStep(order = 2),
            ),
        )
        val result = validator.validate(plan)
        assertTrue(result is PlanValidationResult.Invalid)
        val errors = (result as PlanValidationResult.Invalid).errors
        assertTrue(
            errors.any { it.contains("duplicate step orders") },
        )
    }

    @Test
    fun `validator reports non-contiguous step orders`() {
        val validator = InMemoryOperatorPlanValidator()
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 1),
                buildStep(order = 3),
            ),
        )
        val result = validator.validate(plan)
        assertTrue(result is PlanValidationResult.Invalid)
        val errors = (result as PlanValidationResult.Invalid).errors
        assertTrue(
            errors.any { it.contains("not contiguous") },
        )
    }

    @Test
    fun `validator reports steps with mismatched agentId`() {
        val validator = InMemoryOperatorPlanValidator()
        val planAgentId = UserId.random()
        val otherAgentId = UserId.random()
        val plan = buildPlan(
            agentId = planAgentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    agentId = otherAgentId,
                ),
                buildStep(
                    order = 2,
                    agentId = planAgentId,
                ),
            ),
        )
        val result = validator.validate(plan)
        assertTrue(result is PlanValidationResult.Invalid)
        val errors = (result as PlanValidationResult.Invalid).errors
        assertTrue(
            errors.any { it.contains("mismatched agentId") },
        )
    }

    @Test
    fun `validator isValid returns true for a valid plan`() {
        val validator = InMemoryOperatorPlanValidator()
        val plan = buildPlan()
        assertTrue(validator.isValid(plan))
    }

    @Test
    fun `validator isValid returns false for an invalid plan`() {
        val validator = InMemoryOperatorPlanValidator()
        val plan = buildPlan(
            steps = listOf(
                buildStep(order = 1),
                buildStep(order = 1),
            ),
        )
        assertTrue(!validator.isValid(plan))
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario the "Install Blender" plan from the master vision is a valid 3-step plan`() {
        // The master vision's example
        // (section 8):
        // "Instala Blender, configura
        // aceleración Vulkan y crea un
        // acceso directo."
        // The AI converts this to a
        // 3-step plan:
        //   1. InstallDistro(
        //      "com.elysium.linux")
        //   2. LaunchCapsule(
        //      "com.elysium.blender",
        //      runtime="linux")
        //   3. CreateWorkspace(
        //      "workspaces/blender",
        //      sandboxProfile="standard")
        val validator = InMemoryOperatorPlanValidator()
        val agentId = UserId.random()
        val plan = buildPlan(
            agentId = agentId,
            steps = listOf(
                buildStep(
                    order = 1,
                    description = "Install Elysium Linux",
                    intent = buildInstallDistro(
                        agentId = agentId,
                    ),
                ),
                buildStep(
                    order = 2,
                    description = "Launch Blender",
                    intent = buildLaunchCapsule(
                        agentId = agentId,
                    ),
                ),
                buildStep(
                    order = 3,
                    description = "Create workspace",
                    intent = buildCreateWorkspace(
                        agentId = agentId,
                    ),
                ),
            ),
        )

        // Step 1: The plan is valid.
        val result = validator.validate(plan)
        assertTrue(
            "plan should be valid, got: $result",
            result is PlanValidationResult.Valid,
        )

        // Step 2: The plan has 3 steps.
        assertEquals(3, plan.stepCount)

        // Step 3: The first step is the
        // install.
        val firstStep = plan.firstStep
        assertTrue(
            firstStep.intent
                is OperatorIntent.InstallDistro,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildStep(
        order: Int = 1,
        description: String = "Test step",
        agentId: UserId = UserId.random(),
        intent: OperatorIntent? = null,
    ): PlanStep {
        val resolvedIntent: OperatorIntent = intent
            ?: OperatorIntent.RunDiagnostic(
                intentId = IntentId.random(),
                agentId = agentId,
                description = "Run a diagnostic",
                diagnosticKind = "process-memory-usage",
            )
        return PlanStep(
            order = order,
            description = description,
            intent = resolvedIntent,
        )
    }

    private fun buildPlan(
        planId: PlanId = PlanId.random(),
        agentId: UserId = UserId.random(),
        steps: List<PlanStep> = listOf(buildStep(agentId = agentId)),
        createdAtMs: Long = 1_700_000_000_000L,
    ): OperatorPlan = OperatorPlan(
        planId = planId,
        agentId = agentId,
        steps = steps,
        createdAtMs = createdAtMs,
        signature = Signature("sig-plan-${UUID.randomUUID()}"),
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
}
