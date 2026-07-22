package com.elysium.vanguard.core.runtime.agent

import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.observability.RecordingEventBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Phase 57 — tests for [PlanExecutor].
 *
 * The executor consumes an [AgentPlan] and
 * dispatches each [AgentAction] to the
 * right collaborator. The tests use a
 * [FakeAgentCollaborators] that records
 * every call in a thread-safe list; the
 * executor's orchestration is asserted
 * without actually invoking the runtime.
 *
 * The tests pin:
 *
 *   - The executor refuses HIGH-risk plans
 *     without user confirmation.
 *   - The executor honours
 *     [NaturalLanguageGoal.autoConfirm] for
 *     HIGH-risk plans.
 *   - The executor takes a snapshot
 *     before the first destructive action.
 *   - The executor rolls back to the
 *     snapshot on a failed action.
 *   - The executor publishes the right
 *     [RuntimeEvent]s on the bus for
 *     every state transition.
 *   - The executor returns Success when
 *     every action succeeds.
 *   - The executor returns Failure with
 *     `rolledBack = true` when a
 *     destructive action fails after a
 *     snapshot was taken.
 */
class PlanExecutorTest {

    private lateinit var collaborators: FakeAgentCollaborators
    private lateinit var eventBus: RecordingEventBus
    private lateinit var executor: PlanExecutor

    @Before
    fun setUp() {
        collaborators = FakeAgentCollaborators()
        eventBus = RecordingEventBus()
        executor = PlanExecutor(collaborators, eventBus)
    }

    @Test
    fun `executor refuses a HIGH-risk plan without user confirmation`() {
        val plan = makePlan(
            actions = listOf(
                AgentAction.RollbackToSnapshot(
                    workspaceId = "ws-1",
                    snapshotId = "snap-1"
                )
            ),
            risk = RiskLevel.HIGH
        )
        val outcome = executor.execute(plan)
        assertTrue(outcome is ExecutionOutcome.Refused)
        val refused = outcome as ExecutionOutcome.Refused
        assertEquals(plan, refused.plan)
        // No action was dispatched.
        assertTrue(collaborators.calls.isEmpty())
        // The bus received a Refused event.
        val refusedEvents = eventBus.events
            .filterIsInstance<RuntimeEvent.AgentActionRefusedEvent>()
        assertEquals(1, refusedEvents.size)
    }

    @Test
    fun `executor honours autoConfirm for HIGH-risk plans`() {
        val plan = makePlan(
            actions = listOf(
                AgentAction.RollbackToSnapshot(
                    workspaceId = "ws-1",
                    snapshotId = "snap-1"
                )
            ),
            risk = RiskLevel.HIGH,
            autoConfirm = true
        )
        collaborators.rollbackToSnapshotResult = AgentStepResult.Success("rolled back")
        val outcome = executor.execute(plan)
        assertTrue(outcome is ExecutionOutcome.Success)
        // The rollback action was dispatched.
        assertEquals(1, collaborators.calls.size)
        val call = collaborators.calls[0]
        assertTrue(call is FakeAgentCollaborators.Call.Rollback)
        assertEquals("ws-1", (call as FakeAgentCollaborators.Call.Rollback).workspaceId)
    }

    @Test
    fun `executor takes a snapshot before the first destructive action when targetWorkspaceId is set`() {
        collaborators.snapshotResult = AgentStepResult.Success("snap-1")
        collaborators.installDistroResult = AgentStepResult.Success("installed")
        val plan = makePlan(
            actions = listOf(AgentAction.InstallDistro(distroId = "debian-12")),
            risk = RiskLevel.MEDIUM,
            targetWorkspaceId = "ws-1"
        )
        executor.execute(plan)
        // The first call should be a snapshot;
        // the second should be the install.
        assertEquals(2, collaborators.calls.size)
        assertTrue(collaborators.calls[0] is FakeAgentCollaborators.Call.Snapshot)
        assertTrue(collaborators.calls[1] is FakeAgentCollaborators.Call.Install)
    }

    @Test
    fun `executor does not take a snapshot when targetWorkspaceId is null`() {
        collaborators.installDistroResult = AgentStepResult.Success("installed")
        val plan = makePlan(
            actions = listOf(AgentAction.InstallDistro(distroId = "debian-12")),
            risk = RiskLevel.MEDIUM,
            targetWorkspaceId = null
        )
        executor.execute(plan)
        // No snapshot was taken — the plan
        // is not workspace-scoped.
        assertEquals(1, collaborators.calls.size)
        assertTrue(collaborators.calls[0] is FakeAgentCollaborators.Call.Install)
    }

    @Test
    fun `executor rolls back on a failed destructive action when a snapshot was taken`() {
        collaborators.snapshotResult = AgentStepResult.Success("snap-1")
        collaborators.installDistroResult = AgentStepResult.Failure("install failed")
        collaborators.rollbackToSnapshotResult = AgentStepResult.Success("rolled back")
        val plan = makePlan(
            actions = listOf(AgentAction.InstallDistro(distroId = "debian-12")),
            risk = RiskLevel.MEDIUM,
            targetWorkspaceId = "ws-1"
        )
        val outcome = executor.execute(plan)
        assertTrue(outcome is ExecutionOutcome.Failure)
        val failure = outcome as ExecutionOutcome.Failure
        assertTrue(
            "failure should report rolledBack=true: $failure",
            failure.rolledBack
        )
        // The calls: snapshot, install (failed),
        // rollback.
        assertEquals(3, collaborators.calls.size)
        assertTrue(collaborators.calls[0] is FakeAgentCollaborators.Call.Snapshot)
        assertTrue(collaborators.calls[1] is FakeAgentCollaborators.Call.Install)
        assertTrue(collaborators.calls[2] is FakeAgentCollaborators.Call.Rollback)
    }

    @Test
    fun `executor reports rolledBack=false when the rollback itself fails`() {
        collaborators.snapshotResult = AgentStepResult.Success("snap-1")
        collaborators.installDistroResult = AgentStepResult.Failure("install failed")
        collaborators.rollbackToSnapshotResult = AgentStepResult.Failure("rollback failed")
        val plan = makePlan(
            actions = listOf(AgentAction.InstallDistro(distroId = "debian-12")),
            risk = RiskLevel.MEDIUM,
            targetWorkspaceId = "ws-1"
        )
        val outcome = executor.execute(plan)
        assertTrue(outcome is ExecutionOutcome.Failure)
        val failure = outcome as ExecutionOutcome.Failure
        assertTrue(
            "failure should report rolledBack=false: $failure",
            !failure.rolledBack
        )
    }

    @Test
    fun `executor publishes AgentActionStarted and AgentActionCompleted events on success`() {
        collaborators.installDistroResult = AgentStepResult.Success("installed")
        val plan = makePlan(
            actions = listOf(AgentAction.InstallDistro(distroId = "debian-12")),
            risk = RiskLevel.MEDIUM
        )
        executor.execute(plan)
        val started = eventBus.events
            .filterIsInstance<RuntimeEvent.AgentActionStartedEvent>()
        val completed = eventBus.events
            .filterIsInstance<RuntimeEvent.AgentActionCompletedEvent>()
        assertEquals(1, started.size)
        assertEquals(1, completed.size)
        assertEquals(0, started[0].actionIndex)
        assertEquals(0, completed[0].actionIndex)
    }

    @Test
    fun `executor publishes AgentActionFailed and AgentActionRolledBack events on a failed destructive action`() {
        collaborators.snapshotResult = AgentStepResult.Success("snap-1")
        collaborators.installDistroResult = AgentStepResult.Failure("install failed")
        collaborators.rollbackToSnapshotResult = AgentStepResult.Success("rolled back")
        val plan = makePlan(
            actions = listOf(AgentAction.InstallDistro(distroId = "debian-12")),
            risk = RiskLevel.MEDIUM,
            targetWorkspaceId = "ws-1"
        )
        executor.execute(plan)
        val failed = eventBus.events
            .filterIsInstance<RuntimeEvent.AgentActionFailedEvent>()
        val rolledBack = eventBus.events
            .filterIsInstance<RuntimeEvent.AgentActionRolledBackEvent>()
        assertEquals(1, failed.size)
        assertEquals(1, rolledBack.size)
        assertTrue("rolledBack event should report rolledBack=true", rolledBack[0].rolledBack)
    }

    @Test
    fun `executor returns Success when every action succeeds`() {
        collaborators.installDistroResult = AgentStepResult.Success("installed")
        collaborators.snapshotResult = AgentStepResult.Success("snap-1")
        val plan = makePlan(
            actions = listOf(
                AgentAction.InstallDistro(distroId = "debian-12"),
                AgentAction.CreateSnapshot(workspaceId = "ws-1", label = "post-install")
            ),
            risk = RiskLevel.MEDIUM,
            targetWorkspaceId = "ws-1"
        )
        val outcome = executor.execute(plan)
        assertTrue(outcome is ExecutionOutcome.Success)
        val success = outcome as ExecutionOutcome.Success
        assertEquals(2, success.stepResults.size)
        assertTrue(success.stepResults.all { it is AgentStepResult.Success })
    }

    @Test
    fun `executor does not take a snapshot before a read-only action`() {
        collaborators.runBuildResult = AgentStepResult.Success("built")
        val plan = makePlan(
            actions = listOf(
                AgentAction.RunBuild(toolchainKind = "RUST", command = listOf("build"))
            ),
            risk = RiskLevel.LOW
        )
        executor.execute(plan)
        // No snapshot was taken — the
        // executor only snapshots before
        // destructive actions.
        assertTrue(collaborators.calls.none { it is FakeAgentCollaborators.Call.Snapshot })
    }

    // --- helpers ---

    private fun makePlan(
        actions: List<AgentAction>,
        risk: RiskLevel,
        autoConfirm: Boolean = false,
        targetWorkspaceId: String? = null
    ): AgentPlan {
        val goal = NaturalLanguageGoal(
            text = "test goal",
            autoConfirm = autoConfirm
        )
        return AgentPlan(
            id = "plan-test-1",
            actions = actions,
            riskLevel = risk,
            createdAtMs = 1_700_000_000_000L,
            goal = goal,
            targetWorkspaceId = targetWorkspaceId
        )
    }
}

/**
 * Hand-rolled [AgentCollaborators] for unit
 * tests. Records every call in a
 * thread-safe list; the executor's
 * orchestration is asserted without
 * actually invoking the runtime.
 */
internal class FakeAgentCollaborators : AgentCollaborators {

    sealed class Call {
        data class Install(val distroId: String) : Call()
        data class CreateWindows(val binaryPath: String, val runtimeKind: String) : Call()
        data class Snapshot(val workspaceId: String, val label: String) : Call()
        data class Rollback(val workspaceId: String, val snapshotId: String) : Call()
        data class Build(val toolchainKind: String, val command: List<String>) : Call()
        data class Run(val command: List<String>, val workingDirectory: String?) : Call()
        data class CreateShortcut(
            val targetAppId: String,
            val displayName: String,
            val launchIntent: String?,
            val iconUri: String?,
        ) : Call()
        data class ConfigureRuntime(
            val runtime: String,
            val operation: String,
            val targetAppId: String?,
        ) : Call()
        data class PublishCapsule(
            val capsuleId: String,
            val targetChannel: String,
        ) : Call()
    }

    val calls = Collections.synchronizedList(mutableListOf<Call>())
    var installDistroResult: AgentStepResult = AgentStepResult.Success()
    var createWindowsEnvironmentResult: AgentStepResult = AgentStepResult.Success()
    var snapshotResult: AgentStepResult = AgentStepResult.Success()
    var rollbackToSnapshotResult: AgentStepResult = AgentStepResult.Success()
    var runBuildResult: AgentStepResult = AgentStepResult.Success()
    var runCommandResult: AgentStepResult = AgentStepResult.Success()
    var createShortcutResult: AgentStepResult = AgentStepResult.Success()
    var configureRuntimeResult: AgentStepResult = AgentStepResult.Success()
    var publishCapsuleResult: AgentStepResult = AgentStepResult.Success()

    override fun installDistro(distroId: String): AgentStepResult {
        calls += Call.Install(distroId)
        return installDistroResult
    }

    override fun createWindowsEnvironment(
        binaryPath: String,
        runtimeKind: String
    ): AgentStepResult {
        calls += Call.CreateWindows(binaryPath, runtimeKind)
        return createWindowsEnvironmentResult
    }

    override fun createSnapshot(
        workspaceId: String,
        label: String
    ): AgentStepResult {
        calls += Call.Snapshot(workspaceId, label)
        return snapshotResult
    }

    override fun rollbackToSnapshot(
        workspaceId: String,
        snapshotId: String
    ): AgentStepResult {
        calls += Call.Rollback(workspaceId, snapshotId)
        return rollbackToSnapshotResult
    }

    override fun runBuild(
        toolchainKind: String,
        command: List<String>
    ): AgentStepResult {
        calls += Call.Build(toolchainKind, command)
        return runBuildResult
    }

    override fun runCommand(
        command: List<String>,
        workingDirectory: String?
    ): AgentStepResult {
        calls += Call.Run(command, workingDirectory)
        return runCommandResult
    }

    override fun createShortcut(
        targetAppId: String,
        displayName: String,
        launchIntent: String?,
        iconUri: String?,
    ): AgentStepResult {
        calls += Call.CreateShortcut(targetAppId, displayName, launchIntent, iconUri)
        return createShortcutResult
    }

    override fun configureRuntime(
        runtime: String,
        operation: String,
        targetAppId: String?,
    ): AgentStepResult {
        calls += Call.ConfigureRuntime(runtime, operation, targetAppId)
        return configureRuntimeResult
    }

    override fun publishCapsule(
        capsuleId: String,
        targetChannel: String,
    ): AgentStepResult {
        calls += Call.PublishCapsule(capsuleId, targetChannel)
        return publishCapsuleResult
    }
}
