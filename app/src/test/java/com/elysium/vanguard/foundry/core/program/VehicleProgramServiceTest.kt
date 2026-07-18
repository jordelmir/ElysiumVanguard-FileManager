package com.elysium.vanguard.foundry.core.program

import com.elysium.vanguard.foundry.core.ontology.ids.ProjectId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleProgramServiceTest {

    private val service = VehicleProgramService()

    @Test
    fun `create program with valid name returns success`() {
        val result = service.createProgram(
            projectId = ProjectId.random(),
            name = "Urban Line",
        )
        assertTrue(result.isSuccess)
        val program = result.getOrThrow()
        assertEquals("Urban Line", program.name)
        assertEquals(VehicleProgramStatus.DRAFT, program.status)
        assertTrue(program.revisions.isEmpty())
        assertEquals(0L, program.version)
    }

    @Test
    fun `create program with blank name returns typed failure`() {
        val result = service.createProgram(
            projectId = ProjectId.random(),
            name = "",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `create program with oversized description returns typed failure`() {
        val result = service.createProgram(
            projectId = ProjectId.random(),
            name = "Urban Line",
            description = "x".repeat(VehicleProgram.MAX_DESCRIPTION_LENGTH + 1),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `add revision appends to revisions and increments version`() {
        val program = service.createProgram(ProjectId.random(), "Urban Line").getOrThrow()
        val revisionId = VehicleRevisionId.random()
        val updated = service.addRevision(program, revisionId, expectedVersion = 0L).getOrThrow()
        assertEquals(1, updated.revisions.size)
        assertEquals(revisionId, updated.revisions.first())
        assertEquals(1L, updated.version)
    }

    @Test
    fun `add revision with stale version raises RevisionConflict`() {
        val program = service.createProgram(ProjectId.random(), "Urban Line").getOrThrow()
        val first = service.addRevision(program, VehicleRevisionId.random(), expectedVersion = 0L).getOrThrow()
        val conflict = service.addRevision(first, VehicleRevisionId.random(), expectedVersion = 0L)
        assertTrue(conflict.isFailure)
        val error = conflict.exceptionOrNull()
        assertTrue(
            "expected RevisionConflict, got ${error?.javaClass}",
            error is FoundryError.RevisionConflict,
        )
        error as FoundryError.RevisionConflict
        assertEquals("VehicleProgram", error.aggregateType)
        assertEquals(0L, error.expectedVersion)
        assertEquals(1L, error.actualVersion)
    }

    @Test
    fun `add revision with duplicate id raises typed failure`() {
        val program = service.createProgram(ProjectId.random(), "Urban Line").getOrThrow()
        val revisionId = VehicleRevisionId.random()
        val first = service.addRevision(program, revisionId, expectedVersion = 0L).getOrThrow()
        val second = service.addRevision(first, revisionId, expectedVersion = 1L)
        assertTrue(second.isFailure)
        val error = second.exceptionOrNull()
        assertTrue(error is FoundryError.VehicleDefinitionInvalid)
        error as FoundryError.VehicleDefinitionInvalid
        assertEquals("VehicleProgram.revisions", error.field)
    }

    @Test
    fun `transition status to ACTIVE and increments version`() {
        val program = service.createProgram(ProjectId.random(), "Urban Line").getOrThrow()
        val updated = service.transitionStatus(program, VehicleProgramStatus.ACTIVE, expectedVersion = 0L).getOrThrow()
        assertEquals(VehicleProgramStatus.ACTIVE, updated.status)
        assertEquals(1L, updated.version)
    }

    @Test
    fun `transition from ARCHIVED is rejected`() {
        val program = service.createProgram(ProjectId.random(), "Urban Line").getOrThrow()
        val archived = service.transitionStatus(program, VehicleProgramStatus.ARCHIVED, expectedVersion = 0L).getOrThrow()
        val reverted = service.transitionStatus(archived, VehicleProgramStatus.ACTIVE, expectedVersion = 1L)
        assertTrue(reverted.isFailure)
        val error = reverted.exceptionOrNull()
        assertTrue(error is FoundryError.VehicleDefinitionInvalid)
        error as FoundryError.VehicleDefinitionInvalid
        assertEquals("VehicleProgram.status", error.field)
    }

    @Test
    fun `two creates produce different ids`() {
        val a = service.createProgram(ProjectId.random(), "Alpha").getOrThrow()
        val b = service.createProgram(ProjectId.random(), "Beta").getOrThrow()
        assertNotEquals(a.id, b.id)
    }
}
