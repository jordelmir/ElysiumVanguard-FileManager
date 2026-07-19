package com.elysium.vanguard.core.runtime.critical_e2e

import com.elysium.vanguard.core.linux.ElysiumLinuxDefaultRepository
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.catalog.InMemoryCapsuleCatalog
import com.elysium.vanguard.core.runtime.market.ElysiumLinuxDistroListing
import com.elysium.vanguard.core.runtime.market.InMemoryMarketCatalog
import com.elysium.vanguard.core.runtime.market.LocalMarketInstaller
import com.elysium.vanguard.core.runtime.market.LocalMarketPublisher
import com.elysium.vanguard.core.runtime.market.MarketListing
import com.elysium.vanguard.core.runtime.market.MarketListingDraft
import com.elysium.vanguard.core.runtime.market.MarketSigning
import com.elysium.vanguard.core.runtime.observability.SynchronizedEventBus
import com.elysium.vanguard.core.runtime.workspace_def.EnvSpec
import com.elysium.vanguard.core.runtime.workspace_def.LauncherSpec
import com.elysium.vanguard.core.runtime.workspace_def.MountSpec
import com.elysium.vanguard.core.runtime.workspace_def.ResourceSpec
import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind
import com.elysium.vanguard.core.runtime.workspace_def.WorkspaceDefinition
import com.elysium.vanguard.core.runtime.workspace_orchestrator.WorkspaceOrchestrator
import com.elysium.vanguard.core.runtime.workspaces.FileWorkspaceStore
import com.elysium.vanguard.core.runtime.workspaces.WorkspaceManager
import com.elysium.vanguard.core.runtime.capsule.ElysiumLinuxCapsule
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 77 (vision alignment) — the JVM integration
 * test that exercises the **critical 8-step E2E
 * flow** using the new Elysium Linux components
 * (the `ElysiumLinuxDefaultRepository` + the
 * `ElysiumLinuxCapsule` + the
 * `ElysiumLinuxDistroListing`).
 *
 * Per the master vision's final section
 * ("Prueba de integración crítica"), the canonical
 * 8-step Definition of Done is:
 *   1. Descargue una distro firmada.
 *   2. Verifique su hash.
 *   3. Cree un workspace aislado.
 *   4. Ejecute un binario Linux ARM64.
 *   5. Monte únicamente una carpeta elegida por
 *      el usuario.
 *   6. Detenga el proceso.
 *   7. Restaure el snapshot.
 *   8. Confirme que no hubo escrituras fuera del
 *      workspace autorizado.
 *
 * This test verifies that the **new Elysium Linux
 * track** (the proprietary distro, the default
 * repository, the Capsule) integrates end-to-end
 * with the **existing critical 8-step E2E
 * infrastructure** (the Market + the Capsule
 * catalog + the Workspace + the proot backend).
 */
class ElysiumLinuxCriticalE2EIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val marketSigningKey = "elysium-linux-e2e-test-key".toByteArray()

    // ============================================================
    // 8-step E2E with the new Elysium Linux components
    // ============================================================

    @Test
    fun `8-step critical E2E succeeds with the new Elysium Linux components`() {
        // ---- Step 0: Build the test rig ----
        val audit = E2EAuditLog()
        val proot = InMemoryProotBackend()
        val workspaceStore = FileWorkspaceStore(tempFolder.newFolder("ws-store"))
        val workspaceManager = WorkspaceManager(
            store = workspaceStore,
            eventBus = SynchronizedEventBus(),
        )
        val workspaceOrchestrator = WorkspaceOrchestrator()
        val capsuleCatalog = InMemoryCapsuleCatalog()
        val orchestrator = CriticalE2EOrchestrator(
            marketSigning = MarketSigning,
            capsuleCatalog = capsuleCatalog,
            workspaceManager = workspaceManager,
            workspaceOrchestrator = workspaceOrchestrator,
            prootBackend = proot,
            auditLog = audit,
        )

        // ---- Step 1: Download a signed distro ----
        // The distro is the Elysium Linux
        // proprietary distro (per the vision's
        // "Una distribución propia: Elysium Vanguard
        // Linux"). The listing is built from
        // the typed ElysiumLinuxDistroListing +
        // published via the LocalMarketPublisher.
        val marketCatalog = InMemoryMarketCatalog()
        val publisher = LocalMarketPublisher(
            catalog = marketCatalog,
            signingKey = marketSigningKey,
            publisherId = ElysiumLinuxDistroListing.PUBLISHER_ID,
        )
        val installer = LocalMarketInstaller(catalog = marketCatalog)
        val artifactBytes = ByteArray(2048) { (it % 256).toByte() }
        val contentHash = ContentHash.of(artifactBytes)
        val draft: MarketListingDraft = ElysiumLinuxDistroListing.draft().copy(
            contentHash = contentHash,
            sizeBytes = artifactBytes.size.toLong(),
        )
        val published: MarketListing = publisher.publish(draft).getOrThrow()
        assertTrue(
            "expected Elysium Linux listing to verify with the test key",
            MarketSigning.verify(published, marketSigningKey),
        )
        // Install the listing (local install of
        // the published listing — the
        // ElysiumLinuxDistroListing's "download"
        // step).
        val installTempDir = tempFolder.newFolder("install")
        installer.install(
            com.elysium.vanguard.core.runtime.market.InstallRequest(
                listingId = draft.id,
                byteSource = { artifactBytes },
                targetDir = installTempDir,
                verifyingKey = marketSigningKey,
            ),
        )

        // ---- Build the runtime contract + the workspace ----
        // The Capsule is the runtime contract;
        // the WorkspaceDefinition is the user's
        // per-workspace configuration.
        val capsule: Capsule = ElysiumLinuxCapsule.build()
        val workspaceDefinition = WorkspaceDefinition(
            apiVersion = com.elysium.vanguard.core.runtime.workspace_def.ApiVersion.V1,
            id = "ws-elysium-linux-e2e",
            name = "Elysium Linux E2E Workspace",
            description = "Workspace for the Elysium Linux critical E2E test",
            runtime = RuntimeKind.LINUX_PROOT,
            mounts = listOf(
                MountSpec(
                    hostPath = "/sdcard/ElysiumVanguard/workspaces/elysium-linux-e2e",
                    containerPath = "/workspace",
                    readOnly = false,
                ),
            ),
            env = listOf(
                EnvSpec(name = "ELYSIUM_LINUX", value = "1"),
            ),
            launcher = LauncherSpec(
                command = "/usr/bin/elysium-pm",
                args = listOf("init"),
                workingDirectory = "/",
            ),
            resources = ResourceSpec(maxMemoryMb = 4096, cpuPriority = 50),
            createdAtMs = 1_000L,
        )

        // ---- Run the 8-step E2E ----
        val result = orchestrator.run(
            listing = published,
            capsule = capsule,
            workspaceDefinition = workspaceDefinition,
            marketSigningKey = marketSigningKey,
        )

        // ---- Verify the result ----
        assertTrue(
            "expected 8-step E2E to succeed, got $result",
            result is CriticalE2EOrchestrator.Result.Success,
        )
        val success = result as CriticalE2EOrchestrator.Result.Success
        assertEquals(published.id, success.listingId)
        assertEquals(capsule.id.value, success.capsuleId)
        assertNotNull(success.workspaceId)
        assertNotNull(success.sessionId)
        assertEquals(0, success.exitCode)
    }

    // ============================================================
    // Elysium Linux default repository + 7 packages
    // ============================================================

    @Test
    fun `Elysium Linux default repository provides the meta-package plus 5 layers plus pkgmgr`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        assertEquals(7, repo.size())
        // The meta-package is at the canonical
        // name + version.
        val meta = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
            ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
        )
        assertNotNull("expected meta-package to be in the default repository", meta)
        // Every runtime layer is in the
        // default repository at the canonical
        // version.
        for ((name, version) in listOf(
            ElysiumLinuxDefaultRepository.PackageNames.NATIVE to
                ElysiumLinuxDefaultRepository.PackageVersions.NATIVE,
            ElysiumLinuxDefaultRepository.PackageNames.MESA_TURNIP to
                ElysiumLinuxDefaultRepository.PackageVersions.MESA_TURNIP,
            ElysiumLinuxDefaultRepository.PackageNames.BOX64 to
                ElysiumLinuxDefaultRepository.PackageVersions.BOX64,
            ElysiumLinuxDefaultRepository.PackageNames.FEX to
                ElysiumLinuxDefaultRepository.PackageVersions.FEX,
            ElysiumLinuxDefaultRepository.PackageNames.WINE to
                ElysiumLinuxDefaultRepository.PackageVersions.WINE,
            ElysiumLinuxDefaultRepository.PackageNames.PACKAGE_MANAGER to
                ElysiumLinuxDefaultRepository.PackageVersions.PACKAGE_MANAGER,
        )) {
            val manifest = repo.fetchManifest(name, version)
            assertNotNull("expected $name to be in the default repository", manifest)
        }
    }

    // ============================================================
    // Elysium Linux Capsule + Workspace consistency
    // ============================================================

    @Test
    fun `Elysium Linux Capsule and Workspace definition are consistent`() {
        val capsule = ElysiumLinuxCapsule.build()
        // The Capsule's distribution id
        // matches the Elysium Linux listing's
        // id.
        assertEquals(
            ElysiumLinuxDistroListing.ID,
            capsule.distribution.id,
        )
        // The Capsule's architecture is ARM64.
        assertEquals(
            com.elysium.vanguard.core.runtime.capsule.Architecture.ARM64,
            capsule.architecture,
        )
        // The Capsule's entrypoint is the
        // Elysium Linux package manager.
        assertEquals("/usr/bin/elysium-pm", capsule.entrypoint.executable)
        // The Capsule's GPU config is Vulkan
        // + Turnip (Adreno).
        assertEquals(
            com.elysium.vanguard.core.runtime.capsule.GpuApi.VULKAN,
            capsule.gpu.api,
        )
        assertEquals(
            com.elysium.vanguard.core.runtime.capsule.GpuDriver.TURNIP,
            capsule.gpu.driver,
        )
    }
}
