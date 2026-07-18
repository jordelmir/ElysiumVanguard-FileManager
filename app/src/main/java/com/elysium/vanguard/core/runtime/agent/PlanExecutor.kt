package com.elysium.vanguard.core.runtime.agent

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus

/**
 * Phase 57 — the runtime-side collaborators
 * the executor delegates to. The interface
 * is the seam that lets the executor be
 * JVM-testable: a test injects a fake
 * collaborator that records every call.
 *
 * The interface is intentionally small —
 * one method per [AgentAction] subtype.
 * The executor's dispatch is a `when` over
 * the action's class; the interface's
 * methods are the runners.
 */
interface AgentCollaborators {

    /**
     * Install a signed distro. Returns
     * success or a typed failure.
     */
    fun installDistro(distroId: String): AgentStepResult

    /**
     * Take a Windows binary + a runtime
     * kind and produce a runtime session.
     */
    fun createWindowsEnvironment(
        binaryPath: String,
        runtimeKind: String
    ): AgentStepResult

    /**
     * Capture a snapshot of the workspace's
     * live rootfs.
     */
    fun createSnapshot(workspaceId: String, label: String): AgentStepResult

    /**
     * Restore the workspace to a previous
     * snapshot.
     */
    fun rollbackToSnapshot(
        workspaceId: String,
        snapshotId: String
    ): AgentStepResult

    /**
     * Run a local build.
     */
    fun runBuild(
        toolchainKind: String,
        command: List<String>
    ): AgentStepResult

    /**
     * Run a generic command.
     */
    fun runCommand(
        command: List<String>,
        workingDirectory: String?
    ): AgentStepResult
}

/**
 * Phase 57 — the result of a single
 * [AgentAction] step. The executor
 * collects one [AgentStepResult] per
 * action; the [ExecutionOutcome] is the
 * aggregate.
 */
sealed class AgentStepResult {
    data class Success(val message: String = "") : AgentStepResult()
    data class Failure(val message: String) : AgentStepResult()
}

/**
 * Phase 57 — the executor's output.
 *
 * - [Success] — every action succeeded.
 * - [Failure] — at least one action failed.
 *   The outcome carries the failed action
 *   + the rolled-back state (if the
 *   executor rolled back).
 * - [RolledBack] — at least one action
 *   failed AND the executor successfully
 *   rolled back to the pre-execution
 *   snapshot. The workspace is back to
 *   the state before the agent ran.
 * - [Refused] — the executor refused to
 *   execute the plan (HIGH-risk plan
 *   without user confirmation).
 */
sealed class ExecutionOutcome {
    data class Success(
        val plan: AgentPlan,
        val stepResults: List<AgentStepResult>
    ) : ExecutionOutcome()

    data class Failure(
        val plan: AgentPlan,
        val stepResults: List<AgentStepResult>,
        val failedActionIndex: Int,
        val failureMessage: String,
        val rolledBack: Boolean
    ) : ExecutionOutcome()

    data class Refused(
        val plan: AgentPlan,
        val reason: String
    ) : ExecutionOutcome()
}

/**
 * Phase 57 — the runtime's agent
 * executor. The executor consumes an
 * [AgentPlan] and dispatches each
 * [AgentAction] to the right collaborator.
 *
 * ## Snapshot / rollback policy
 *
 * The master vision:
> "Crear snapshots antes de modificar un
> entorno. Revertir automáticamente cuando
> una operación falle."
 *
 * The executor's policy:
 * 1. Before the first destructive action
 *    in the plan, the executor takes a
 *    snapshot of the workspace identified
 *    in the plan. The snapshot's label is
 *    `agent-pre-<plan.id>`.
 * 2. After each action, the executor
 *    publishes a [RuntimeEvent.AgentActionCompletedEvent]
 *    or [RuntimeEvent.AgentActionFailedEvent]
 *    on the bus.
 * 3. On failure, the executor rolls back
 *    to the snapshot from step 1. The
 *    rollback's success is reported as a
 *    [RuntimeEvent.AgentActionRolledBackEvent].
 * 4. If the rollback itself fails, the
 *    executor reports [ExecutionOutcome.Failure]
 *    with `rolledBack = false` and a
 *    message that names the failed
 *    rollback.
 *
 * ## Confirmation gate
 *
 * HIGH-risk plans require user
 * confirmation. The executor returns
 * [ExecutionOutcome.Refused] if the
 * plan's risk is HIGH and the goal's
 * [NaturalLanguageGoal.autoConfirm] is
 * `false`.
 */
class PlanExecutor(
    private val collaborators: AgentCollaborators,
    private val eventBus: RuntimeEventBus,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Execute [plan]. The executor's policy
     * is documented in the class header.
     */
    fun execute(plan: AgentPlan): ExecutionOutcome {
        // Confirmation gate: HIGH-risk
        // plans require user confirmation
        // unless the goal's autoConfirm is
        // true.
        if (plan.riskLevel == RiskLevel.HIGH && !plan.goal.autoConfirm) {
            eventBus.publish(
                RuntimeEvent.AgentActionRefusedEvent(
                    atMs = clock(),
                    workspaceId = null,
                    planId = plan.id,
                    reason = "HIGH-risk plan requires user confirmation; set autoConfirm=true to override"
                )
            )
            return ExecutionOutcome.Refused(
                plan = plan,
                reason = "HIGH-risk plan requires user confirmation; set autoConfirm=true to override"
            )
        }

        val stepResults = ArrayList<AgentStepResult>(plan.actions.size)
        var rolledBack = false
        var failedActionIndex = -1
        var failureMessage = ""
        var snapshotTakenFor: String? = null

        for ((index, action) in plan.actions.withIndex()) {
            eventBus.publish(
                RuntimeEvent.AgentActionStartedEvent(
                    atMs = clock(),
                    workspaceId = plan.targetWorkspaceId,
                    planId = plan.id,
                    actionIndex = index
                )
            )

            // Snapshot before the first
            // destructive action. The
            // snapshot targets the plan's
            // [targetWorkspaceId]; if that's
            // null, no snapshot is taken
            // (the plan is not workspace-
            // scoped).
            val result = if (isDestructive(action) && snapshotTakenFor == null) {
                val targetWorkspace = plan.targetWorkspaceId
                if (targetWorkspace != null) {
                    val snapshotResult = collaborators.createSnapshot(
                        workspaceId = targetWorkspace,
                        label = "agent-pre-${plan.id}"
                    )
                    if (snapshotResult is AgentStepResult.Success) {
                        snapshotTakenFor = targetWorkspace
                    }
                }
                dispatch(action)
            } else {
                dispatch(action)
            }

            stepResults += result
            when (result) {
                is AgentStepResult.Success -> {
                    eventBus.publish(
                        RuntimeEvent.AgentActionCompletedEvent(
                            atMs = clock(),
                            workspaceId = plan.targetWorkspaceId,
                            planId = plan.id,
                            actionIndex = index
                        )
                    )
                }
                is AgentStepResult.Failure -> {
                    failedActionIndex = index
                    failureMessage = result.message
                    eventBus.publish(
                        RuntimeEvent.AgentActionFailedEvent(
                            atMs = clock(),
                            workspaceId = plan.targetWorkspaceId,
                            planId = plan.id,
                            actionIndex = index,
                            error = result.message
                        )
                    )
                    // Roll back if we took a
                    // snapshot.
                    if (snapshotTakenFor != null) {
                        val rollbackResult = collaborators.rollbackToSnapshot(
                            workspaceId = snapshotTakenFor!!,
                            snapshotId = "latest"
                        )
                        rolledBack = rollbackResult is AgentStepResult.Success
                        eventBus.publish(
                            RuntimeEvent.AgentActionRolledBackEvent(
                                atMs = clock(),
                                workspaceId = snapshotTakenFor,
                                planId = plan.id,
                                actionIndex = index,
                                rolledBack = rolledBack
                            )
                        )
                    }
                    return ExecutionOutcome.Failure(
                        plan = plan,
                        stepResults = stepResults,
                        failedActionIndex = failedActionIndex,
                        failureMessage = failureMessage,
                        rolledBack = rolledBack
                    )
                }
            }
        }

        return ExecutionOutcome.Success(
            plan = plan,
            stepResults = stepResults
        )
    }

    private fun dispatch(action: AgentAction): AgentStepResult = when (action) {
        is AgentAction.InstallDistro ->
            collaborators.installDistro(action.distroId)
        is AgentAction.CreateWindowsEnvironment ->
            collaborators.createWindowsEnvironment(
                binaryPath = action.binaryPath,
                runtimeKind = action.runtimeKind
            )
        is AgentAction.CreateSnapshot ->
            collaborators.createSnapshot(
                workspaceId = action.workspaceId,
                label = action.label
            )
        is AgentAction.RollbackToSnapshot ->
            collaborators.rollbackToSnapshot(
                workspaceId = action.workspaceId,
                snapshotId = action.snapshotId
            )
        is AgentAction.RunBuild ->
            collaborators.runBuild(
                toolchainKind = action.toolchainKind,
                command = action.command
            )
        is AgentAction.RunCommand ->
            collaborators.runCommand(
                command = action.command,
                workingDirectory = action.workingDirectory
            )
    }

    /**
     * True iff the action is destructive
     * (modifies user state) and warrants a
     * pre-execution snapshot. Read-only
     * actions (build, run command) are
     * not destructive.
     */
    private fun isDestructive(action: AgentAction): Boolean = when (action) {
        is AgentAction.InstallDistro -> true
        is AgentAction.CreateWindowsEnvironment -> true
        is AgentAction.CreateSnapshot -> false
        is AgentAction.RollbackToSnapshot -> false
        is AgentAction.RunBuild -> false
        is AgentAction.RunCommand -> false
    }
}
