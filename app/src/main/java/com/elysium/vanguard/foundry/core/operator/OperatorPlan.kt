package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 84 (Foundry / AI Operator) — the
 * **Operator Plan**, a multi-step plan
 * that the AI agent can issue.
 *
 * Per the master vision (section 8):
 * "La IA debía convertir eso en un plan
 * declarativo, mostrar cambios y ejecutar
 * únicamente operaciones autorizadas."
 *
 * Example: "Instala Blender, configura
 * aceleración Vulkan y crea un acceso
 * directo." The AI converts this to a
 * 3-step plan:
 *   1. InstallDistro("com.elysium.linux")
 *   2. LaunchCapsule("com.elysium.blender",
 *      runtime="linux")
 *   3. CreateSnapshot("workspaces/blender",
 *      label="after-install")
 *
 * The `OperatorPlan` is the typed
 * representation of the multi-step plan.
 * Each step is a typed `OperatorIntent`
 * (per Phase 83). The plan is **pure-
 * domain** (no I/O, no Android
 * dependencies).
 *
 * The plan is **5 primitives**:
 *
 *   - **`PlanStep`** — a single step in
 *     the plan (order + description +
 *     intent).
 *   - **`OperatorPlan`** — the multi-step
 *     plan (a list of steps + the agent
 *     + the timestamp + the signature).
 *   - **`PlanStatus`** — the typed plan
 *     status (the lifecycle state).
 *   - **`PlanValidationResult`** — the
 *     validator's output (the plan is
 *     valid / the plan has errors).
 *   - **`OperatorPlanValidator`** + impl
 *     — the validator.
 *
 * The validator enforces the
 * **intra-plan invariants**:
 *   - The steps' orders are unique +
 *     contiguous (1, 2, 3, ...).
 *   - All steps' intents have the same
 *     `agentId` as the plan.
 *   - The plan has at least one step.
 */
sealed class OperatorPlanValidator {

    /**
     * Validate an [OperatorPlan]. Returns
     * a [PlanValidationResult] with the
     * typed result.
     */
    abstract fun validate(plan: OperatorPlan): PlanValidationResult

    /**
     * Check whether an [OperatorPlan] is
     * valid (the convenience predicate
     * for the "no errors" case).
     */
    fun isValid(plan: OperatorPlan): Boolean =
        validate(plan) is PlanValidationResult.Valid
}

/**
 * A single step in an [OperatorPlan]. The
 * step is **immutable** (a data class; no
 * setters). A new step is a new value.
 *
 * The step has:
 *   - **`order`** — the step's order in
 *     the plan (1-based; the first step
 *     is `order = 1`).
 *   - **`description`** — a human-readable
 *     description of the step.
 *   - **`intent`** — the typed intent the
 *     step performs (per Phase 83).
 */
data class PlanStep(
    val order: Int,
    val description: String,
    val intent: OperatorIntent,
) {
    init {
        require(order > 0) {
            "PlanStep.order must be > 0, got $order"
        }
        require(description.isNotBlank()) {
            "PlanStep.description must not be blank"
        }
    }
}

/**
 * The typed operator plan. The plan is
 * **immutable** (a data class; no
 * setters). A new plan is a new value.
 *
 * The plan has:
 *   - **`planId`** — UUID.
 *   - **`agentId`** — the AI agent that
 *     issued the plan.
 *   - **`steps`** — the list of steps (in
 *     order of `order`).
 *   - **`createdAtMs`** — the timestamp
 *     the plan was created.
 *   - **`signature`** — the plan's
 *     signature.
 */
data class OperatorPlan(
    val planId: PlanId,
    val agentId: UserId,
    val steps: List<PlanStep>,
    val createdAtMs: Long,
    val signature: Signature,
) {
    init {
        require(steps.isNotEmpty()) {
            "OperatorPlan.steps must not be empty"
        }
        require(createdAtMs > 0) {
            "OperatorPlan.createdAtMs must be > 0, " +
                "got $createdAtMs"
        }
    }

    /**
     * Get a step by order. Returns
     * `null` if no step has the given
     * order.
     */
    fun stepByOrder(order: Int): PlanStep? =
        steps.firstOrNull { it.order == order }

    /**
     * The first step. The first step is
     * the step with `order = 1`.
     */
    val firstStep: PlanStep
        get() = steps.minBy { it.order }

    /**
     * The number of steps in the plan.
     */
    val stepCount: Int
        get() = steps.size

    /**
     * The orders of all the steps (in
     * sorted order). The list is the
     * join key the validator uses to
     * check the contiguity invariant.
     */
    val sortedOrders: List<Int>
        get() = steps.map { it.order }.sorted()
}

/**
 * The typed plan status. The status is
 * the **lifecycle state** of the plan.
 *
 * The status is a sealed class with 5
 * cases:
 *   - **`Pending`** — the plan has been
 *     created but not yet executed.
 *   - **`Running`** — the plan is
 *     currently executing (at least one
 *     step has started).
 *   - **`Completed`** — all steps
 *     completed successfully.
 *   - **`Failed(reason)`** — a step
 *     failed; the plan stopped.
 *   - **`Paused(reason)`** — the plan
 *     is paused (e.g. waiting for human
 *     approval).
 */
sealed class PlanStatus {

    /**
     * The plan is pending. The plan
     * has been created but not yet
     * executed.
     */
    data object Pending : PlanStatus()

    /**
     * The plan is running. At least one
     * step has started; the plan is
     * making progress.
     */
    data object Running : PlanStatus()

    /**
     * The plan is completed. All steps
     * completed successfully.
     */
    data object Completed : PlanStatus()

    /**
     * The plan is failed. A step
     * failed; the plan stopped. The
     * reason is a human-readable
     * string.
     */
    data class Failed(
        val reason: String,
    ) : PlanStatus() {
        init {
            require(reason.isNotBlank()) {
                "PlanStatus.Failed.reason must not be blank"
            }
        }
    }

    /**
     * The plan is paused. The plan
     * is waiting for human approval
     * OR is paused for some other
     * reason. The reason is a
     * human-readable string.
     */
    data class Paused(
        val reason: String,
    ) : PlanStatus() {
        init {
            require(reason.isNotBlank()) {
                "PlanStatus.Paused.reason must not be blank"
            }
        }
    }
}

/**
 * The typed plan validation result. The
 * result is the validator's output for
 * a given [OperatorPlan].
 *
 * The result is a sealed class with 2
 * cases:
 *   - **`Valid`** — the plan is valid.
 *   - **`Invalid(errors)`** — the plan
 *     has errors. The errors are a list
 *     of human-readable strings.
 */
sealed class PlanValidationResult {

    /**
     * The plan is valid. The plan's
     * intra-plan invariants are all
     * satisfied.
     */
    data object Valid : PlanValidationResult()

    /**
     * The plan is invalid. The plan's
     * intra-plan invariants are NOT all
     * satisfied. The errors are a list
     * of human-readable strings.
     */
    data class Invalid(
        val errors: List<String>,
    ) : PlanValidationResult() {
        init {
            require(errors.isNotEmpty()) {
                "PlanValidationResult.Invalid.errors " +
                    "must not be empty"
            }
        }
    }
}

/**
 * The in-memory [OperatorPlanValidator]
 * for testing + production. The
 * validator is the stateless composition
 * of:
 *   - The intra-plan invariant rules.
 *
 * The validator is **thread-safe** (no
 * mutable fields).
 *
 * The validator enforces 3 rules:
 *   - **Rule 1: Steps' orders are
 *     unique.** No two steps have the
 *     same `order`.
 *   - **Rule 2: Steps' orders are
 *     contiguous starting from 1.**
 *     The orders are `1, 2, 3, ..., N`
 *     (no gaps).
 *   - **Rule 3: All steps' intents have
 *     the same `agentId` as the plan.**
 *     The agent that issued the plan is
 *     the agent that issued every step.
 */
class InMemoryOperatorPlanValidator : OperatorPlanValidator() {

    override fun validate(plan: OperatorPlan): PlanValidationResult {
        val errors = mutableListOf<String>()

        // Rule 1: Steps' orders are
        // unique.
        val orders = plan.steps.map { it.order }
        val uniqueOrders = orders.toSet()
        if (orders.size != uniqueOrders.size) {
            errors.add(
                "duplicate step orders: " +
                    orders
                        .groupingBy { it }
                        .eachCount()
                        .filter { it.value > 1 }
                        .keys
                        .joinToString(", "),
            )
        }

        // Rule 2: Steps' orders are
        // contiguous starting from 1.
        val expectedOrders = (1..plan.steps.size).toSet()
        if (uniqueOrders != expectedOrders) {
            errors.add(
                "step orders are not contiguous " +
                    "starting from 1: " +
                    "got ${uniqueOrders.sorted()}, " +
                    "expected ${expectedOrders.sorted()}",
            )
        }

        // Rule 3: All steps' intents have
        // the same agentId as the plan.
        val mismatchedSteps = plan.steps.filter {
            it.intent.agentId != plan.agentId
        }
        if (mismatchedSteps.isNotEmpty()) {
            errors.add(
                "steps with mismatched agentId: " +
                    mismatchedSteps.joinToString(", ") {
                        "order=${it.order}"
                    },
            )
        }

        return if (errors.isEmpty()) {
            PlanValidationResult.Valid
        } else {
            PlanValidationResult.Invalid(errors)
        }
    }
}

/**
 * The typed id of an operator plan. The
 * id is a UUID (per the Foundry id
 * convention).
 */
@JvmInline
value class PlanId(val value: UUID) {
    companion object {
        fun random(): PlanId = PlanId(UUID.randomUUID())
        fun from(raw: String): Result<PlanId> = try {
            Result.success(PlanId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                OperatorPlanError.InvalidPlanIdFormat(raw, e),
            )
        }
    }
}

/**
 * The typed error envelope for the
 * operator plan. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the `operator`
 * package because Kotlin sealed classes
 * only permit subclassing in the same
 * package where the base class is
 * declared).
 */
sealed class OperatorPlanError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The plan id string was not a
     * valid UUID. Raised at the boundary
     * (per `.ai/AGENTS.md` 24.1) — never
     * inside the domain.
     */
    data class InvalidPlanIdFormat(
        val rawInput: String,
        val parseFailure: Throwable,
    ) : OperatorPlanError(
        message = "Invalid UUID format for PlanId: $rawInput",
        code = "INVALID_PLAN_ID_FORMAT",
    )
}
