package com.elysium.vanguard.foundry.integration

import com.elysium.vanguard.foundry.core.audit.InMemoryAuditTrail
import com.elysium.vanguard.foundry.core.audit.SignedEventPayload
import com.elysium.vanguard.foundry.core.compiler.DeterministicVehicleCompiler
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision
import com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.project.ProjectService
import com.elysium.vanguard.foundry.core.revision.RevisionService
import com.elysium.vanguard.foundry.fixture.VehicleDefinitionFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Critical integration test for Phase 1 (G1).
 *
 * Per `docs/foundry/implementation-roadmap.md` section 12 +
 * skill 00 section 5.3 (the "critical integration test"):
 *   - Create a `Project`.
 *   - Define a `VehicleDefinition` (a compact electric).
 *   - Compile the configuration (deterministic).
 *   - Freeze the `VehicleRevision` (immutable + provenance +
 *     scene manifest + audit trail).
 *   - Recompile (must produce the same `contentHash`).
 *   - Assert the revision is immutable, the provenance is
 *     complete, the scene manifest is non-null, the
 *     representation level is set, the audit trail is
 *     populated, and a mutation attempt throws
 *     `FrozenRevisionMutationRejected`.
 *
 * This test validates the platform's foundation: determinism,
 * traceability, immutability, stable identity, audit-trail
 * persistence, and the connection between engineering and
 * visualization.
 */
class VehicleProjectToDigitalTwinIntegrationTest {

    private val auditTrail = InMemoryAuditTrail()
    private val projectService = ProjectService()
    private val compiler = DeterministicVehicleCompiler()
    private val revisionService = RevisionService(auditTrail = auditTrail)

    @Test
    fun `vehicle project compiles into immutable traceable digital twin`() {
        // 1. Create project
        val project = projectService.createProject(
            ownerId = UserId.random(),
            name = "Urban One",
        ).getOrThrow()
        assertEquals("expected version 0 on fresh project", 0L, project.version)

        // 2. Define vehicle
        val definition = VehicleDefinitionFixture.validCompactElectricVehicleDefinition(
            projectId = project.id,
        )

        // 3. Compile configuration
        val compilation = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()

        // 4. Generate immutable revision
        val revision = revisionService.freeze(
            projectId = project.id,
            compilation = compilation,
            definition = definition,
        ).getOrThrow()

        // 5. Recompile — must be deterministic
        val secondCompilation = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()

        // 6. Assertions

        // 6a. Determinism: same input -> same content hash
        assertEquals(
            "compilation must be deterministic: same (definition, catalog, compiler) -> same content hash",
            compilation.contentHash,
            secondCompilation.contentHash,
        )

        // 6b. The revision is immutable
        assertTrue("revision.isImmutable must be true", revision.isImmutable)

        // 6c. The provenance is complete
        assertTrue(
            "revision.provenance.isComplete must be true",
            revision.provenance.isComplete,
        )

        // 6d. The scene manifest is non-null
        assertNotNull("revision.sceneManifest must be non-null", revision.sceneManifest)

        // 6e. The representation level is set
        assertEquals(
            "representation level must be PARAMETRIC_FUNCTIONAL for Phase 1 (no validated OEM assets)",
            RepresentationLevel.PARAMETRIC_FUNCTIONAL,
            revision.representationLevel,
        )

        // 6f. The audit trail is populated with the provenance event
        val events = auditTrail.findBySubject(compilation.contentHash.value)
        assertEquals(
            "audit trail should have exactly one event for the content hash",
            1,
            events.size,
        )
        val event = events.first()
        assertEquals("provenance.appended", event.eventType)
        val payload = event.payload
        assertTrue(
            "payload should be ProvenanceAppended, got ${payload?.javaClass}",
            payload is SignedEventPayload.ProvenanceAppended,
        )
        payload as SignedEventPayload.ProvenanceAppended
        // The subjectId of the audit-trail event IS the compilation's
        // content hash (the RevisionService passes it as the subject).
        // The event's own contentHash is a derived hash of the record,
        // NOT the compilation's content hash.
        assertEquals(compilation.contentHash.value, payload.provenanceSubjectId)
        assertEquals("compiler:deterministic-v1", payload.source)

        // 6g. The scene manifest's content hash is stable
        val firstManifest = revision.sceneManifest
        val recomputedManifest = com.elysium.vanguard.foundry.core.scene.SceneManifestGenerator()
            .generate(compilation, definition, RepresentationLevel.PARAMETRIC_FUNCTIONAL)
        assertEquals(
            "scene manifest content hash must be stable",
            firstManifest,
            recomputedManifest,
        )

        // 6h. A mutation attempt throws
        val thrown = kotlin.runCatching { revisionService.modifyFrozenRevision(revision.id) }
            .exceptionOrNull()
        assertTrue(
            "expected FrozenRevisionMutationRejected, got ${thrown?.javaClass}",
            thrown is FoundryError.FrozenRevisionMutationRejected,
        )
        thrown as FoundryError.FrozenRevisionMutationRejected
        assertEquals(revision.id, thrown.revisionId)
        assertEquals("FROZEN_REVISION_MUTATION_REJECTED", thrown.code)
    }
}
