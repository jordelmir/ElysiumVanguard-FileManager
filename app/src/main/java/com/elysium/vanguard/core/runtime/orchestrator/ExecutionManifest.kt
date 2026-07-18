package com.elysium.vanguard.core.runtime.orchestrator

/**
 * Phase 53 — the declarative execution
 * manifest.
 *
 * The manifest is the orchestrator's input
 * AND output:
 *
 * - The user can hand-write a manifest
 *   (the "I-know-exactly-what-I-want"
 *   path) and pass it to the runner.
 * - The orchestrator generates a manifest
 *   from a file + a workspace (the
 *   "I-just-want-it-to-work" path) and
 *   returns it as an [ExecutionPlan].
 *
 * A manifest is a value type: no I/O, no
 * runtime, no side effects. The runner
 * consumes the manifest; the orchestrator
 * produces it.
 *
 * ## Required fields
 *
 * - [binaryPath]: the absolute path to the
 *   executable. The runner reads this.
 * - [runtime]: the [RuntimeKind] the
 *   orchestrator selected (or the user
 *   specified). The runner dispatches.
 *
 * ## Optional fields
 *
 * - [interpreter]: for scripts, the
 *   interpreter to invoke. For ELF/PE
 *   binaries, `null`.
 * - [workspaceId]: the workspace the run
 *   belongs to. The runner uses the
 *   workspace's mount policy (Phase 50)
 *   to constrain the run.
 * - [commandLineArgs]: the arguments the
 *   user wants to pass to the binary.
 * - [environmentVariables]: env vars to
 *   set on the child process. The runner
 *   passes them via the [com.elysium.vanguard.core.runtime.runner.ProcessLauncher].
 * - [workingDirectory]: the cwd for the
 *   child process. Defaults to the
 *   binary's directory if `null`.
 * - [selectionReason]: the orchestrator's
 *   human-readable reason for selecting
 *   the runtime. The user sees this in the
 *   UI.
 */
data class ExecutionManifest(
    val binaryPath: String,
    val runtime: RuntimeKind,
    val interpreter: String? = null,
    val workspaceId: String? = null,
    val commandLineArgs: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val selectionReason: String = ""
) {
    init {
        require(binaryPath.isNotBlank()) { "binaryPath must not be blank" }
        require(runtime != RuntimeKind.REJECTED) {
            "runtime must not be REJECTED; the orchestrator should not produce a manifest for a rejected binary"
        }
    }
}

/**
 * Phase 53 — the orchestrator's output.
 *
 * An [ExecutionPlan] is the manifest + a
 * sanity check on the manifest's viability.
 * The plan is what the runner consumes.
 *
 * A plan is either:
 * - [ExecutionPlan.Ready]: the manifest is
 *   complete and runnable.
 * - [ExecutionPlan.Rejected]: the
 *   orchestrator refused to produce a
 *   manifest; the rejection carries the
 *   typed reason.
 */
sealed class ExecutionPlan {
    data class Ready(val manifest: ExecutionManifest) : ExecutionPlan()
    data class Rejected(
        val rejection: RejectionReason,
        val reason: String
    ) : ExecutionPlan()
}

/**
 * Phase 53 — the user-facing entry point.
 *
 * The orchestrator is the runtime's planner.
 * Given a file + a workspace, it returns an
 * [ExecutionPlan] (the manifest + a sanity
 * check) or an [ExecutionPlan.Rejected]
 * (the typed reason the run is impossible).
 *
 * The orchestrator does NOT start the
 * process. That is the [com.elysium.vanguard.core.runtime.runner.SessionRunner]'s
 * job. The orchestrator is the planner; the
 * runner is the executor.
 *
 * ## Three entry points
 *
 * - [planExecution] — the full flow. The
 *   orchestrator inspects the file, selects
 *   a runtime, and returns a plan.
 * - [planFromManifest] — the user supplies
 *   the manifest (skipping inspection +
 *   selection). The orchestrator validates
 *   the manifest (a sanity check, not a
 *   re-inspection) and returns a plan.
 * - [inspect] — the inspector's result,
 *   exposed for callers that want the
 *   metadata without the plan.
 */
class RuntimeOrchestrator(
    private val inspector: ExecutableInspector = ExecutableInspector(),
    private val selector: RuntimeSelector = RuntimeSelector()
) {

    /**
     * Plan an execution for the file at
     * [binaryPath] on [workspaceId]. The
     * orchestrator inspects the file, selects
     * a runtime, and returns an
     * [ExecutionPlan.Ready] or
     * [ExecutionPlan.Rejected].
     *
     * @param binaryPath the absolute path to
     *   the executable.
     * @param workspaceId the workspace the
     *   run belongs to. The runner uses the
     *   workspace's mount policy.
     * @param commandLineArgs the args to pass
     *   to the binary.
     * @param environmentVariables the env
     *   vars to set on the child process.
     */
    fun planExecution(
        binaryPath: String,
        capabilities: RuntimeCapabilities,
        workspaceId: String? = null,
        commandLineArgs: List<String> = emptyList(),
        environmentVariables: Map<String, String> = emptyMap()
    ): ExecutionPlan {
        val file = java.io.File(binaryPath)
        val metadata = inspector.inspect(file)
        if (!metadata.isRunnable) {
            return ExecutionPlan.Rejected(
                rejection = RejectionReason.UnknownFormat,
                reason = "binary format is UNKNOWN; cannot plan a run"
            )
        }
        val choice = selector.select(metadata, capabilities)
        return when (choice) {
            is RuntimeChoice.Selected -> ExecutionPlan.Ready(
                manifest = ExecutionManifest(
                    binaryPath = binaryPath,
                    runtime = choice.kind,
                    interpreter = metadata.interpreter,
                    workspaceId = workspaceId,
                    commandLineArgs = commandLineArgs,
                    environmentVariables = environmentVariables,
                    selectionReason = choice.reason
                )
            )
            is RuntimeChoice.Rejected -> ExecutionPlan.Rejected(
                rejection = choice.rejection,
                reason = choice.reason
            )
        }
    }

    /**
     * Plan an execution from a pre-built
     * [ExecutionManifest]. The orchestrator
     * validates the manifest and returns a
     * plan. A manifest with
     * `runtime = REJECTED` is rejected (the
     * `ExecutionManifest` init block already
     * enforces this, so this method is a
     * thin pass-through).
     */
    fun planFromManifest(manifest: ExecutionManifest): ExecutionPlan =
        ExecutionPlan.Ready(manifest)

    /**
     * Inspect the file at [binaryPath] and
     * return the inspector's output. The
     * orchestrator's selector is NOT
     * consulted; this is the raw
     * "what is this file?" entry point.
     */
    fun inspect(binaryPath: String): ExecutableMetadata =
        inspector.inspect(java.io.File(binaryPath))
}
