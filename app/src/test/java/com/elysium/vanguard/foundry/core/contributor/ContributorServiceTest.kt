package com.elysium.vanguard.foundry.core.contributor

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributorServiceTest {

    private val service = ContributorService()

    @Test
    fun `create contributor with valid inputs returns success`() {
        val result = service.createContributor(
            displayName = "Ada Lovelace",
            email = "ada@example.com",
            role = ContributorRole.DESIGNER,
        )
        assertTrue(result.isSuccess)
        val contributor = result.getOrThrow()
        assertEquals("Ada Lovelace", contributor.displayName)
        assertEquals("ada@example.com", contributor.email)
        assertEquals(ContributorRole.DESIGNER, contributor.role)
        assertEquals(0L, contributor.version)
    }

    @Test
    fun `create contributor with blank display name returns typed failure`() {
        val result = service.createContributor(
            displayName = "",
            email = "ada@example.com",
            role = ContributorRole.DESIGNER,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `create contributor with malformed email returns typed failure`() {
        val result = service.createContributor(
            displayName = "Ada",
            email = "not-an-email",
            role = ContributorRole.DESIGNER,
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is FoundryError.VehicleDefinitionInvalid)
        error as FoundryError.VehicleDefinitionInvalid
        assertEquals("Contributor.email", error.field)
    }

    @Test
    fun `create contributor trims surrounding whitespace`() {
        val result = service.createContributor(
            displayName = "  Ada  ",
            email = "  ada@example.com  ",
            role = ContributorRole.DESIGNER,
        )
        assertTrue(result.isSuccess)
        val c = result.getOrThrow()
        assertEquals("Ada", c.displayName)
        assertEquals("ada@example.com", c.email)
    }

    @Test
    fun `update role changes role and increments version`() {
        val contributor = service.createContributor(
            displayName = "Ada",
            email = "ada@example.com",
            role = ContributorRole.DESIGNER,
        ).getOrThrow()
        val updated = service.updateRole(
            contributor = contributor,
            newRole = ContributorRole.ENGINEER,
            expectedVersion = 0L,
        ).getOrThrow()
        assertEquals(ContributorRole.ENGINEER, updated.role)
        assertEquals(1L, updated.version)
    }

    @Test
    fun `update role with stale version raises RevisionConflict`() {
        val contributor = service.createContributor(
            displayName = "Ada",
            email = "ada@example.com",
            role = ContributorRole.DESIGNER,
        ).getOrThrow()
        val first = service.updateRole(contributor, ContributorRole.ENGINEER, expectedVersion = 0L).getOrThrow()
        val conflict = service.updateRole(first, ContributorRole.REVIEWER, expectedVersion = 0L)
        assertTrue(conflict.isFailure)
        val error = conflict.exceptionOrNull()
        assertTrue(
            "expected RevisionConflict, got ${error?.javaClass}",
            error is FoundryError.RevisionConflict,
        )
        error as FoundryError.RevisionConflict
        assertEquals("Contributor", error.aggregateType)
    }

    @Test
    fun `update role to the same role returns typed failure`() {
        val contributor = service.createContributor(
            displayName = "Ada",
            email = "ada@example.com",
            role = ContributorRole.DESIGNER,
        ).getOrThrow()
        val result = service.updateRole(contributor, ContributorRole.DESIGNER, expectedVersion = 0L)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `two creates produce different ids`() {
        val a = service.createContributor("Ada", "ada@example.com", ContributorRole.DESIGNER).getOrThrow()
        val b = service.createContributor("Boole", "boole@example.com", ContributorRole.ENGINEER).getOrThrow()
        assertNotEquals(a.id, b.id)
    }
}
