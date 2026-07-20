package com.elysium.vanguard.foundry.core.operator

/**
 * Phase 88 (Foundry / AI Operator) — the
 * **Operator Plan Status Tracker**, the
 * typed utility that reads the
 * [OperatorAuditLog] (Phase 85) and
 * computes the current [PlanStatus] of a
 * plan.
 *
 * The tracker is the **read side** of the
 * AI Operator:
 *   - The [OperatorPlanExecutor] (Phase 86)
 *     is the **write side** (the executor
 *     logs the events + returns the
 *     `PlanExecutionResult`).
 *   - The tracker is the **query side** (a
 *     consumer can ask "what is the
 *     current status of plan X?").
 *
 * The tracker is **pure-domain** (no I/O,
 * no Android dependencies). The test impl
 * is the
 * `InMemoryOperatorPlanStatusTracker`. The
 * production impl may be the same
 * (the tracker is stateless + pure; the
 * same impl is used in production).
 *
 * The tracker's algorithm (given the
 * audit log entries for a plan, in
 * chronological order):
 *   - If the log has no entries for the
 *     plan → `Pending`.
 *   - If the log has
 *     `PlanExecutionFailed` (the last
 *     action for the plan) → `Failed`
 *     (with the reason from the most
 *     recent failure).
 *   - If the log has `PlanExecutionCompleted`
 *     (the last action for the plan) →
 *     `Completed`.
 *   - If the log has `PlanExecutionStarted`
 *     (the last action for the plan) →
 *     `Running`.
 *   - If the log has `PlanDenied` (the last
 *     action for the plan) → `Failed`
 *     (with the denied reason).
 *   - If the log has `PlanApproved` (the
 *     last action for the plan) →
 *     `Pending` (approved but not yet
 *     started).
 *   - If the log has `PlanCreated` (the
 *     last action for the plan) →
 *     `Pending`.
 *   - Otherwise → `Pending`.
 */
sealed class OperatorPlanStatusTracker {

    /**
     * Compute the current [PlanStatus] of
     * a plan. The tracker reads the audit
     * log + returns the typed status.
     */
    abstract fun currentStatus(
        planId: PlanId,
        auditLog: OperatorAuditLog,
    ): PlanStatus
}

/**
 * The in-memory
 * [OperatorPlanStatusTracker] for testing
 * + production. The tracker is the
 * stateless composition of:
 *   - The audit log entries for the
 *     plan (in chronological order).
 *   - The tracking algorithm (per
 *     [OperatorPlanStatusTracker]).
 *
 * The tracker is **thread-safe** (no
 * mutable fields).
 */
class InMemoryOperatorPlanStatusTracker :
    OperatorPlanStatusTracker() {

    override fun currentStatus(
        planId: PlanId,
        auditLog: OperatorAuditLog,
    ): PlanStatus {
        val entries = auditLog.entriesForPlan(planId)
        if (entries.isEmpty()) {
            return PlanStatus.Pending
        }
        // The status is determined by
        // the most recent terminal
        // action. The terminal actions
        // are: PlanExecutionCompleted,
        // PlanExecutionFailed,
        // PlanDenied. If none of these
        // are present, the plan is either
        // Pending (not yet started) or
        // Running (started but not
        // completed).
        val lastTerminalAction = entries
            .map { it.action }
            .lastOrNull { action ->
                action is OperatorAction.PlanExecutionCompleted ||
                    action is OperatorAction.PlanExecutionFailed ||
                    action is OperatorAction.PlanDenied
            }
        if (lastTerminalAction != null) {
            return when (lastTerminalAction) {
                is OperatorAction.PlanExecutionCompleted ->
                    PlanStatus.Completed
                is OperatorAction.PlanExecutionFailed ->
                    PlanStatus.Failed(
                        reason = lastTerminalAction.reason,
                    )
                is OperatorAction.PlanDenied ->
                    PlanStatus.Failed(
                        reason = "plan denied: " +
                            lastTerminalAction.reason,
                    )
                else ->
                    // Unreachable (the
                    // `lastOrNull` filter
                    // already restricts the
                    // types).
                    PlanStatus.Pending
            }
        }
        // No terminal action. Check if
        // the plan has started
        // (PlanExecutionStarted or any
        // PlanStep*).
        val hasStarted = entries.any { entry ->
            entry.action is OperatorAction.PlanExecutionStarted ||
                entry.action is OperatorAction.PlanStepStarted ||
                entry.action is OperatorAction.PlanStepCompleted ||
                entry.action is OperatorAction.PlanStepFailed
        }
        return if (hasStarted) {
            PlanStatus.Running
        } else {
            PlanStatus.Pending
        }
    }
}

/**
 * The typed error envelope for the
 * operator plan status tracker. The
 * error extends `RuntimeException`
 * (mirrors the `FoundryError` contract
 * with `code` + `message`, but lives in
 * the `operator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class OperatorPlanStatusError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The plan id is unknown. The plan
     * has no entries in the audit log.
     * The tracker returns `Pending` for
     * unknown plan ids (this is the
     * conservative default).
     */
    data class UnknownPlanId(
        val planId: PlanId,
    ) : OperatorPlanStatusError(
        message = "Unknown plan id: " +
            "${planId.value}",
        code = "UNKNOWN_PLAN_ID",
    )
}
