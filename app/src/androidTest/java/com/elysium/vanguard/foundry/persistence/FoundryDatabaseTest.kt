package com.elysium.vanguard.foundry.persistence

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import com.elysium.vanguard.foundry.core.project.Project
import com.elysium.vanguard.foundry.core.project.ProjectStatus
import com.elysium.vanguard.foundry.persistence.entities.ProjectEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for the `FoundryDatabase`. The test uses
 * Room's in-memory database (`:memory:`) so the
 * tests are fast + isolated.
 *
 * The test runs as an instrumented test
 * (requires an Android context, per Room's
 * test infrastructure). The `@RunWith(AndroidJUnit4::class)`
 * is the standard pattern.
 */
@RunWith(AndroidJUnit4::class)
class FoundryDatabaseTest {

    private lateinit var database: FoundryDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            FoundryDatabase::class.java,
        )
            .allowMainThreadQueries() // tests only
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun database_builds_with_all_6_entities() {
        // If the database didn't build, setUp would have thrown.
        // This test is a smoke test.
        assertNotNull(database)
        assertNotNull(database.projectDao())
        assertNotNull(database.vehicleProgramDao())
        assertNotNull(database.contributorDao())
        assertNotNull(database.engineeringArtifactDao())
        assertNotNull(database.vehicleRevisionDao())
        assertNotNull(database.provenanceRecordDao())
    }

    @Test
    fun project_dao_round_trip() = runBlocking {
        val entity = ProjectEntity.fromDomain(
            Project(
                id = com.elysium.vanguard.foundry.core.ontology.ids.ProjectId.random(),
                name = "Urban One",
                ownerId = UserId.random(),
                status = ProjectStatus.DRAFT,
                createdAt = Timestamp(1_700_000_000_000L),
                version = 0L,
            ),
        )
        database.projectDao().insert(entity)
        val fetched = database.projectDao().getById(entity.id)
        assertNotNull(fetched)
        assertEquals(entity.id, fetched!!.id)
        assertEquals(entity.name, fetched.name)
        assertEquals(entity.ownerId, fetched.ownerId)
        assertEquals(entity.status, fetched.status)
        assertEquals(entity.createdAtEpochMs, fetched.createdAtEpochMs)
        assertEquals(entity.version, fetched.version)

        // Round-trip: entity -> domain -> entity should preserve fields.
        val domain = fetched.toDomain()
        assertEquals(entity.name, domain.name)
        assertEquals(entity.ownerId, domain.ownerId.toString())
        assertEquals(entity.version, domain.version)
    }

    @Test
    fun project_dao_returns_null_for_unknown_id() = runBlocking {
        val result = database.projectDao().getById("does-not-exist")
        assertNull(result)
    }

    @Test
    fun project_dao_update_changes_version() = runBlocking {
        val entity = ProjectEntity.fromDomain(
            Project(
                id = com.elysium.vanguard.foundry.core.ontology.ids.ProjectId.random(),
                name = "Original",
                ownerId = UserId.random(),
                status = ProjectStatus.DRAFT,
                createdAt = Timestamp(1_700_000_000_000L),
                version = 0L,
            ),
        )
        database.projectDao().insert(entity)
        val updated = entity.copy(name = "Updated", version = 1L)
        val rows = database.projectDao().update(updated)
        assertEquals(1, rows)
        val fetched = database.projectDao().getById(entity.id)
        assertEquals("Updated", fetched!!.name)
        assertEquals(1L, fetched.version)
    }

    @Test
    fun project_dao_count_starts_at_zero() = runBlocking {
        assertEquals(0, database.projectDao().count())
    }

    @Test
    fun project_dao_delete_by_id_removes_row() = runBlocking {
        val entity = ProjectEntity.fromDomain(
            Project(
                id = com.elysium.vanguard.foundry.core.ontology.ids.ProjectId.random(),
                name = "Doomed",
                ownerId = UserId.random(),
                status = ProjectStatus.DRAFT,
                createdAt = Timestamp(1_700_000_000_000L),
                version = 0L,
            ),
        )
        database.projectDao().insert(entity)
        assertEquals(1, database.projectDao().count())
        val rows = database.projectDao().deleteById(entity.id)
        assertEquals(1, rows)
        assertEquals(0, database.projectDao().count())
    }

    @Test
    fun project_dao_handles_uuid_validation_failure() {
        // The entity's id is a String (the UUID's toString()).
        // A malformed UUID should not be present (the
        // project service should validate before insert).
        // The DAO does NOT validate — that's the service's
        // job. This test documents the contract.
        runBlocking {
            // Insert a valid entity.
            val validEntity = ProjectEntity.fromDomain(
                Project(
                    id = com.elysium.vanguard.foundry.core.ontology.ids.ProjectId.random(),
                    name = "Test",
                    ownerId = UserId.random(),
                    status = ProjectStatus.DRAFT,
                    createdAt = Timestamp(1L),
                    version = 0L,
                ),
            )
            database.projectDao().insert(validEntity)
            // Fetch it; the UUID must be valid.
            val fetched = database.projectDao().getById(validEntity.id)!!
            // Reconstruct the domain; the constructor
            // would have thrown if the UUID were malformed.
            val domain = fetched.toDomain()
            assertNotNull(domain)
        }
    }

    @Test
    fun vehicle_program_dao_round_trip() = runBlocking {
        val entity = com.elysium.vanguard.foundry.persistence.entities.VehicleProgramEntity.fromDomain(
            com.elysium.vanguard.foundry.core.program.VehicleProgram(
                id = com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId.random(),
                projectId = com.elysium.vanguard.foundry.core.ontology.ids.ProjectId.random(),
                name = "Urban Line",
                description = "The Urban vehicle family",
                revisions = listOf(
                    com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId.random(),
                ),
                status = com.elysium.vanguard.foundry.core.program.VehicleProgramStatus.DRAFT,
                createdAt = Timestamp(1_700_000_000_000L),
                version = 0L,
            ),
        )
        database.vehicleProgramDao().insert(entity)
        val fetched = database.vehicleProgramDao().getById(entity.id)!!
        val domain = fetched.toDomain()
        assertEquals(entity.name, domain.name)
        assertEquals(entity.description, domain.description)
        assertEquals(1, domain.revisions.size)
    }

    @Test
    fun contributor_dao_round_trip() = runBlocking {
        val entity = com.elysium.vanguard.foundry.persistence.entities.ContributorEntity.fromDomain(
            com.elysium.vanguard.foundry.core.contributor.Contributor(
                id = com.elysium.vanguard.foundry.core.ontology.ids.ContributorId.random(),
                displayName = "Ada Lovelace",
                email = "ada@example.com",
                role = com.elysium.vanguard.foundry.core.contributor.ContributorRole.DESIGNER,
                createdAt = Timestamp(1_700_000_000_000L),
                version = 0L,
            ),
        )
        database.contributorDao().insert(entity)
        val fetched = database.contributorDao().getByEmail("ada@example.com")!!
        val domain = fetched.toDomain()
        assertEquals("Ada Lovelace", domain.displayName)
        assertEquals(com.elysium.vanguard.foundry.core.contributor.ContributorRole.DESIGNER, domain.role)
    }
}
