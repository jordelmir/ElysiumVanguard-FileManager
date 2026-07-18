package com.elysium.vanguard.foundry.integration

import com.elysium.vanguard.foundry.core.contributor.ContributorRole
import com.elysium.vanguard.foundry.core.contributor.ContributorService
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifactFormat
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifactService
import com.elysium.vanguard.foundry.core.program.VehicleProgramService
import com.elysium.vanguard.foundry.core.project.ProjectService
import com.elysium.vanguard.foundry.core.provenance.ProvenanceService
import com.elysium.vanguard.foundry.core.audit.InMemoryAuditTrail
import com.elysium.vanguard.foundry.core.revision.RevisionService
import com.elysium.vanguard.foundry.persistence.repository.InMemoryContributorRepository
import com.elysium.vanguard.foundry.persistence.repository.InMemoryEngineeringArtifactRepository
import com.elysium.vanguard.foundry.persistence.repository.InMemoryProjectRepository
import com.elysium.vanguard.foundry.persistence.repository.InMemoryProvenanceRecordRepository
import com.elysium.vanguard.foundry.persistence.repository.InMemoryVehicleProgramRepository
import com.elysium.vanguard.foundry.persistence.repository.InMemoryVehicleRevisionRepository
import com.elysium.vanguard.foundry.fixture.VehicleDefinitionFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the **service + repository** pipeline.
 *
 * The services are pure (no I/O); the repositories are the
 * persistence boundary. The integration test validates the
 * round-trip:
 *   1. Service creates a domain object (with version=0).
 *   2. Repository persists it.
 *   3. Repository reads it back (preserves all fields).
 *   4. Service mutates it (with optimistic concurrency).
 *   5. Repository persists the mutation (with the
 *      `expectedVersion`).
 *   6. Repository reads it back (preserves the new state).
 *   7. A stale mutation is rejected with
 *      `FoundryError.RevisionConflict`.
 *
 * The test uses the in-memory repository implementations
 * (JVM-friendly). The Room-backed implementations have a
 * separate instrumented test that validates the same
 * contract.
 *
 * Per `.ai/AGENTS.md` 24.1 + the Foundry's "service creates,
 * repository persists" rule (skill 00): the integration
 * test is the bridge between the pure domain + the
 * persistence layer.
 */
class FoundryServiceRepositoryIntegrationTest {

    private val fixedTimestamp: Timestamp.Companion.TimestampSource = object : Timestamp.Companion.TimestampSource {
        private val fixed = Timestamp(1_700_000_000_000L)
        override fun now(): Timestamp = fixed
    }

    @Test
    fun `project service + repository round trip rename preserves version`() = runTest {
        val projectRepo = InMemoryProjectRepository()
        val projectService = ProjectService(clock = fixedTimestamp)

        val owner = UserId.random()
        val created = projectService.createProject(ownerId = owner, name = "Urban One").getOrThrow()
        assertEquals(0L, created.version)
        projectRepo.insert(created)

        val fetched = projectRepo.getById(created.id)
        assertNotNull(fetched)
        assertEquals("Urban One", fetched!!.name)
        assertEquals(0L, fetched.version)

        val renamed = projectService.rename(
            project = fetched,
            newName = "Urban Two",
            expectedVersion = 0L,
        ).getOrThrow()
        assertEquals(1L, renamed.version)
        val updateResult = projectRepo.update(renamed, expectedVersion = 0L)
        assertTrue(updateResult.isSuccess)

        val renamedFetched = projectRepo.getById(created.id)
        assertEquals("Urban Two", renamedFetched!!.name)
        assertEquals(1L, renamedFetched.version)
    }

    @Test
    fun `project service + repository rejects stale rename with RevisionConflict`() = runTest {
        val projectRepo = InMemoryProjectRepository()
        val projectService = ProjectService(clock = fixedTimestamp)

        val owner = UserId.random()
        val project = projectService.createProject(ownerId = owner, name = "Original").getOrThrow()
        projectRepo.insert(project)

        // First rename succeeds.
        val first = projectService.rename(project, "First", expectedVersion = 0L).getOrThrow()
        projectRepo.update(first, expectedVersion = 0L)

        // Stale read: still at v0. Service renames successfully (it
        // doesn't know about the version bump), but the repository
        // rejects the update because the stored version is now 1.
        val stale = projectService.rename(project, "Stale", expectedVersion = 0L).getOrThrow()
        val staleUpdate = projectRepo.update(stale, expectedVersion = 0L)
        assertTrue(staleUpdate.isFailure)
        assertTrue(staleUpdate.exceptionOrNull() is FoundryError.RevisionConflict)
    }

    @Test
    fun `vehicle program service + repository addRevision appends to list`() = runTest {
        val programRepo = InMemoryVehicleProgramRepository()
        val programService = VehicleProgramService(clock = fixedTimestamp)

        val program = programService.createProgram(
            projectId = ProjectId.random(),
            name = "Urban Line",
        ).getOrThrow()
        programRepo.insert(program)

        val revA = VehicleRevisionId.random()
        val updated = programService.addRevision(
            program = program,
            revisionId = revA,
            expectedVersion = 0L,
        ).getOrThrow()
        val updateResult = programRepo.update(updated, expectedVersion = 0L)
        assertTrue(updateResult.isSuccess)

        val fetched = programRepo.getById(program.id)
        assertEquals(1, fetched!!.revisions.size)
        assertEquals(revA, fetched.revisions[0])
    }

    @Test
    fun `contributor service + repository updateRole is versioned`() = runTest {
        val contributorRepo = InMemoryContributorRepository()
        val contributorService = ContributorService(clock = fixedTimestamp)

        val contributor = contributorService.createContributor(
            displayName = "Ada Lovelace",
            email = "ada@example.com",
            role = ContributorRole.DESIGNER,
        ).getOrThrow()
        contributorRepo.insert(contributor)

        val updated = contributorService.updateRole(
            contributor = contributor,
            newRole = ContributorRole.ENGINEER,
            expectedVersion = 0L,
        ).getOrThrow()
        val updateResult = contributorRepo.update(updated, expectedVersion = 0L)
        assertTrue(updateResult.isSuccess)

        val fetched = contributorRepo.getById(contributor.id)
        assertEquals(ContributorRole.ENGINEER, fetched!!.role)
        assertEquals(1L, fetched.version)
    }

    @Test
    fun `engineering artifact service + repository insert then getByContentHash`() = runTest {
        val artifactRepo = InMemoryEngineeringArtifactRepository()
        val artifactService = EngineeringArtifactService(clock = fixedTimestamp)

        val hash = ContentHash("a".repeat(64))
        val artifact = artifactService.createArtifact(
            contentHash = hash,
            format = EngineeringArtifactFormat.GLTF,
            sizeBytes = 1_024L,
            subjectId = "compilation:abc",
        ).getOrThrow()
        artifactRepo.insert(artifact)

        val fetched = artifactRepo.getByContentHash(hash.value)
        assertNotNull(fetched)
        assertEquals(artifact.id, fetched!!.id)
        assertEquals(hash, fetched.contentHash)
    }

    @Test
    fun `provenance service + repository round trip preserves witnesses`() = runTest {
        val provenanceRepo = InMemoryProvenanceRecordRepository()
        val auditTrail = InMemoryAuditTrail()
        val provenanceService = ProvenanceService(
            auditTrail = auditTrail,
            clock = fixedTimestamp,
        )

        val subjectId = "compilation:abc"
        val signingKey = "test-key".toByteArray()
        val recordResult = provenanceService.createProvenance(
            subjectId = subjectId,
            source = "compiler:deterministic-v1",
            signingKey = signingKey,
        )
        val record = recordResult.getOrThrow()
        val appendResult = provenanceRepo.append(record)
        assertTrue(appendResult.isSuccess)

        val fetched = provenanceRepo.getBySubject(subjectId)
        assertEquals(1, fetched.size)
        assertEquals(record.subjectId, fetched[0].subjectId)
        assertEquals(record.witnesses.size, fetched[0].witnesses.size)
        assertTrue(fetched[0].isComplete)
    }

    @Test
    fun `revision service + repository append preserves provenance subjectId and witnesses`() = runTest {
        val revisionRepo = InMemoryVehicleRevisionRepository()
        val provenanceRepo = InMemoryProvenanceRecordRepository()
        val auditTrail = InMemoryAuditTrail()
        val revisionService = RevisionService(
            auditTrail = auditTrail,
            clock = fixedTimestamp,
        )

        val projectId = ProjectId.random()

        // Build a definition + compile + freeze.
        val definition = VehicleDefinitionFixture.validCompactElectricVehicleDefinition(
            projectId = projectId,
        )
        val compilation = com.elysium.vanguard.foundry.core.compiler.DeterministicVehicleCompiler()
            .compile(
                definition = definition,
                catalogRevision = com.elysium.vanguard.foundry.core.ontology.primitives.CatalogRevision("2026.07"),
                compilerVersion = com.elysium.vanguard.foundry.core.ontology.primitives.CompilerVersion("1.0.0"),
            ).getOrThrow()

        val revision = revisionService.freeze(
            projectId = projectId,
            compilation = compilation,
            definition = definition,
        ).getOrThrow()

        // Append the provenance record (the audit trail already
        // has the event; the repository is a parallel write so the
        // provenance is queryable by id).
        provenanceRepo.append(revision.provenance)

        // Append the revision.
        val appendResult = revisionRepo.append(revision)
        assertTrue(appendResult.isSuccess)

        // The fetched revision must preserve the provenance
        // subjectId (the compilation content hash) + the
        // witnesses list (one witness = the system signature).
        val fetched = revisionRepo.getById(revision.id)
        assertNotNull(fetched)
        assertEquals(
            compilation.contentHash.value,
            fetched!!.provenance.subjectId,
        )
        assertTrue(
            "provenance.isComplete must be true after append",
            fetched.provenance.isComplete,
        )
        assertEquals(1, fetched.provenance.witnesses.size)

        // The repository is also queryable by content hash.
        val byHash = revisionRepo.getByContentHash(compilation.contentHash.value)
        assertNotNull(byHash)
        assertEquals(revision.id, byHash!!.id)
    }
}
