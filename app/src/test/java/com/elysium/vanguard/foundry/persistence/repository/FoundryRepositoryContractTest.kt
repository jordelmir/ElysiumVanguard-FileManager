package com.elysium.vanguard.foundry.persistence.repository

import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifact
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifactFormat
import com.elysium.vanguard.foundry.core.contributor.Contributor
import com.elysium.vanguard.foundry.core.contributor.ContributorRole
import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.ids.EngineeringArtifactId
import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.ProvenanceRecordId
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.program.VehicleProgram
import com.elysium.vanguard.foundry.core.program.VehicleProgramStatus
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.core.project.ProjectStatus
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.core.revision.VehicleRevision
import com.elysium.vanguard.foundry.core.scene.ComponentRef
import com.elysium.vanguard.foundry.core.scene.LodRef
import com.elysium.vanguard.foundry.core.scene.SceneManifest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Contract tests for the Foundry repositories.
 *
 * The contract is verified by:
 *   1. The in-memory implementation (JVM unit test).
 *   2. The Room implementation (instrumented test —
 *      `FoundryRepositoryRoomTest`).
 *
 * Both implementations MUST pass the same assertions. The
 * `InMemory` impl is a verbatim port of the Room impl's
 * contract; the Room impl is the production source of truth.
 *
 * Per `.ai/AGENTS.md` 24.1: every mutation returns a
 * `Result<Unit, FoundryError>`; the typed error is the
 * contract. The optimistic-concurrency conflict is a
 * `FoundryError.RevisionConflict`; a duplicate-key insert
 * is a `FoundryError.VehicleDefinitionInvalid`.
 */
class FoundryRepositoryContractTest {

    // ============================================================
    // ProjectRepository
    // ============================================================

    @Test
    fun `project repository insert then getById round trip`() = runTest {
        val repo = InMemoryProjectRepository()
        val project = sampleProject(name = "Urban One")
        val result = repo.insert(project)
        assertTrue(result.isSuccess)
        val fetched = repo.getById(project.id)
        assertNotNull(fetched)
        assertEquals(project.id, fetched!!.id)
        assertEquals(project.name, fetched.name)
        assertEquals(project.ownerId, fetched.ownerId)
        assertEquals(project.status, fetched.status)
        assertEquals(project.version, fetched.version)
    }

    @Test
    fun `project repository rejects duplicate id`() = runTest {
        val repo = InMemoryProjectRepository()
        val project = sampleProject(name = "Urban One")
        repo.insert(project)
        val result = repo.insert(project.copy(name = "Different Name"))
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected VehicleDefinitionInvalid, got ${error?.javaClass}",
            error is FoundryError.VehicleDefinitionInvalid,
        )
        error as FoundryError.VehicleDefinitionInvalid
        assertEquals("Project.id", error.field)
    }

    @Test
    fun `project repository update with correct version succeeds and increments version`() = runTest {
        val repo = InMemoryProjectRepository()
        val project = sampleProject(name = "Original")
        repo.insert(project)
        val renamed = project.copy(name = "Renamed", version = project.version + 1)
        val result = repo.update(renamed, expectedVersion = project.version)
        assertTrue(result.isSuccess)
        assertEquals(renamed, result.getOrNull())
        val fetched = repo.getById(project.id)
        assertEquals("Renamed", fetched!!.name)
        assertEquals(1L, fetched.version)
    }

    @Test
    fun `project repository update with stale version returns RevisionConflict`() = runTest {
        val repo = InMemoryProjectRepository()
        val project = sampleProject(name = "Original")
        repo.insert(project)
        // Mutator 1: rename to v1
        repo.update(project.copy(name = "Renamed", version = 1L), expectedVersion = 0L)
        // Mutator 2 tries to rename from v0 (stale read)
        val result = repo.update(project.copy(name = "Stale", version = 1L), expectedVersion = 0L)
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected RevisionConflict, got ${error?.javaClass}",
            error is FoundryError.RevisionConflict,
        )
        error as FoundryError.RevisionConflict
        assertEquals("Project", error.aggregateType)
        assertEquals(0L, error.expectedVersion)
        assertEquals(1L, error.actualVersion)
    }

    @Test
    fun `project repository deleteById removes the row`() = runTest {
        val repo = InMemoryProjectRepository()
        val project = sampleProject(name = "Doomed")
        repo.insert(project)
        assertEquals(1, repo.count())
        val result = repo.deleteById(project.id)
        assertTrue(result.isSuccess)
        assertEquals(0, repo.count())
        assertNull(repo.getById(project.id))
    }

    @Test
    fun `project repository deleteById rejects unknown id`() = runTest {
        val repo = InMemoryProjectRepository()
        val result = repo.deleteById(ProjectId.random())
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `project repository getByOwner returns only the matching projects`() = runTest {
        val repo = InMemoryProjectRepository()
        val alice = UserId.random()
        val bob = UserId.random()
        repo.insert(sampleProject(name = "A1", ownerId = alice))
        repo.insert(sampleProject(name = "A2", ownerId = alice))
        repo.insert(sampleProject(name = "B1", ownerId = bob))
        val aliceProjects = repo.getByOwner(alice)
        assertEquals(2, aliceProjects.size)
        assertTrue(aliceProjects.all { it.ownerId == alice })
    }

    @Test
    fun `project repository observeAll emits the current snapshot on subscription`() = runTest {
        val repo = InMemoryProjectRepository()
        // Initial: empty
        val initial = repo.observeAll().first()
        assertTrue(initial.isEmpty())
        // Insert one
        val project = sampleProject(name = "First")
        repo.insert(project)
        // Re-subscribe (StateFlow replays the latest value)
        val afterInsert = repo.observeAll().first()
        assertEquals(1, afterInsert.size)
        assertEquals(project.id, afterInsert[0].id)
    }

    // ============================================================
    // VehicleProgramRepository
    // ============================================================

    @Test
    fun `vehicle program repository insert then getByProject`() = runTest {
        val repo = InMemoryVehicleProgramRepository()
        val projectId = ProjectId.random()
        val program = sampleVehicleProgram(projectId = projectId, name = "Urban Line")
        repo.insert(program)
        val programs = repo.getByProject(projectId)
        assertEquals(1, programs.size)
        assertEquals("Urban Line", programs[0].name)
    }

    @Test
    fun `vehicle program repository update preserves revisions list`() = runTest {
        val repo = InMemoryVehicleProgramRepository()
        val program = sampleVehicleProgram(
            projectId = ProjectId.random(),
            name = "Urban Line",
            revisions = listOf(VehicleRevisionId.random(), VehicleRevisionId.random()),
        )
        repo.insert(program)
        val renamed = program.copy(name = "Urban Line v2", version = 1L)
        val result = repo.update(renamed, expectedVersion = 0L)
        assertTrue(result.isSuccess)
        val fetched = repo.getById(program.id)
        assertEquals(2, fetched!!.revisions.size)
    }

    // ============================================================
    // ContributorRepository
    // ============================================================

    @Test
    fun `contributor repository insert then getByEmail`() = runTest {
        val repo = InMemoryContributorRepository()
        val contributor = sampleContributor(email = "ada@example.com")
        repo.insert(contributor)
        val fetched = repo.getByEmail("ada@example.com")
        assertNotNull(fetched)
        assertEquals(contributor.id, fetched!!.id)
        assertEquals("Ada Lovelace", fetched.displayName)
    }

    @Test
    fun `contributor repository getByEmail returns null for unknown email`() = runTest {
        val repo = InMemoryContributorRepository()
        assertNull(repo.getByEmail("nobody@example.com"))
    }

    @Test
    fun `contributor repository update with stale version returns RevisionConflict`() = runTest {
        val repo = InMemoryContributorRepository()
        val contributor = sampleContributor(email = "ada@example.com", role = ContributorRole.DESIGNER)
        repo.insert(contributor)
        repo.update(contributor.copy(role = ContributorRole.ENGINEER, version = 1L), expectedVersion = 0L)
        val result = repo.update(
            contributor.copy(role = ContributorRole.MECHANIC, version = 1L),
            expectedVersion = 0L,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.RevisionConflict)
    }

    // ============================================================
    // EngineeringArtifactRepository
    // ============================================================

    @Test
    fun `engineering artifact repository getByContentHash returns matching artifact`() = runTest {
        val repo = InMemoryEngineeringArtifactRepository()
        val hash = "a".repeat(64) // 64 hex chars = SHA-256
        val artifact = sampleEngineeringArtifact(contentHash = ContentHash(hash), subjectId = "compilation:abc")
        repo.insert(artifact)
        val fetched = repo.getByContentHash(hash)
        assertNotNull(fetched)
        assertEquals(artifact.id, fetched!!.id)
    }

    @Test
    fun `engineering artifact repository getBySubject returns all artifacts for the subject`() = runTest {
        val repo = InMemoryEngineeringArtifactRepository()
        repo.insert(sampleEngineeringArtifact(contentHash = ContentHash("a".repeat(64)), subjectId = "s1"))
        repo.insert(sampleEngineeringArtifact(contentHash = ContentHash("b".repeat(64)), subjectId = "s1"))
        repo.insert(sampleEngineeringArtifact(contentHash = ContentHash("c".repeat(64)), subjectId = "s2"))
        assertEquals(2, repo.getBySubject("s1").size)
        assertEquals(1, repo.getBySubject("s2").size)
    }

    // ============================================================
    // VehicleRevisionRepository (append-only)
    // ============================================================

    @Test
    fun `vehicle revision repository append then getById round trip preserves provenance`() = runTest {
        val repo = InMemoryVehicleRevisionRepository()
        val revision = sampleVehicleRevision(subjectId = "compilation:abc")
        val result = repo.append(revision)
        assertTrue(result.isSuccess)
        val fetched = repo.getById(revision.id)
        assertNotNull(fetched)
        // The provenance must round-trip including subjectId + witnesses.
        assertEquals(revision.provenance.subjectId, fetched!!.provenance.subjectId)
        assertEquals(revision.provenance.witnesses.size, fetched.provenance.witnesses.size)
        assertEquals(revision.provenance.signature, fetched.provenance.signature)
        assertTrue("provenance.isComplete must be true after round trip", fetched.provenance.isComplete)
    }

    @Test
    fun `vehicle revision repository rejects duplicate id`() = runTest {
        val repo = InMemoryVehicleRevisionRepository()
        val revision = sampleVehicleRevision(subjectId = "compilation:abc")
        repo.append(revision)
        val result = repo.append(revision)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `vehicle revision repository getByContentHash returns matching revision`() = runTest {
        val repo = InMemoryVehicleRevisionRepository()
        val hash = ContentHash("a".repeat(64))
        val revision = sampleVehicleRevision(contentHash = hash, subjectId = "compilation:abc")
        repo.append(revision)
        val fetched = repo.getByContentHash(hash.value)
        assertNotNull(fetched)
        assertEquals(revision.id, fetched!!.id)
    }

    @Test
    fun `vehicle revision repository getByProject returns revisions for the project`() = runTest {
        val repo = InMemoryVehicleRevisionRepository()
        val projectId = ProjectId.random()
        repo.append(sampleVehicleRevision(projectId = projectId, subjectId = "s1"))
        repo.append(sampleVehicleRevision(projectId = projectId, subjectId = "s2"))
        repo.append(sampleVehicleRevision(projectId = ProjectId.random(), subjectId = "s3"))
        val fetched = repo.getByProject(projectId)
        assertEquals(2, fetched.size)
    }

    // ============================================================
    // ProvenanceRecordRepository (append-only)
    // ============================================================

    @Test
    fun `provenance record repository append then getBySubject preserves records in insertion order`() = runTest {
        val repo = InMemoryProvenanceRecordRepository()
        // Use distinct `createdAt` to make the insertion order deterministic
        // (the contract is "ORDER BY createdAt ASC", matching the Room DAO).
        val t0 = Timestamp(1_700_000_000_000L)
        val t1 = Timestamp(1_700_000_000_001L)
        val t2 = Timestamp(1_700_000_000_002L)
        val recordA = sampleProvenanceRecord(subjectId = "compilation:abc", createdAt = t0)
        val recordB = sampleProvenanceRecord(subjectId = "compilation:abc", createdAt = t1)
        val recordC = sampleProvenanceRecord(subjectId = "compilation:abc", createdAt = t2)
        repo.append(recordA)
        repo.append(recordB)
        repo.append(recordC)
        val fetched = repo.getBySubject("compilation:abc")
        assertEquals(3, fetched.size)
        assertEquals(recordA.id, fetched[0].id)
        assertEquals(recordB.id, fetched[1].id)
        assertEquals(recordC.id, fetched[2].id)
    }

    @Test
    fun `provenance record repository rejects duplicate id`() = runTest {
        val repo = InMemoryProvenanceRecordRepository()
        val record = sampleProvenanceRecord(subjectId = "compilation:abc")
        repo.append(record)
        val result = repo.append(record)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun sampleProject(
        name: String = "Test Project",
        ownerId: UserId = UserId.random(),
        status: ProjectStatus = ProjectStatus.DRAFT,
        version: Long = 0L,
    ): Project = Project(
        id = ProjectId.random(),
        name = name,
        ownerId = ownerId,
        status = status,
        createdAt = Timestamp(1_700_000_000_000L),
        version = version,
    )

    private fun sampleVehicleProgram(
        projectId: ProjectId,
        name: String,
        description: String = "Test program",
        revisions: List<VehicleRevisionId> = emptyList(),
        status: VehicleProgramStatus = VehicleProgramStatus.DRAFT,
    ): VehicleProgram = VehicleProgram(
        id = VehicleProgramId.random(),
        projectId = projectId,
        name = name,
        description = description,
        revisions = revisions,
        status = status,
        createdAt = Timestamp(1_700_000_000_000L),
        version = 0L,
    )

    private fun sampleContributor(
        email: String,
        displayName: String = "Ada Lovelace",
        role: ContributorRole = ContributorRole.DESIGNER,
    ): Contributor = Contributor(
        id = ContributorId.random(),
        displayName = displayName,
        email = email,
        role = role,
        createdAt = Timestamp(1_700_000_000_000L),
        version = 0L,
    )

    private fun sampleEngineeringArtifact(
        contentHash: ContentHash,
        subjectId: String,
        format: EngineeringArtifactFormat = EngineeringArtifactFormat.GLTF,
    ): EngineeringArtifact = EngineeringArtifact(
        id = EngineeringArtifactId.random(),
        contentHash = contentHash,
        format = format,
        sizeBytes = 1_024L,
        subjectId = subjectId,
        createdAt = Timestamp(1_700_000_000_000L),
        version = 0L,
    )

    private fun sampleVehicleRevision(
        projectId: ProjectId = ProjectId.random(),
        contentHash: ContentHash = ContentHash("a".repeat(64)),
        subjectId: String = "compilation:abc",
    ): VehicleRevision = VehicleRevision(
        id = VehicleRevisionId.random(),
        projectId = projectId,
        contentHash = contentHash,
        provenance = sampleProvenanceRecord(subjectId = subjectId),
        sceneManifest = SceneManifest(
            revisionContentHash = contentHash,
            components = listOf(ComponentRef(id = "comp-1", label = "Body")),
            lods = listOf(LodRef(level = 0, resolution = "1024")),
            representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        ),
        representationLevel = RepresentationLevel.PARAMETRIC_FUNCTIONAL,
        createdAt = Timestamp(1_700_000_000_000L),
        isImmutable = true,
    )

    private fun sampleProvenanceRecord(
        subjectId: String,
        createdAt: Timestamp = Timestamp(1_700_000_000_000L),
    ): ProvenanceRecord = ProvenanceRecord(
        id = ProvenanceRecordId.random(),
        subjectId = subjectId,
        source = "compiler:deterministic-v1",
        signature = Signature("sig-${UUID.randomUUID()}"),
        witnesses = listOf(Signature("witness-sig")),
        createdAt = createdAt,
    )
}
