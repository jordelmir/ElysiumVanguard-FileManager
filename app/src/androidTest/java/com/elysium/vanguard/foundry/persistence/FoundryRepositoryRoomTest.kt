package com.elysium.vanguard.foundry.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifact
import com.elysium.vanguard.foundry.core.artifact.EngineeringArtifactFormat
import com.elysium.vanguard.foundry.core.contributor.Contributor
import com.elysium.vanguard.foundry.core.contributor.ContributorRole
import com.elysium.vanguard.foundry.core.program.VehicleProgram
import com.elysium.vanguard.foundry.core.program.VehicleProgramStatus
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.core.project.ProjectStatus
import com.elysium.vanguard.foundry.core.provenance.ProvenanceRecord
import com.elysium.vanguard.foundry.core.revision.VehicleRevision
import com.elysium.vanguard.foundry.core.scene.ComponentRef
import com.elysium.vanguard.foundry.core.scene.LodRef
import com.elysium.vanguard.foundry.core.scene.SceneManifest
import com.elysium.vanguard.foundry.persistence.repository.RoomContributorRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomEngineeringArtifactRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomProjectRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomProvenanceRecordRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomVehicleProgramRepository
import com.elysium.vanguard.foundry.persistence.repository.RoomVehicleRevisionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Room-backed
 * `FoundryRepository` implementations. The test uses
 * Room's in-memory database (`:memory:`) so the tests
 * are fast + isolated.
 *
 * The test runs as an instrumented test (requires an
 * Android context, per Room's test infrastructure).
 *
 * The test exercises the **same** contract as the
 * JVM-side `FoundryRepositoryContractTest`. Both suites
 * MUST pass; a divergence is a P0 contract violation.
 *
 * Per `.ai/AGENTS.md` 24.1: every mutation returns a
 * `Result<Unit, FoundryError>`; the typed error is the
 * contract.
 */
@RunWith(AndroidJUnit4::class)
class FoundryRepositoryRoomTest {

    private lateinit var database: FoundryDatabase

    private lateinit var projectRepo: RoomProjectRepository
    private lateinit var programRepo: RoomVehicleProgramRepository
    private lateinit var contributorRepo: RoomContributorRepository
    private lateinit var artifactRepo: RoomEngineeringArtifactRepository
    private lateinit var revisionRepo: RoomVehicleRevisionRepository
    private lateinit var provenanceRepo: RoomProvenanceRecordRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            FoundryDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        projectRepo = RoomProjectRepository(database.projectDao())
        programRepo = RoomVehicleProgramRepository(database.vehicleProgramDao())
        contributorRepo = RoomContributorRepository(database.contributorDao())
        artifactRepo = RoomEngineeringArtifactRepository(database.engineeringArtifactDao())
        revisionRepo = RoomVehicleRevisionRepository(database.vehicleRevisionDao())
        provenanceRepo = RoomProvenanceRecordRepository(database.provenanceRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ============================================================
    // ProjectRepository — Room
    // ============================================================

    @Test
    fun room_project_repository_insert_then_getById_round_trip() = runTest {
        val project = sampleProject(name = "Urban One")
        val result = projectRepo.insert(project)
        assertTrue(result.isSuccess)
        val fetched = projectRepo.getById(project.id)
        assertNotNull(fetched)
        assertEquals(project.id, fetched!!.id)
        assertEquals(project.name, fetched.name)
        assertEquals(project.ownerId, fetched.ownerId)
        assertEquals(project.status, fetched.status)
        assertEquals(project.version, fetched.version)
        assertEquals(project.createdAt, fetched.createdAt)
    }

    @Test
    fun room_project_repository_update_with_stale_version_returns_RevisionConflict() = runTest {
        val project = sampleProject(name = "Original")
        projectRepo.insert(project)
        projectRepo.update(
            project.copy(name = "Renamed", version = 1L),
            expectedVersion = 0L,
        )
        val result = projectRepo.update(
            project.copy(name = "Stale", version = 1L),
            expectedVersion = 0L,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.RevisionConflict)
    }

    @Test
    fun room_project_repository_deleteById_removes_row() = runTest {
        val project = sampleProject(name = "Doomed")
        projectRepo.insert(project)
        val result = projectRepo.deleteById(project.id)
        assertTrue(result.isSuccess)
        assertNull(projectRepo.getById(project.id))
        assertEquals(0, projectRepo.count())
    }

    @Test
    fun room_project_repository_observeAll_emits_after_insert() = runTest {
        val initial = projectRepo.observeAll().first()
        assertTrue(initial.isEmpty())
        val project = sampleProject(name = "First")
        projectRepo.insert(project)
        val after = projectRepo.observeAll().first()
        assertEquals(1, after.size)
        assertEquals(project.id, after[0].id)
    }

    @Test
    fun room_project_repository_getByOwner_filters_by_owner() = runTest {
        val alice = UserId.random()
        val bob = UserId.random()
        projectRepo.insert(sampleProject(name = "A1", ownerId = alice))
        projectRepo.insert(sampleProject(name = "A2", ownerId = alice))
        projectRepo.insert(sampleProject(name = "B1", ownerId = bob))
        assertEquals(2, projectRepo.getByOwner(alice).size)
        assertEquals(1, projectRepo.getByOwner(bob).size)
    }

    // ============================================================
    // VehicleProgramRepository — Room
    // ============================================================

    @Test
    fun room_vehicle_program_repository_insert_then_getByProject() = runTest {
        val projectId = ProjectId.random()
        val program = sampleVehicleProgram(projectId = projectId, name = "Urban Line")
        programRepo.insert(program)
        val programs = programRepo.getByProject(projectId)
        assertEquals(1, programs.size)
        assertEquals("Urban Line", programs[0].name)
    }

    @Test
    fun room_vehicle_program_repository_update_preserves_revisions() = runTest {
        val program = sampleVehicleProgram(
            projectId = ProjectId.random(),
            name = "Urban Line",
            revisions = listOf(VehicleRevisionId.random(), VehicleRevisionId.random()),
        )
        programRepo.insert(program)
        val renamed = program.copy(name = "Urban Line v2", version = 1L)
        val result = programRepo.update(renamed, expectedVersion = 0L)
        assertTrue(result.isSuccess)
        val fetched = programRepo.getById(program.id)
        assertEquals(2, fetched!!.revisions.size)
    }

    // ============================================================
    // ContributorRepository — Room
    // ============================================================

    @Test
    fun room_contributor_repository_insert_then_getByEmail() = runTest {
        val contributor = sampleContributor(email = "ada@example.com")
        contributorRepo.insert(contributor)
        val fetched = contributorRepo.getByEmail("ada@example.com")
        assertNotNull(fetched)
        assertEquals(contributor.id, fetched!!.id)
    }

    // ============================================================
    // EngineeringArtifactRepository — Room
    // ============================================================

    @Test
    fun room_engineering_artifact_repository_getByContentHash() = runTest {
        val hash = "a".repeat(64)
        val artifact = sampleEngineeringArtifact(contentHash = ContentHash(hash), subjectId = "s1")
        artifactRepo.insert(artifact)
        val fetched = artifactRepo.getByContentHash(hash)
        assertNotNull(fetched)
        assertEquals(artifact.id, fetched!!.id)
    }

    // ============================================================
    // VehicleRevisionRepository — Room (append-only)
    // ============================================================

    @Test
    fun room_vehicle_revision_repository_append_then_getById_preserves_provenance() = runTest {
        val revision = sampleVehicleRevision(subjectId = "compilation:abc")
        val result = revisionRepo.append(revision)
        assertTrue(result.isSuccess)
        val fetched = revisionRepo.getById(revision.id)
        assertNotNull(fetched)
        // The provenance must round-trip including subjectId + witnesses.
        assertEquals(revision.provenance.subjectId, fetched!!.provenance.subjectId)
        assertEquals(revision.provenance.witnesses.size, fetched.provenance.witnesses.size)
        assertEquals(revision.provenance.signature, fetched.provenance.signature)
        assertTrue(
            "provenance.isComplete must be true after Room round trip",
            fetched.provenance.isComplete,
        )
    }

    @Test
    fun room_vehicle_revision_repository_round_trip_preserves_scene_manifest_content_hash() = runTest {
        val revision = sampleVehicleRevision(subjectId = "compilation:abc")
        revisionRepo.append(revision)
        val fetched = revisionRepo.getById(revision.id)
        assertNotNull(fetched)
        // The scene manifest is reconstructed from the stored snapshot.
        // The `contentHash` is computed in the `init` block from the
        // canonical form, so the reconstructed manifest must have the
        // same `contentHash` as the original.
        assertEquals(
            revision.sceneManifest.contentHash,
            fetched!!.sceneManifest.contentHash,
        )
        assertEquals(revision.sceneManifest.components, fetched.sceneManifest.components)
        assertEquals(revision.sceneManifest.lods, fetched.sceneManifest.lods)
    }

    @Test
    fun room_vehicle_revision_repository_rejects_duplicate() = runTest {
        val revision = sampleVehicleRevision(subjectId = "compilation:abc")
        revisionRepo.append(revision)
        val result = revisionRepo.append(revision)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    // ============================================================
    // ProvenanceRecordRepository — Room (append-only)
    // ============================================================

    @Test
    fun room_provenance_record_repository_append_then_getBySubject() = runTest {
        val recordA = sampleProvenanceRecord(subjectId = "compilation:abc")
        val recordB = sampleProvenanceRecord(subjectId = "compilation:abc")
        provenanceRepo.append(recordA)
        provenanceRepo.append(recordB)
        val fetched = provenanceRepo.getBySubject("compilation:abc")
        assertEquals(2, fetched.size)
        // Insertion order is preserved (the DAO orders by created_at_epoch_ms ASC).
        assertEquals(recordA.id, fetched[0].id)
        assertEquals(recordB.id, fetched[1].id)
    }

    @Test
    fun room_provenance_record_repository_round_trip_preserves_witnesses() = runTest {
        val record = sampleProvenanceRecord(subjectId = "compilation:abc")
        provenanceRepo.append(record)
        val fetched = provenanceRepo.getById(record.id)
        assertNotNull(fetched)
        assertEquals(record.witnesses.size, fetched!!.witnesses.size)
        assertEquals(record.signature, fetched.signature)
    }

    // ============================================================
    // Fixtures (mirror the JVM-side fixtures)
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
        revisions: List<VehicleRevisionId> = emptyList(),
    ): VehicleProgram = VehicleProgram(
        id = VehicleProgramId.random(),
        projectId = projectId,
        name = name,
        description = "Test program",
        revisions = revisions,
        status = VehicleProgramStatus.DRAFT,
        createdAt = Timestamp(1_700_000_000_000L),
        version = 0L,
    )

    private fun sampleContributor(
        email: String,
        role: ContributorRole = ContributorRole.DESIGNER,
    ): Contributor = Contributor(
        id = ContributorId.random(),
        displayName = "Ada Lovelace",
        email = email,
        role = role,
        createdAt = Timestamp(1_700_000_000_000L),
        version = 0L,
    )

    private fun sampleEngineeringArtifact(
        contentHash: ContentHash,
        subjectId: String,
    ): EngineeringArtifact = EngineeringArtifact(
        id = EngineeringArtifactId.random(),
        contentHash = contentHash,
        format = EngineeringArtifactFormat.GLTF,
        sizeBytes = 1_024L,
        subjectId = subjectId,
        createdAt = Timestamp(1_700_000_000_000L),
        version = 0L,
    )

    private fun sampleVehicleRevision(
        subjectId: String,
    ): VehicleRevision {
        val contentHash = ContentHash("a".repeat(64))
        return VehicleRevision(
            id = VehicleRevisionId.random(),
            projectId = ProjectId.random(),
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
    }

    private fun sampleProvenanceRecord(
        subjectId: String,
    ): ProvenanceRecord = ProvenanceRecord(
        id = ProvenanceRecordId.random(),
        subjectId = subjectId,
        source = "compiler:deterministic-v1",
        signature = Signature("sig-${java.util.UUID.randomUUID()}"),
        witnesses = listOf(Signature("witness-1"), Signature("witness-2")),
        createdAt = Timestamp(1_700_000_000_000L),
    )
}
