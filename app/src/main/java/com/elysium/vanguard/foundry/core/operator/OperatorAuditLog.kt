package com.elysium.vanguard.foundry.core.operator

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 85 (Foundry / AI Operator) — the
 * **Operator Audit Log**, the typed
 * immutable record of the AI Operator's
 * actions.
 *
 * Per the master vision (section 9):
 * "Auditoría de procesos. Registro
 * inmutable de operaciones críticas."
 *
 * The audit log is the **immutable
 * append-only log** of every operator
 * action. The log captures:
 *   - Every intent the AI agent issues
 *     (an `IntentIssued` entry).
 *   - Every plan the AI agent creates
 *     (a `PlanCreated` entry).
 *   - Every plan validation (a
 *     `PlanValidated` entry).
 *   - Every human approval / denial
 *     (a `PlanApproved` / `PlanDenied`
 *     entry).
 *   - Every plan execution start /
 *     completion / failure (a
 *     `PlanExecutionStarted` /
 *     `PlanExecutionCompleted` /
 *     `PlanExecutionFailed` entry).
 *   - Every step start / completion /
 *     failure (a `PlanStepStarted` /
 *     `PlanStepCompleted` /
 *     `PlanStepFailed` entry).
 *   - Every plan cancellation (a
 *     `PlanCancelled` entry).
 *
 * The log is **pure-domain** (no I/O, no
 * Android dependencies). The test impl
 * is the `InMemoryOperatorAuditLog`. The
 * production impl may be the same
 * (the log is append-only; the same impl
 * is used in tests + production).
 *
 * The log is **thread-safe** (the
 * underlying list is a
 * `CopyOnWriteArrayList` for safe
 * iteration during query + safe mutation
 * during `append`).
 */
sealed class OperatorAuditLog {

    /**
     * The log's current state. The state
     * is the list of all entries (in
     * append order).
     */
    abstract val entries: List<OperatorAuditEntry>

    /**
     * Append a new entry to the log. The
     * entry is added to the end of the
     * list; the existing entries are
     * preserved. The log is **append-only**;
     * existing entries are never modified.
     */
    abstract fun append(entry: OperatorAuditEntry)

    /**
     * Get the entries for a specific
     * plan. The entries are in append
     * order. Returns an empty list if the
     * plan has no entries.
     */
    abstract fun entriesForPlan(planId: PlanId): List<OperatorAuditEntry>

    /**
     * Get the entries for a specific
     * agent. The entries are in append
     * order. Returns an empty list if the
     * agent has no entries.
     */
    abstract fun entriesForAgent(agentId: UserId): List<OperatorAuditEntry>

    /**
     * Get the entries of a specific
     * action kind. The entries are in
     * append order. Returns an empty list
     * if no entries of the kind exist.
     */
    abstract fun entriesByActionKind(
        kind: OperatorActionKind,
    ): List<OperatorAuditEntry>

    /**
     * The number of entries in the log.
     */
    val size: Int
        get() = entries.size
}

/**
 * The typed action the operator performs.
 * The action is a sealed class with 12
 * cases.
 *
 * The action captures the **typed event**
 * the operator logs. Every case carries
 * the timestamp + the plan id (if
 * applicable) + the agent id (if
 * applicable) + the step order (if
 * applicable).
 */
sealed class OperatorAction {

    /**
     * The action's timestamp. The
     * timestamp is the millis since
     * epoch the action was performed.
     */
    abstract val timestampMs: Long

    /**
     * An intent was issued. The
     * `IntentIssued` action is logged
     * when the AI agent issues an
     * `OperatorIntent` (Phase 83).
     */
    data class IntentIssued(
        val intentId: IntentId,
        val agentId: UserId,
        val intentKind: IntentKind,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(timestampMs > 0) {
                "OperatorAction.IntentIssued.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A plan was created. The
     * `PlanCreated` action is logged
     * when the AI agent creates an
     * `OperatorPlan` (Phase 84).
     */
    data class PlanCreated(
        val planId: PlanId,
        val agentId: UserId,
        val stepCount: Int,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(stepCount > 0) {
                "OperatorAction.PlanCreated.stepCount " +
                    "must be > 0, got $stepCount"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanCreated.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A plan was validated. The
     * `PlanValidated` action is logged
     * when the plan is validated by
     * `InMemoryOperatorPlanValidator`
     * (Phase 84).
     */
    data class PlanValidated(
        val planId: PlanId,
        val isValid: Boolean,
        val errorCount: Int,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(errorCount >= 0) {
                "OperatorAction.PlanValidated.errorCount " +
                    "must be >= 0, got $errorCount"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanValidated.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A plan was approved by a human.
     * The `PlanApproved` action is
     * logged when a human reviews the
     * plan + approves it for execution.
     */
    data class PlanApproved(
        val planId: PlanId,
        val approverId: UserId,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(timestampMs > 0) {
                "OperatorAction.PlanApproved.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A plan was denied by a human. The
     * `PlanDenied` action is logged when
     * a human reviews the plan + denies
     * it.
     */
    data class PlanDenied(
        val planId: PlanId,
        val approverId: UserId,
        val reason: String,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(reason.isNotBlank()) {
                "OperatorAction.PlanDenied.reason " +
                    "must not be blank"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanDenied.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A plan started executing. The
     * `PlanExecutionStarted` action is
     * logged when the executor begins
     * executing the plan.
     */
    data class PlanExecutionStarted(
        val planId: PlanId,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(timestampMs > 0) {
                "OperatorAction.PlanExecutionStarted." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A step started executing. The
     * `PlanStepStarted` action is logged
     * when the executor begins executing
     * a step.
     */
    data class PlanStepStarted(
        val planId: PlanId,
        val stepOrder: Int,
        val intentId: IntentId,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(stepOrder > 0) {
                "OperatorAction.PlanStepStarted.stepOrder " +
                    "must be > 0, got $stepOrder"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanStepStarted.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A step completed. The
     * `PlanStepCompleted` action is
     * logged when the executor finishes
     * executing a step.
     */
    data class PlanStepCompleted(
        val planId: PlanId,
        val stepOrder: Int,
        val exitCode: Int,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(stepOrder > 0) {
                "OperatorAction.PlanStepCompleted.stepOrder " +
                    "must be > 0, got $stepOrder"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanStepCompleted." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A step failed. The
     * `PlanStepFailed` action is logged
     * when a step's execution fails.
     */
    data class PlanStepFailed(
        val planId: PlanId,
        val stepOrder: Int,
        val reason: String,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(stepOrder > 0) {
                "OperatorAction.PlanStepFailed.stepOrder " +
                    "must be > 0, got $stepOrder"
            }
            require(reason.isNotBlank()) {
                "OperatorAction.PlanStepFailed.reason " +
                    "must not be blank"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanStepFailed.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A plan completed. The
     * `PlanExecutionCompleted` action is
     * logged when the executor finishes
     * executing the plan.
     */
    data class PlanExecutionCompleted(
        val planId: PlanId,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(timestampMs > 0) {
                "OperatorAction.PlanExecutionCompleted." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A plan failed. The
     * `PlanExecutionFailed` action is
     * logged when the executor fails to
     * complete the plan.
     */
    data class PlanExecutionFailed(
        val planId: PlanId,
        val reason: String,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(reason.isNotBlank()) {
                "OperatorAction.PlanExecutionFailed.reason " +
                    "must not be blank"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanExecutionFailed." +
                    "timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A plan was cancelled. The
     * `PlanCancelled` action is logged
     * when a human cancels the plan
     * (e.g. the plan was approved but
     * the human changed their mind).
     */
    data class PlanCancelled(
        val planId: PlanId,
        val cancellerId: UserId,
        val reason: String,
        override val timestampMs: Long,
    ) : OperatorAction() {
        init {
            require(reason.isNotBlank()) {
                "OperatorAction.PlanCancelled.reason " +
                    "must not be blank"
            }
            require(timestampMs > 0) {
                "OperatorAction.PlanCancelled.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }
}

/**
 * Helper extension to map an
 * [OperatorAction] to its
 * [OperatorActionKind].
 */
val OperatorAction.kind: OperatorActionKind
    get() = when (this) {
        is OperatorAction.IntentIssued ->
            OperatorActionKind.INTENT_ISSUED
        is OperatorAction.PlanCreated ->
            OperatorActionKind.PLAN_CREATED
        is OperatorAction.PlanValidated ->
            OperatorActionKind.PLAN_VALIDATED
        is OperatorAction.PlanApproved ->
            OperatorActionKind.PLAN_APPROVED
        is OperatorAction.PlanDenied ->
            OperatorActionKind.PLAN_DENIED
        is OperatorAction.PlanExecutionStarted ->
            OperatorActionKind.PLAN_EXECUTION_STARTED
        is OperatorAction.PlanStepStarted ->
            OperatorActionKind.PLAN_STEP_STARTED
        is OperatorAction.PlanStepCompleted ->
            OperatorActionKind.PLAN_STEP_COMPLETED
        is OperatorAction.PlanStepFailed ->
            OperatorActionKind.PLAN_STEP_FAILED
        is OperatorAction.PlanExecutionCompleted ->
            OperatorActionKind.PLAN_EXECUTION_COMPLETED
        is OperatorAction.PlanExecutionFailed ->
            OperatorActionKind.PLAN_EXECUTION_FAILED
        is OperatorAction.PlanCancelled ->
            OperatorActionKind.PLAN_CANCELLED
    }

/**
 * The typed action kind. The kind is the
 * **classification** of the action; a
 * `when` on the kind is **exhaustive**.
 */
enum class OperatorActionKind(val displayLabel: String) {
    /** An intent was issued. */
    INTENT_ISSUED("Intent Issued"),

    /** A plan was created. */
    PLAN_CREATED("Plan Created"),

    /** A plan was validated. */
    PLAN_VALIDATED("Plan Validated"),

    /** A plan was approved. */
    PLAN_APPROVED("Plan Approved"),

    /** A plan was denied. */
    PLAN_DENIED("Plan Denied"),

    /** A plan started executing. */
    PLAN_EXECUTION_STARTED("Plan Execution Started"),

    /** A step started executing. */
    PLAN_STEP_STARTED("Plan Step Started"),

    /** A step completed. */
    PLAN_STEP_COMPLETED("Plan Step Completed"),

    /** A step failed. */
    PLAN_STEP_FAILED("Plan Step Failed"),

    /** A plan completed. */
    PLAN_EXECUTION_COMPLETED("Plan Execution Completed"),

    /** A plan failed. */
    PLAN_EXECUTION_FAILED("Plan Execution Failed"),

    /** A plan was cancelled. */
    PLAN_CANCELLED("Plan Cancelled"),
}

/**
 * A single entry in the audit log. The
 * entry is **immutable** (a data class; no
 * setters). A new entry is a new value.
 *
 * The entry has:
 *   - **`entryId`** — UUID.
 *   - **`action`** — the typed action.
 *   - **`signature`** — the entry's
 *     signature.
 */
data class OperatorAuditEntry(
    val entryId: AuditEntryId,
    val action: OperatorAction,
    val signature: Signature,
)

/**
 * The typed id of an audit entry. The id
 * is a UUID (per the Foundry id
 * convention).
 */
@JvmInline
value class AuditEntryId(val value: UUID) {
    companion object {
        fun random(): AuditEntryId = AuditEntryId(UUID.randomUUID())
        fun from(raw: String): Result<AuditEntryId> = try {
            Result.success(AuditEntryId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                OperatorAuditError.InvalidAuditEntryIdFormat(
                    raw,
                    e,
                ),
            )
        }
    }
}

/**
 * The in-memory [OperatorAuditLog] for
 * testing + production. The log is the
 * stateless composition of:
 *   - A list of entries (in append order).
 *
 * The log is **thread-safe** (the
 * underlying list is a
 * `CopyOnWriteArrayList` for safe
 * iteration during query + safe mutation
 * during `append`).
 */
class InMemoryOperatorAuditLog : OperatorAuditLog() {

    private val mutableEntries:
        CopyOnWriteArrayList<OperatorAuditEntry> =
        CopyOnWriteArrayList()

    override val entries: List<OperatorAuditEntry>
        get() = mutableEntries.toList()

    override fun append(entry: OperatorAuditEntry) {
        mutableEntries.add(entry)
    }

    override fun entriesForPlan(
        planId: PlanId,
    ): List<OperatorAuditEntry> =
        mutableEntries.filter {
            it.action.planIdOrNull() == planId
        }

    override fun entriesForAgent(
        agentId: UserId,
    ): List<OperatorAuditEntry> =
        mutableEntries.filter {
            it.action.agentIdOrNull() == agentId
        }

    override fun entriesByActionKind(
        kind: OperatorActionKind,
    ): List<OperatorAuditEntry> =
        mutableEntries.filter { it.action.kind == kind }
}

/**
 * Helper extension to extract the
 * [PlanId] from an [OperatorAction] (if
 * any). Returns `null` for actions
 * without a plan id (e.g. the
 * `IntentIssued` action).
 */
private fun OperatorAction.planIdOrNull(): PlanId? = when (this) {
    is OperatorAction.IntentIssued -> null
    is OperatorAction.PlanCreated -> planId
    is OperatorAction.PlanValidated -> planId
    is OperatorAction.PlanApproved -> planId
    is OperatorAction.PlanDenied -> planId
    is OperatorAction.PlanExecutionStarted -> planId
    is OperatorAction.PlanStepStarted -> planId
    is OperatorAction.PlanStepCompleted -> planId
    is OperatorAction.PlanStepFailed -> planId
    is OperatorAction.PlanExecutionCompleted -> planId
    is OperatorAction.PlanExecutionFailed -> planId
    is OperatorAction.PlanCancelled -> planId
}

/**
 * Helper extension to extract the
 * [UserId] from an [OperatorAction] (if
 * any). Returns `null` for actions
 * without an agent id (e.g. some
 * execution-completed actions).
 */
private fun OperatorAction.agentIdOrNull(): UserId? = when (this) {
    is OperatorAction.IntentIssued -> agentId
    is OperatorAction.PlanCreated -> agentId
    is OperatorAction.PlanValidated -> null
    is OperatorAction.PlanApproved -> approverId
    is OperatorAction.PlanDenied -> approverId
    is OperatorAction.PlanExecutionStarted -> null
    is OperatorAction.PlanStepStarted -> null
    is OperatorAction.PlanStepCompleted -> null
    is OperatorAction.PlanStepFailed -> null
    is OperatorAction.PlanExecutionCompleted -> null
    is OperatorAction.PlanExecutionFailed -> null
    is OperatorAction.PlanCancelled -> cancellerId
}

/**
 * The typed error envelope for the
 * operator audit log. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the `operator`
 * package because Kotlin sealed classes
 * only permit subclassing in the same
 * package where the base class is
 * declared).
 */
sealed class OperatorAuditError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The audit entry id string was not
     * a valid UUID. Raised at the
     * boundary (per `.ai/AGENTS.md`
     * 24.1) — never inside the domain.
     */
    data class InvalidAuditEntryIdFormat(
        val rawInput: String,
        val parseFailure: Throwable,
    ) : OperatorAuditError(
        message = "Invalid UUID format for " +
            "AuditEntryId: $rawInput",
        code = "INVALID_AUDIT_ENTRY_ID_FORMAT",
    )
}
