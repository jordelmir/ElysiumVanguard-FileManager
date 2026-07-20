package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 87 (Universal Execution Engine) — the
 * JVM tests for [SandboxApplication].
 *
 * The tests cover:
 *   - PreparationStep.Skipped invariants
 *     (blank reason rejected).
 *   - SandboxPreparation invariants
 *     (empty steps rejected, non-positive
 *     preparedAtMs rejected, bindMountSteps
 *     helper, skippedSteps helper,
 *     hasSkippedSteps helper).
 *   - InMemorySandboxApplication:
 *     - The preparation has the expected
 *       steps in order (BindMount +
 *       ApplySeLinuxContext +
 *       ApplyResourceLimits +
 *       ApplyNetworkPolicy + Skipped).
 *     - The bind mount steps match the
 *       policy's mounts.
 *     - The security profile is the
 *       policy's security.
 *     - The resource limits are the
 *       policy's limits.
 *     - The network policy is the policy's
 *       network.
 *     - The preparation is partial
 *       (has skipped steps).
 *   - Realistic scenario: a workspace
 *     with 3 mounts + Standard security +
 *     DEFAULT limits + LocalOnly network.
 */
class SandboxApplicationTest {

    // ============================================================
    // PreparationStep invariants
    // ============================================================

    @Test
    fun `PreparationStep Skipped rejects blank reason`() {
        try {
            PreparationStep.Skipped(reason = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    // ============================================================
    // SandboxPreparation invariants
    // ============================================================

    @Test
    fun `SandboxPreparation rejects empty steps`() {
        try {
            buildPreparation(steps = emptyList())
            fail(
                "expected IllegalArgumentException for " +
                    "empty steps",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("steps"))
        }
    }

    @Test
    fun `SandboxPreparation rejects non-positive preparedAtMs`() {
        try {
            buildPreparation(preparedAtMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive preparedAtMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("preparedAtMs"))
        }
    }

    @Test
    fun `SandboxPreparation bindMountSteps returns the bind mount entries in order`() {
        val mount1 = buildMount(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
        )
        val mount2 = buildMount(
            source = ElysiumRootfsPath("/workspaces/data"),
            target = ElysiumRootfsPath("/workspaces/data"),
        )
        val mount3 = buildMount(
            source = ElysiumRootfsPath("/dev"),
            target = ElysiumRootfsPath("/dev"),
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
                    reason = "OS-level enforcement is " +
                        "the OS executor's " +
                        "responsibility",
                ),
            ),
        )
        val bindMounts = preparation.bindMountSteps
        assertEquals(3, bindMounts.size)
        assertEquals(mount1, bindMounts[0])
        assertEquals(mount2, bindMounts[1])
        assertEquals(mount3, bindMounts[2])
    }

    @Test
    fun `SandboxPreparation skippedSteps returns the skipped steps in order`() {
        val preparation = buildPreparation(
            steps = listOf(
                PreparationStep.ApplySeLinuxContext(
                    securityProfile = SecurityProfile.Standard,
                ),
                PreparationStep.Skipped(reason = "reason 1"),
                PreparationStep.Skipped(reason = "reason 2"),
            ),
        )
        val skipped = preparation.skippedSteps
        assertEquals(2, skipped.size)
        assertEquals("reason 1", skipped[0].reason)
        assertEquals("reason 2", skipped[1].reason)
        assertTrue(preparation.hasSkippedSteps)
    }

    @Test
    fun `SandboxPreparation hasSkippedSteps returns false when no skipped steps`() {
        val preparation = buildPreparation(
            steps = listOf(
                PreparationStep.ApplySeLinuxContext(
                    securityProfile = SecurityProfile.Standard,
                ),
                PreparationStep.ApplyResourceLimits(
                    sandboxLimits = SandboxLimits.DEFAULT,
                ),
            ),
        )
        assertTrue(!preparation.hasSkippedSteps)
    }

    // ============================================================
    // InMemorySandboxApplication
    // ============================================================

    @Test
    fun `prepare emits the expected steps in order`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val policy = buildPolicy(
            mounts = listOf(
                buildMount(
                    source = ElysiumRootfsPath("/usr/lib"),
                    target = ElysiumRootfsPath("/usr/lib"),
                ),
            ),
        )
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )
        // 1 BindMount + ApplySeLinuxContext +
        // ApplyResourceLimits + ApplyNetworkPolicy
        // + Skipped = 5 steps.
        assertEquals(5, preparation.steps.size)

        // Step 0: BindMount for the single mount.
        assertTrue(
            preparation.steps[0]
                is PreparationStep.BindMount,
        )
        // Step 1: ApplySeLinuxContext.
        assertTrue(
            preparation.steps[1]
                is PreparationStep.ApplySeLinuxContext,
        )
        // Step 2: ApplyResourceLimits.
        assertTrue(
            preparation.steps[2]
                is PreparationStep.ApplyResourceLimits,
        )
        // Step 3: ApplyNetworkPolicy.
        assertTrue(
            preparation.steps[3]
                is PreparationStep.ApplyNetworkPolicy,
        )
        // Step 4: Skipped.
        assertTrue(
            preparation.steps[4]
                is PreparationStep.Skipped,
        )
    }

    @Test
    fun `prepare emits a BindMount step for each mount in the policy`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val mount1 = buildMount(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
        )
        val mount2 = buildMount(
            source = ElysiumRootfsPath("/workspaces/data"),
            target = ElysiumRootfsPath("/workspaces/data"),
        )
        val policy = buildPolicy(mounts = listOf(mount1, mount2))
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )
        val bindMounts = preparation.bindMountSteps
        assertEquals(2, bindMounts.size)
        assertEquals(mount1, bindMounts[0])
        assertEquals(mount2, bindMounts[1])
    }

    @Test
    fun `prepare emits the policy's security profile in the SELinux step`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val policy = buildPolicy(
            security = SecurityProfile.Strict,
        )
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )
        val selinuxStep = preparation.steps[1]
            as PreparationStep.ApplySeLinuxContext
        assertEquals(
            SecurityProfile.Strict,
            selinuxStep.securityProfile,
        )
    }

    @Test
    fun `prepare emits the policy's limits in the resource limits step`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val customLimits = SandboxLimits(
            maxMemoryMb = 4_096L,
            maxCpuPercent = 80,
            maxOpenFileDescriptors = 2_048,
            maxProcesses = 128,
            maxDiskWriteMb = 500L,
        )
        val policy = buildPolicy(limits = customLimits)
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )
        val limitsStep = preparation.steps[2]
            as PreparationStep.ApplyResourceLimits
        assertEquals(customLimits, limitsStep.sandboxLimits)
    }

    @Test
    fun `prepare emits the policy's network in the network policy step`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val policy = buildPolicy(
            network = NetworkPolicy.Allowlisted(
                allowlist = setOf("api.example.com"),
            ),
        )
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )
        val networkStep = preparation.steps[3]
            as PreparationStep.ApplyNetworkPolicy
        assertEquals(
            NetworkPolicy.Allowlisted(
                allowlist = setOf("api.example.com"),
            ),
            networkStep.networkPolicy,
        )
    }

    @Test
    fun `prepare produces a partial preparation (has skipped steps)`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val policy = buildPolicy()
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(preparation.hasSkippedSteps)
        assertEquals(1, preparation.skippedSteps.size)
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario a workspace with 3 mounts, Standard security, DEFAULT limits, LocalOnly network`() {
        val applier = InMemorySandboxApplication()
        val plan = buildPlan()
        val policy = buildPolicy(
            mounts = listOf(
                buildMount(
                    source = ElysiumRootfsPath("/usr/lib"),
                    target = ElysiumRootfsPath("/usr/lib"),
                    purpose = MountPurpose.SystemLibraries,
                ),
                buildMount(
                    source = ElysiumRootfsPath(
                        "/data/user-selected",
                    ),
                    target = ElysiumRootfsPath(
                        "/workspaces/data",
                    ),
                    purpose = MountPurpose.WorkspaceData(
                        WorkspaceId.random(),
                    ),
                ),
                buildMount(
                    source = ElysiumRootfsPath("/dev"),
                    target = ElysiumRootfsPath("/dev"),
                    purpose = MountPurpose.DeviceNodes,
                ),
            ),
            security = SecurityProfile.Standard,
            network = NetworkPolicy.LocalOnly,
        )
        val preparation = applier.prepare(
            plan = plan,
            policy = policy,
            nowMs = 1_700_000_000_000L,
        )

        // Step 1: The preparation has 3
        // bind mount steps + 1 SELinux
        // + 1 limits + 1 network + 1
        // skipped = 7 steps.
        assertEquals(7, preparation.steps.size)

        // Step 2: The bind mounts are the
        // 3 mounts in order.
        val bindMounts = preparation.bindMountSteps
        assertEquals(3, bindMounts.size)

        // Step 3: The SELinux step uses
        // Standard.
        val selinuxStep = preparation.steps[3]
            as PreparationStep.ApplySeLinuxContext
        assertEquals(
            SecurityProfile.Standard,
            selinuxStep.securityProfile,
        )

        // Step 4: The resource limits
        // step uses DEFAULT.
        val limitsStep = preparation.steps[4]
            as PreparationStep.ApplyResourceLimits
        assertEquals(
            SandboxLimits.DEFAULT,
            limitsStep.sandboxLimits,
        )

        // Step 5: The network policy step
        // uses LocalOnly.
        val networkStep = preparation.steps[5]
            as PreparationStep.ApplyNetworkPolicy
        assertEquals(
            NetworkPolicy.LocalOnly,
            networkStep.networkPolicy,
        )

        // Step 6: The preparation is
        // partial.
        assertTrue(preparation.hasSkippedSteps)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildPlan(): LaunchPlan = LaunchPlan(
        runtime = LaunchRuntime.BOX64,
        executable = "/usr/bin/box64",
        args = listOf(
            "/usr/bin/box64",
            "/opt/steam/steam",
        ),
        workingDirectory = "/opt/steam",
        environment = emptyMap(),
    )

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

    private fun buildPolicy(
        workspaceId: WorkspaceId = WorkspaceId.random(),
        mounts: List<MountEntry> = listOf(buildMount()),
        limits: SandboxLimits = SandboxLimits.DEFAULT,
        network: NetworkPolicy = NetworkPolicy.LocalOnly,
        security: SecurityProfile = SecurityProfile.Standard,
    ): SandboxPolicy = SandboxPolicy(
        workspaceId = workspaceId,
        mounts = mounts,
        limits = limits,
        network = network,
        security = security,
        signature = Signature(
            "sig-policy-${UUID.randomUUID()}",
        ),
    )

    private fun buildPreparation(
        preparationId: UUID = UUID.randomUUID(),
        plan: LaunchPlan = buildPlan(),
        policy: SandboxPolicy = buildPolicy(),
        steps: List<PreparationStep> = listOf(
            PreparationStep.ApplySeLinuxContext(
                securityProfile = SecurityProfile.Standard,
            ),
        ),
        preparedAtMs: Long = 1_700_000_000_000L,
    ): SandboxPreparation = SandboxPreparation(
        preparationId = preparationId,
        plan = plan,
        policy = policy,
        steps = steps,
        preparedAtMs = preparedAtMs,
        signature = Signature(
            "sig-prep-${UUID.randomUUID()}",
        ),
    )
}
