package com.elysium.vanguard.foundry.core.audit

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AuditTrailTest {

    private val trail = InMemoryAuditTrail()
    private val now = Timestamp(1_700_000_000_000L)

    private fun sampleEvent(
        id: String = UUID.randomUUID().toString(),
        subjectId: String = "compilation:abc",
        eventType: String = "provenance.appended",
    ): SignedEvent = SignedEvent(
        id = id,
        eventType = eventType,
        subjectId = subjectId,
        payload = SignedEventPayload.ProvenanceAppended(
            provenanceSubjectId = subjectId,
            provenanceContentHash = "hash-$id",
            source = "compiler:1.0.0",
        ),
        signature = Signature.sign(id, "key".toByteArray()),
        contentHash = ContentHash.of(id),
        createdAt = now,
    )

    @Test
    fun `append returns success and grows the trail`() {
        val before = trail.count()
        val result = trail.append(sampleEvent())
        assertTrue(result.isSuccess)
        assertEquals(before + 1, trail.count())
    }

    @Test
    fun `append is append-only — duplicate id is rejected`() {
        val event = sampleEvent()
        val first = trail.append(event)
        assertTrue(first.isSuccess)
        val second = trail.append(event)
        assertTrue("expected failure, got $second", second.isFailure)
        val error = second.exceptionOrNull()
        assertTrue(
            "expected VehicleDefinitionInvalid, got ${error?.javaClass}",
            error is FoundryError.VehicleDefinitionInvalid,
        )
        error as FoundryError.VehicleDefinitionInvalid
        assertEquals("AuditTrail.events", error.field)
    }

    @Test
    fun `findBySubject returns the events for a given subject`() {
        trail.append(sampleEvent(subjectId = "compilation:abc"))
        trail.append(sampleEvent(subjectId = "compilation:abc"))
        trail.append(sampleEvent(subjectId = "compilation:def"))
        val abc = trail.findBySubject("compilation:abc")
        assertEquals(2, abc.size)
        val def = trail.findBySubject("compilation:def")
        assertEquals(1, def.size)
    }

    @Test
    fun `findBySubject returns empty list for unknown subject`() {
        trail.append(sampleEvent(subjectId = "compilation:abc"))
        val unknown = trail.findBySubject("compilation:unknown")
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun `events preserve insertion order`() {
        val a = trail.append(sampleEvent(id = "id-a")).getOrThrow()
        val b = trail.append(sampleEvent(id = "id-b")).getOrThrow()
        val c = trail.append(sampleEvent(id = "id-c")).getOrThrow()
        val events = trail.findBySubject("compilation:abc")
        assertEquals(listOf(a, b, c), events)
    }

    @Test
    fun `signed event payload is typed — no free form strings`() {
        val event = sampleEvent()
        val payload = event.payload
        assertTrue(
            "expected ProvenanceAppended, got ${payload?.javaClass}",
            payload is SignedEventPayload.ProvenanceAppended,
        )
        payload as SignedEventPayload.ProvenanceAppended
        assertEquals("compilation:abc", payload.provenanceSubjectId)
        assertEquals("compiler:1.0.0", payload.source)
    }

    @Test
    fun `signed event rejects blank id at construction`() {
        try {
            sampleEvent(id = "")
            assert(false) { "expected IllegalArgumentException for blank id" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("id must not be blank"))
        }
    }

    @Test
    fun `signed event rejects blank event type at construction`() {
        try {
            sampleEvent(eventType = "")
            assert(false) { "expected IllegalArgumentException for blank eventType" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("eventType must not be blank"))
        }
    }

    @Test
    fun `signed event rejects blank subject id at construction`() {
        try {
            sampleEvent(subjectId = "")
            assert(false) { "expected IllegalArgumentException for blank subjectId" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("subjectId must not be blank"))
        }
    }

    @Test
    fun `two trails are independent`() {
        val a = InMemoryAuditTrail()
        val b = InMemoryAuditTrail()
        a.append(sampleEvent(subjectId = "s1"))
        b.append(sampleEvent(subjectId = "s2"))
        assertEquals(1, a.count())
        assertEquals(1, b.count())
        assertNotEquals(a.findBySubject("s1"), b.findBySubject("s2"))
    }
}
