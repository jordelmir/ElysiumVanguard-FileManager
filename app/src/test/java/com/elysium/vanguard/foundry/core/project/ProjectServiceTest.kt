package com.elysium.vanguard.foundry.core.project

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectServiceTest {

    @Test
    fun `create project with valid name returns success`() {
        val service = ProjectService()
        val result = service.createProject(
            ownerId = UserId.random(),
            name = "Urban One",
        )
        assertTrue(result.isSuccess)
        val project = result.getOrThrow()
        assertEquals("Urban One", project.name)
        assertEquals(ProjectStatus.DRAFT, project.status)
        assertNotNull(project.id)
        assertNotNull(project.createdAt)
    }

    @Test
    fun `create project with blank name returns typed failure`() {
        val service = ProjectService()
        val result = service.createProject(
            ownerId = UserId.random(),
            name = "",
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is FoundryError.VehicleDefinitionInvalid)
        error as FoundryError.VehicleDefinitionInvalid
        assertEquals("Project.name", error.field)
    }

    @Test
    fun `create project trims surrounding whitespace from name`() {
        val service = ProjectService()
        val result = service.createProject(
            ownerId = UserId.random(),
            name = "  Spacious Trim  ",
        )
        assertTrue(result.isSuccess)
        assertEquals("Spacious Trim", result.getOrThrow().name)
    }

    @Test
    fun `create project with oversized name returns typed failure`() {
        val service = ProjectService()
        val result = service.createProject(
            ownerId = UserId.random(),
            name = "x".repeat(Project.MAX_NAME_LENGTH + 1),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `create project twice produces different ids`() {
        val service = ProjectService()
        val owner = UserId.random()
        val a = service.createProject(owner, "Alpha").getOrThrow()
        val b = service.createProject(owner, "Beta").getOrThrow()
        assertNotEquals(a.id, b.id)
        assertEquals("Alpha", a.name)
        assertEquals("Beta", b.name)
    }
}
