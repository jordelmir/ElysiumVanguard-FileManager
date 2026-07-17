package com.elysium.vanguard.foundry.core.revision

import com.elysium.vanguard.foundry.core.compiler.Compilation
import com.elysium.vanguard.foundry.core.compiler.DeterministicVehicleCompiler
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision
import com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionServiceTest {

    private val compiler = DeterministicVehicleCompiler()
    private val service = RevisionService()

    @Test
    fun `freeze produces immutable revision with complete provenance and scene manifest`() {
        val projectId = ProjectId.random()
        val definition = sampleDefinition(projectId)
        val compilation = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()

        val revision = service.freeze(
            projectId = projectId,
            compilation = compilation,
            definition = definition,
        ).getOrThrow()

        assertTrue(revision.isImmutable)
        assertTrue(revision.provenance.isComplete)
        assertNotNull(revision.sceneManifest)
        assertEquals(RepresentationLevel.PARAMETRIC_FUNCTIONAL, revision.representationLevel)
    }

    @Test
    fun `freeze produces a revision whose content hash matches the compilation content hash`() {
        val projectId = ProjectId.random()
        val definition = sampleDefinition(projectId)
        val compilation = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()

        val revision = service.freeze(
            projectId = projectId,
            compilation = compilation,
            definition = definition,
        ).getOrThrow()

        assertEquals(compilation.contentHash, revision.contentHash)
    }

    @Test
    fun `modify frozen revision always throws FrozenRevisionMutationRejected`() {
        val id = VehicleRevisionId.random()
        val thrown = runCatching { service.modifyFrozenRevision(id) }.exceptionOrNull()
        assertTrue(
            "expected FrozenRevisionMutationRejected, got ${thrown?.javaClass}",
            thrown is FoundryError.FrozenRevisionMutationRejected,
        )
        thrown as FoundryError.FrozenRevisionMutationRejected
        assertEquals(id, thrown.revisionId)
    }

    @Test
    fun `two freezes of the same compilation produce different revision ids but same content hash`() {
        val projectId = ProjectId.random()
        val definition = sampleDefinition(projectId)
        val compilation = compiler.compile(
            definition = definition,
            catalogRevision = CatalogRevision("2026.07"),
            compilerVersion = CompilerVersion("1.0.0"),
        ).getOrThrow()

        val a = service.freeze(projectId, compilation, definition).getOrThrow()
        val b = service.freeze(projectId, compilation, definition).getOrThrow()

        assertNotEquals(a.id, b.id)
        assertEquals(a.contentHash, b.contentHash)
    }

    @Test
    fun `vehicle revision rejects construction with isImmutable false`() {
        val projectId = ProjectId.random()
        val definition = sampleDefinition(projectId)
        val compilation = Compilation(contentHash = ContentHash.of("abc"))
        val sceneManifest = com.elysium.vanguard.foundry.core.scene.SceneManifestGenerator().generate(compilation, definition)
        val provenance = com.elysium.vanguard.foundry.core.provenance.ProvenanceService().createProvenance(
            subjectId = "abc",
            source = "test",
            signingKey = RevisionService.DEFAULT_SIGNING_KEY,
        )

        try {
            VehicleRevision(
                id = VehicleRevisionId.random(),
                projectId = projectId,
                contentHash = compilation.contentHash,
                provenance = provenance,
                sceneManifest = sceneManifest,
                representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
                createdAt = com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp(0L),
                isImmutable = false,
            )
            assert(false) { "expected IllegalArgumentException for isImmutable=false" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("isImmutable must be true"))
        }
    }

    private fun sampleDefinition(projectId: ProjectId): VehicleDefinition = VehicleDefinition(
        projectId = projectId,
        name = "Urban One",
        parameters = linkedMapOf(
            "powertrain.type" to "Electric",
            "body.style" to "Compact",
            "battery.kwh" to "40",
        ),
    )
}
