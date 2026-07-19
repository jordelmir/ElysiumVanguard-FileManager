package com.elysium.vanguard.core.runtime.critical_e2e

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
import com.elysium.vanguard.core.runtime.capsule.catalog.InMemoryCapsuleCatalog
import com.elysium.vanguard.core.runtime.market.MarketListing
import com.elysium.vanguard.core.runtime.market.MarketListingType
import com.elysium.vanguard.core.runtime.market.MarketSigning
import com.elysium.vanguard.core.runtime.observability.RuntimeEventBus
import com.elysium.vanguard.core.runtime.workspace_def.EnvSpec
import com.elysium.vanguard.core.runtime.workspace_def.LauncherSpec
import com.elysium.vanguard.core.runtime.workspace_def.MountSpec
import com.elysium.vanguard.core.runtime.workspace_def.ResourceSpec
import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import com.elysium.vanguard.core.runtime.workspace_orchestrator.WorkspaceOrchestrator
import com.elysium.vanguard.core.runtime.workspaces.FileWorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 70 — the **Critical E2E test** (the
 * Definition of Done of the platform, per master
 * vision's final section).
 *
 * The test runs the 8-step integration scenario:
 *   1. Download signed distro (simulated by an
 *      already-built `MarketListing`).
 *   2. Verify the signature + content hash.
 *   3. Create an isolated workspace.
 *   4. Install the capsule into the local catalog
 *      (the trust check).
 *   5. Orchestrate the workspace definition into a
 *      runtime plan.
 *   6. Launch the binary via the proot backend.
 *   7. Stop the process.
 *   8. Restore the snapshot.
 *   9. Audit the writes: every write must be
 *      within the authorized mount list.
 *
 * The test uses real implementations where they
 * exist (MarketSigning, CapsuleCatalog,
 * WorkspaceManager, WorkspaceOrchestrator) +
 * a `ProotBackendStub` for the Android-side
 * execution. The audit step validates the
 * mount policy end-to-end.
 */
class CriticalE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val marketSigningKey = "elysium-e2e-test-signing-key".toByteArray()

    // ============================================================
    // Canonical happy-path scenario
    // ============================================================

    @Test
    fun `critical e2e happy path runs all 8 steps and audits the writes`() {
        val (orchestrator, listing, capsule, workspaceDef, proot, audit) = setup()

        val result = orchestrator.run(
            listing = listing,
            capsule = capsule,
            workspaceDefinition = workspaceDef,
            marketSigningKey = marketSigningKey,
        )

        // Assert: every step succeeded.
        assertTrue("expected success, got $result", result is CriticalE2EOrchestrator.Result.Success)
        result as CriticalE2EOrchestrator.Result.Success
        assertEquals(0, result.exitCode)
        assertTrue("expected at least 1 authorized write", result.authorizedWriteCount >= 1)
        // The audit log has the launch + the stop + the
        // restore + the write (and more).
        assertTrue("expected audit log to be non-empty", result.auditEvents.isNotEmpty())

        // Step 1: signature verified.
        assertTrue(
            "expected a 'verify' event for the listing",
            result.auditEvents.any {
                it.subjectId == "listing:${listing.id}" && it.eventType == "verify"
            },
        )
        // Step 3: capsule installed.
        assertTrue(
            "expected an 'install' event for the capsule",
            result.auditEvents.any {
                it.subjectId == "capsule:${capsule.id.value}" && it.eventType == "install"
            },
        )
        // Step 4: workspace created.
        assertTrue(
            "expected a 'create' event for the workspace",
            result.auditEvents.any {
                it.eventType == "create" && it.subjectId.startsWith("workspace:")
            },
        )
        // Step 5: orchestrated.
        assertTrue(
            "expected an 'orchestrate' event",
            result.auditEvents.any { it.eventType == "orchestrate" },
        )
        // Step 6: launched.
        assertTrue(
            "expected a 'launch' event",
            result.auditEvents.any { it.eventType == "launch" },
        )
        // Step 7: stopped.
        assertTrue(
            "expected a 'stop' event",
            result.auditEvents.any { it.eventType == "stop" },
        )
        // Step 8: snapshot restored.
        assertTrue(
            "expected a 'restore' event",
            result.auditEvents.any { it.eventType == "restore" },
        )
    }

    // ============================================================
    // Step 1: signature verification
    // ============================================================

    @Test
    fun `step 1 fails when listing signature is invalid`() {
        val (orchestrator, listing, capsule, workspaceDef, proot, audit) = setup()
        val wrongKey = "wrong-key".toByteArray()
        val result = orchestrator.run(
            listing = listing,
            capsule = capsule,
            workspaceDefinition = workspaceDef,
            marketSigningKey = wrongKey,
        )
        assertTrue("expected failure, got $result", result is CriticalE2EOrchestrator.Result.Failure)
        result as CriticalE2EOrchestrator.Result.Failure
        assertEquals(1, result.failedAtStep)
        assertEquals("verify signed distro", result.stepName)
    }

    // ============================================================
    // Step 3: capsule install
    // ============================================================

    @Test
    fun `step 3 fails when capsule trust check fails (duplicate id)`() {
        // Pre-install the capsule into a separate
        // catalog so the orchestrator's `put` hits
        // the duplicate-id check at step 3.
        val proot = ProotBackendStub()
        val audit = E2EAuditLog()
        val capsule = buildCapsule()
        val preInstalled = InMemoryCapsuleCatalog()
        preInstalled.put(capsule)
        val setup = buildOrchestrator(proot, audit, preInstalled)
        val listing = setup.listing
        val workspaceDef = setup.workspaceDefinition
        val result = setup.orchestrator.run(
            listing = listing,
            capsule = capsule,
            workspaceDefinition = workspaceDef,
            marketSigningKey = marketSigningKey,
        )
        assertTrue("expected failure, got $result", result is CriticalE2EOrchestrator.Result.Failure)
        result as CriticalE2EOrchestrator.Result.Failure
        assertEquals(3, result.failedAtStep)
    }

    // ============================================================
    // Step 6: launch
    // ============================================================

    @Test
    fun `step 6 fails when proot launch fails`() {
        val (orchestrator, listing, capsule, workspaceDef, proot, audit) = setup()
        proot.nextLaunchFails = true
        val result = orchestrator.run(
            listing = listing,
            capsule = capsule,
            workspaceDefinition = workspaceDef,
            marketSigningKey = marketSigningKey,
        )
        assertTrue("expected failure, got $result", result is CriticalE2EOrchestrator.Result.Failure)
        result as CriticalE2EOrchestrator.Result.Failure
        assertEquals(6, result.failedAtStep)
        assertEquals("launch binary", result.stepName)
    }

    // ============================================================
    // Step 9: audit (unauthorized write)
    // ============================================================

    @Test
    fun `step 9 fails when the process writes outside the authorized mounts`() {
        val (orchestrator, listing, capsule, workspaceDef, proot, audit) = setup()
        // The process writes to /etc/passwd, which is NOT
        // in the workspace's authorized mount list.
        proot.nextWrites = listOf("/etc/passwd")
        val result = orchestrator.run(
            listing = listing,
            capsule = capsule,
            workspaceDefinition = workspaceDef,
            marketSigningKey = marketSigningKey,
        )
        assertTrue("expected failure, got $result", result is CriticalE2EOrchestrator.Result.Failure)
        result as CriticalE2EOrchestrator.Result.Failure
        assertEquals(9, result.failedAtStep)
        assertEquals("audit writes", result.stepName)
        assertTrue(
            "expected error to mention /etc/passwd, got: ${result.reason}",
            result.reason.contains("/etc/passwd"),
        )
    }

    // ============================================================
    // Step 9: audit (authorized write — happy path)
    // ============================================================

    @Test
    fun `step 9 passes when the process writes within the authorized mounts`() {
        val (orchestrator, listing, capsule, workspaceDef, proot, audit) = setup()
        // The process writes to its working directory
        // (which IS in the authorized mounts).
        proot.nextWrites = listOf("/workspace/projects/output.json")
        val result = orchestrator.run(
            listing = listing,
            capsule = capsule,
            workspaceDefinition = workspaceDef,
            marketSigningKey = marketSigningKey,
        )
        assertTrue("expected success, got $result", result is CriticalE2EOrchestrator.Result.Success)
        result as CriticalE2EOrchestrator.Result.Success
        assertEquals(1, result.authorizedWriteCount)
    }

    // ============================================================
    // Setup
    // ============================================================

    private data class Setup(
        val orchestrator: CriticalE2EOrchestrator,
        val listing: MarketListing,
        val capsule: Capsule,
        val workspaceDefinition: WorkspaceDefinition,
        val proot: ProotBackendStub,
        val audit: E2EAuditLog,
    )

    private fun setup(): Setup {
        val proot = ProotBackendStub()
        val audit = E2EAuditLog()
        val (orchestrator, listing, capsule, workspaceDef) = buildOrchestrator(proot, audit)
        return Setup(orchestrator, listing, capsule, workspaceDef, proot, audit)
    }

    private fun buildOrchestrator(
        proot: ProotBackendStub,
        audit: E2EAuditLog,
        catalog: InMemoryCapsuleCatalog = InMemoryCapsuleCatalog(),
    ): Quad {
        val workspaceManager = WorkspaceManager(
            store = FileWorkspaceStore(tempFolder.newFolder("ws-store")),
            eventBus = com.elysium.vanguard.core.runtime.observability.SynchronizedEventBus(),
        )
        val workspaceOrchestrator = WorkspaceOrchestrator()
        val orchestrator = CriticalE2EOrchestrator(
            marketSigning = MarketSigning,
            capsuleCatalog = catalog,
            workspaceManager = workspaceManager,
            workspaceOrchestrator = workspaceOrchestrator,
            prootBackend = proot,
            auditLog = audit,
        )
        // Build the listing + the capsule + the
        // workspace definition.
        val listingId = "distro-elysium-linux-1"
        val capsule = buildCapsule()
        // The listing's signature is computed by the
        // publisher with the publisher's key. The test
        // signs with the test key. The MarketSigning
        // computes the signature over the listing's
        // canonical form.
        val unsignedListing = MarketListing(
            id = listingId,
            name = "Elysium Linux 1",
            type = MarketListingType.DISTRO,
            version = "1.0.0",
            contentHash = ContentHash.of("elysium-linux-1.v1"),
            signatureKeyId = "elysium-e2e-test",
            signature = Signature("unsigned"),
            sizeBytes = 1_500_000_000L,
            dependencies = emptyList(),
            tags = listOf("elysium", "linux", "arm64"),
            createdAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(1_700_000_000_000L),
        )
        val listing = MarketSigning.sign(unsignedListing, marketSigningKey)
        val workspaceDef = buildWorkspaceDefinition(capsule.id)
        return Quad(orchestrator, listing, capsule, workspaceDef)
    }

    private data class Quad(
        val orchestrator: CriticalE2EOrchestrator,
        val listing: MarketListing,
        val capsule: Capsule,
        val workspaceDefinition: WorkspaceDefinition,
    )

    private fun buildCapsule(): Capsule = Capsule(
        apiVersion = CapsuleApiVersion.V1,
        id = CapsuleId("com.elysium.e2etest.arm64"),
        name = "E2E Test Capsule",
        version = "1.0.0",
        description = "Capsule for the critical E2E test",
        runtime = Runtime.LINUX,
        architecture = Architecture.ARM64,
        distribution = Distribution.ELYSIUM_LINUX_1,
        entrypoint = EntryPoint(
            executable = "/usr/bin/e2e-test",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
        ),
        gpu = GpuConfig(api = GpuApi.NONE, driver = GpuDriver.NONE),
        permissions = Permissions(
            network = false,
            storage = listOf(StorageScope.USER_SELECTED),
        ),
        signature = Signature("sig-e2e-test"),
        contentHash = ContentHash("a".repeat(64)),
    )

    private fun buildWorkspaceDefinition(capsuleId: CapsuleId): WorkspaceDefinition =
        WorkspaceDefinition(
            apiVersion = com.elysium.vanguard.core.runtime.workspace_def.ApiVersion.V1,
            id = "ws-e2e-test",
            name = "E2E Test Workspace",
            description = "Workspace for the critical E2E test",
            runtime = RuntimeKind.LINUX_PROOT,
            mounts = listOf(
                MountSpec(
                    hostPath = "/sdcard/ElysiumVanguard/workspaces/e2e-test/projects",
                    containerPath = "/workspace/projects",
                    readOnly = false,
                ),
            ),
            env = listOf(
                EnvSpec(name = "DISPLAY", value = ":0"),
            ),
            launcher = LauncherSpec(
                command = "/usr/bin/e2e-test",
                args = listOf("--benchmark"),
                workingDirectory = "/workspace/projects",
            ),
            resources = ResourceSpec(maxMemoryMb = 1024, cpuPriority = 50),
            createdAtMs = 1_700_000_000_000L,
        )
}
