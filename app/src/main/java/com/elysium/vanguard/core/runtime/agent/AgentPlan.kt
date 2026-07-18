package com.elysium.vanguard.core.runtime.agent

/**
 * Phase 57 — the agent's natural-language
 * input.
 *
 * The [NaturalLanguageGoal] is what the user
 * types (or speaks, in a future phase) to
 * the agent. The agent translates the goal
 * to an [AgentPlan] (a sequence of
 * [AgentAction]s).
 *
 * The goal is a value type. It carries:
 * - [text]: the raw user input.
 * - [languageCode]: an optional BCP-47
 *   language tag (e.g. "es-MX", "en-US").
 *   The parser uses it to choose the rule
 *   table for the user's language. Phase 57
 *   ships English + Spanish; a future phase
 *   adds more.
 * - [autoConfirm]: when true, the executor
 *   skips the user-confirmation gate for
 *   HIGH-risk plans. Phase 57 honors this
 *   for the user's automation workflows.
 */
data class NaturalLanguageGoal(
    val text: String,
    val languageCode: String = "en-US",
    val autoConfirm: Boolean = false
) {
    init {
        require(text.isNotBlank()) { "text must not be blank" }
        require(languageCode.isNotBlank()) { "languageCode must not be blank" }
    }
}

/**
 * Phase 57 — the agent's plan.
 *
 * The plan is the agent's output: a
 * deterministic sequence of [AgentAction]s
 * the executor runs. The plan is a value
 * type. The executor consumes it; the
 * planner produces it.
 *
 * - [id]: a unique id (UUID-like; Phase 57
 *   uses a counter).
 * - [actions]: the sequence of actions to
 *   execute, in order.
 * - [riskLevel]: the executor's gate. The
 *   executor refuses to execute a
 *   HIGH-risk plan without user
 *   confirmation.
 * - [createdAtMs]: the wall-clock timestamp
 *   the plan was created. Useful for
 *   forensic reconstruction.
 * - [goal]: the goal the plan was created
 *   from. The audit log carries the
 *   plan + the goal; a user with a "what
 *   did the agent do for this goal?"
 *   question can look up the plan.
 */
data class AgentPlan(
    val id: String,
    val actions: List<AgentAction>,
    val riskLevel: RiskLevel,
    val createdAtMs: Long,
    val goal: NaturalLanguageGoal,
    /**
     * Phase 57 — the workspace the plan
     * operates on, if any. When non-null,
     * the [PlanExecutor] takes a snapshot
     * of this workspace before the first
     * destructive action (the master
     * vision's "create snapshots before
     * modifying an environment" rule) and
     * rolls back on failure.
     *
     * When null, the executor does NOT
     * take snapshots (the plan is not
     * workspace-scoped; e.g. "install
     * debian" affects all workspaces or no
     * workspace at all). A future phase
     * adds a "global snapshot" concept.
     */
    val targetWorkspaceId: String? = null
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(actions.isNotEmpty()) { "actions must not be empty" }
    }

    /**
     * The textual rendering of the plan.
     * The UI shows this in the
     * "review the plan" dialog.
     */
    fun describe(): String = buildString {
        append("Plan ").append(id).append(" (").append(riskLevel.name).append("):")
        actions.forEachIndexed { i, action ->
            append("\n  ").append(i + 1).append(". ").append(action.describe())
        }
    }
}

/**
 * Phase 57 — the risk level of an
 * [AgentPlan]. The executor's gate.
 *
 * - [LOW]: read-only or trivially
 *   recoverable (e.g. "build rust" writes
 *   to `target/`; `cargo clean` recovers).
 * - [MEDIUM]: writes to user-visible state
 *   but recoverable via snapshot (e.g.
 *   "install debian" writes to
 *   `<filesDir>/distros/`; snapshot +
 *   rollback recovers).
 * - [HIGH]: destructive or irreversible
 *   (e.g. "rollback" is destructive; "run
 *   command" runs an arbitrary command).
 *   The executor requires user
 *   confirmation unless the goal's
 *   [NaturalLanguageGoal.autoConfirm] is
 *   `true`.
 */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * Phase 57 — the agent's primitive
 * operations. Each [AgentAction] is a
 * runtime-level operation the executor
 * can dispatch to the right collaborator
 * (the orchestrator, the workspace
 * manager, the snapshot engine, the
 * build runner, the process launcher).
 *
 * The action is a value type. The
 * executor is the runner; the planner
 * produces a list of actions.
 */
sealed class AgentAction {
    /**
     * A textual rendering of the action.
     * Used by [AgentPlan.describe] and the
     * UI's "review the plan" dialog.
     */
    abstract fun describe(): String

    /**
     * Install a signed distro (delegates to
     * [com.elysium.vanguard.core.runtime.distros.manifest.installWithSignedManifest]).
     * The manifest + public key are the
     * caller's responsibility; the agent
     * does NOT auto-download distros.
     */
    data class InstallDistro(val distroId: String) : AgentAction() {
        init {
            require(distroId.isNotBlank()) { "distroId must not be blank" }
        }
        override fun describe(): String = "install distro '$distroId'"
    }

    /**
     * Take a Windows binary + a runtime
     * kind and produce an
     * [com.elysium.vanguard.core.runtime.orchestrator.ExecutionPlan].
     * The orchestrator's rule table picks
     * the best runtime; the executor
     * delegates to the runner for that
     * runtime.
     */
    data class CreateWindowsEnvironment(
        val binaryPath: String,
        val runtimeKind: String
    ) : AgentAction() {
        init {
            require(binaryPath.isNotBlank()) { "binaryPath must not be blank" }
            require(runtimeKind.isNotBlank()) { "runtimeKind must not be blank" }
        }
        override fun describe(): String =
            "create Windows environment for '$binaryPath' via $runtimeKind"
    }

    /**
     * Capture a snapshot of the workspace's
     * live rootfs (delegates to the
     * [com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager.snapshotWorkspace]
     * method, Phase 49).
     */
    data class CreateSnapshot(
        val workspaceId: String,
        val label: String
    ) : AgentAction() {
        init {
            require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
            require(label.isNotBlank()) { "label must not be blank" }
        }
        override fun describe(): String =
            "create snapshot of workspace '$workspaceId' labelled '$label'"
    }

    /**
     * Restore the workspace to a previous
     * snapshot (delegates to the
     * [com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager.rollbackWorkspace]
     * method, Phase 49).
     */
    data class RollbackToSnapshot(
        val workspaceId: String,
        val snapshotId: String
    ) : AgentAction() {
        init {
            require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
            require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }
        }
        override fun describe(): String =
            "rollback workspace '$workspaceId' to snapshot '$snapshotId'"
    }

    /**
     * Run a local build (delegates to the
     * [com.elysium.vanguard.core.runtime.build.LocalBuildRunner],
     * Phase 56).
     */
    data class RunBuild(
        val toolchainKind: String,
        val command: List<String>
    ) : AgentAction() {
        init {
            require(toolchainKind.isNotBlank()) { "toolchainKind must not be blank" }
            require(command.isNotEmpty()) { "command must not be empty" }
        }
        override fun describe(): String =
            "build with $toolchainKind: ${command.joinToString(" ")}"
    }

    /**
     * Run a generic command (delegates to
     * the
     * [com.elysium.vanguard.core.runtime.runner.ProcessLauncher],
     * Phase 36). The action is HIGH-risk;
     * the executor requires user
     * confirmation.
     */
    data class RunCommand(
        val command: List<String>,
        val workingDirectory: String? = null
    ) : AgentAction() {
        init {
            require(command.isNotEmpty()) { "command must not be empty" }
        }
        override fun describe(): String =
            "run command: ${command.joinToString(" ")}"
    }
}
