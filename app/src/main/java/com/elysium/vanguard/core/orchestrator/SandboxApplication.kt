package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID

/**
 * Phase 87 (Universal Execution Engine) —
 * the **Sandbox Application**, the
 * integration that takes a [SandboxPolicy]
 * + a [LaunchPlan] and produces a
 * [SandboxPreparation] (the plan + the
 * policy + the application steps in
 * order).
 *
 * Per the master vision's Universal
 * Execution Engine (section 6), the
 * dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   **Sandbox and Mount Policy**  ← this phase
 *     ↓
 *   Process Supervisor
 *     ↓
 *   Telemetry and Recovery
 *
 * Phase 81 was the **typed spec** for the
 * sandbox + the validator. This phase is
 * the **application** of the policy to a
 * launch plan.
 *
 * The application is **pure-domain**
 * (no I/O, no Android dependencies). The
 * test impl is the
 * `InMemorySandboxApplication`. The
 * production impl is the same (the
 * application is a typed list of steps;
 * the same impl is used in tests +
 * production). The actual OS-level
 * enforcement of the bind mounts +
 * SELinux context + resource limits is
 * the OS's responsibility (the
 * production impl emits the typed
 * [PreparationStep] list; the OS
 * executor consumes the list and
 * applies the steps).
 */
sealed class SandboxApplication {

    /**
     * Prepare a launch. The applier takes
     * a [LaunchPlan] + a [SandboxPolicy] +
     * a `nowMs` (the timestamp the
     * preparation is performed at) +
     * returns a [SandboxPreparation]
     * with the plan + the policy + the
     * typed application steps in order.
     *
     * The applier does NOT actually
     * apply the steps to the OS; the
     * applier produces the typed
     * preparation that the OS executor
     * consumes.
     */
    abstract fun prepare(
        plan: LaunchPlan,
        policy: SandboxPolicy,
        nowMs: Long,
    ): SandboxPreparation
}

/**
 * The typed preparation step. The step
 * is a sealed class with 5 cases:
 *   - **`BindMount`** — the bind mount
 *     entry to apply.
 *   - **`ApplySeLinuxContext`** — the
 *     SELinux context to apply.
 *   - **`ApplyResourceLimits`** — the
 *     resource limits to apply.
 *   - **`ApplyNetworkPolicy`** — the
 *     network policy to apply.
 *   - **`Skipped`** — a step that was
 *     skipped (e.g. due to insufficient
 *     permissions; the reason is a
 *     human-readable string).
 */
sealed class PreparationStep {

    /**
     * Bind the mount entry. The OS
     * executor applies the bind mount
     * (the actual `mount` syscall or
     * the PRoot `-b` flag).
     */
    data class BindMount(
        val mountEntry: MountEntry,
    ) : PreparationStep()

    /**
     * Apply the SELinux security profile.
     * The OS executor sets the SELinux
     * context for the process (the
     * actual `chcon` / `runcon`
     * command).
     */
    data class ApplySeLinuxContext(
        val securityProfile: SecurityProfile,
    ) : PreparationStep()

    /**
     * Apply the resource limits. The OS
     * executor sets the rlimits + the
     * cgroup limits for the process.
     */
    data class ApplyResourceLimits(
        val sandboxLimits: SandboxLimits,
    ) : PreparationStep()

    /**
     * Apply the network policy. The OS
     * executor configures the network
     * namespace + the firewall rules
     * for the process.
     */
    data class ApplyNetworkPolicy(
        val networkPolicy: NetworkPolicy,
    ) : PreparationStep()

    /**
     * A step that was skipped. The
     * `reason` is a human-readable
     * string (e.g. "insufficient
     * permissions to apply the SELinux
     * context").
     */
    data class Skipped(
        val reason: String,
    ) : PreparationStep() {
        init {
            require(reason.isNotBlank()) {
                "PreparationStep.Skipped.reason " +
                    "must not be blank"
            }
        }
    }
}

/**
 * The typed preparation result. The
 * result is the applier's output for
 * a given [LaunchPlan] + [SandboxPolicy].
 *
 * The result is **immutable** (a data
 * class; no setters). A new result is a
 * new value.
 *
 * The result has:
 *   - **`preparationId`** — UUID; the
 *     unique id of this preparation.
 *   - **`plan`** — the launch plan.
 *   - **`policy`** — the sandbox policy.
 *   - **`steps`** — the typed
 *     application steps in order
 *     (the OS executor consumes the list
 *     in order).
 *   - **`preparedAtMs`** — the timestamp
 *     the preparation was performed.
 *   - **`signature`** — the preparation's
 *     signature.
 */
data class SandboxPreparation(
    val preparationId: UUID,
    val plan: LaunchPlan,
    val policy: SandboxPolicy,
    val steps: List<PreparationStep>,
    val preparedAtMs: Long,
    val signature: Signature,
) {
    init {
        require(steps.isNotEmpty()) {
            "SandboxPreparation.steps must " +
                "not be empty"
        }
        require(preparedAtMs > 0) {
            "SandboxPreparation.preparedAtMs " +
                "must be > 0, got $preparedAtMs"
        }
    }

    /**
     * Get the bind mount steps in order.
     * The list is the OS executor's
     * input for the actual bind mount
     * application.
     */
    val bindMountSteps: List<MountEntry>
        get() = steps.filterIsInstance<PreparationStep.BindMount>()
            .map { it.mountEntry }

    /**
     * Get the skipped steps in order.
     * The list is the user-visible
     * "what was NOT applied" record.
     */
    val skippedSteps: List<PreparationStep.Skipped>
        get() = steps.filterIsInstance<PreparationStep.Skipped>()

    /**
     * Check whether the preparation has
     * any skipped steps. A preparation
     * with skipped steps is **partial**
     * (some sandbox requirements were
     * not applied).
     */
    val hasSkippedSteps: Boolean
        get() = skippedSteps.isNotEmpty()
}

/**
 * The in-memory [SandboxApplication] for
 * testing + production. The applier is
 * the stateless composition of:
 *   - The application algorithm (the
 *     order in which the steps are
 *     applied).
 *
 * The applier is **thread-safe** (no
 * mutable fields).
 *
 * The application algorithm:
 *   1. For each mount in the policy's
 *      `mounts`, emit a `BindMount`
 *      step.
 *   2. Emit an `ApplySeLinuxContext`
 *      step (with the policy's
 *      `security` profile).
 *   3. Emit an `ApplyResourceLimits` step
 *      (with the policy's `limits`).
 *   4. Emit an `ApplyNetworkPolicy`
 *      step (with the policy's
 *      `network`).
 *   5. Always emit a final `Skipped`
 *      step ("the OS-level enforcement
 *      is the OS executor's
 *      responsibility; the applier
 *      produces the typed plan").
 *
 * The order of the steps is the order
 * the OS executor should apply them:
 *   - Bind mounts first (so the process
 *     can find the files).
 *   - SELinux context second (so the
 *     process has the right MAC).
 *   - Resource limits third (so the
 *     process has the right rlimits +
 *     cgroup limits).
 *   - Network policy fourth (so the
 *     process has the right network
 *     namespace + firewall rules).
 */
class InMemorySandboxApplication : SandboxApplication() {

    override fun prepare(
        plan: LaunchPlan,
        policy: SandboxPolicy,
        nowMs: Long,
    ): SandboxPreparation {
        val steps = mutableListOf<PreparationStep>()

        // Step 1: Emit a BindMount step
        // for each mount in the policy.
        for (mountEntry in policy.mounts) {
            steps.add(PreparationStep.BindMount(mountEntry))
        }

        // Step 2: Emit the SELinux
        // context step.
        steps.add(
            PreparationStep.ApplySeLinuxContext(
                securityProfile = policy.security,
            ),
        )

        // Step 3: Emit the resource
        // limits step.
        steps.add(
            PreparationStep.ApplyResourceLimits(
                sandboxLimits = policy.limits,
            ),
        )

        // Step 4: Emit the network
        // policy step.
        steps.add(
            PreparationStep.ApplyNetworkPolicy(
                networkPolicy = policy.network,
            ),
        )

        // Step 5: Emit a final Skipped
        // step (the OS-level enforcement
        // is the OS executor's
        // responsibility).
        steps.add(
            PreparationStep.Skipped(
                reason = "the OS-level enforcement " +
                    "is the OS executor's " +
                    "responsibility; the applier " +
                    "produces the typed plan",
            ),
        )

        return SandboxPreparation(
            preparationId = UUID.randomUUID(),
            plan = plan,
            policy = policy,
            steps = steps,
            preparedAtMs = nowMs,
            signature = Signature(
                "sig-prep-${UUID.randomUUID()}",
            ),
        )
    }
}

/**
 * The typed error envelope for the
 * sandbox application. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the
 * `orchestrator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class SandboxApplicationError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The preparation failed. The
     * `reason` is a human-readable
     * string.
     */
    data class PreparationFailed(
        val reason: String,
    ) : SandboxApplicationError(
        message = "Sandbox preparation failed: " +
            reason,
        code = "PREPARATION_FAILED",
    )
}
