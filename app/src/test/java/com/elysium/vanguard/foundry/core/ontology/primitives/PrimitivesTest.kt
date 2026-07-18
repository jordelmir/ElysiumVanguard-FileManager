package com.elysium.vanguard.foundry.core.ontology.primitives

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Unit tests for the domain primitives. These are the lowest layer of
 * the Foundry's type system; failures here are contract violations.
 */
class PrimitivesTest {

    // --- ContentHash ---

    @Test
    fun `content hash is 64 hex chars`() {
        val hash = ContentHash.of("hello".toByteArray())
        assertEquals(64, hash.value.length)
        assertTrue(hash.value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `content hash is deterministic for same bytes`() {
        val a = ContentHash.of("hello".toByteArray())
        val b = ContentHash.of("hello".toByteArray())
        assertEquals(a, b)
    }

    @Test
    fun `content hash differs for different bytes`() {
        val a = ContentHash.of("hello".toByteArray())
        val b = ContentHash.of("hellp".toByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun `content hash rejects wrong length`() {
        try {
            ContentHash("abcd")
            fail("expected IllegalArgumentException for short hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("64-character"))
        }
    }

    @Test
    fun `content hash rejects non-hex characters`() {
        val badHex = "g".repeat(64)
        try {
            ContentHash(badHex)
            fail("expected IllegalArgumentException for non-hex hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("lowercase hex"))
        }
    }

    @Test
    fun `content hash of string equals content hash of bytes`() {
        val a = ContentHash.of("Urban One")
        val b = ContentHash.of("Urban One".toByteArray(Charsets.UTF_8))
        assertEquals(a, b)
    }

    // --- RepresentationLevel ---

    @Test
    fun `representation level has six values including UNKNOWN sentinel`() {
        // The 6 values (per Phase F2 / schema):
        //   0. UNKNOWN               — sentinel for "not set"
        //   1. OEM_EXACT             — geometry validated against an OEM-shipped vehicle
        //   2. OEM_PARTIAL           — most geometry validated; some surfaces parametric
        //   3. PARAMETRIC_FUNCTIONAL — geometry defined parametrically (default)
        //   4. CONCEPTUAL            — placeholder for an engineering concept
        //   5. VISUAL_ONLY           — visual-only representation (no engineering claim)
        assertEquals(6, RepresentationLevel.values().size)
    }

    @Test
    fun `representation level preserves enum order`() {
        val levels = RepresentationLevel.values()
        assertEquals(RepresentationLevel.UNKNOWN, levels[0])
        assertEquals(RepresentationLevel.OEM_EXACT, levels[1])
        assertEquals(RepresentationLevel.OEM_PARTIAL, levels[2])
        assertEquals(RepresentationLevel.PARAMETRIC_FUNCTIONAL, levels[3])
        assertEquals(RepresentationLevel.CONCEPTUAL, levels[4])
        assertEquals(RepresentationLevel.VISUAL_ONLY, levels[5])
    }

    // --- Timestamp ---

    @Test
    fun `timestamp rejects negative epoch`() {
        try {
            Timestamp(-1L)
            fail("expected IllegalArgumentException for negative epoch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }
    }

    @Test
    fun `monotonic wall clock is monotonic under rapid calls`() {
        val source = Timestamp.monotonicWallClock()
        val a = source.now().epochMs
        val b = source.now().epochMs
        val c = source.now().epochMs
        assertTrue("expected monotonic non-decreasing, got a=$a b=$b c=$c", a <= b && b <= c)
    }

    // --- Signature ---

    @Test
    fun `signature of same payload and key is deterministic`() {
        val key = "phase-1-test-key".toByteArray()
        val a = Signature.sign("payload", key)
        val b = Signature.sign("payload", key)
        assertEquals(a, b)
    }

    @Test
    fun `signature differs for different payload`() {
        val key = "phase-1-test-key".toByteArray()
        val a = Signature.sign("payload-a", key)
        val b = Signature.sign("payload-b", key)
        assertNotEquals(a, b)
    }

    @Test
    fun `signature differs for different key`() {
        val a = Signature.sign("payload", "key-1".toByteArray())
        val b = Signature.sign("payload", "key-2".toByteArray())
        assertNotEquals(a, b)
    }

    @Test
    fun `signature rejects empty value`() {
        try {
            Signature("")
            fail("expected IllegalArgumentException for empty signature")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not be empty"))
        }
    }

    // --- CatalogRevision ---

    @Test
    fun `catalog revision accepts canonical yyyy dot mm`() {
        CatalogRevision("2026.07") // should not throw
    }

    @Test
    fun `catalog revision accepts patch suffix`() {
        CatalogRevision("2026.07.1")
    }

    @Test
    fun `catalog revision accepts pre-release suffix`() {
        CatalogRevision("2026.07-pre")
    }

    @Test
    fun `catalog revision rejects blank`() {
        try {
            CatalogRevision("")
            fail("expected IllegalArgumentException for blank")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not be blank"))
        }
    }

    @Test
    fun `catalog revision rejects malformed input`() {
        try {
            CatalogRevision("not-a-revision")
            fail("expected IllegalArgumentException for malformed revision")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must match"))
        }
    }

    // --- CompilerVersion ---

    @Test
    fun `compiler version accepts canonical semver`() {
        CompilerVersion("1.0.0")
        CompilerVersion("2.10.5")
    }

    @Test
    fun `compiler version accepts pre-release suffix`() {
        CompilerVersion("1.0.0-pre")
        CompilerVersion("2.0.0-alpha.1")
    }

    @Test
    fun `compiler version rejects blank`() {
        try {
            CompilerVersion("")
            fail("expected IllegalArgumentException for blank")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not be blank"))
        }
    }

    @Test
    fun `compiler version rejects non-semver`() {
        try {
            CompilerVersion("1.0")
            fail("expected IllegalArgumentException for non-semver")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must match"))
        }
    }

    // --- FoundryError ---

    @Test
    fun `frozen revision mutation rejected is a runtime exception`() {
        // This is required by `assertFailsWith<FrozenRevisionMutationRejected>`
        // in the integration test. The error must be a Throwable.
        val error: Throwable = FoundryError.FrozenRevisionMutationRejected(
            revisionId = com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId.random(),
        )
        assertTrue(error is FoundryError)
    }

    @Test
    fun `frozen revision mutation rejected carries the revision id`() {
        val id = com.elysium.vanguard.foundry.core.ontology.ids.VehicleRevisionId.random()
        val error = FoundryError.FrozenRevisionMutationRejected(revisionId = id)
        assertEquals(id, error.revisionId)
        assertEquals("FROZEN_REVISION_MUTATION_REJECTED", error.code)
        assertEquals(FoundryError.RetryClassification.NON_RETRYABLE, error.retryClassification)
    }

    @Test
    fun `invalid uuid format carries the id type name and raw input`() {
        val cause = IllegalArgumentException("not a UUID")
        val error = FoundryError.InvalidUuidFormat("ProjectId", "garbage", cause)
        assertEquals("ProjectId", error.idTypeName)
        assertEquals("garbage", error.rawInput)
        assertEquals(cause, error.parseFailure)
        assertEquals("INVALID_UUID_FORMAT", error.code)
    }

    // --- Unit ---

    @Test
    fun `si base unit has factor one`() {
        assertEquals(BigDecimal.ONE, Unit.METER.siConversionFactor)
        assertEquals(BigDecimal.ONE, Unit.NEWTON.siConversionFactor)
    }

    @Test
    fun `millimeter to si base scales by 0_001`() {
        val scaled = Unit.MILLIMETER.toSiBase(BigDecimal("1500"))
        // 1500 * 0.001 = 1.500 (BigDecimal preserves scale 3 on multiply).
        assertEquals(0, scaled.compareTo(BigDecimal("1.500")))
    }

    @Test
    fun `inch to si base scales by 0_0254`() {
        val scaled = Unit.INCH.toSiBase(BigDecimal("10"))
        // BigDecimal preserves the scale of the factor (4) on multiply,
        // so 10 * 0.0254 = 0.2540. Compare via compareTo for value equality.
        assertEquals(0, scaled.compareTo(BigDecimal("0.254")))
    }
}
