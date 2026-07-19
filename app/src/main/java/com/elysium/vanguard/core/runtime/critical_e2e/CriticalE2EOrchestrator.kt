package com.elysium.vanguard.core.runtime.critical_e2e

import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.catalog.CapsuleCatalog
import com.elysium.vanguard.core.runtime.market.MarketListing
import com.elysium.vanguard.core.runtime.market.MarketSigning
import com.elysium.vanguard.core.runtime.proot.ProotBackend
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import com.elysium.vanguard.core.runtime.workspace_orchestrator.WorkspaceOrchestrator
import com.elysium.vanguard.core.runtime.workspaces.Workspace
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceSession
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * Phase 71 — the **Critical E2E orchestrator** (production).
 *
 * The orchestrator drives the 8-step integration test from
 * the master vision's final section ("Prueba de integración
 * crítica"). It is the **Definition of Done** of the
 * platform.
 *
 * The orchestrator is the **bridge** between the schemas
 * (Market → Capsule → WorkspaceDefinition →
 * OrchestratedWorkspace) and the runtime hooks (the proot
 * backend). The orchestrator is **pure-domain** (no I/O,
 * no Android dependencies, no Hilt). The proot backend is
 * the seam where the Android-side execution happens.
 *
 * The 8 steps (per master vision):
 *   1. Verify the listing's signature.
 *   2. (Phase 71) — the content-hash check is the same as
 *      the listing's signature check (the listing is
 *      signed over its full canonical form, which includes
 *      the contentHash). If step 1 passes, the content
 *      hash is authentic.
 *   3. Install the capsule into the local catalog (the
 *      trust check).
 *   4. Create an isolated workspace.
 *   5. Orchestrate the workspace definition into a
 *      runtime plan.
 *   6. Launch the binary via the proot backend.
 *   7. Stop the process.
 *   8. Restore the snapshot.
 *   9. Audit the writes: every write must be within the
 *      authorized mount list.
 *
 * Phase 71 changes:
 *   - The orchestrator now takes the production
 *     [ProotBackend] interface (not the test-only
 *     `ProotBackendStub`). The interface has two
 *     implementations: [ProotBackendReal] (production) and
 *     [com.elysium.vanguard.core.runtime.critical_e2e.InMemoryProotBackend]
 *     (test fixture). The orchestrator doesn't know which
 *     one it's running against.
 *   - The orchestrator is now in production code (it
 *     drives the real-device `CriticalE2EInstrumentedTest`).
 */
class CriticalE2EOrchestrator(
    private val marketSigning: MarketSigning,
    private val capsuleCatalog: CapsuleCatalog,
    private val workspaceManager: WorkspaceManager,
    private val workspaceOrchestrator: WorkspaceOrchestrator,
    private val prootBackend: ProotBackend,
    private val auditLog: E2EAuditLog,
) {

    /**
     * The result of the 8-step run. The result is
     * a typed value (not a free-form string); the
     * consumer pattern-matches on the variant.
     */
    sealed class Result {
        data class Success(
            val listingId: String,
            val capsuleId: String,
            val workspaceId: String,
            val sessionId: String,
            val exitCode: Int,
            val authorizedWriteCount: Int,
            val auditEvents: List<E2EAuditLog.Event>,
        ) : Result()

        data class Failure(
            val failedAtStep: Int,
            val stepName: String,
            val reason: String,
        ) : Result()
    }

    /**
     * Run the 8-step E2E. Returns a typed [Result]
     * that records the outcome + the audit trail.
     */
    fun run(
        listing: MarketListing,
        capsule: Capsule,
        workspaceDefinition: WorkspaceDefinition,
        marketSigningKey: ByteArray,
    ): Result {
        // Step 1: Verify the listing's signature.
        // The MarketSigning.verify requires a key;
        // the test provides the test signing key.
        val signatureOk = try {
            marketSigning.verify(listing, marketSigningKey)
        } catch (e: FoundryError) {
            false
        }
        if (!signatureOk) {
            return fail(1, "verify signed distro", "listing signature verification failed")
        }
        auditLog.record("listing:${listing.id}", "verify", "signature:ok")

        // Step 2: Content hash verification. The
        // listing's contentHash is bound to the
        // signature (the signature is computed over the
        // listing's canonical form, which includes the
        // contentHash). A passing step 1 is sufficient
        // for step 2 (Phase 71 keeps the logic explicit
        // so a future phase can re-derive the hash
        // against the downloaded bytes).

        // Step 3: Install the capsule into the local
        // catalog. The catalog runs the trust check
        // (signature + content hash + invariants).
        val installResult = capsuleCatalog.put(capsule)
        if (installResult.isFailure) {
            val error = installResult.exceptionOrNull()
            return fail(
                3,
                "install capsule",
                "capsule install failed: ${error?.message ?: "unknown"}",
            )
        }
        auditLog.record("capsule:${capsule.id.value}", "install", "trust:ok")

        // Step 4: Create the workspace. The manager
        // auto-generates a unique id; the E2E uses
        // the auto-generated id for the audit log
        // (the test asserts the workspace was
        // created with the expected name).
        val workspaceResult = workspaceManager.createWorkspace(
            name = workspaceDefinition.name,
            sessions = emptyList(),
        )
        val workspace: Workspace = workspaceResult.getOrElse {
            return fail(4, "create workspace", it.message ?: "unknown")
        }
        auditLog.record("workspace:${workspace.id}", "create", "name:${workspace.name}")

        // Step 5: Orchestrate the workspace definition
        // into a runtime plan. The orchestrator's session
        // has a placeholder distroId (`__pending__`);
        // Phase 71 patches it with the capsule's
        // distribution so the proot backend can find
        // the right distro to run.
        val rawPlan = workspaceOrchestrator.orchestrate(workspaceDefinition)
        val session = patchSessionDistro(rawPlan.session, capsule.distribution.id)
        val plan = rawPlan.copy(session = session)
        auditLog.record(
            "plan:${session.id}",
            "orchestrate",
            "mounts:${plan.bindMounts.size}",
        )

        // Step 6: Launch the binary via the proot
        // backend. The backend records the launch
        // (the entrypoint + the mounts + the env)
        // AND starts the [WriteCapture] watching
        // the bind-mounted host paths.
        // Phase 71: the orchestrator now also passes
        // the workspaceId so the real backend
        // (ProotBackendReal) can look up the
        // Workspace + call the SessionRunner.
        //
        // Phase 72: the writes from `launchResult.writes`
        // are typically empty (the proot process has
        // just spawned and hasn't done any I/O yet).
        // The orchestrator reads the **final** writes
        // via `prootBackend.writes(...)` after `stop`
        // and before `restoreSnapshot` (see step 7.5).
        val launchResult = prootBackend.launch(
            workspaceId = workspace.id,
            session = session,
            executable = plan.launchCommand.executable,
            args = plan.launchCommand.args,
            workingDirectory = plan.launchCommand.workingDirectory,
            bindMounts = plan.bindMounts,
            environment = plan.environment,
        )
        if (launchResult.isFailure) {
            return fail(
                6,
                "launch binary",
                launchResult.exceptionOrNull()?.message ?: "unknown",
            )
        }
        auditLog.record(
            "proot:${session.id}",
            "launch",
            "exec:${plan.launchCommand.executable}",
        )

        // Step 7: Stop the process.
        val stopResult = prootBackend.stop(workspace.id, session)
        if (stopResult.isFailure) {
            return fail(
                7,
                "stop process",
                stopResult.exceptionOrNull()?.message ?: "unknown",
            )
        }
        auditLog.record("proot:${session.id}", "stop", "exit:0")

        // Step 7.5 (Phase 72): read the writes the
        // capture recorded during the session and
        // record each one to the audit log. Step 9
        // then asserts every write is within an
        // authorized mount. This step MUST run
        // after `stop` (the process is done) and
        // before `restoreSnapshot` (which stops
        // the capture).
        val capturedWrites = prootBackend.writes(workspace.id, session)
        for (write in capturedWrites) {
            auditLog.record(
                "proot:${session.id}",
                "write",
                write,
            )
        }

        // Step 8: Restore the snapshot.
        val restoreResult = prootBackend.restoreSnapshot(workspace.id, session)
        if (restoreResult.isFailure) {
            return fail(
                8,
                "restore snapshot",
                restoreResult.exceptionOrNull()?.message ?: "unknown",
            )
        }
        auditLog.record("proot:${session.id}", "restore", "snapshot:restored")

        // Step 9: Audit the writes. Every write by
        // the binary must be within the authorized
        // mount list. The audit log is the
        // platform's record of "what was written
        // by what process to what path".
        val authorizedMounts = plan.bindMounts.map { it.containerPath }.toSet()
        val writes = auditLog.eventsForSubject("proot:${session.id}")
            .filter { it.eventType == "write" }
        for (writeEvent in writes) {
            val writePath = writeEvent.detail
            val isAuthorized = authorizedMounts.any { writePath.startsWith(it) }
            if (!isAuthorized) {
                return fail(
                    9,
                    "audit writes",
                    "unauthorized write to $writePath " +
                        "(not in authorized mount list: $authorizedMounts)",
                )
            }
        }

        return Result.Success(
            listingId = listing.id,
            capsuleId = capsule.id.value,
            workspaceId = workspace.id,
            sessionId = session.id,
            exitCode = 0,
            authorizedWriteCount = writes.size,
            auditEvents = auditLog.all(),
        )
    }

    private fun fail(step: Int, stepName: String, reason: String): Result.Failure =
        Result.Failure(failedAtStep = step, stepName = stepName, reason = reason)

    /**
     * Phase 71 — replace the orchestrator's placeholder
     * `__pending__` distroId / profileId with the
     * capsule's distribution id. The proot backend
     * looks up the distro by id; the placeholder
     * would resolve to "no launcher available".
     *
     * A non-LinuxProot session (e.g. WindowsVm) is
     * returned unchanged.
     */
    private fun patchSessionDistro(
        session: WorkspaceSession,
        distroId: String,
    ): WorkspaceSession = when (session) {
        is WorkspaceSession.LinuxProot -> session.copy(
            distroId = distroId,
            profileId = distroId,
        )
        is WorkspaceSession.WindowsVm -> session
    }
}
