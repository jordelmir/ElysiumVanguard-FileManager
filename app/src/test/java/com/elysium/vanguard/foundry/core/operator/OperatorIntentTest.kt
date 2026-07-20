package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 83 (Foundry / AI Operator) — the JVM
 * tests for [OperatorIntent] +
 * [OperatorAuthority] +
 * [OperatorIntentValidator].
 *
 * The tests cover:
 *   - OperatorIntent invariants (blank
 *     fields per case).
 *   - OperatorAuthority invariants
 *     (agentId != issuedBy).
 *   - OperatorAuthorityScope invariants
 *     (Restricted allowedKinds non-empty).
 *   - ValidationResult invariants (blank
 *     reasons rejected).
 *   - InMemoryOperatorIntentValidator
 *     (Full → Allowed for all; Restricted
 *     → Allowed / RequiresApproval;
 *     ReadOnly → Allowed for safe kinds /
 *     Denied for sensitive kinds;
 *     isAllowed helper).
 *   - Realistic scenario: a ReadOnly AI
 *     agent tries to install a distro; the
 *     validator denies; the agent tries a
 *     diagnostic; the validator allows.
 */
class OperatorIntentTest {

    // ============================================================
    // OperatorIntent invariants
    // ============================================================

    @Test
    fun `OperatorIntent InstallDistro rejects blank distroId`() {
        try {
            buildInstallDistro(distroId = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank distroId",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("distroId"))
        }
    }

    @Test
    fun `OperatorIntent InstallDistro rejects blank targetWorkspaceId`() {
        try {
            buildInstallDistro(targetWorkspaceId = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank targetWorkspaceId",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("targetWorkspaceId"))
        }
    }

    @Test
    fun `OperatorIntent CreateWorkspace rejects blank workspaceName`() {
        try {
            buildCreateWorkspace(workspaceName = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank workspaceName",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("workspaceName"))
        }
    }

    @Test
    fun `OperatorIntent LaunchCapsule rejects blank capsuleId`() {
        try {
            buildLaunchCapsule(capsuleId = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank capsuleId",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("capsuleId"))
        }
    }

    @Test
    fun `OperatorIntent StopProcess rejects blank handleId`() {
        try {
            buildStopProcess(handleId = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank handleId",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("handleId"))
        }
    }

    @Test
    fun `OperatorIntent RunDiagnostic rejects blank diagnosticKind`() {
        try {
            buildRunDiagnostic(diagnosticKind = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank diagnosticKind",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("diagnosticKind"))
        }
    }

    @Test
    fun `OperatorIntent GenerateScript rejects blank language`() {
        try {
            buildGenerateScript(language = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank language",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("language"))
        }
    }

    // ============================================================
    // OperatorAuthority invariants
    // ============================================================

    @Test
    fun `OperatorAuthority rejects agentId equal to issuedBy`() {
        try {
            val sameId = UserId.random()
            OperatorAuthority(
                agentId = sameId,
                scope = OperatorAuthorityScope.Full,
                issuedBy = sameId,
                signature = Signature("sig"),
            )
            fail(
                "expected IllegalArgumentException for " +
                    "agentId == issuedBy",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("agentId"))
        }
    }

    // ============================================================
    // OperatorAuthorityScope invariants
    // ============================================================

    @Test
    fun `OperatorAuthorityScope Restricted rejects empty allowedKinds`() {
        try {
            OperatorAuthorityScope.Restricted(allowedKinds = emptySet())
            fail(
                "expected IllegalArgumentException for " +
                    "empty allowedKinds",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("allowedKinds"))
        }
    }

    // ============================================================
    // ValidationResult invariants
    // ============================================================

    @Test
    fun `ValidationResult RequiresApproval rejects blank reason`() {
        try {
            ValidationResult.RequiresApproval(reason = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `ValidationResult Denied rejects blank reason`() {
        try {
            ValidationResult.Denied(reason = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    // ============================================================
    // InMemoryOperatorIntentValidator — Full
    // ============================================================

    @Test
    fun `Full authority allows any kind`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.Full,
        )
        assertTrue(
            validator.validate(
                buildInstallDistro(),
                authority,
            ) is ValidationResult.Allowed,
        )
        assertTrue(
            validator.validate(
                buildCreateWorkspace(),
                authority,
            ) is ValidationResult.Allowed,
        )
        assertTrue(
            validator.validate(
                buildLaunchCapsule(),
                authority,
            ) is ValidationResult.Allowed,
        )
        assertTrue(
            validator.validate(
                buildStopProcess(),
                authority,
            ) is ValidationResult.Allowed,
        )
        assertTrue(
            validator.validate(
                buildRunDiagnostic(),
                authority,
            ) is ValidationResult.Allowed,
        )
        assertTrue(
            validator.validate(
                buildGenerateScript(),
                authority,
            ) is ValidationResult.Allowed,
        )
    }

    // ============================================================
    // InMemoryOperatorIntentValidator — Restricted
    // ============================================================

    @Test
    fun `Restricted authority allows a kind in allowedKinds`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.Restricted(
                allowedKinds = setOf(
                    IntentKind.RUN_DIAGNOSTIC,
                ),
            ),
        )
        assertTrue(
            validator.validate(
                buildRunDiagnostic(),
                authority,
            ) is ValidationResult.Allowed,
        )
    }

    @Test
    fun `Restricted authority requires approval for a kind not in allowedKinds`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.Restricted(
                allowedKinds = setOf(
                    IntentKind.RUN_DIAGNOSTIC,
                ),
            ),
        )
        val result = validator.validate(
            buildInstallDistro(),
            authority,
        )
        assertTrue(result is ValidationResult.RequiresApproval)
    }

    // ============================================================
    // InMemoryOperatorIntentValidator — ReadOnly
    // ============================================================

    @Test
    fun `ReadOnly authority allows RunDiagnostic`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        assertTrue(
            validator.validate(
                buildRunDiagnostic(),
                authority,
            ) is ValidationResult.Allowed,
        )
    }

    @Test
    fun `ReadOnly authority allows GenerateScript`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        assertTrue(
            validator.validate(
                buildGenerateScript(),
                authority,
            ) is ValidationResult.Allowed,
        )
    }

    @Test
    fun `ReadOnly authority denies InstallDistro`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        val result = validator.validate(
            buildInstallDistro(),
            authority,
        )
        assertTrue(result is ValidationResult.Denied)
    }

    @Test
    fun `ReadOnly authority denies CreateWorkspace`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        val result = validator.validate(
            buildCreateWorkspace(),
            authority,
        )
        assertTrue(result is ValidationResult.Denied)
    }

    @Test
    fun `ReadOnly authority denies LaunchCapsule`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        val result = validator.validate(
            buildLaunchCapsule(),
            authority,
        )
        assertTrue(result is ValidationResult.Denied)
    }

    @Test
    fun `ReadOnly authority denies StopProcess`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        val result = validator.validate(
            buildStopProcess(),
            authority,
        )
        assertTrue(result is ValidationResult.Denied)
    }

    // ============================================================
    // InMemoryOperatorIntentValidator — isAllowed helper
    // ============================================================

    @Test
    fun `isAllowed returns true for an allowed intent`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        assertTrue(
            validator.isAllowed(
                buildRunDiagnostic(),
                authority,
            ),
        )
    }

    @Test
    fun `isAllowed returns false for a denied intent`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )
        assertTrue(
            !validator.isAllowed(
                buildInstallDistro(),
                authority,
            ),
        )
    }

    @Test
    fun `isAllowed returns false for a requires-approval intent`() {
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.Restricted(
                allowedKinds = setOf(
                    IntentKind.RUN_DIAGNOSTIC,
                ),
            ),
        )
        assertTrue(
            !validator.isAllowed(
                buildInstallDistro(),
                authority,
            ),
        )
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario a ReadOnly AI agent tries to install a distro, the validator denies, the agent tries a diagnostic, the validator allows`() {
        // Step 1: A human user issues a
        // ReadOnly authority to an AI
        // agent.
        val validator = InMemoryOperatorIntentValidator()
        val authority = buildAuthority(
            scope = OperatorAuthorityScope.ReadOnly,
        )

        // Step 2: The AI agent tries to
        // install a distro (sensitive
        // operation).
        val installIntent = buildInstallDistro()
        val installResult = validator.validate(installIntent, authority)
        assertTrue(
            "install should be denied for ReadOnly",
            installResult is ValidationResult.Denied,
        )

        // Step 3: The AI agent tries to run
        // a diagnostic (safe operation).
        val diagnosticIntent = buildRunDiagnostic()
        val diagnosticResult = validator.validate(
            diagnosticIntent,
            authority,
        )
        assertTrue(
            "diagnostic should be allowed for ReadOnly",
            diagnosticResult is ValidationResult.Allowed,
        )

        // Step 4: The AI agent tries to
        // generate a script (safe
        // operation).
        val scriptIntent = buildGenerateScript()
        val scriptResult = validator.validate(scriptIntent, authority)
        assertTrue(
            "script should be allowed for ReadOnly",
            scriptResult is ValidationResult.Allowed,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildInstallDistro(
        intentId: IntentId = IntentId.random(),
        agentId: UserId = UserId.random(),
        description: String = "Install a Linux distro",
        distroId: String = "com.elysium.linux",
        targetWorkspaceId: String = "com.elysium.workspace.test",
    ): OperatorIntent.InstallDistro =
        OperatorIntent.InstallDistro(
            intentId = intentId,
            agentId = agentId,
            description = description,
            distroId = distroId,
            targetWorkspaceId = targetWorkspaceId,
        )

    private fun buildCreateWorkspace(
        intentId: IntentId = IntentId.random(),
        agentId: UserId = UserId.random(),
        description: String = "Create a workspace",
        workspaceName: String = "test-workspace",
        sandboxProfile: String = "standard",
    ): OperatorIntent.CreateWorkspace =
        OperatorIntent.CreateWorkspace(
            intentId = intentId,
            agentId = agentId,
            description = description,
            workspaceName = workspaceName,
            sandboxProfile = sandboxProfile,
        )

    private fun buildLaunchCapsule(
        intentId: IntentId = IntentId.random(),
        agentId: UserId = UserId.random(),
        description: String = "Launch a Capsule",
        capsuleId: String = "com.elysium.blender.arm64",
        runtime: String = "linux",
    ): OperatorIntent.LaunchCapsule =
        OperatorIntent.LaunchCapsule(
            intentId = intentId,
            agentId = agentId,
            description = description,
            capsuleId = capsuleId,
            runtime = runtime,
        )

    private fun buildStopProcess(
        intentId: IntentId = IntentId.random(),
        agentId: UserId = UserId.random(),
        description: String = "Stop a running process",
        handleId: String = "process-1234",
    ): OperatorIntent.StopProcess =
        OperatorIntent.StopProcess(
            intentId = intentId,
            agentId = agentId,
            description = description,
            handleId = handleId,
        )

    private fun buildRunDiagnostic(
        intentId: IntentId = IntentId.random(),
        agentId: UserId = UserId.random(),
        description: String = "Run a diagnostic",
        diagnosticKind: String = "process-memory-usage",
    ): OperatorIntent.RunDiagnostic =
        OperatorIntent.RunDiagnostic(
            intentId = intentId,
            agentId = agentId,
            description = description,
            diagnosticKind = diagnosticKind,
        )

    private fun buildGenerateScript(
        intentId: IntentId = IntentId.random(),
        agentId: UserId = UserId.random(),
        description: String = "Generate a script",
        language: String = "bash",
    ): OperatorIntent.GenerateScript =
        OperatorIntent.GenerateScript(
            intentId = intentId,
            agentId = agentId,
            description = description,
            language = language,
        )

    private fun buildAuthority(
        agentId: UserId = UserId.random(),
        scope: OperatorAuthorityScope = OperatorAuthorityScope.ReadOnly,
        issuedBy: UserId = UserId.random(),
    ): OperatorAuthority {
        // Ensure agentId != issuedBy.
        val realIssuedBy = if (agentId == issuedBy) {
            UserId(UUID.randomUUID())
        } else {
            issuedBy
        }
        return OperatorAuthority(
            agentId = agentId,
            scope = scope,
            issuedBy = realIssuedBy,
            signature = Signature("sig-authority-${UUID.randomUUID()}"),
        )
    }
}
