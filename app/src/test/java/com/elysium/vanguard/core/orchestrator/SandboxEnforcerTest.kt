package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 89 (Universal Execution Engine) — the
 * JVM tests for [SandboxEnforcer].
 *
 * The tests cover:
 *   - EnforcementStep invariants
 *     (timestampMs > 0, blank reason).
 *   - SandboxEnforcementResult invariants
 *     (empty steps, non-positive
 *     enforcedAtMs, bindMountedSteps
 *     helper, skippedSteps helper,
 *     hasSkippedSteps helper).
 *   - InMemorySandboxEnforcer:
 *     - The enforcer produces a step
 *       for each preparation step.
 *     - The enforcer produces matching
 *       steps for each preparation step
 *       type.
 *     - The enforcer preserves the
 *       preparation's order.
 *   - Realistic scenario: a full
 *     preparation is enforced.
 */
class SandboxEnforcerTest {

    // ============================================================
    // EnforcementStep invariants
    // ============================================================

    @Test
    fun `EnforcementStep BindMounted rejects non-positive timestampMs`() {
        try {
            EnforcementStep.BindMounted(
                mountEntry = buildMount(),
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
    fun `EnforcementStep SeLinuxContextApplied rejects non-positive timestampMs`() {
        try {
            EnforcementStep.SeLinuxContextApplied(
                securityProfile = SecurityProfile.Standard,
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
    fun `EnforcementStep ResourceLimitsApplied rejects non-positive timestampMs`() {
        try {
            EnforcementStep.ResourceLimitsApplied(
                sandboxLimits = SandboxLimits.DEFAULT,
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
    fun `EnforcementStep NetworkPolicyApplied rejects non-positive timestampMs`() {
        try {
            EnforcementStep.NetworkPolicyApplied(
                networkPolicy = NetworkPolicy.LocalOnly,
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
    fun `EnforcementStep Skipped rejects blank reason`() {
        try {
            EnforcementStep.Skipped(
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
    fun `EnforcementStep Skipped rejects non-positive timestampMs`() {
        try {
            EnforcementStep.Skipped(
                reason = "test",
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

    // ============================================================
    // SandboxEnforcementResult invariants
    // ============================================================

    @Test
    fun `SandboxEnforcementResult rejects empty steps`() {
        try {
            buildResult(steps = emptyList())
            fail(
                "expected IllegalArgumentException for " +
                    "empty steps",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("steps"))
        }
    }

    @Test
    fun `SandboxEnforcementResult rejects non-positive enforcedAtMs`() {
        try {
            buildResult(enforcedAtMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive enforcedAtMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("enforcedAtMs"))
        }
    }

    // ============================================================
    // InMemorySandboxEnforcer
    // ============================================================

    @Test
    fun `enforce produces a step for each preparation step`() {
        val enforcer = InMemorySandboxEnforcer()
        val preparation = buildPreparation(
            steps = listOf(
                PreparationStep.BindMount(buildMount()),
                PreparationStep.ApplySeLinuxContext(
                    securityProfile = SecurityProfile.Standard,
                ),
                PreparationStep.ApplyResourceLimits(
                    sandboxLimits = SandboxLimits.DEFAULT,
                ),
                PreparationStep.ApplyNetworkPolicy(
                    networkPolicy = NetworkPolicy.LocalOnly,
                ),
                PreparationStep.Skipped(reason = "test reason"),
            ),
        )
        val result = enforcer.enforce(
            preparation = preparation,
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(5, result.steps.size)
    }

    @Test
    fun `enforce produces matching steps for each preparation step type`() {
        val enforcer = InMemorySandboxEnforcer()
        val mount = buildMount()
        val preparation = buildPreparation(
            steps = listOf(
                PreparationStep.BindMount(mount),
                PreparationStep.ApplySeLinuxContext(
                    securityProfile = SecurityProfile.Strict,
                ),
                PreparationStep.ApplyResourceLimits(
                    sandboxLimits = SandboxLimits.DEFAULT,
                ),
                PreparationStep.ApplyNetworkPolicy(
                    networkPolicy = NetworkPolicy.Allowlisted(
                        allowlist = setOf("api.example.com"),
                    ),
                ),
                PreparationStep.Skipped(
                    reason = "OS-level enforcement " +
                        "is the OS executor's " +
                        "responsibility",
                ),
            ),
        )
        val result = enforcer.enforce(
            preparation = preparation,
            nowMs = 1_700_000_000_000L,
        )
        // Step 0: BindMounted.
        assertTrue(
            result.steps[0]
                is EnforcementStep.BindMounted,
        )
        assertEquals(
            mount,
            (result.steps[0]
                as EnforcementStep.BindMounted).mountEntry,
        )
        // Step 1: SeLinuxContextApplied.
        assertTrue(
            result.steps[1]
                is EnforcementStep.SeLinuxContextApplied,
        )
        assertEquals(
            SecurityProfile.Strict,
            (result.steps[1]
                as EnforcementStep.SeLinuxContextApplied)
                .securityProfile,
        )
        // Step 2: ResourceLimitsApplied.
        assertTrue(
            result.steps[2]
                is EnforcementStep.ResourceLimitsApplied,
        )
        // Step 3: NetworkPolicyApplied.
        assertTrue(
            result.steps[3]
                is EnforcementStep.NetworkPolicyApplied,
        )
        // Step 4: Skipped.
        assertTrue(
            result.steps[4]
                is EnforcementStep.Skipped,
        )
    }

    @Test
    fun `enforce preserves the preparation order`() {
        val enforcer = InMemorySandboxEnforcer()
        val mount1 = buildMount(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
        )
        val mount2 = buildMount(
            source = ElysiumRootfsPath(
                "/workspaces/data",
            ),
            target = ElysiumRootfsPath(
                "/workspaces/data",
            ),
        )
        val preparation = buildPreparation(
            steps = listOf(
                PreparationStep.BindMount(mount1),
                PreparationStep.BindMount(mount2),
                PreparationStep.ApplySeLinuxContext(
                    securityProfile = SecurityProfile.Standard,
                ),
                PreparationStep.Skipped(reason = "test reason"),
            ),
        )
        val result = enforcer.enforce(
            preparation = preparation,
            nowMs = 1_700_000_000_000L,
        )
        val bindMounted = result.bindMountedSteps
        assertEquals(2, bindMounted.size)
        assertEquals(mount1, bindMounted[0])
        assertEquals(mount2, bindMounted[1])
    }

    @Test
    fun `enforce produces a result with the expected preparation id`() {
        val enforcer = InMemorySandboxEnforcer()
        val preparationId = UUID.randomUUID()
        val preparation = buildPreparation(
            preparationId = preparationId,
        )
        val result = enforcer.enforce(
            preparation = preparation,
            nowMs = 1_700_000_000_000L,
        )
        assertEquals(preparationId, result.preparationId)
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario a full preparation is enforced`() {
        val enforcer = InMemorySandboxEnforcer()
        val mount1 = buildMount(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
            purpose = MountPurpose.SystemLibraries,
        )
        val mount2 = buildMount(
            source = ElysiumRootfsPath(
                "/data/user-selected",
            ),
            target = ElysiumRootfsPath(
                "/workspaces/data",
            ),
            purpose = MountPurpose.WorkspaceData(
                WorkspaceId.random(),
            ),
        )
        val mount3 = buildMount(
            source = ElysiumRootfsPath("/dev"),
            target = ElysiumRootfsPath("/dev"),
            purpose = MountPurpose.DeviceNodes,
        )
        val preparation = buildPreparation(
            steps = listOf(
                PreparationStep.BindMount(mount1),
                PreparationStep.BindMount(mount2),
                PreparationStep.BindMount(mount3),
                PreparationStep.ApplySeLinuxContext(
                    securityProfile = SecurityProfile.Standard,
                ),
                PreparationStep.ApplyResourceLimits(
                    sandboxLimits = SandboxLimits.DEFAULT,
                ),
                PreparationStep.ApplyNetworkPolicy(
                    networkPolicy = NetworkPolicy.LocalOnly,
                ),
                PreparationStep.Skipped(
                    reason = "OS-level enforcement " +
                        "is the OS executor's " +
                        "responsibility",
                ),
            ),
        )
        val result = enforcer.enforce(
            preparation = preparation,
            nowMs = 1_700_000_000_000L,
        )

        // The result has 7 steps.
        assertEquals(7, result.steps.size)

        // The bind mounted steps are the
        // 3 mounts in order.
        val bindMounted = result.bindMountedSteps
        assertEquals(3, bindMounted.size)
        assertEquals(mount1, bindMounted[0])
        assertEquals(mount2, bindMounted[1])
        assertEquals(mount3, bindMounted[2])

        // The result has 1 skipped step
        // (the final Skipped preparation
        // step).
        val skipped = result.skippedSteps
        assertEquals(1, skipped.size)
        assertTrue(result.hasSkippedSteps)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildMount(
        source: ElysiumRootfsPath = ElysiumRootfsPath(
            "/workspaces/source",
        ),
        target: ElysiumRootfsPath = ElysiumRootfsPath(
            "/workspaces/target",
        ),
        mode: MountMode = MountMode.READ_WRITE,
        purpose: MountPurpose = MountPurpose.WorkspaceData(
            WorkspaceId.random(),
        ),
    ): MountEntry = MountEntry(
        source = source,
        target = target,
        mode = mode,
        purpose = purpose,
    )

    private fun buildPreparation(
        preparationId: UUID = UUID.randomUUID(),
        steps: List<PreparationStep> = listOf(
            PreparationStep.ApplySeLinuxContext(
                securityProfile = SecurityProfile.Standard,
            ),
        ),
    ): SandboxPreparation = SandboxPreparation(
        preparationId = preparationId,
        plan = LaunchPlan(
            runtime = LaunchRuntime.BOX64,
            executable = "/usr/bin/box64",
            args = listOf(
                "/usr/bin/box64",
                "/opt/steam/steam",
            ),
            workingDirectory = "/opt/steam",
            environment = emptyMap(),
        ),
        policy = SandboxPolicy(
            workspaceId = WorkspaceId.random(),
            mounts = listOf(buildMount()),
            limits = SandboxLimits.DEFAULT,
            network = NetworkPolicy.LocalOnly,
            security = SecurityProfile.Standard,
            signature = Signature(
                "sig-policy-${UUID.randomUUID()}",
            ),
        ),
        steps = steps,
        preparedAtMs = 1_700_000_000_000L,
        signature = Signature(
            "sig-prep-${UUID.randomUUID()}",
        ),
    )

    private fun buildResult(
        steps: List<EnforcementStep> = listOf(
            EnforcementStep.SeLinuxContextApplied(
                securityProfile = SecurityProfile.Standard,
                timestampMs = 1_700_000_000_000L,
            ),
        ),
        enforcedAtMs: Long = 1_700_000_000_000L,
    ): SandboxEnforcementResult = SandboxEnforcementResult(
        enforcementId = UUID.randomUUID(),
        preparationId = UUID.randomUUID(),
        steps = steps,
        enforcedAtMs = enforcedAtMs,
        signature = Signature(
            "sig-enforce-${UUID.randomUUID()}",
        ),
    )
}
