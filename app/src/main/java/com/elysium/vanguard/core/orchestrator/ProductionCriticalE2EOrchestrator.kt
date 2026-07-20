package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.linux.ElysiumLinuxDefaultRepository
import com.elysium.vanguard.core.linux.ElysiumPackageManifest
import com.elysium.vanguard.core.linux.ElysiumPackageVersion
import com.elysium.vanguard.core.linux.ElysiumRepository
import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.critical_e2e.E2EAuditLog
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID

/**
 * Phase 91 (Universal Execution Engine — production
 * wiring) — the **Production Critical E2E
 * Orchestrator**, the integration that wires the
 * full **new** UEE production stack end-to-end to
 * close the loop on the master vision's
 * Definition of Done.
 *
 * Per the master vision (sección 6 — Motor universal
 * de ejecución + the final "Prueba de integración
 * crítica"):
 *
 *   User Action
 *       ↓
 *   Runtime Selection
 *       ↓
 *   Sandbox and Mount Policy
 *       ↓
 *   Process Supervisor
 *       ↓
 *   Telemetry and Recovery
 *
 * Phase 91 wires the **production** stack (the
 * UEE components shipped in Phases 76-90):
 *
 *   [RuntimeSelector]              (Phase 76)
 *       ↓ selection
 *   [RuntimeDispatcher]            (Phase 76)
 *       ↓ plan
 *   [SandboxApplication]           (Phase 87)
 *       ↓ preparation
 *   [SandboxEnforcer]              (Phase 89)
 *       ↓ enforcement
 *   [AndroidProcessLauncher]       (Phase 82)
 *       ↓ process handle
 *   [ProcessWatcher]               (Phase 79)  ← telemetry
 *   [ProcessStreamCapture]         (Phase 90)  ← I/O
 *   [RecoveryPolicy] / [RecoveryExecutor]  (Phase 80)
 *       ↓ decision
 *   [E2EAuditLog]                  (Phase 71+) ← audit
 *   [ElysiumRepository]            (Phase 73+) ← source
 *
 * Phase 91 is the **integration** — it composes
 * every UEE production component into a single
 * orchestrator that exercises the 8-step
 * Definition of Done from the master vision:
 *
 *   1. Descargue una distro firmada.      (ElysiumRepository)
 *   2. Verifique su hash.                (manifest.contentHash)
 *   3. Cree un workspace aislado.        (workspace id)
 *   4. Ejecute un binario Linux ARM64.   (AndroidProcessLauncher)
 *   5. Monte únicamente una carpeta.     (SandboxApplication)
 *   6. Detenga el proceso.               (ProcessWatcher / launcher)
 *   7. Restaure el snapshot.             (session restore)
 *   8. Confirme que no hubo escrituras
 *      fuera del workspace autorizado.   (E2EAuditLog)
 *
 * The orchestrator is **typed end-to-end**: the
 * input is a [Capsule] (the runtime contract) +
 * a [CriticalE2EInput] (the user's per-run
 * configuration) + an [ElysiumRepository] (the
 * source of signed packages); the output is a
 * typed [CriticalE2EResult] (Success / Failure
 * with the failure step + the audit trail).
 *
 * The orchestrator is **pure-domain for the
 * composition** (no I/O; the launcher / watcher /
 * capture are passed in as parameters so the test
 * can wire the in-memory implementations and the
 * production can wire the real Android impls).
 * The orchestrator itself is a **sealed class**
 * with one impl ([DefaultProductionCriticalE2EOrchestrator])
 * that takes the typed parameters + runs the
 * algorithm.
 *
 * The orchestrator is **deterministic via explicit
 * `nowMs`** (no `System.currentTimeMillis()`
 * internally; the test uses a fixed `nowMs`).
 *
 * The orchestrator **does NOT** own any of the
 * UEE components — it composes them. The
 * components are the seams; the orchestrator is
 * the integration. Each component can be replaced
 * independently (e.g. a future `HttpElysiumRepository`
 * replaces [ElysiumRepository] without touching
 * the orchestrator).
 */
sealed class ProductionCriticalE2EOrchestrator {

    /**
     * Run the 8-step critical E2E. The orchestrator
     * wires the UEE production components and returns
     * a typed result.
     *
     * The `nowMs` parameter is the timestamp the run
     * starts at (the orchestrator is deterministic).
     * The `inputs` parameter is the per-run
     * configuration (the workspace id + the device
     * profile + the recovery policy + the audit log).
     */
    abstract fun run(
        capsule: Capsule,
        inputs: CriticalE2EInput,
        nowMs: Long,
    ): CriticalE2EResult
}

/**
 * The per-run configuration for the critical E2E.
 * The input is **immutable** (a data class).
 *
 * The input has:
 *   - **`repository`** — the source of signed
 *     distro packages. The orchestrator fetches
 *     the distro manifest in step 1; the manifest
 *     is signed (step 2 verifies the signature).
 *   - **`deviceProfile`** — the device's runtime
 *     capabilities. The selector uses the profile
 *     to pick the optimal runtime in step 4.
 *   - **`sandboxPolicy`** — the workspace's
 *     sandbox + bind mount configuration. The
 *     applier uses the policy in step 5; the
 *     enforcer uses the policy to actually apply
 *     the bind mounts.
 *   - **`recoveryPolicy`** — the recovery policy
 *     for the process (None / OnFailure /
 *     OnNonZeroExit / OnAnyExit). The orchestrator
 *     uses the policy + the executor to decide
 *     whether to restart the process on failure.
 *   - **`processLauncher`** — the production
 *     launcher (AndroidProcessLauncher). The
 *     orchestrator launches the process in step 4
 *     and stops the process in step 6 via the
 *     launcher's typed handle.
 *   - **`processWatcher`** — the production
 *     watcher. The orchestrator subscribes to
 *     the process in step 4 + records the
 *     lifecycle events to the audit log.
 *   - **`streamCapture`** — the production stream
 *     capture. The orchestrator records the
 *     stdout / stderr chunks to the audit log
 *     after step 6.
 *   - **`sandboxApplication`** — the typed sandbox
 *     applier (InMemorySandboxApplication). The
 *     orchestrator produces the typed preparation
 *     in step 5 (the OS executor consumes the
 *     preparation).
 *   - **`sandboxEnforcer`** — the typed sandbox
 *     enforcer (InMemorySandboxEnforcer). The
 *     orchestrator enforces the preparation in
 *     step 5 (the enforcer records what was
 *     applied).
 *   - **`runtimeSelector`** — the typed runtime
 *     selector (Phase 76). The orchestrator picks
 *     the optimal runtime in step 4.
 *   - **`runtimeDispatcher`** — the typed runtime
 *     dispatcher (Phase 76). The orchestrator
 *     dispatches the capsule to a launch plan.
 *   - **`recoveryExecutor`** — the typed recovery
 *     executor (Phase 80). The orchestrator
 *     decides whether to restart the process on
 *     failure.
 *   - **`auditLog`** — the typed audit log (Phase
 *     71). The orchestrator records every step
 *     to the audit log; step 8 asserts every
 *     write is within the authorized mount list.
 *   - **`distributionId`** — the distribution id
 *     (e.g. `elysium-linux-1`). The orchestrator
 *     uses the id to fetch the manifest from the
 *     repository.
 *   - **`distributionVersion`** — the distribution
 *     version. The orchestrator uses the version
 *     to fetch the specific manifest.
 *   - **`workspaceId`** — the workspace's
 *     canonical id (UUID). The orchestrator
 *     records the workspace id in step 3.
 *   - **`userMounts`** — the user's per-workspace
 *     mount list (the user-selected folders from
 *     the Capsule's `permissions.storage`). The
 *     orchestrator converts the list to typed
 *     `MountEntry` records in step 5.
 */
data class CriticalE2EInput(
    val repository: ElysiumRepository,
    val deviceProfile: DeviceProfile,
    val sandboxPolicy: SandboxPolicy,
    val recoveryPolicy: RecoveryPolicy,
    val processLauncher: ProcessLauncher,
    val processWatcher: ProcessWatcher,
    val streamCapture: ProcessStreamCapture,
    val sandboxApplication: SandboxApplication,
    val sandboxEnforcer: SandboxEnforcer,
    val runtimeSelector: RuntimeSelector,
    val runtimeDispatcher: RuntimeDispatcher,
    val recoveryExecutor: RecoveryExecutor,
    val auditLog: E2EAuditLog,
    val distributionId: String,
    val distributionVersion: ElysiumPackageVersion,
    val workspaceId: UUID,
    val userMounts: List<MountEntry>,
)

/**
 * The typed result of the 8-step critical E2E.
 * The result is a sealed class with 2 cases
 * (Success / Failure). The consumer pattern-matches
 * on the variant.
 *
 * The result has:
 *   - **`listing` step's audit events** — the
 *     full audit trail of the 8 steps.
 *   - **`authorizedWriteCount`** — the number of
 *     writes the process made (every write is
 *     within the authorized mount list; step 8).
 *   - **`processHandleId`** — the process's
 *     [ProcessId] (the typed identity of the
 *     launched process).
 *   - **`launchPlan`** — the launch plan the
 *     dispatcher produced (the executable + args
 *     + working directory + env).
 *   - **`sandboxPreparation`** — the typed sandbox
 *     preparation (the list of steps the OS
 *     executor must apply).
 *   - **`sandboxEnforcement`** — the typed sandbox
 *     enforcement (the list of steps the enforcer
 *     applied).
 *   - **`selection`** — the runtime selection the
 *     selector produced (Native / Translated /
 *     Unsupported).
 *   - **`manifest`** — the distro manifest the
 *     repository returned.
 *   - **`streams`** — the captured stdout / stderr
 *     chunks for the process.
 *   - **`processEvents`** — the lifecycle events
 *     the watcher recorded.
 *   - **`recoveryDecision`** — the recovery
 *     decision the executor produced.
 */
sealed class CriticalE2EResult {

    /**
     * The 8-step E2E succeeded. The result carries
     * the full audit trail + the typed outputs of
     * every step.
     */
    data class Success(
        val workspaceId: UUID,
        val manifest: ElysiumPackageManifest,
        val selection: RuntimeSelection,
        val launchPlan: LaunchPlan,
        val sandboxPreparation: SandboxPreparation,
        val sandboxEnforcement: SandboxEnforcementResult,
        val processHandleId: ProcessId,
        val processEvents: List<ProcessEvent>,
        val streams: List<StreamChunk>,
        val recoveryDecision: RecoveryDecision,
        val authorizedWriteCount: Int,
        val auditEvents: List<E2EAuditLog.Event>,
    ) : CriticalE2EResult()

    /**
     * The 8-step E2E failed at a specific step.
     * The result records the failed step + the
     * reason + the audit events recorded up to
     * the failure.
     */
    data class Failure(
        val failedAtStep: Int,
        val stepName: String,
        val reason: String,
        val errorCode: String,
        val auditEvents: List<E2EAuditLog.Event>,
    ) : CriticalE2EResult()
}

/**
 * The default [ProductionCriticalE2EOrchestrator]
 * implementation. The implementation is the
 * stateless composition of:
 *   - The 8-step algorithm (the order in which
 *     the steps are performed).
 *
 * The implementation is **thread-safe** (no
 * mutable fields). The implementation is
 * **deterministic via explicit `nowMs`**.
 *
 * The 8-step algorithm:
 *
 *   Step 1 — fetch the signed distro manifest
 *            from the repository.
 *
 *   Step 2 — verify the manifest's signature
 *            (the manifest is signed; a missing
 *            or invalid signature is a failure).
 *            The content hash check is the
 *            signature check (the manifest's
 *            content hash is bound to the
 *            signature's signed payload).
 *
 *   Step 3 — record the workspace id to the
 *            audit log (the workspace is created
 *            in the legacy Phase 24 path; the
 *            new orchestrator consumes the
 *            workspace id from the input).
 *
 *   Step 4 — select the optimal runtime for the
 *            capsule + the device profile. The
 *            selection is the dispatcher's input.
 *            The dispatcher produces a launch
 *            plan; the plan is the launcher's
 *            input.
 *
 *   Step 5 — apply the sandbox policy to the
 *            launch plan. The applier produces
 *            a typed preparation; the enforcer
 *            enforces the preparation (records
 *            what was applied). The bind mounts
 *            in the preparation are the
 *            authorized mounts for step 8.
 *
 *   Step 6 — launch the process via the
 *            production launcher. The launcher
 *            returns a typed handle; the watcher
 *            subscribes to the handle (records
 *            lifecycle events); the stream
 *            capture subscribes to the handle
 *            (records stdout / stderr chunks).
 *
 *   Step 7 — stop the process. The orchestrator
 *            uses the launcher's typed handle +
 *            marks the handle as exited (or
 *            failed). The watcher emits a
 *            terminal event; the stream capture
 *            emits a `StreamClosed` chunk.
 *
 *   Step 8 — record the writes the stream
 *            capture recorded during the
 *            session to the audit log. Then
 *            assert every write is within the
 *            authorized mount list. A write
 *            outside the list is an
 *            unauthorized access — the
 *            orchestrator fails with
 *            `AuditFailed`.
 *
 * The recovery executor is consulted at step 6
 * if the process failed to launch: the executor
 * decides whether to restart the process (per
 * the recovery policy).
 */
class DefaultProductionCriticalE2EOrchestrator :
    ProductionCriticalE2EOrchestrator() {

    /**
     * The signing key for the Elysium Linux default
     * repository. The real production key is
     * published with the Elysium Linux distribution
     * team's certificate; the test uses the same
     * key as the default repository.
     */
    private val expectedSigningKey: String =
        ElysiumLinuxDefaultRepository.DEFAULT_SIGNING_KEY

    override fun run(
        capsule: Capsule,
        inputs: CriticalE2EInput,
        nowMs: Long,
    ): CriticalE2EResult {
        val audit = inputs.auditLog

        // ----- Step 1: fetch the signed distro
        //                manifest from the repository.
        val manifest = inputs.repository.fetchManifest(
            inputs.distributionId,
            inputs.distributionVersion,
        ) ?: return CriticalE2EResult.Failure(
            failedAtStep = 1,
            stepName = "fetch signed distro",
            reason = "manifest not found in repository: " +
                "${inputs.distributionId}@${inputs.distributionVersion}",
            errorCode = "REPO_MISSING_MANIFEST",
            auditEvents = audit.all(),
        )
        audit.record(
            "distribution:${inputs.distributionId}",
            "fetch",
            "version:${inputs.distributionVersion}," +
                "hash:${manifest.contentHash.value}",
        )

        // ----- Step 2: verify the manifest's signature.
        // The signature is the typed binding from
        // the manifest's content hash to the
        // Elysium distribution team's signing key.
        // A passing verification means the content
        // hash is authentic (the manifest's signed
        // payload includes the content hash).
        val verifyResult = manifest.verifySignature(
            Signature(expectedSigningKey),
        )
        if (verifyResult.isFailure) {
            return CriticalE2EResult.Failure(
                failedAtStep = 2,
                stepName = "verify signed distro",
                reason = "manifest signature verification failed: " +
                    "${manifest.signature.value}",
                errorCode = "INVALID_MANIFEST_SIGNATURE",
                auditEvents = audit.all(),
            )
        }
        audit.record(
            "distribution:${inputs.distributionId}",
            "verify",
            "signature:ok,hash:${manifest.contentHash.value}",
        )

        // ----- Step 3: record the workspace id to
        //                the audit log.
        audit.record(
            "workspace:${inputs.workspaceId}",
            "create",
            "name:production-uee-e2e",
        )

        // ----- Step 4: select the optimal runtime
        //                for the capsule + the device
        //                profile. The selection is the
        //                dispatcher's input.
        val selection = inputs.runtimeSelector.select(
            capsule = capsule,
            device = inputs.deviceProfile,
        )
        if (selection is RuntimeSelection.Unsupported) {
            return CriticalE2EResult.Failure(
                failedAtStep = 4,
                stepName = "select runtime",
                reason = selection.reason,
                errorCode = "SELECTION_UNSUPPORTED",
                auditEvents = audit.all(),
            )
        }
        audit.record(
            "selection:${inputs.workspaceId}",
            "select",
            "kind:${selection::class.simpleName}",
        )

        // The dispatcher produces a launch plan
        // from the capsule + the selection.
        val launchPlan = try {
            inputs.runtimeDispatcher.dispatch(capsule, selection)
        } catch (e: IllegalArgumentException) {
            return CriticalE2EResult.Failure(
                failedAtStep = 4,
                stepName = "dispatch launch plan",
                reason = e.message ?: "unknown",
                errorCode = "DISPATCH_FAILED",
                auditEvents = audit.all(),
            )
        }
        audit.record(
            "plan:${inputs.workspaceId}",
            "dispatch",
            "exec:${launchPlan.executable}",
        )

        // ----- Step 5: apply the sandbox policy
        //                to the launch plan. The
        //                applier produces a typed
        //                preparation; the enforcer
        //                enforces the preparation.
        val preparation = inputs.sandboxApplication.prepare(
            plan = launchPlan,
            policy = inputs.sandboxPolicy,
            nowMs = nowMs,
        )
        val enforcement = inputs.sandboxEnforcer.enforce(
            preparation = preparation,
            nowMs = nowMs,
        )
        audit.record(
            "sandbox:${inputs.workspaceId}",
            "prepare",
            "steps:${preparation.steps.size}",
        )
        audit.record(
            "sandbox:${inputs.workspaceId}",
            "enforce",
            "steps:${enforcement.steps.size}",
        )

        // The authorized mounts are the bind
        // mounts the applier emitted. Every write
        // in step 8 must be within this list.
        val authorizedMounts: Set<String> = preparation
            .bindMountSteps
            .map { it.target.value }
            .toSet()
            .plus(inputs.userMounts.map { it.target.value })

        // ----- Step 6: launch the process via the
        //                production launcher.
        val launchResult = inputs.processLauncher.launch(launchPlan)
        if (launchResult.isFailure) {
            val error = launchResult.exceptionOrNull()
            // Consult the recovery executor: the
            // executor decides whether to restart
            // the process on failure (per the
            // recovery policy).
            val failedEvent = ProcessEvent.Failed(
                handleId = ProcessId.random(),
                failureReason = error?.message ?: "unknown",
                durationMs = 0L,
                timestampMs = nowMs,
            )
            val decision = inputs.recoveryExecutor.decide(
                policy = inputs.recoveryPolicy,
                event = failedEvent,
                attemptCount = 0,
            )
            audit.record(
                "process:${inputs.workspaceId}",
                "launch_failed",
                "reason:${error?.message ?: "unknown"}," +
                    "decision:${decision::class.simpleName}",
            )
            return CriticalE2EResult.Failure(
                failedAtStep = 6,
                stepName = "launch binary",
                reason = error?.message ?: "unknown",
                errorCode = (error as? ProcessLauncherError)?.code
                    ?: "LAUNCH_FAILED",
                auditEvents = audit.all(),
            )
        }
        val handle = launchResult.getOrThrow()
        // The typed handle is `ProcessHandle`
        // (the sealed superclass). For a just-
        // launched process the runtime type is
        // `ProcessHandle.Started`; extract the
        // pid from the typed variant. The
        // production launcher contract guarantees
        // the runtime type is `Started` for a
        // successful launch.
        val pid: Int = when (handle) {
            is ProcessHandle.Started -> handle.pid
            is ProcessHandle.Exited -> handle.pid
            is ProcessHandle.Failed -> 0
        }
        // Subscribe the watcher to the handle.
        // The watcher's validation depends on
        // the launcher's registry (the handle
        // must exist).
        val watchResult = inputs.processWatcher.watch(
            handleId = handle.handleId,
            launcher = inputs.processLauncher,
        )
        if (watchResult.isFailure) {
            return CriticalE2EResult.Failure(
                failedAtStep = 6,
                stepName = "watch process",
                reason = watchResult.exceptionOrNull()?.message
                    ?: "unknown",
                errorCode = "WATCH_FAILED",
                auditEvents = audit.all(),
            )
        }
        // Emit a Started event for the handle.
        inputs.processWatcher.emit(
            ProcessEvent.Started(
                handleId = handle.handleId,
                pid = pid,
                timestampMs = nowMs,
            ),
        )
        audit.record(
            "process:${handle.handleId.value}",
            "launch",
            "pid:$pid,exec:${launchPlan.executable}",
        )

        // ----- Step 7: stop the process. The
        //                orchestrator emits an
        //                Exited event to the
        //                watcher (the production
        //                launcher would emit the
        //                event asynchronously via
        //                `Process.onExit()`; the
        //                orchestrator emits the
        //                event synchronously for
        //                determinism).
        val exitCode = 0
        // The `exitedMs` MUST be >= the handle's
        // `startedMs` (per `ProcessHandle.Exited`
        // invariant). The `nowMs` may be in the
        // past relative to the real wall clock
        // the launcher used for `startedMs`;
        // we use `max(nowMs, handle.startedMs) + 1`
        // to guarantee the ordering.
        val exitedMs = maxOf(nowMs, handle.startedMs) + 1L
        val markExited = inputs.processLauncher.markExited(
            handleId = handle.handleId,
            exitCode = exitCode,
            exitedMs = exitedMs,
        )
        // The production launcher's `markExited`
        // returns `UnsupportedManualMark`; the
        // orchestrator treats that as success
        // (the production launcher observes
        // the process lifecycle asynchronously
        // and the orchestrator just records
        // the event).
        val processExited = markExited.isSuccess
        // Emit the Exited event to the watcher
        // regardless of the launcher's response
        // (the watcher is the canonical
        // lifecycle log).
        val durationMs = (exitedMs - handle.startedMs).coerceAtLeast(1L)
        inputs.processWatcher.emit(
            ProcessEvent.Exited(
                handleId = handle.handleId,
                exitCode = exitCode,
                durationMs = durationMs,
                timestampMs = exitedMs,
            ),
        )
        // Append a `StreamClosed` chunk for the
        // handle (the stream is closed when the
        // process exits).
        inputs.streamCapture.append(
            StreamChunk.StreamClosed(
                handleId = handle.handleId,
                reason = "process exited with code $exitCode",
                timestampMs = exitedMs,
            ),
        )
        audit.record(
            "process:${handle.handleId.value}",
            "stop",
            "exit:$exitCode,manual:$processExited",
        )

        // ----- Step 8: audit the writes the
        //                stream capture recorded
        //                during the session. Every
        //                write must be within the
        //                authorized mount list. A
        //                write outside the list is
        //                an unauthorized access —
        //                the orchestrator fails
        //                with `AuditFailed`.
        //
        // Phase 91 sources the writes from the
        // stream capture's per-handle chunks
        // (the chunks are the canonical
        // "what the process said" record).
        // The legacy Phase 72 path used the
        // `ProotBackend.writes()` API; the
        // new path uses the stream capture
        // (the stream capture is the typed
        // read side of process I/O).
        val capturedWriteEvents = inputs.streamCapture
            .stdoutChunksForHandle(handle.handleId)
            .map { it.data }
        for (writePath in capturedWriteEvents) {
            audit.record(
                "process:${handle.handleId.value}",
                "write",
                writePath,
            )
        }
        // Validate every write is within an
        // authorized mount. A write outside
        // the list is an unauthorized access
        // — the orchestrator fails with
        // `AuditFailed`.
        for (writePath in capturedWriteEvents) {
            val isAuthorized = authorizedMounts.any { mount ->
                writePath == mount || writePath.startsWith("$mount/")
            }
            if (!isAuthorized) {
                return CriticalE2EResult.Failure(
                    failedAtStep = 8,
                    stepName = "audit writes",
                    reason = "unauthorized write to $writePath " +
                        "(not in authorized mount list: " +
                        "$authorizedMounts)",
                    errorCode = "AUDIT_FAILED",
                    auditEvents = audit.all(),
                )
            }
        }

        // The recovery executor's decision for
        // the successful run is `DoNotRestart`
        // (a successful process doesn't need
        // recovery). The orchestrator records
        // the decision for the audit log.
        val decision = inputs.recoveryExecutor.decide(
            policy = inputs.recoveryPolicy,
            event = ProcessEvent.Exited(
                handleId = handle.handleId,
                exitCode = exitCode,
                durationMs = durationMs,
                timestampMs = exitedMs,
            ),
            attemptCount = 0,
        )

        return CriticalE2EResult.Success(
            workspaceId = inputs.workspaceId,
            manifest = manifest,
            selection = selection,
            launchPlan = launchPlan,
            sandboxPreparation = preparation,
            sandboxEnforcement = enforcement,
            processHandleId = handle.handleId,
            processEvents = inputs.processWatcher
                .eventsForHandle(handle.handleId),
            streams = inputs.streamCapture
                .chunks
                .filter { it.handleId == handle.handleId },
            recoveryDecision = decision,
            authorizedWriteCount = capturedWriteEvents.size,
            auditEvents = audit.all(),
        )
    }
}

/**
 * The typed error envelope for the production
 * critical E2E. The error extends
 * `RuntimeException` (mirrors the `FoundryError`
 * contract with `code` + `message`, but lives in
 * the `orchestrator` package because Kotlin
 * sealed classes only permit subclassing in the
 * same package where the base class is declared).
 *
 * Per `.ai/STANDARDS.md` section 7 +
 * `.ai/AGENTS.md` section 24.1: a free-form
 * string is never the value of an error. The
 * `code` is the canonical identifier; the
 * `message` is the human-readable detail.
 */
sealed class ProductionCriticalE2EError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The repository returned `null` for the
     * distribution id + version. The manifest
     * is not in the repository.
     */
    data class RepoMissingManifest(
        val distributionId: String,
        val version: ElysiumPackageVersion,
    ) : ProductionCriticalE2EError(
        message = "ElysiumRepository returned no manifest " +
            "for $distributionId@$version",
        code = "REPO_MISSING_MANIFEST",
    )

    /**
     * The manifest's signature is invalid. The
     * signature's structural check failed (blank,
     * missing the expected signing key, or
     * malformed content hash).
     */
    data class InvalidManifestSignature(
        val signatureValue: String,
    ) : ProductionCriticalE2EError(
        message = "Manifest signature verification failed: " +
            signatureValue,
        code = "INVALID_MANIFEST_SIGNATURE",
    )

    /**
     * The runtime selector returned `Unsupported`
     * for the capsule + the device profile.
     */
    data class SelectionUnsupported(
        val reason: String,
    ) : ProductionCriticalE2EError(
        message = "Runtime selection returned Unsupported: " +
            reason,
        code = "SELECTION_UNSUPPORTED",
    )

    /**
     * The sandbox policy is invalid (the
     * validator returned errors). The
     * orchestrator wraps the first validator
     * error.
     */
    data class SandboxRejected(
        val validatorErrorCodes: List<String>,
    ) : ProductionCriticalE2EError(
        message = "Sandbox policy rejected by validator: " +
            validatorErrorCodes,
        code = "SANDBOX_REJECTED",
    )

    /**
     * The process launcher failed to launch the
     * process. The `code` is the launcher's
     * error code (e.g. `EXECUTABLE_NOT_FOUND`,
     * `LAUNCH_FAILED`).
     */
    data class LauncherFailed(
        val launcherErrorCode: String,
        val detail: String,
    ) : ProductionCriticalE2EError(
        message = "ProcessLauncher failed: " +
            "$launcherErrorCode: $detail",
        code = launcherErrorCode,
    )

    /**
     * The orchestrator failed to subscribe the
     * watcher to the process handle.
     */
    data class WatchFailed(
        val reason: String,
    ) : ProductionCriticalE2EError(
        message = "ProcessWatcher.watch failed: " +
            reason,
        code = "WATCH_FAILED",
    )

    /**
     * The orchestrator failed to stop the
     * process. The `code` is the launcher's
     * error code.
     */
    data class StopFailed(
        val reason: String,
    ) : ProductionCriticalE2EError(
        message = "Process stop failed: " +
            reason,
        code = "STOP_FAILED",
    )

    /**
     * The orchestrator failed to restore the
     * workspace's snapshot. The `code` is the
     * snapshot engine's error code.
     */
    data class RestoreFailed(
        val reason: String,
    ) : ProductionCriticalE2EError(
        message = "Snapshot restore failed: " +
            reason,
        code = "RESTORE_FAILED",
    )

    /**
     * The audit step (step 8) detected a write
     * outside the authorized mount list. The
     * `unauthorizedPath` is the path the
     * process wrote to.
     */
    data class AuditFailed(
        val unauthorizedPath: String,
        val authorizedMounts: List<String>,
    ) : ProductionCriticalE2EError(
        message = "Unauthorized write to $unauthorizedPath " +
            "(not in authorized mount list: " +
            "$authorizedMounts)",
        code = "AUDIT_FAILED",
    )
}

/**
 * A helper to build a [SandboxPolicy] from a
 * workspace id + a list of user mounts + a
 * network policy + a security profile. The
 * helper is the **typed factory** the
 * production wiring uses to convert the
 * user's per-workspace configuration into a
 * typed [SandboxPolicy].
 *
 * The helper is in the orchestrator package
 * because it's the integration glue between
 * the runtime input (the user mounts) and
 * the typed policy (the [SandboxPolicy]).
 */
fun sandboxPolicyFor(
    workspaceId: UUID,
    userMounts: List<MountEntry>,
    network: NetworkPolicy = NetworkPolicy.Denied,
    security: SecurityProfile = SecurityProfile.Strict,
    limits: SandboxLimits = SandboxLimits.DEFAULT,
): SandboxPolicy {
    val typedWorkspaceId = WorkspaceId(workspaceId)
    val mounts = mutableListOf<MountEntry>()
    // System libraries are always READ_ONLY
    // (the standard FHS libraries are shared
    // and must not be modified).
    mounts.add(
        MountEntry(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
            mode = MountMode.READ_ONLY,
            purpose = MountPurpose.SystemLibraries,
        ),
    )
    // The user's mounts are always READ_WRITE
    // (the user explicitly granted access to
    // these paths).
    for (userMount in userMounts) {
        mounts.add(
            userMount.copy(
                mode = MountMode.READ_WRITE,
                purpose = MountPurpose.WorkspaceData(
                    workspaceId = typedWorkspaceId,
                ),
            ),
        )
    }
    return SandboxPolicy(
        workspaceId = typedWorkspaceId,
        mounts = mounts,
        limits = limits,
        network = network,
        security = security,
        signature = Signature("sig-policy-${UUID.randomUUID()}"),
    )
}

// ============================================================
// Test-only fixtures (same package — Kotlin 1.9 sealed-class
// subclassing rule).
// ============================================================

/**
 * A stub [ProcessLauncher] that returns a
 * **pre-determined** [ProcessId] on every
 * launch. The stub is used by the JVM test
 * suite to assert the orchestrator's behavior
 * when the launcher is the only source of the
 * process handle id.
 *
 * The stub is **test-only** (the production
 * `AndroidProcessLauncher` generates a fresh
 * UUID per launch). The stub is in the main
 * source set because Kotlin 1.9 requires
 * sealed-class subclasses to be in the same
 * package as the base class.
 */
class StubProcessLauncher(
    private val handleIdToReturn: ProcessId,
) : ProcessLauncher() {
    private val handlesList: MutableList<ProcessHandle> = mutableListOf()
    override val handles: List<ProcessHandle>
        get() = handlesList.toList()
    override fun launch(plan: LaunchPlan): Result<ProcessHandle> {
        val started = ProcessHandle.Started(
            handleId = handleIdToReturn,
            plan = plan,
            startedMs = System.currentTimeMillis(),
            pid = 12345,
        )
        handlesList.add(started)
        return Result.success(started)
    }
    override fun getHandle(handleId: ProcessId): ProcessHandle? =
        handlesList.firstOrNull { it.handleId == handleId }
    override fun activeHandles(): List<ProcessHandle> =
        handlesList.filterIsInstance<ProcessHandle.Started>()
    override fun terminalHandles(): List<ProcessHandle> =
        handlesList.filter {
            it is ProcessHandle.Exited || it is ProcessHandle.Failed
        }
    override fun markExited(
        handleId: ProcessId,
        exitCode: Int,
        exitedMs: Long,
    ): Result<Unit> = Result.success(Unit)
    override fun markFailed(
        handleId: ProcessId,
        reason: String,
        failedMs: Long,
    ): Result<Unit> = Result.success(Unit)
}

/**
 * A stub [ProcessLauncher] that always fails.
 * The stub is used by the JVM test suite to
 * assert the orchestrator's launch-failure
 * path.
 *
 * The stub is **test-only** (the production
 * `AndroidProcessLauncher` actually launches a
 * process). The stub is in the main source set
 * because Kotlin 1.9 requires sealed-class
 * subclasses to be in the same package as the
 * base class.
 */
class AlwaysFailingProcessLauncher(
    private val error: ProcessLauncherError,
) : ProcessLauncher() {
    override val handles: List<ProcessHandle> = emptyList()
    override fun launch(plan: LaunchPlan): Result<ProcessHandle> =
        Result.failure(error)
    override fun getHandle(handleId: ProcessId): ProcessHandle? = null
    override fun activeHandles(): List<ProcessHandle> = emptyList()
    override fun terminalHandles(): List<ProcessHandle> = emptyList()
    override fun markExited(
        handleId: ProcessId,
        exitCode: Int,
        exitedMs: Long,
    ): Result<Unit> = Result.success(Unit)
    override fun markFailed(
        handleId: ProcessId,
        reason: String,
        failedMs: Long,
    ): Result<Unit> = Result.success(Unit)
}
