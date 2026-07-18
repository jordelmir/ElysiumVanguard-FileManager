package com.elysium.vanguard.foundry.core.artifact

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineeringArtifactServiceTest {

    private val service = EngineeringArtifactService()
    private val sampleHash = ContentHash.of("phase-1-test")

    @Test
    fun `create artifact with valid inputs returns success`() {
        val result = service.createArtifact(
            contentHash = sampleHash,
            format = EngineeringArtifactFormat.GLB,
            sizeBytes = 1024,
            subjectId = "compilation:abc",
        )
        assertTrue(result.isSuccess)
        val artifact = result.getOrThrow()
        assertEquals(sampleHash, artifact.contentHash)
        assertEquals(EngineeringArtifactFormat.GLB, artifact.format)
        assertEquals(1024L, artifact.sizeBytes)
        assertEquals("compilation:abc", artifact.subjectId)
        assertEquals(0L, artifact.version)
    }

    @Test
    fun `create artifact with negative size returns typed failure`() {
        val result = service.createArtifact(
            contentHash = sampleHash,
            format = EngineeringArtifactFormat.GLB,
            sizeBytes = -1,
            subjectId = "compilation:abc",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `create artifact with blank subject returns typed failure`() {
        val result = service.createArtifact(
            contentHash = sampleHash,
            format = EngineeringArtifactFormat.GLB,
            sizeBytes = 1024,
            subjectId = "",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `reassign subject changes subject and increments version`() {
        val artifact = service.createArtifact(
            contentHash = sampleHash,
            format = EngineeringArtifactFormat.GLB,
            sizeBytes = 1024,
            subjectId = "compilation:abc",
        ).getOrThrow()
        val updated = service.reassignSubject(
            artifact = artifact,
            newSubjectId = "compilation:def",
            expectedVersion = 0L,
        ).getOrThrow()
        assertEquals("compilation:def", updated.subjectId)
        assertEquals(1L, updated.version)
    }

    @Test
    fun `reassign subject with stale version raises RevisionConflict`() {
        val artifact = service.createArtifact(
            contentHash = sampleHash,
            format = EngineeringArtifactFormat.GLB,
            sizeBytes = 1024,
            subjectId = "compilation:abc",
        ).getOrThrow()
        val first = service.reassignSubject(artifact, "compilation:def", expectedVersion = 0L).getOrThrow()
        val conflict = service.reassignSubject(first, "compilation:ghi", expectedVersion = 0L)
        assertTrue(conflict.isFailure)
        val error = conflict.exceptionOrNull()
        assertTrue(
            "expected RevisionConflict, got ${error?.javaClass}",
            error is FoundryError.RevisionConflict,
        )
        error as FoundryError.RevisionConflict
        assertEquals("EngineeringArtifact", error.aggregateType)
    }

    @Test
    fun `reassign subject to same value returns typed failure`() {
        val artifact = service.createArtifact(
            contentHash = sampleHash,
            format = EngineeringArtifactFormat.GLB,
            sizeBytes = 1024,
            subjectId = "compilation:abc",
        ).getOrThrow()
        val result = service.reassignSubject(artifact, "compilation:abc", expectedVersion = 0L)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `engineering artifact format enum has 10 values`() {
        assertEquals(10, EngineeringArtifactFormat.values().size)
    }
}
