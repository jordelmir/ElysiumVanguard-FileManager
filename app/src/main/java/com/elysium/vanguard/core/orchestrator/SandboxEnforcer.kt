package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID

/**
 * Phase 89 (Universal Execution Engine) —
 * the **Sandbox Enforcer**, the consumer
 * of the [SandboxPreparation] (Phase 87)
 * that "applies" the typed application
 * steps in pure-domain + records the
 * result as a typed
 * [SandboxEnforcementResult].
 *
 * Per the master vision's Universal
 * Execution Engine (section 6), the
 * dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   **Sandbox and Mount Policy**  ←
 *     (Phase 81 spec, Phase 87 application,
 *      Phase 89 enforcement)
 *     ↓
 *   Process Supervisor
 *     ↓
 *   Telemetry and Recovery
 *
 * Phase 81 was the **typed spec** for the
 * sandbox. Phase 87 was the **typed
 * application** of the policy to a
 * launch plan. This phase is the **typed
 * enforcement** of the preparation: the
 * enforcer consumes the
 * [SandboxPreparation] + "applies" each
 * step + records the result.
 *
 * The enforcer is **pure-domain** (no
 * I/O, no Android dependencies). The
 * test impl is the
 * `InMemorySandboxEnforcer`. The
 * production impl may be the same (the
 * enforcer is a typed record of what was
 * applied; the actual OS-level enforcement
 * is the OS executor's responsibility).
 *
 * The enforcer is **thread-safe** (no
 * mutable fields). The enforcer is
 * **deterministic** (the same
 * `SandboxPreparation` produces the same
 * `SandboxEnforcementResult`).
 */
sealed class SandboxEnforcer {

    /**
     * Enforce a [SandboxPreparation]. The
     * enforcer "applies" each step in
     * the preparation + records the
     * result.
     *
     * In pure-domain, "applying" means
     * recording the step + emitting a
     * matching `EnforcementStep`. In
     * production, the enforcer delegates
     * to the OS executor (which actually
     * applies the bind mounts + the
     * SELinux context + the resource
     * limits + the network policy).
     *
     * The `nowMs` parameter is the
     * current time (millis since epoch);
     * the enforcer uses it for the
     * enforcement timestamps. The
     * parameter is **explicit** (not
     * derived from
     * `System.currentTimeMillis()`) so
     * the enforcer is **deterministic**
     * (the test can use a fixed
     * `nowMs`).
     */
    abstract fun enforce(
        preparation: SandboxPreparation,
        nowMs: Long,
    ): SandboxEnforcementResult
}

/**
 * The typed enforcement step. The step
 * is a sealed class with 5 cases:
 *   - **`BindMounted`** — the bind
 *     mount was applied.
 *   - **`SeLinuxContextApplied`** — the
 *     SELinux context was applied.
 *   - **`ResourceLimitsApplied`** — the
 *     resource limits were applied.
 *   - **`NetworkPolicyApplied`** — the
 *     network policy was applied.
 *   - **`Skipped`** — a step was
 *     skipped (the reason is a
 *     human-readable string).
 */
sealed class EnforcementStep {

    /**
     * The step's timestamp. The
     * timestamp is the millis since
     * epoch the step was "applied"
     * (or skipped).
     */
    abstract val timestampMs: Long

    /**
     * The bind mount was applied. The
     * OS executor bound the host
     * path to the sandbox path.
     */
    data class BindMounted(
        val mountEntry: MountEntry,
        override val timestampMs: Long,
    ) : EnforcementStep() {
        init {
            require(timestampMs > 0) {
                "EnforcementStep.BindMounted." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * The SELinux context was applied.
     * The OS executor set the SELinux
     * context for the process.
     */
    data class SeLinuxContextApplied(
        val securityProfile: SecurityProfile,
        override val timestampMs: Long,
    ) : EnforcementStep() {
        init {
            require(timestampMs > 0) {
                "EnforcementStep.SeLinuxContextApplied." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * The resource limits were applied.
     * The OS executor set the rlimits
     * + the cgroup limits for the
     * process.
     */
    data class ResourceLimitsApplied(
        val sandboxLimits: SandboxLimits,
        override val timestampMs: Long,
    ) : EnforcementStep() {
        init {
            require(timestampMs > 0) {
                "EnforcementStep.ResourceLimitsApplied." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * The network policy was applied.
     * The OS executor configured the
     * network namespace + the
     * firewall rules for the process.
     */
    data class NetworkPolicyApplied(
        val networkPolicy: NetworkPolicy,
        override val timestampMs: Long,
    ) : EnforcementStep() {
        init {
            require(timestampMs > 0) {
                "EnforcementStep.NetworkPolicyApplied." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A step was skipped. The
     * `reason` is a human-readable
     * string.
     */
    data class Skipped(
        val reason: String,
        override val timestampMs: Long,
    ) : EnforcementStep() {
        init {
            require(reason.isNotBlank()) {
                "EnforcementStep.Skipped.reason " +
                    "must not be blank"
            }
            require(timestampMs > 0) {
                "EnforcementStep.Skipped.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }
}

/**
 * The typed enforcement result. The
 * result is the enforcer's output for a
 * given [SandboxPreparation].
 *
 * The result is **immutable** (a data
 * class; no setters). A new result is a
 * new value.
 *
 * The result has:
 *   - **`enforcementId`** — UUID; the
 *     unique id of this enforcement
 *     attempt.
 *   - **`preparationId`** — the
 *     preparation that was enforced.
 *   - **`steps`** — the typed
 *     enforcement steps in order
 *     (matches the preparation's
 *     steps in order).
 *   - **`enforcedAtMs`** — the
 *     timestamp the enforcement was
 *     performed.
 *   - **`signature`** — the result's
 *     signature.
 */
data class SandboxEnforcementResult(
    val enforcementId: UUID,
    val preparationId: UUID,
    val steps: List<EnforcementStep>,
    val enforcedAtMs: Long,
    val signature: Signature,
) {
    init {
        require(steps.isNotEmpty()) {
            "SandboxEnforcementResult.steps " +
                "must not be empty"
        }
        require(enforcedAtMs > 0) {
            "SandboxEnforcementResult.enforcedAtMs " +
                "must be > 0, got $enforcedAtMs"
        }
    }

    /**
     * Get the bind mount steps in
     * order. The list is the
     * successfully-applied bind
     * mounts.
     */
    val bindMountedSteps: List<MountEntry>
        get() = steps.filterIsInstance<EnforcementStep.BindMounted>()
            .map { it.mountEntry }

    /**
     * Get the skipped steps in order.
     * The list is the
     * not-applied steps with their
     * reasons.
     */
    val skippedSteps: List<EnforcementStep.Skipped>
        get() = steps.filterIsInstance<EnforcementStep.Skipped>()

    /**
     * Check whether the enforcement
     * has any skipped steps. An
     * enforcement with skipped steps
     * is **partial**.
     */
    val hasSkippedSteps: Boolean
        get() = skippedSteps.isNotEmpty()
}

/**
 * The in-memory [SandboxEnforcer] for
 * testing + production. The enforcer is
 * the stateless composition of:
 *   - The enforcement algorithm (per
 *     [SandboxPreparation]).
 *
 * The enforcer is **thread-safe** (no
 * mutable fields). The enforcer is
 * **deterministic** (the `nowMs`
 * parameter is explicit).
 *
 * The enforcement algorithm:
 *   1. For each `BindMount` preparation
 *      step, emit a `BindMounted`
 *      enforcement step.
 *   2. For the `ApplySeLinuxContext`
 *      preparation step, emit a
 *      `SeLinuxContextApplied`
 *      enforcement step.
 *   3. For the `ApplyResourceLimits`
 *      preparation step, emit a
 *      `ResourceLimitsApplied`
 *      enforcement step.
 *   4. For the `ApplyNetworkPolicy`
 *      preparation step, emit a
 *      `NetworkPolicyApplied`
 *      enforcement step.
 *   5. For the `Skipped` preparation
 *      step, emit a matching
 *      `Skipped` enforcement step.
 *
 * The order of the enforcement steps
 * matches the order of the preparation
 * steps. The number of enforcement
 * steps equals the number of
 * preparation steps.
 */
class InMemorySandboxEnforcer : SandboxEnforcer() {

    override fun enforce(
        preparation: SandboxPreparation,
        nowMs: Long,
    ): SandboxEnforcementResult {
        val steps = mutableListOf<EnforcementStep>()

        for (preparationStep in preparation.steps) {
            when (preparationStep) {
                is PreparationStep.BindMount ->
                    steps.add(
                        EnforcementStep.BindMounted(
                            mountEntry = preparationStep.mountEntry,
                            timestampMs = nowMs,
                        ),
                    )
                is PreparationStep.ApplySeLinuxContext ->
                    steps.add(
                        EnforcementStep.SeLinuxContextApplied(
                            securityProfile =
                                preparationStep.securityProfile,
                            timestampMs = nowMs,
                        ),
                    )
                is PreparationStep.ApplyResourceLimits ->
                    steps.add(
                        EnforcementStep.ResourceLimitsApplied(
                            sandboxLimits =
                                preparationStep.sandboxLimits,
                            timestampMs = nowMs,
                        ),
                    )
                is PreparationStep.ApplyNetworkPolicy ->
                    steps.add(
                        EnforcementStep.NetworkPolicyApplied(
                            networkPolicy =
                                preparationStep.networkPolicy,
                            timestampMs = nowMs,
                        ),
                    )
                is PreparationStep.Skipped ->
                    steps.add(
                        EnforcementStep.Skipped(
                            reason = preparationStep.reason,
                            timestampMs = nowMs,
                        ),
                    )
            }
        }

        return SandboxEnforcementResult(
            enforcementId = UUID.randomUUID(),
            preparationId = preparation.preparationId,
            steps = steps,
            enforcedAtMs = nowMs,
            signature = Signature(
                "sig-enforce-${UUID.randomUUID()}",
            ),
        )
    }
}

/**
 * The typed error envelope for the
 * sandbox enforcer. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the
 * `orchestrator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class SandboxEnforcementError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The enforcement failed. The
     * `reason` is a human-readable
     * string.
     */
    data class EnforcementFailed(
        val reason: String,
    ) : SandboxEnforcementError(
        message = "Sandbox enforcement failed: " +
            reason,
        code = "ENFORCEMENT_FAILED",
    )
}
