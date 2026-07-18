package com.elysium.vanguard.foundry.core.concurrency

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticConcurrencyTest {

    @Test
    fun `check returns null when expected matches actual`() {
        val result = OptimisticConcurrency.check(
            aggregateType = "Project",
            aggregateId = "00000000-0000-0000-0000-000000000001",
            expectedVersion = 0L,
            actualVersion = 0L,
        )
        assertNull("expected null on success, got $result", result)
    }

    @Test
    fun `check returns RevisionConflict when expected differs from actual`() {
        val result = OptimisticConcurrency.check(
            aggregateType = "Project",
            aggregateId = "00000000-0000-0000-0000-000000000001",
            expectedVersion = 0L,
            actualVersion = 1L,
        )
        assertNotNull("expected RevisionConflict, got null", result)
        assertTrue(
            "expected RevisionConflict, got ${result?.javaClass}",
            result is FoundryError.RevisionConflict,
        )
        result as FoundryError.RevisionConflict
        assertEquals("Project", result.aggregateType)
        assertEquals(0L, result.expectedVersion)
        assertEquals(1L, result.actualVersion)
    }

    @Test
    fun `check returns RevisionConflict when expected is ahead of actual`() {
        val result = OptimisticConcurrency.check(
            aggregateType = "VehicleProgram",
            aggregateId = "00000000-0000-0000-0000-000000000002",
            expectedVersion = 5L,
            actualVersion = 3L,
        )
        assertNotNull(result)
        assertTrue(result is FoundryError.RevisionConflict)
        result as FoundryError.RevisionConflict
        assertEquals(5L, result.expectedVersion)
        assertEquals(3L, result.actualVersion)
        assertEquals("REVISION_CONFLICT", result.code)
    }

    @Test
    fun `check returns RevisionConflict when expected is behind actual`() {
        val result = OptimisticConcurrency.check(
            aggregateType = "Contributor",
            aggregateId = "00000000-0000-0000-0000-000000000003",
            expectedVersion = 2L,
            actualVersion = 5L,
        )
        assertNotNull(result)
        assertTrue(result is FoundryError.RevisionConflict)
        result as FoundryError.RevisionConflict
        assertEquals(2L, result.expectedVersion)
        assertEquals(5L, result.actualVersion)
    }

    @Test
    fun `RevisionConflict has retry classification retryable idempotent only`() {
        val result = OptimisticConcurrency.check(
            aggregateType = "X",
            aggregateId = "y",
            expectedVersion = 1L,
            actualVersion = 0L,
        )
        assertNotNull(result)
        assertEquals(
            FoundryError.RetryClassification.RETRYABLE_IDEMPOTENT_ONLY,
            result!!.retryClassification,
        )
    }
}
