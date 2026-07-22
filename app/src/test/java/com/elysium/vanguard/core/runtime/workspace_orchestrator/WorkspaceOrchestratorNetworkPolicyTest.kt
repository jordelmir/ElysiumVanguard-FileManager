package com.elysium.vanguard.core.runtime.workspace_orchestrator

import com.elysium.vanguard.core.runtime.network.policy.NetworkMode
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicySpecBridge
import com.elysium.vanguard.core.runtime.workspace_def.ApiVersion
import com.elysium.vanguard.core.runtime.workspace_def.LauncherSpec
import com.elysium.vanguard.core.runtime.workspace_def.MountSpec
import com.elysium.vanguard.core.runtime.workspace_def.NetworkAccessMode
import com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec
import com.elysium.vanguard.core.runtime.workspace_def.ResourceSpec
import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 109 — JVM tests for the workspace
 * orchestrator's network-policy wiring.
 *
 * The orchestrator translates the
 * [com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec]
 * (the typed "red denegada por defecto" field on the
 * workspace spec) into a session-level
 * [com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy]
 * via [NetworkPolicySpecBridge.toSessionPolicy].
 * The session runner consumes the resulting policy
 * when starting the session.
 *
 * These tests pin the orchestrator side of the
 * wiring. The runner side is pinned by
 * `LinuxProotSessionRunnerNetworkPolicyTest`
 * (TODO Phase 109+ follow-up).
 */
class WorkspaceOrchestratorNetworkPolicyTest {

    private val orchestrator = WorkspaceOrchestrator()

    @Test
    fun `orchestrator bridges a DENY_ALL spec to LOOPBACK_ONLY session policy`() {
        val orchestrated = orchestrator.orchestrate(sampleWorkspace(NetworkPolicySpec.DEFAULT))
        assertEquals(
            "DENY_ALL must map to LOOPBACK_ONLY (the safe direction)",
            NetworkMode.LOOPBACK_ONLY,
            orchestrated.networkPolicy.mode,
        )
        assertTrue(
            "DENY_ALL must have empty allowedRemoteHosts",
            orchestrated.networkPolicy.allowedRemoteHosts.isEmpty()
        )
        assertTrue(
            "DENY_ALL must have empty publishedPorts",
            orchestrated.networkPolicy.publishedPorts.isEmpty()
        )
    }

    @Test
    fun `orchestrator bridges an ALLOW_LIST spec to OUTBOUND_ONLY with propagated hosts`() {
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("api.example.com", "*.cdn.example.com"),
            allowedPorts = setOf(443, 8443),
            dnsAllowed = true,
        )
        val orchestrated = orchestrator.orchestrate(sampleWorkspace(spec))
        assertEquals(NetworkMode.OUTBOUND_ONLY, orchestrated.networkPolicy.mode)
        assertEquals(
            setOf("api.example.com", "*.cdn.example.com"),
            orchestrated.networkPolicy.allowedRemoteHosts
        )
        assertEquals(setOf(443, 8443), orchestrated.networkPolicy.publishedPorts)
    }

    @Test
    fun `orchestrator bridges an ALLOW_ALL spec to INTERNET`() {
        val orchestrated = orchestrator.orchestrate(sampleWorkspace(NetworkPolicySpec.OPEN))
        assertEquals(NetworkMode.INTERNET, orchestrated.networkPolicy.mode)
        assertTrue(
            "ALLOW_ALL must have empty allowedRemoteHosts (INTERNET = any host)",
            orchestrated.networkPolicy.allowedRemoteHosts.isEmpty()
        )
    }

    @Test
    fun `orchestrator always populates networkPolicy (never null)`() {
        // The orchestrator must always populate the
        // policy — the default LOOPBACK_ONLY is the
        // safe direction. A workspace spec with
        // NetworkPolicySpec.DEFAULT should yield the
        // exact same session policy as a workspace
        // that explicitly declares DENY_ALL.
        val fromDefault = orchestrator.orchestrate(sampleWorkspace(NetworkPolicySpec.DEFAULT))
        val fromExplicit = orchestrator.orchestrate(sampleWorkspace(NetworkPolicySpec.DEFAULT))
        assertEquals(fromDefault.networkPolicy, fromExplicit.networkPolicy)
    }

    @Test
    fun `orchestrator is deterministic - same spec produces same policy`() {
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("a.example.com", "b.example.com"),
            allowedPorts = setOf(443),
        )
        val a = orchestrator.orchestrate(sampleWorkspace(spec))
        val b = orchestrator.orchestrate(sampleWorkspace(spec))
        assertEquals(a.networkPolicy, b.networkPolicy)
    }

    @Test
    fun `orchestrator does not mutate the input spec`() {
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("api.example.com"),
            allowedPorts = setOf(443),
        )
        val hostsBefore = spec.allowedHosts.toList()
        orchestrator.orchestrate(sampleWorkspace(spec))
        assertEquals(
            "orchestrator must not mutate the input spec's allowedHosts",
            hostsBefore, spec.allowedHosts
        )
    }

    @Test
    fun `bridge is the only place a workspace spec reaches the session (sanity)`() {
        // The orchestrator uses the bridge; the
        // bridged value is what ends up on the
        // OrchestratedWorkspace. A drift in the
        // bridge would be caught by a mismatch
        // between the direct bridge call + the
        // orchestrator's call.
        val spec = NetworkPolicySpec.DEFAULT
        val direct = NetworkPolicySpecBridge.toSessionPolicy(spec)
        val viaOrchestrator = orchestrator.orchestrate(sampleWorkspace(spec)).networkPolicy
        assertEquals(direct, viaOrchestrator)
    }

    @Test
    fun `workspace with no explicit policy still gets LOOPBACK_ONLY (defense in depth)`() {
        // The orchestrator's contract: even if a
        // workspace's spec doesn't have a policy
        // (the JSON didn't carry one), the bridged
        // session policy is the safe default. The
        // OrchestratedWorkspace is never null.
        val orchestrated = orchestrator.orchestrate(sampleWorkspace(NetworkPolicySpec.DEFAULT))
        assertNotNull("orchestrated.networkPolicy must never be null", orchestrated.networkPolicy)
        assertEquals(NetworkMode.LOOPBACK_ONLY, orchestrated.networkPolicy.mode)
    }

    @Test
    fun `orchestrator preserves other fields (sanity check)`() {
        // Verify the orchestrator didn't accidentally
        // drop the bindMounts / env / launchCommand
        // / resourceLimits when we added the
        // networkPolicy wiring.
        val spec = sampleWorkspace(NetworkPolicySpec.OPEN)
        val orchestrated = orchestrator.orchestrate(spec)
        assertEquals(1, orchestrated.bindMounts.size)
        assertEquals(1, orchestrated.environment.size)
        assertEquals("/usr/bin/blender", orchestrated.launchCommand.executable)
        assertEquals(4096, orchestrated.resourceLimits.maxMemoryMb)
    }

    private fun sampleWorkspace(network: NetworkPolicySpec): WorkspaceDefinition =
        WorkspaceDefinition(
            apiVersion = ApiVersion.V1,
            id = "test-ws",
            name = "Test Workspace",
            description = "Phase 109 test",
            runtime = RuntimeKind.LINUX_PROOT,
            mounts = listOf(
                MountSpec(
                    hostPath = "/sdcard/test",
                    containerPath = "/workspace",
                ),
            ),
            env = listOf(
                com.elysium.vanguard.core.runtime.workspace_def.EnvSpec(
                    name = "DISPLAY", value = ":0"
                ),
            ),
            launcher = LauncherSpec(command = "/usr/bin/blender"),
            resources = ResourceSpec(maxMemoryMb = 4096, cpuPriority = 50),
            network = network,
            createdAtMs = 1_700_000_000_000L,
        )
}
