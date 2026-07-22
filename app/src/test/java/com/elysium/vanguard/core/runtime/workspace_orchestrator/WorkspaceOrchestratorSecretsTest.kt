package com.elysium.vanguard.core.runtime.workspace_orchestrator

import com.elysium.vanguard.core.runtime.workspace_def.ApiVersion
import com.elysium.vanguard.core.runtime.workspace_def.EnvSpec
import com.elysium.vanguard.core.runtime.workspace_def.LauncherSpec
import com.elysium.vanguard.core.runtime.workspace_def.MountSpec
import com.elysium.vanguard.core.runtime.workspace_def.ResourceSpec
import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import com.elysium.vanguard.core.security.SecretResolver
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 111 — the test suite for the secret
 * resolution path in the
 * [WorkspaceOrchestrator]. The orchestrator
 * is pure-domain; the [SecretResolver] is
 * the I/O seam. The tests use a hand-rolled
 * `FakeSecretResolver` to drive the
 * success + failure paths.
 */
class WorkspaceOrchestratorSecretsTest {

    private val orchestrator = WorkspaceOrchestrator()

    // --- orchestrator emits secretEnvRefs ---

    @Test
    fun `secret env var is emitted as a secretEnvRef with the secret id as the value`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "OAUTH_REFRESH_TOKEN", value = "OAUTH_REFRESH_TOKEN", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        // The plan's `environment` carries the
        // literal value (the secret id) — the
        // orchestrator does NOT resolve secrets
        // (that's a separate step).
        assertEquals("OAUTH_REFRESH_TOKEN", plan.environment["OAUTH_REFRESH_TOKEN"])
        // The plan's `secretEnvRefs` marks the
        // entry as needing resolution at session
        // start.
        assertEquals(1, plan.secretEnvRefs.size)
        assertEquals("OAUTH_REFRESH_TOKEN", plan.secretEnvRefs["OAUTH_REFRESH_TOKEN"])
    }

    @Test
    fun `literal env var does NOT appear in secretEnvRefs`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "DEBUG", value = "1", secret = false),
            )
        )
        val plan = orchestrator.orchestrate(def)
        assertTrue("literal env should not be in secretEnvRefs", plan.secretEnvRefs.isEmpty())
        assertEquals("1", plan.environment["DEBUG"])
    }

    @Test
    fun `mixed literal plus secret env vars are distinguished correctly`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "DEBUG", value = "1", secret = false),
                EnvSpec(name = "OAUTH_REFRESH_TOKEN", value = "OAUTH_REFRESH_TOKEN", secret = true),
                EnvSpec(name = "LANG", value = "en_US.UTF-8", secret = false),
            )
        )
        val plan = orchestrator.orchestrate(def)
        assertEquals(1, plan.secretEnvRefs.size)
        assertEquals("OAUTH_REFRESH_TOKEN", plan.secretEnvRefs["OAUTH_REFRESH_TOKEN"])
        // The literal envs are present in
        // `environment` but NOT in
        // `secretEnvRefs`.
        assertEquals("1", plan.environment["DEBUG"])
        assertEquals("en_US.UTF-8", plan.environment["LANG"])
        assertNull(plan.secretEnvRefs["DEBUG"])
        assertNull(plan.secretEnvRefs["LANG"])
    }

    // --- resolveSecrets: success path ---

    @Test
    fun `resolveSecrets returns the same plan when no secrets are declared`() {
        val def = sampleDefinition(env = emptyList())
        val plan = orchestrator.orchestrate(def)
        val resolver = FakeSecretResolver(mapOf())
        val result = orchestrator.resolveSecrets(plan, resolver)
        assertTrue(
            "expected Success, got $result",
            result is SecretResolutionResult.Success
        )
        result as SecretResolutionResult.Success
        assertEquals(plan, result.plan)
    }

    @Test
    fun `resolveSecrets populates the resolved value in environment`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "API_KEY", value = "API_KEY", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        val resolver = FakeSecretResolver(
            mapOf("API_KEY" to "sk-resolved-12345")
        )
        val result = orchestrator.resolveSecrets(plan, resolver)
        assertTrue(
            "expected Success, got $result",
            result is SecretResolutionResult.Success
        )
        result as SecretResolutionResult.Success
        // The resolved value is in
        // `environment`.
        assertEquals("sk-resolved-12345", result.plan.environment["API_KEY"])
        // The `secretEnvRefs` is cleared (the
        // secret is now resolved).
        assertTrue(
            "secretEnvRefs should be cleared, got ${result.plan.secretEnvRefs}",
            result.plan.secretEnvRefs.isEmpty()
        )
    }

    @Test
    fun `resolveSecrets resolves multiple secrets in declaration order`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "API_KEY", value = "API_KEY", secret = true),
                EnvSpec(name = "VAULT_PASSPHRASE", value = "VAULT_PASSPHRASE", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        val resolver = FakeSecretResolver(
            mapOf(
                "API_KEY" to "sk-aaa",
                "VAULT_PASSPHRASE" to "correct horse battery staple",
            )
        )
        val result = orchestrator.resolveSecrets(plan, resolver)
        result as SecretResolutionResult.Success
        assertEquals("sk-aaa", result.plan.environment["API_KEY"])
        assertEquals(
            "correct horse battery staple",
            result.plan.environment["VAULT_PASSPHRASE"]
        )
        assertTrue(result.plan.secretEnvRefs.isEmpty())
    }

    @Test
    fun `resolveSecrets preserves literal env vars untouched`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "DEBUG", value = "1", secret = false),
                EnvSpec(name = "API_KEY", value = "API_KEY", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        val resolver = FakeSecretResolver(mapOf("API_KEY" to "sk-xyz"))
        val result = orchestrator.resolveSecrets(plan, resolver) as SecretResolutionResult.Success
        assertEquals("1", result.plan.environment["DEBUG"])
        assertEquals("sk-xyz", result.plan.environment["API_KEY"])
    }

    // --- resolveSecrets: failure path ---

    @Test
    fun `resolveSecrets returns MissingSecret when a secret is absent`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "MISSING_KEY", value = "MISSING_KEY", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        val resolver = FakeSecretResolver(mapOf()) // no secrets
        val result = orchestrator.resolveSecrets(plan, resolver)
        assertTrue(
            "expected MissingSecret, got $result",
            result is SecretResolutionResult.MissingSecret
        )
        result as SecretResolutionResult.MissingSecret
        assertEquals("MISSING_KEY", result.envName)
        assertEquals("MISSING_KEY", result.secretId)
        assertTrue(
            "cause should be non-blank, got '${result.cause}'",
            result.cause.isNotBlank()
        )
    }

    @Test
    fun `resolveSecrets fails fast on the first missing secret`() {
        // Two secret envs; the first one
        // resolves, the second one fails. The
        // resolution aborts on the first
        // failure.
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "PRESENT_KEY", value = "PRESENT_KEY", secret = true),
                EnvSpec(name = "MISSING_KEY", value = "MISSING_KEY", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        val resolver = FakeSecretResolver(
            // Only PRESENT_KEY is present.
            mapOf("PRESENT_KEY" to "value-a")
        )
        val result = orchestrator.resolveSecrets(plan, resolver)
        assertTrue(
            "expected MissingSecret, got $result",
            result is SecretResolutionResult.MissingSecret
        )
        result as SecretResolutionResult.MissingSecret
        // The error names the SECOND secret
        // (the first one resolved, the second
        // one failed).
        assertEquals("MISSING_KEY", result.envName)
    }

    @Test
    fun `resolveSecrets surfaces the resolver's failure reason`() {
        val def = sampleDefinition(
            env = listOf(
                EnvSpec(name = "BAD_KEY", value = "BAD_KEY", secret = true),
            )
        )
        val plan = orchestrator.orchestrate(def)
        val resolver = object : SecretResolver {
            override fun resolve(secretId: String) = Result.failure<String>(
                FoundryError.VehicleDefinitionInvalid(
                    field = "SecretStore.secret",
                    reason = "explicit test failure for $secretId",
                )
            )
        }
        val result = orchestrator.resolveSecrets(plan, resolver)
        result as SecretResolutionResult.MissingSecret
        assertEquals("BAD_KEY", result.envName)
        assertTrue(
            "cause should mention the failure reason, got '${result.cause}'",
            result.cause.contains("explicit test failure", ignoreCase = true) ||
                result.cause.contains("BAD_KEY")
        )
    }

    // --- helpers ---

    private fun sampleDefinition(env: List<EnvSpec>): WorkspaceDefinition = WorkspaceDefinition(
        apiVersion = ApiVersion.V1,
        id = "test-workspace",
        name = "Test Workspace",
        description = "Phase 111 test workspace",
        runtime = RuntimeKind.LINUX_PROOT,
        mounts = listOf(
            MountSpec(
                hostPath = "/sdcard/data",
                containerPath = "/mnt/data",
                readOnly = false,
            )
        ),
        env = env,
        launcher = LauncherSpec(command = "/usr/bin/blender"),
        resources = ResourceSpec(maxMemoryMb = 1024, cpuPriority = 50),
        createdAtMs = 1700000000000L,
    )
}

/**
 * A hand-rolled [SecretResolver] for the
 * test suite. The map is the "vault"; a
 * missing entry returns a typed failure.
 */
private class FakeSecretResolver(
    private val vault: Map<String, String>,
) : SecretResolver {
    override fun resolve(secretId: String): Result<String> {
        val value = vault[secretId]
        return if (value != null) {
            Result.success(value)
        } else {
            Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "SecretStore.secret",
                    reason = "no secret with id $secretId",
                )
            )
        }
    }
}
