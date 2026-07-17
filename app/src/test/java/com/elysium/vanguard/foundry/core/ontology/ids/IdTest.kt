package com.elysium.vanguard.foundry.core.ontology.ids

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the strongly-typed ID layer. The platform's contract
 * (per `.ai/AGENTS.md` 24.1 + `.ai/skills/03-vehicle-domain-ontology/
 * SKILL.md` section 14): a raw `String` is never a domain ID; a malformed
 * UUID is a typed `FoundryError.InvalidUuidFormat`; equality is value-
 * based.
 */
class IdTest {

    @Test
    fun `random ids are unique`() {
        val a = UserId.random()
        val b = UserId.random()
        assertNotEquals(a, b)
    }

    @Test
    fun `ids with same uuid are equal`() {
        val uuid = java.util.UUID.randomUUID()
        assertEquals(UserId(uuid), UserId(uuid))
        assertEquals(UserId(uuid).hashCode(), UserId(uuid).hashCode())
    }

    @Test
    fun `ids with different uuid are not equal`() {
        assertNotEquals(UserId.random(), UserId.random())
    }

    @Test
    fun `valid uuid string round-trips to id`() {
        val uuid = java.util.UUID.randomUUID()
        val id = UserId.from(uuid.toString()).getOrThrow()
        assertEquals(UserId(uuid), id)
    }

    @Test
    fun `invalid uuid string returns typed failure`() {
        val result = UserId.from("not-a-uuid")
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected FoundryError.InvalidUuidFormat, got ${error?.javaClass}",
            error is FoundryError.InvalidUuidFormat,
        )
        error as FoundryError.InvalidUuidFormat
        assertEquals("UserId", error.idTypeName)
        assertEquals("not-a-uuid", error.rawInput)
        assertTrue(error.parseFailure is IllegalArgumentException)
    }

    @Test
    fun `empty string returns typed failure`() {
        val result = ProjectId.from("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.InvalidUuidFormat)
    }

    @Test
    fun `whitespace string returns typed failure`() {
        val result = VehicleRevisionId.from("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.InvalidUuidFormat)
    }

    @Test
    fun `all sixteen id types have valid from and random factories`() {
        // Smoke test: every ID type exposes a working from / random pair.
        val cases: List<Pair<String, () -> Result<*>>> = listOf(
            "UserId" to { UserId.from(java.util.UUID.randomUUID().toString()) },
            "ProjectId" to { ProjectId.from(java.util.UUID.randomUUID().toString()) },
            "VehicleProgramId" to { VehicleProgramId.from(java.util.UUID.randomUUID().toString()) },
            "VehicleRevisionId" to { VehicleRevisionId.from(java.util.UUID.randomUUID().toString()) },
            "ContributorId" to { ContributorId.from(java.util.UUID.randomUUID().toString()) },
            "EngineeringArtifactId" to { EngineeringArtifactId.from(java.util.UUID.randomUUID().toString()) },
            "ProvenanceRecordId" to { ProvenanceRecordId.from(java.util.UUID.randomUUID().toString()) },
            "PartId" to { PartId.from(java.util.UUID.randomUUID().toString()) },
            "VariantId" to { VariantId.from(java.util.UUID.randomUUID().toString()) },
            "CompatibilityId" to { CompatibilityId.from(java.util.UUID.randomUUID().toString()) },
            "SubsystemId" to { SubsystemId.from(java.util.UUID.randomUUID().toString()) },
            "AssemblyId" to { AssemblyId.from(java.util.UUID.randomUUID().toString()) },
            "BrandId" to { BrandId.from(java.util.UUID.randomUUID().toString()) },
            "DiagnosticId" to { DiagnosticId.from(java.util.UUID.randomUUID().toString()) },
            "FaultId" to { FaultId.from(java.util.UUID.randomUUID().toString()) },
            "RepairActionId" to { RepairActionId.from(java.util.UUID.randomUUID().toString()) },
        )
        for ((name, factory) in cases) {
            val result = factory()
            assertTrue("$name from() should succeed for a valid UUID, got $result", result.isSuccess)
        }
    }

    @Test
    fun `all sixteen id types reject malformed input`() {
        val cases: List<Pair<String, () -> Result<*>>> = listOf(
            "UserId" to { UserId.from("bad") },
            "ProjectId" to { ProjectId.from("bad") },
            "VehicleProgramId" to { VehicleProgramId.from("bad") },
            "VehicleRevisionId" to { VehicleRevisionId.from("bad") },
            "ContributorId" to { ContributorId.from("bad") },
            "EngineeringArtifactId" to { EngineeringArtifactId.from("bad") },
            "ProvenanceRecordId" to { ProvenanceRecordId.from("bad") },
            "PartId" to { PartId.from("bad") },
            "VariantId" to { VariantId.from("bad") },
            "CompatibilityId" to { CompatibilityId.from("bad") },
            "SubsystemId" to { SubsystemId.from("bad") },
            "AssemblyId" to { AssemblyId.from("bad") },
            "BrandId" to { BrandId.from("bad") },
            "DiagnosticId" to { DiagnosticId.from("bad") },
            "FaultId" to { FaultId.from("bad") },
            "RepairActionId" to { RepairActionId.from("bad") },
        )
        for ((name, factory) in cases) {
            val result = factory()
            assertTrue("$name from() should fail for malformed input, got $result", result.isFailure)
            assertTrue(
                "$name should fail with FoundryError.InvalidUuidFormat, got ${result.exceptionOrNull()?.javaClass}",
                result.exceptionOrNull() is FoundryError.InvalidUuidFormat,
            )
        }
    }
}
