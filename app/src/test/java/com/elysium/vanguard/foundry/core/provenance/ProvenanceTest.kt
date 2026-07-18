package com.elysium.vanguard.foundry.core.provenance

import com.elysium.vanguard.foundry.core.audit.InMemoryAuditTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {

    private val auditTrail = InMemoryAuditTrail()
    private val service = ProvenanceService(auditTrail = auditTrail)
    private val signingKey = "phase-1-test-key".toByteArray()

    @Test
    fun `provenance record is complete when source and signature and witness are present`() {
        val record = service.createProvenance(
            subjectId = "compilation:abc123",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        assertTrue(record.isComplete)
    }

    @Test
    fun `provenance record appends a signed event to the audit trail`() {
        val before = auditTrail.count()
        service.createProvenance(
            subjectId = "compilation:abc123",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        val after = auditTrail.count()
        assertEquals("audit trail should grow by 1", before + 1, after)
    }

    @Test
    fun `find by subject returns the events for a given subject`() {
        service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.1",
            signingKey = signingKey,
        ).getOrThrow()
        service.createProvenance(
            subjectId = "compilation:def",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        val abcEvents = auditTrail.findBySubject("compilation:abc")
        assertEquals(2, abcEvents.size)
    }

    @Test
    fun `provenance record is not complete when source is blank`() {
        val result = service.createProvenance(
            subjectId = "compilation:abc123",
            source = "",
            signingKey = signingKey,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `provenance record is not complete when subjectId is blank`() {
        val result = service.createProvenance(
            subjectId = "",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError.VehicleDefinitionInvalid)
    }

    @Test
    fun `provenance records for the same inputs are different instances but signature matches`() {
        val a = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        val b = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        assertNotEquals(a.id, b.id)
        // The signature depends only on the payload, not the ID.
        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `provenance records for different sources have different signatures`() {
        val a = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        ).getOrThrow()
        val b = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.1",
            signingKey = signingKey,
        ).getOrThrow()
        assertNotEquals(a.signature, b.signature)
    }

    @Test
    fun `provenance records for different keys have different signatures`() {
        val a = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = "key-1".toByteArray(),
        ).getOrThrow()
        val b = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = "key-2".toByteArray(),
        ).getOrThrow()
        assertNotEquals(a.signature, b.signature)
    }
}
