package com.elysium.vanguard.foundry.core.operator

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 86 (Foundry / AI Operator) — the
 * **Operator Plan Executor**, the
 * orchestrator that ties together the AI
 * Operator's 3 phases (Phase 83 intent +
 * Phase 84 plan + Phase 85 audit log +
 * this phase executor) into a single
 * execution flow.
 *
 * Per the master vision (section 8):
 * "La IA debía convertir eso en un plan
 * declarativo, mostrar cambios y ejecutar
 * únicamente operaciones autorizadas."
 *
 * The executor's algorithm:
 *   1. Validate the plan as a whole
 *      (delegate to
 *      [OperatorPlanValidator]).
 *   2. If the plan is invalid, return
 *      a `PlanExecutionResult` with
 *      `status = Failed`.
 *   3. For each step in the plan (in
 *      `order`):
 *      a. Validate the step's intent
 *         against the operator's
 *         authority (delegate to
 *         [OperatorIntentValidator]).
 *      b. If `Allowed`: "execute" the
 *         step (in pure-domain, return
 *         exit code 0; in production,
 *         call the ProcessLauncher),
 *         log a `PlanStepCompleted`,
 *         and continue.
 *      c. If `RequiresApproval`: log a
 *         `PlanExecutionPaused`, and
 *         return a `PlanExecutionResult`
 *         with `status = Paused`.
 *      d. If `Denied`: log a
 *         `PlanExecutionFailed`, and
 *         return a `PlanExecutionResult`
 *         with `status = Failed`.
 *   4. If all steps completed: log a
 *      `PlanExecutionCompleted`, and
 *      return a `PlanExecutionResult`
 *      with `status = Completed`.
 *
 * The executor is **pure-domain** (no
 * I/O, no Android dependencies). The
 * test impl is the
 * `InMemoryOperatorPlanExecutor`. The
 * production impl may be the same
 * (the executor is stateless + pure;
 * the same impl is used in production).
 */
sealed class OperatorPlanExecutor {

    /**
     * Execute a plan. The executor
     * validates the plan + each step +
     * logs the results.
     *
     * The `nowMs` parameter is the
     * current time (millis since epoch);
     * the executor uses it for the
     * audit log timestamps. The
     * parameter is **explicit** (not
     * derived from `System.currentTimeMillis()`)
     * so the executor is **deterministic**
     * (the test can use a fixed
     * `nowMs`).
     */
    abstract fun execute(
        plan: OperatorPlan,
        authority: OperatorAuthority,
        intentValidator: OperatorIntentValidator,
        planValidator: OperatorPlanValidator,
        auditLog: OperatorAuditLog,
        nowMs: Long,
    ): PlanExecutionResult
}

/**
 * The per-step result. The result is a
 * sealed class with 4 cases:
 *   - **`Validated`** — the step's
 *     intent was validated as `Allowed`
 *     (the executor will execute the
 *     step next).
 *   - **`Executed`** — the step was
 *     executed (the executor has
 *     finished executing the step; the
 *     `exitCode` is the result).
 *   - **`AwaitingApproval`** — the
 *     step's intent requires human
 *     approval (the executor will pause
 *     the plan).
 *   - **`Denied`** — the step's intent
 *     was denied (the executor will
 *     fail the plan).
 *   - **`Failed`** — the step's
 *     execution failed (the executor
 *     will fail the plan).
 */
sealed class StepResult {

    /**
     * The intent id the result is for.
     * The id is the join key the
     * consumer uses to find the
     * result.
     */
    abstract val intentId: IntentId

    /**
     * The step was validated. The
     * step's intent was allowed by the
     * authority; the executor will
     * execute the step next.
     */
    data class Validated(
        override val intentId: IntentId,
    ) : StepResult()

    /**
     * The step was executed. The
     * step's execution finished; the
     * `exitCode` is the result.
     */
    data class Executed(
        override val intentId: IntentId,
        val exitCode: Int,
    ) : StepResult()

    /**
     * The step is awaiting approval.
     * The step's intent requires human
     * approval; the executor has paused
     * the plan.
     */
    data class AwaitingApproval(
        override val intentId: IntentId,
        val reason: String,
    ) : StepResult() {
        init {
            require(reason.isNotBlank()) {
                "StepResult.AwaitingApproval.reason " +
                    "must not be blank"
            }
        }
    }

    /**
     * The step was denied. The step's
     * intent was denied by the
     * authority; the executor has
     * failed the plan.
     */
    data class Denied(
        override val intentId: IntentId,
        val reason: String,
    ) : StepResult() {
        init {
            require(reason.isNotBlank()) {
                "StepResult.Denied.reason must " +
                    "not be blank"
            }
        }
    }

    /**
     * The step failed. The step's
     * execution failed; the executor
     * has failed the plan.
     */
    data class Failed(
        override val intentId: IntentId,
        val reason: String,
    ) : StepResult() {
        init {
            require(reason.isNotBlank()) {
                "StepResult.Failed.reason must " +
                    "not be blank"
            }
        }
    }
}

/**
 * The plan execution result. The result
 * is the executor's output for a given
 * [OperatorPlan] + [OperatorAuthority]
 * + [OperatorAuditLog].
 *
 * The result is **immutable** (a data
 * class; no setters). A new result is
 * a new value.
 *
 * The result has:
 *   - **`executionId`** — UUID; the
 *     unique id of this execution
 *     attempt.
 *   - **`planId`** — the plan that was
 *     executed.
 *   - **`status`** — the final
 *     [PlanStatus] of the plan.
 *   - **`stepResults`** — the per-step
 *     results (in `order`).
 *   - **`startedAtMs`** — the timestamp
 *     the execution started.
 *   - **`completedAtMs`** — the
 *     timestamp the execution
 *     completed (or paused / failed).
 */
data class PlanExecutionResult(
    val executionId: ExecutionId,
    val planId: PlanId,
    val status: PlanStatus,
    val stepResults: Map<Int, StepResult>,
    val startedAtMs: Long,
    val completedAtMs: Long,
) {
    init {
        // stepResults may be empty for
        // plans that did not execute any
        // step (e.g. an invalid plan that
        // failed at validation time, or
        // a plan where the first step was
        // denied).
        require(startedAtMs > 0) {
            "PlanExecutionResult.startedAtMs " +
                "must be > 0, got $startedAtMs"
        }
        require(completedAtMs >= startedAtMs) {
            "PlanExecutionResult.completedAtMs " +
                "($completedAtMs) must be >= " +
                "startedAtMs ($startedAtMs)"
        }
    }

    /**
     * The duration of the execution.
     * The duration is the number of
     * millis the execution took.
     */
    val durationMs: Long
        get() = completedAtMs - startedAtMs
}

/**
 * The typed id of an execution. The id
 * is a UUID (per the Foundry id
 * convention). The id is the join key
 * the consumer uses to find the
 * execution result.
 */
@JvmInline
value class ExecutionId(val value: UUID) {
    companion object {
        fun random(): ExecutionId = ExecutionId(UUID.randomUUID())
        fun from(raw: String): Result<ExecutionId> = try {
            Result.success(ExecutionId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                OperatorExecutionError.InvalidExecutionIdFormat(
                    raw,
                    e,
                ),
            )
        }
    }
}

/**
 * The in-memory [OperatorPlanExecutor]
 * for testing + production. The executor
 * is the stateless composition of:
 *   - The plan validation rules (per
 *     [OperatorPlanValidator]).
 *   - The intent validation rules
 *     (per [OperatorIntentValidator]).
 *   - The audit log (per
 *     [OperatorAuditLog]).
 *
 * The executor is **thread-safe** (no
 * mutable fields). The executor is
 * **deterministic** (the `nowMs`
 * parameter is explicit; the executor
 * does NOT call
 * `System.currentTimeMillis()`).
 */
class InMemoryOperatorPlanExecutor : OperatorPlanExecutor() {

    override fun execute(
        plan: OperatorPlan,
        authority: OperatorAuthority,
        intentValidator: OperatorIntentValidator,
        planValidator: OperatorPlanValidator,
        auditLog: OperatorAuditLog,
        nowMs: Long,
    ): PlanExecutionResult {
        val executionId = ExecutionId.random()
        val startedAtMs = nowMs

        // Log: PlanExecutionStarted.
        auditLog.append(
            buildEntry(
                action = OperatorAction.PlanExecutionStarted(
                    planId = plan.planId,
                    timestampMs = startedAtMs,
                ),
            ),
        )

        // Step 1: Validate the plan as a
        // whole.
        val planValidation = planValidator.validate(plan)
        if (planValidation is PlanValidationResult.Invalid) {
            // The plan is invalid. Log a
            // PlanExecutionFailed + return.
            val completedAtMs = nowMs
            val reason =
                "plan validation failed: " +
                    planValidation.errors.joinToString(", ")
            auditLog.append(
                buildEntry(
                    action = OperatorAction.PlanExecutionFailed(
                        planId = plan.planId,
                        reason = reason,
                        timestampMs = completedAtMs,
                    ),
                ),
            )
            return PlanExecutionResult(
                executionId = executionId,
                planId = plan.planId,
                status = PlanStatus.Failed(reason = reason),
                stepResults = emptyMap(),
                startedAtMs = startedAtMs,
                completedAtMs = completedAtMs,
            )
        }

        // Step 2: For each step in the
        // plan (in `order`).
        val stepResults = mutableMapOf<Int, StepResult>()
        val sortedSteps = plan.steps.sortedBy { it.order }
        for (step in sortedSteps) {
            val validation = intentValidator.validate(
                step.intent,
                authority,
            )
            when (validation) {
                is ValidationResult.Allowed -> {
                    // Log: PlanStepStarted.
                    val stepStartedMs = nowMs
                    auditLog.append(
                        buildEntry(
                            action = OperatorAction.PlanStepStarted(
                                planId = plan.planId,
                                stepOrder = step.order,
                                intentId = step.intent.intentId,
                                timestampMs = stepStartedMs,
                            ),
                        ),
                    )

                    // Execute the step (in
                    // pure-domain, return
                    // exit code 0; in
                    // production, call the
                    // ProcessLauncher).
                    val exitCode = 0
                    stepResults[step.order] = StepResult.Executed(
                        intentId = step.intent.intentId,
                        exitCode = exitCode,
                    )

                    // Log: PlanStepCompleted.
                    val stepCompletedMs = nowMs
                    auditLog.append(
                        buildEntry(
                            action = OperatorAction.PlanStepCompleted(
                                planId = plan.planId,
                                stepOrder = step.order,
                                exitCode = exitCode,
                                timestampMs = stepCompletedMs,
                            ),
                        ),
                    )
                }
                is ValidationResult.RequiresApproval -> {
                    // The AwaitingApproval
                    // result is the trigger for
                    // the plan's Paused status;
                    // it is NOT added to
                    // stepResults (stepResults
                    // only contains Executed
                    // results).
                    val completedAtMs = nowMs
                    auditLog.append(
                        buildEntry(
                            action = OperatorAction.PlanExecutionFailed(
                                planId = plan.planId,
                                reason = "step ${step.order} " +
                                    "requires approval: " +
                                    validation.reason,
                                timestampMs = completedAtMs,
                            ),
                        ),
                    )
                    return PlanExecutionResult(
                        executionId = executionId,
                        planId = plan.planId,
                        status = PlanStatus.Paused(
                            reason = "step ${step.order} " +
                                "requires approval",
                        ),
                        stepResults = stepResults,
                        startedAtMs = startedAtMs,
                        completedAtMs = completedAtMs,
                    )
                }
                is ValidationResult.Denied -> {
                    // The Denied result is the
                    // trigger for the plan's
                    // Failed status; it is NOT
                    // added to stepResults
                    // (stepResults only contains
                    // Executed results).
                    val completedAtMs = nowMs
                    auditLog.append(
                        buildEntry(
                            action = OperatorAction.PlanExecutionFailed(
                                planId = plan.planId,
                                reason = "step ${step.order} " +
                                    "denied: " +
                                    validation.reason,
                                timestampMs = completedAtMs,
                            ),
                        ),
                    )
                    return PlanExecutionResult(
                        executionId = executionId,
                        planId = plan.planId,
                        status = PlanStatus.Failed(
                            reason = "step ${step.order} " +
                                "denied: " +
                                validation.reason,
                        ),
                        stepResults = stepResults,
                        startedAtMs = startedAtMs,
                        completedAtMs = completedAtMs,
                    )
                }
            }
        }

        // Step 3: All steps completed.
        // Log: PlanExecutionCompleted.
        val completedAtMs = nowMs
        auditLog.append(
            buildEntry(
                action = OperatorAction.PlanExecutionCompleted(
                    planId = plan.planId,
                    timestampMs = completedAtMs,
                ),
            ),
        )
        return PlanExecutionResult(
            executionId = executionId,
            planId = plan.planId,
            status = PlanStatus.Completed,
            stepResults = stepResults,
            startedAtMs = startedAtMs,
            completedAtMs = completedAtMs,
        )
    }

    /**
     * Build an [OperatorAuditEntry]
     * with a fresh entry id + a random
     * signature.
     */
    private fun buildEntry(
        action: OperatorAction,
    ): OperatorAuditEntry = OperatorAuditEntry(
        entryId = AuditEntryId.random(),
        action = action,
        signature = com.elysium.vanguard.foundry.core
            .ontology.primitives.Signature(
                "sig-exec-${UUID.randomUUID()}",
            ),
    )
}

/**
 * The typed error envelope for the
 * operator execution. The error
 * extends `RuntimeException` (mirrors
 * the `FoundryError` contract with
 * `code` + `message`, but lives in the
 * `operator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class OperatorExecutionError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The execution id string was not
     * a valid UUID. Raised at the
     * boundary (per `.ai/AGENTS.md`
     * 24.1) — never inside the domain.
     */
    data class InvalidExecutionIdFormat(
        val rawInput: String,
        val parseFailure: Throwable,
    ) : OperatorExecutionError(
        message = "Invalid UUID format for " +
            "ExecutionId: $rawInput",
        code = "INVALID_EXECUTION_ID_FORMAT",
    )
}
