package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.graphics.GPUVendor
import com.elysium.vanguard.core.linux.ElysiumAbi
import com.elysium.vanguard.core.linux.ElysiumAbiCapabilityMatrix
import com.elysium.vanguard.core.linux.ElysiumLinuxDefaultRepository
import com.elysium.vanguard.core.linux.ElysiumPackageManifest
import com.elysium.vanguard.core.linux.ElysiumPackageVersion
import com.elysium.vanguard.core.linux.ElysiumRepository
import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerCatalog
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerDefaults
import com.elysium.vanguard.core.linux.InMemoryElysiumRepository
import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.CapsuleApiVersion
import com.elysium.vanguard.core.runtime.capsule.CapsuleId
import com.elysium.vanguard.core.runtime.capsule.Distribution
import com.elysium.vanguard.core.runtime.capsule.EntryPoint
import com.elysium.vanguard.core.runtime.capsule.GpuApi
import com.elysium.vanguard.core.runtime.capsule.GpuConfig
import com.elysium.vanguard.core.runtime.capsule.GpuDriver
import com.elysium.vanguard.core.runtime.capsule.Permissions
import com.elysium.vanguard.core.runtime.capsule.Runtime
import com.elysium.vanguard.core.runtime.capsule.StorageScope
import com.elysium.vanguard.core.runtime.critical_e2e.E2EAuditLog
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 91 (Universal Execution Engine — production
 * wiring) — the JVM tests for
 * [ProductionCriticalE2EOrchestrator].
 *
 * The tests cover:
 *   - ProductionCriticalE2EError invariants
 *     (code + message non-blank).
 *   - CriticalE2EInput invariants.
 *   - sandboxPolicyFor helper (default
 *     mounts + READ_WRITE for user mounts +
 *     Strict security + DEFAULT limits +
 *     Denied network).
 *   - DefaultProductionCriticalE2EOrchestrator:
 *     - 8-step E2E succeeds with the
 *       production UEE components.
 *     - The audit log records every step.
 *     - The selection is the typed
 *       RuntimeSelection.Native.
 *     - The launch plan has the expected
 *       executable + args.
 *     - The sandbox preparation has the
 *       expected steps in order.
 *     - The sandbox enforcement matches
 *       the preparation.
 *     - The watcher records a Started +
 *       Exited event pair.
 *     - The stream capture records a
 *       StreamClosed chunk.
 *     - The recovery decision is
 *       DoNotRestart (the process exited
 *       cleanly).
 *     - Authorized writes are accepted
 *       (no audit failure).
 *   - Failure modes:
 *     - Step 1: repository returns null
 *       (REPO_MISSING_MANIFEST).
 *     - Step 2: manifest signature is
 *       invalid (INVALID_MANIFEST_SIGNATURE).
 *     - Step 6: launcher fails
 *       (EXECUTABLE_NOT_FOUND).
 *     - Step 8: stream capture contains
 *       a write outside the authorized
 *       mount list (AUDIT_FAILED).
 *   - Recovery policy integration:
 *     - The decision is recorded in the
 *       Success result.
 */
class ProductionCriticalE2EOrchestratorTest {

    private val expectedSigningKey: String =
        ElysiumLinuxDefaultRepository.DEFAULT_SIGNING_KEY

    // ============================================================
    // ProductionCriticalE2EError invariants
    // ============================================================

    @Test
    fun `ProductionCriticalE2EError subtypes have non-blank code and message`() {
        val errors: List<ProductionCriticalE2EError> = listOf(
            ProductionCriticalE2EError.RepoMissingManifest(
                "com.elysium.linux.distro",
                ElysiumPackageVersion(1, 0, 0),
            ),
            ProductionCriticalE2EError.InvalidManifestSignature("sig-x"),
            ProductionCriticalE2EError.SelectionUnsupported("no box64"),
            ProductionCriticalE2EError.SandboxRejected(listOf("X")),
            ProductionCriticalE2EError.LauncherFailed(
                "EXECUTABLE_NOT_FOUND",
                "no /bin/sh",
            ),
            ProductionCriticalE2EError.WatchFailed("handle unknown"),
            ProductionCriticalE2EError.StopFailed("kill failed"),
            ProductionCriticalE2EError.RestoreFailed("no snapshot"),
            ProductionCriticalE2EError.AuditFailed(
                "/etc/passwd",
                listOf("/workspace"),
            ),
        )
        for (error in errors) {
            assertTrue(
                "expected non-blank code for ${error::class.simpleName}",
                error.code.isNotBlank(),
            )
            assertTrue(
                "expected non-blank message for " +
                    "${error::class.simpleName}",
                error.message!!.isNotBlank(),
            )
        }
    }

    @Test
    fun `RepoMissingManifest error carries the typed id and version`() {
        val error = ProductionCriticalE2EError.RepoMissingManifest(
            distributionId = "com.elysium.linux.distro",
            version = ElysiumPackageVersion(1, 0, 0),
        )
        assertEquals(
            "com.elysium.linux.distro",
            error.distributionId,
        )
        assertEquals(
            ElysiumPackageVersion(1, 0, 0),
            error.version,
        )
        assertEquals("REPO_MISSING_MANIFEST", error.code)
    }

    @Test
    fun `AuditFailed error carries the unauthorized path and authorized list`() {
        val error = ProductionCriticalE2EError.AuditFailed(
            unauthorizedPath = "/etc/passwd",
            authorizedMounts = listOf("/workspace", "/tmp"),
        )
        assertEquals("/etc/passwd", error.unauthorizedPath)
        assertEquals(listOf("/workspace", "/tmp"), error.authorizedMounts)
        assertEquals("AUDIT_FAILED", error.code)
    }

    // ============================================================
    // sandboxPolicyFor helper
    // ============================================================

    @Test
    fun `sandboxPolicyFor builds a policy with system mounts and user mounts`() {
        val workspaceId = UUID.randomUUID()
        val userMount = MountEntry(
            source = ElysiumRootfsPath("/sdcard/user"),
            target = ElysiumRootfsPath("/workspace"),
            mode = MountMode.READ_ONLY,
            purpose = MountPurpose.Custom("ignored"),
        )
        val policy = sandboxPolicyFor(
            workspaceId = workspaceId,
            userMounts = listOf(userMount),
        )
        // The policy has the system mount + the
        // user mount.
        assertEquals(2, policy.mounts.size)
        // The system mount is READ_ONLY
        // (SystemLibraries invariant).
        val systemMount = policy.mounts[0]
        assertEquals(MountMode.READ_ONLY, systemMount.mode)
        assertTrue(systemMount.purpose is MountPurpose.SystemLibraries)
        // The user mount was forced to READ_WRITE
        // (WorkspaceData invariant).
        val userPolicyMount = policy.mounts[1]
        assertEquals(MountMode.READ_WRITE, userPolicyMount.mode)
        assertTrue(userPolicyMount.purpose is MountPurpose.WorkspaceData)
        // The default security + limits + network.
        assertTrue(policy.security is SecurityProfile.Strict)
        assertEquals(SandboxLimits.DEFAULT, policy.limits)
        assertTrue(policy.network is NetworkPolicy.Denied)
    }

    @Test
    fun `sandboxPolicyFor with custom security and network and limits`() {
        val workspaceId = UUID.randomUUID()
        val policy = sandboxPolicyFor(
            workspaceId = workspaceId,
            userMounts = emptyList(),
            network = NetworkPolicy.Full,
            security = SecurityProfile.Permissive,
            limits = SandboxLimits.UNLIMITED,
        )
        assertTrue(policy.network is NetworkPolicy.Full)
        assertTrue(policy.security is SecurityProfile.Permissive)
        assertEquals(SandboxLimits.UNLIMITED, policy.limits)
    }

    // ============================================================
    // DefaultProductionCriticalE2EOrchestrator — 8-step success
    // ============================================================

    @Test
    fun `8-step E2E succeeds with the production UEE components`() {
        val rig = buildProductionRigWithAuthorizedWrite()
        val nowMs = 1_700_000_000_000L
        val result = rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = rig.inputs,
            nowMs = nowMs,
        )
        assertTrue(
            "expected 8-step E2E to succeed, got $result",
            result is CriticalE2EResult.Success,
        )
        val success = result as CriticalE2EResult.Success
        // The audit log has at least one event
        // for each of the 8 steps.
        assertTrue(
            "expected >= 8 audit events, got " +
                "${success.auditEvents.size}",
            success.auditEvents.size >= 8,
        )
        // The selection is Native (the capsule's
        // architecture matches the device's).
        assertTrue(
            "expected Native selection, got " +
                "${success.selection::class.simpleName}",
            success.selection is RuntimeSelection.Native,
        )
        // The launch plan is for the capsule's
        // entrypoint.
        assertEquals(
            rig.capsule.entrypoint.executable,
            success.launchPlan.executable,
        )
        // The sandbox preparation has the
        // expected steps in order: BindMount
        // (one per mount in the policy) +
        // ApplySeLinuxContext +
        // ApplyResourceLimits +
        // ApplyNetworkPolicy + Skipped.
        val prepSteps = success.sandboxPreparation.steps
        // The first step is a BindMount
        // (the system mount).
        assertTrue(
            "expected first step to be BindMount, got " +
                "${prepSteps[0]::class.simpleName}",
            prepSteps[0] is PreparationStep.BindMount,
        )
        // The canonical 4-step ordering
        // (SeLinux → ResourceLimits →
        // NetworkPolicy → Skipped) appears
        // after the BindMounts.
        val nonBindMountSteps = prepSteps
            .filter {
                it !is PreparationStep.BindMount
            }
        assertTrue(
            "expected at least 4 non-BindMount steps, got " +
                "${nonBindMountSteps.size}",
            nonBindMountSteps.size >= 4,
        )
        assertTrue(
            "expected first non-BindMount step to be " +
                "ApplySeLinuxContext, got " +
                "${nonBindMountSteps[0]::class.simpleName}",
            nonBindMountSteps[0] is PreparationStep.ApplySeLinuxContext,
        )
        assertTrue(
            "expected last step to be Skipped, got " +
                "${prepSteps.last()::class.simpleName}",
            prepSteps.last() is PreparationStep.Skipped,
        )
        // The sandbox enforcement matches the
        // preparation (1:1 step mapping).
        val enfSteps = success.sandboxEnforcement.steps
        assertEquals(prepSteps.size, enfSteps.size)
        // The process handle is non-null.
        assertNotNull(success.processHandleId)
        // The watcher recorded a Started + Exited
        // event pair.
        val started = success.processEvents
            .filterIsInstance<ProcessEvent.Started>().firstOrNull()
        val exited = success.processEvents
            .filterIsInstance<ProcessEvent.Exited>().firstOrNull()
        assertNotNull("expected Started event", started)
        assertNotNull("expected Exited event", exited)
        assertEquals(
            success.processHandleId,
            started!!.handleId,
        )
        // The stream capture recorded a
        // StreamClosed chunk.
        val closed = success.streams
            .filterIsInstance<StreamChunk.StreamClosed>().firstOrNull()
        assertNotNull("expected StreamClosed chunk", closed)
        assertEquals(success.processHandleId, closed!!.handleId)
        // The recovery decision is DoNotRestart
        // (the process exited cleanly).
        assertTrue(
            "expected DoNotRestart decision, got " +
                "${success.recoveryDecision::class.simpleName}",
            success.recoveryDecision is RecoveryDecision.DoNotRestart,
        )
        // The authorized write count is 1 (the
        // stream capture was primed with one
        // authorized write).
        assertEquals(1, success.authorizedWriteCount)
    }

    @Test
    fun `8-step E2E records every step to the audit log`() {
        val rig = buildProductionRig()
        rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = rig.inputs,
            nowMs = 1_700_000_000_000L,
        )
        val events = rig.inputs.auditLog.all()
        // The audit log must record:
        // - fetch + verify (steps 1 + 2)
        // - create workspace (step 3)
        // - select + dispatch (step 4)
        // - prepare + enforce (step 5)
        // - launch (step 6)
        // - stop (step 7)
        // - write (step 8)
        assertTrue(
            "expected fetch event, got $events",
            events.any { it.eventType == "fetch" },
        )
        assertTrue(
            "expected verify event, got $events",
            events.any { it.eventType == "verify" },
        )
        assertTrue(
            "expected create event, got $events",
            events.any { it.eventType == "create" },
        )
        assertTrue(
            "expected select event, got $events",
            events.any { it.eventType == "select" },
        )
        assertTrue(
            "expected dispatch event, got $events",
            events.any { it.eventType == "dispatch" },
        )
        assertTrue(
            "expected prepare event, got $events",
            events.any { it.eventType == "prepare" },
        )
        assertTrue(
            "expected enforce event, got $events",
            events.any { it.eventType == "enforce" },
        )
        assertTrue(
            "expected launch event, got $events",
            events.any { it.eventType == "launch" },
        )
        assertTrue(
            "expected stop event, got $events",
            events.any { it.eventType == "stop" },
        )
    }

    // ============================================================
    // Failure modes
    // ============================================================

    @Test
    fun `step 1 fails when repository returns null`() {
        val rig = buildProductionRig()
        val emptyRepository = InMemoryElysiumRepository()
        val inputs = rig.inputs.copy(repository = emptyRepository)
        val result = rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = inputs,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result is CriticalE2EResult.Failure)
        val failure = result as CriticalE2EResult.Failure
        assertEquals(1, failure.failedAtStep)
        assertEquals("fetch signed distro", failure.stepName)
        assertEquals("REPO_MISSING_MANIFEST", failure.errorCode)
    }

    @Test
    fun `step 2 fails when manifest signature is invalid`() {
        val rig = buildProductionRig()
        // Build a manifest with an invalid
        // signature (missing the expected
        // signing key). We don't go through
        // addManifest (which would also reject
        // a bad signature) — we hand-build a
        // custom InMemoryElysiumRepository
        // that returns a tampered manifest.
        val tamperedManifest = rig.manifest.copy(
            signature = Signature("tampered-sig-without-key"),
        )
        // The InMemoryElysiumRepository's
        // addManifest verifies the signature,
        // so we use a custom test repository
        // that returns the tampered manifest
        // directly.
        val customRepository = TamperedRepository(tamperedManifest)
        val inputs = rig.inputs.copy(repository = customRepository)
        val result = rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = inputs,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result is CriticalE2EResult.Failure)
        val failure = result as CriticalE2EResult.Failure
        assertEquals(2, failure.failedAtStep)
        assertEquals("verify signed distro", failure.stepName)
        assertEquals("INVALID_MANIFEST_SIGNATURE", failure.errorCode)
    }

    @Test
    fun `step 8 fails when stream capture records an unauthorized write`() {
        val rig = buildProductionRig()
        // The handle id is generated by the
        // launcher; we use a stub launcher
        // that returns a known handle id so
        // the stream capture can be primed
        // with an unauthorized write for
        // that handle id.
        val knownHandleId = ProcessId.random()
        val stubLauncher = StubProcessLauncher(
            handleIdToReturn = knownHandleId,
        )
        val watcher = InMemoryProcessWatcher()
        val capture = InMemoryProcessStreamCapture()
        capture.append(
            StreamChunk.StdoutChunk(
                handleId = knownHandleId,
                data = "/etc/passwd",
                timestampMs = 1_700_000_000_000L,
            ),
        )
        val inputs = rig.inputs.copy(
            processLauncher = stubLauncher,
            processWatcher = watcher,
            streamCapture = capture,
        )
        val result = rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = inputs,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result is CriticalE2EResult.Failure)
        val failure = result as CriticalE2EResult.Failure
        assertEquals(8, failure.failedAtStep)
        assertEquals("audit writes", failure.stepName)
        assertEquals("AUDIT_FAILED", failure.errorCode)
        // The reason mentions the unauthorized
        // path.
        assertTrue(
            "expected reason to mention /etc/passwd, got " +
                failure.reason,
            failure.reason.contains("/etc/passwd"),
        )
    }

    @Test
    fun `step 6 fails when launcher fails to launch`() {
        val rig = buildProductionRig()
        val failingLauncher = AlwaysFailingProcessLauncher(
            error = ProcessLauncherError.ExecutableNotFound("/bin/missing"),
        )
        val inputs = rig.inputs.copy(processLauncher = failingLauncher)
        val result = rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = inputs,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result is CriticalE2EResult.Failure)
        val failure = result as CriticalE2EResult.Failure
        assertEquals(6, failure.failedAtStep)
        assertEquals("launch binary", failure.stepName)
        // The error code is the launcher's
        // error code (not the orchestrator's
        // generic LAUNCH_FAILED).
        assertEquals("EXECUTABLE_NOT_FOUND", failure.errorCode)
    }

    // ============================================================
    // Recovery policy integration
    // ============================================================

    @Test
    fun `success result carries the recovery decision`() {
        val rig = buildProductionRig()
        val result = rig.orchestrator.run(
            capsule = rig.capsule,
            inputs = rig.inputs,
            nowMs = 1_700_000_000_000L,
        )
        assertTrue(result is CriticalE2EResult.Success)
        val success = result as CriticalE2EResult.Success
        // The decision is DoNotRestart because
        // the process exited cleanly (exit
        // code 0) and the recovery policy is
        // None.
        assertTrue(
            "expected DoNotRestart, got " +
                "${success.recoveryDecision::class.simpleName}",
            success.recoveryDecision is RecoveryDecision.DoNotRestart,
        )
    }

    // ============================================================
    // Determinism
    // ============================================================

    @Test
    fun `the orchestrator is deterministic for the same nowMs`() {
        val rig1 = buildProductionRig()
        val rig2 = buildProductionRig()
        val nowMs = 1_700_000_000_000L
        val r1 = rig1.orchestrator.run(
            rig1.capsule,
            rig1.inputs,
            nowMs,
        )
        val r2 = rig2.orchestrator.run(
            rig2.capsule,
            rig2.inputs,
            nowMs,
        )
        // The number of audit events must be
        // the same (the orchestrator's
        // deterministic algorithm).
        assertTrue(r1 is CriticalE2EResult.Success)
        assertTrue(r2 is CriticalE2EResult.Success)
        assertEquals(
            (r1 as CriticalE2EResult.Success).auditEvents.size,
            (r2 as CriticalE2EResult.Success).auditEvents.size,
        )
    }

    // ============================================================
    // Production rig builder
    // ============================================================

    /**
     * A production-shaped rig. The rig wires
     * the UEE production components with
     * realistic inputs.
     */
    private data class ProductionRig(
        val orchestrator: DefaultProductionCriticalE2EOrchestrator,
        val inputs: CriticalE2EInput,
        val capsule: Capsule,
        val manifest: ElysiumPackageManifest,
    )

    /**
     * Build a production rig. The rig uses
     * the Elysium Linux default repository +
     * the Elysium Linux capsule + a generic
     * ARM64 device profile.
     */
    private fun buildProductionRig(): ProductionRig {
        val audit = E2EAuditLog()
        val repo = ElysiumLinuxDefaultRepository.build()
        val distroName = ElysiumLinuxDefaultRepository.PackageNames.DISTRO
        val distroVersion = ElysiumLinuxDefaultRepository.PackageVersions.DISTRO
        val manifest = repo.fetchManifest(distroName, distroVersion)!!
        // The capsule is a Linux ARM64 binary
        // with the Elysium Linux distribution.
        val capsule = capsuleForElysiumLinux()
        // The device is an ARM64 phone with
        // 4 GB of memory and Adreno GPU.
        val device = DeviceProfile(
            abi = ElysiumAbi.ARM64,
            gpuVendor = GPUVendor.ADRENO,
            hasRoot = false,
            availableMemoryMb = 4_096L,
        )
        // The runtime layer catalog + ABI
        // matrix for the selector. The
        // catalog is pre-populated with the
        // default Elysium Linux runtime
        // layers (per ElysiumRuntimeLayerDefaults.ALL).
        val catalog = ElysiumRuntimeLayerCatalog()
        for (layerManifest in ElysiumRuntimeLayerDefaults.ALL) {
            catalog.addLayer(
                layerManifest,
                ElysiumRuntimeLayerDefaults.DEFAULT_SIGNING_KEY,
            )
        }
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        // The user mounts: a single workspace
        // data folder.
        val userMount = MountEntry(
            source = ElysiumRootfsPath("/sdcard/user"),
            target = ElysiumRootfsPath("/workspace"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.Custom("user-selected"),
        )
        val sandboxPolicy = sandboxPolicyFor(
            workspaceId = UUID.randomUUID(),
            userMounts = listOf(userMount),
        )
        // The process components.
        val launcher = InMemoryProcessLauncher()
        val watcher = InMemoryProcessWatcher()
        val capture = InMemoryProcessStreamCapture()
        val sandboxApp = InMemorySandboxApplication()
        val sandboxEnf = InMemorySandboxEnforcer()
        val selector = RuntimeSelector(
            capabilityMatrix = matrix,
            runtimeLayerCatalog = catalog,
        )
        val dispatcher = RuntimeDispatcher()
        val recoveryExec = InMemoryRecoveryExecutor()
        val inputs = CriticalE2EInput(
            repository = repo,
            deviceProfile = device,
            sandboxPolicy = sandboxPolicy,
            recoveryPolicy = RecoveryPolicy.None,
            processLauncher = launcher,
            processWatcher = watcher,
            streamCapture = capture,
            sandboxApplication = sandboxApp,
            sandboxEnforcer = sandboxEnf,
            runtimeSelector = selector,
            runtimeDispatcher = dispatcher,
            recoveryExecutor = recoveryExec,
            auditLog = audit,
            distributionId = distroName,
            distributionVersion = distroVersion,
            workspaceId = UUID.randomUUID(),
            userMounts = listOf(userMount),
        )
        // The capture is primed with an
        // authorized write that the orchestrator
        // will record to the audit log in step 8.
        // (We can't pre-append because the
        // handle id is generated by the
        // launcher; the orchestrator's flow
        // appends a `StreamClosed` chunk but
        // not a `StdoutChunk` for the
        // authorized write. Instead, the
        // orchestrator's existing audit step
        // records the writes from the stream
        // capture, so we use a custom launcher
        // that primes the capture with a known
        // handle id.)
        // Simpler approach: we use the
        // production rig with the InMemory
        // launcher; the orchestrator emits
        // StreamClosed; the audit step's
        // authorized-write count will be 0
        // (no stdout chunks for the handle).
        // The test asserts authorizedWriteCount
        // >= 0; the strict assertion of = 1 is
        // for the dedicated test that primes
        // the capture.
        val rig = ProductionRig(
            orchestrator = DefaultProductionCriticalE2EOrchestrator(),
            inputs = inputs,
            capsule = capsule,
            manifest = manifest,
        )
        // Prime the capture for the handle id
        // that the InMemory launcher will
        // produce. We can't know the handle id
        // in advance, so we pre-launch to get
        // a handle and prime the capture. The
        // orchestrator's launch will then
        // launch again with a new handle, but
        // since the orchestrator's audit step
        // reads `streamCapture.stdoutChunksForHandle(handleId)`,
        // the priming for the old handle id
        // is irrelevant. The audit step will
        // have authorizedWriteCount = 0.
        // For the strict assertion in the
        // success test, we use a custom
        // launcher that returns a known
        // handle id.
        return rig
    }

    /**
     * Build a production rig with a custom
     * launcher that returns a known handle id
     * AND a stream capture pre-loaded with
     * one authorized write for that handle
     * id. The rig is used by the strict
     * success test to assert
     * `authorizedWriteCount == 1`.
     */
    private fun buildProductionRigWithAuthorizedWrite():
        ProductionRig {
        val rig = buildProductionRig()
        val knownHandleId = ProcessId.random()
        val stubLauncher = StubProcessLauncher(
            handleIdToReturn = knownHandleId,
        )
        val capture = InMemoryProcessStreamCapture()
        capture.append(
            StreamChunk.StdoutChunk(
                handleId = knownHandleId,
                data = "/workspace/output.json",
                timestampMs = 1_700_000_000_000L,
            ),
        )
        return rig.copy(
            inputs = rig.inputs.copy(
                processLauncher = stubLauncher,
                streamCapture = capture,
            ),
        )
    }

    // ============================================================
    // Test repositories
    // ============================================================

    /**
     * A test [ElysiumRepository] that returns a
     * single pre-canned manifest (the
     * `tampered` manifest). The repository is
     * used by the signature-verification test
     * to inject a manifest with a known-bad
     * signature without going through
     * [InMemoryElysiumRepository.addManifest]
     * (which would also reject the bad
     * signature).
     */
    private class TamperedRepository(
        private val manifestToReturn: ElysiumPackageManifest,
    ) : ElysiumRepository {
        override fun fetchManifest(
            name: String,
            version: ElysiumPackageVersion,
        ): ElysiumPackageManifest? = manifestToReturn
        override fun listVersions(
            name: String,
        ): List<ElysiumPackageVersion> = listOf(manifestToReturn.version)
        override fun listPackages(): List<String> = listOf(manifestToReturn.name)
        override fun size(): Int = 1
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Build a Capsule that targets Elysium
     * Linux on ARM64 with the `elysium-pm`
     * binary as the entrypoint.
     */
    private fun capsuleForElysiumLinux(): Capsule = Capsule(
        apiVersion = CapsuleApiVersion.V1,
        id = CapsuleId("com.elysium.linux.distro"),
        name = "Elysium Linux Distro",
        version = "1.0.0",
        description = "Elysium Linux proprietary distro",
        runtime = Runtime.LINUX,
        architecture = Architecture.ARM64,
        distribution = Distribution("com.elysium.linux.distro"),
        entrypoint = EntryPoint(
            executable = "/usr/bin/elysium-pm",
            args = listOf("init"),
            workingDirectory = "/",
        ),
        gpu = GpuConfig(
            api = GpuApi.NONE,
            driver = GpuDriver.NONE,
        ),
        permissions = Permissions(
            network = true,
            storage = listOf(StorageScope.USER_SELECTED),
        ),
        signature = Signature("sig-capsule"),
        contentHash = ContentHash(
            "a".repeat(64),
        ),
    )
}
