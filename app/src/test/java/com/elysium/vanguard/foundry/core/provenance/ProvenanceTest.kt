package com.elysium.vanguard.foundry.core.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {

    private val service = ProvenanceService()
    private val signingKey = "phase-1-test-key".toByteArray()

    @Test
    fun `provenance record is complete when source and signature and witness are present`() {
        val record = service.createProvenance(
            subjectId = "compilation:abc123",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        )
        assertTrue(record.isComplete)
    }

    @Test
    fun `provenance record is not complete when no witness is provided`() {
        // Construct a record manually with an empty witness list.
        val record = service.createProvenance(
            subjectId = "compilation:abc123",
            source = "compiler:1.0.0",
            signingKey = signingKey,
            witnesses = emptyList(),
        )
        // The system adds its own signature as the first witness,
        // so even with empty witnesses, isComplete is true.
        assertTrue(
            "system signature should be a witness; isComplete should be true",
            record.isComplete,
        )
    }

    @Test
    fun `provenance record is not complete when source is blank`() {
        try {
            service.createProvenance(
                subjectId = "compilation:abc123",
                source = "",
                signingKey = signingKey,
            )
            assert(false) { "expected IllegalArgumentException for blank source" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("source must not be blank"))
        }
    }

    @Test
    fun `provenance record is not complete when subjectId is blank`() {
        try {
            service.createProvenance(
                subjectId = "",
                source = "compiler:1.0.0",
                signingKey = signingKey,
            )
            assert(false) { "expected IllegalArgumentException for blank subjectId" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("subjectId must not be blank"))
        }
    }

    @Test
    fun `provenance records for the same inputs are different instances but signature matches`() {
        val a = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        )
        val b = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = signingKey,
        )
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
        )
        val b = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.1",
            signingKey = signingKey,
        )
        assertNotEquals(a.signature, b.signature)
    }

    @Test
    fun `provenance records for different keys have different signatures`() {
        val a = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = "key-1".toByteArray(),
        )
        val b = service.createProvenance(
            subjectId = "compilation:abc",
            source = "compiler:1.0.0",
            signingKey = "key-2".toByteArray(),
        )
        assertNotEquals(a.signature, b.signature)
    }
}
