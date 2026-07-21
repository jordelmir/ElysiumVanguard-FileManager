package com.elysium.vanguard.core.runtime.distros.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 102 — JVM tests for [CgroupSpec] invariants + the
 * `controllerList()` derivation. No Android imports.
 */
class CgroupSpecTest {

    @Test
    fun `controllerList is empty when all fields are null`() {
        assertEquals("", CgroupSpec.NONE.controllerList())
        assertTrue(CgroupSpec.NONE.isEmpty)
    }

    @Test
    fun `controllerList activates the four supported controllers in canonical order`() {
        // We construct a spec with all four non-null fields;
        // the order in the list should be cpu,memory,io,pids
        // regardless of which order the test set them.
        val spec = CgroupSpec(
            pidsMax = 256,
            ioWeight = 200,
            memoryMaxBytes = 1_000_000_000L,
            cpuWeight = 500,
        )
        assertEquals("cpu,memory,io,pids", spec.controllerList())
    }

    @Test
    fun `controllerList includes only the controllers that have a non-null field`() {
        val spec = CgroupSpec(cpuWeight = 500, pidsMax = 256)
        assertEquals("cpu,pids", spec.controllerList())
    }

    @Test
    fun `controllerList includes memory when EITHER high or max is set`() {
        val onlyHigh = CgroupSpec(memoryHighBytes = 1_000_000L)
        val onlyMax = CgroupSpec(memoryMaxBytes = 1_000_000L)
        assertEquals("memory", onlyHigh.controllerList())
        assertEquals("memory", onlyMax.controllerList())
    }

    @Test
    fun `cpuWeight out of range throws at construction time`() {
        try {
            CgroupSpec(cpuWeight = 0)
            org.junit.Assert.fail("expected IllegalArgumentException for cpuWeight=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cpuWeight"))
        }
        try {
            CgroupSpec(cpuWeight = 10_001)
            org.junit.Assert.fail("expected IllegalArgumentException for cpuWeight=10001")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cpuWeight"))
        }
    }

    @Test
    fun `ioWeight out of range throws at construction time`() {
        try {
            CgroupSpec(ioWeight = -1)
            org.junit.Assert.fail("expected IllegalArgumentException for ioWeight=-1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ioWeight"))
        }
    }

    @Test
    fun `negative memory values throw at construction time`() {
        try {
            CgroupSpec(memoryHighBytes = -1L)
            org.junit.Assert.fail("expected IllegalArgumentException for negative memoryHighBytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memoryHighBytes"))
        }
        try {
            CgroupSpec(memoryMaxBytes = -1L)
            org.junit.Assert.fail("expected IllegalArgumentException for negative memoryMaxBytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memoryMaxBytes"))
        }
    }

    @Test
    fun `pidsMax zero or negative throws at construction time`() {
        try {
            CgroupSpec(pidsMax = 0)
            org.junit.Assert.fail("expected IllegalArgumentException for pidsMax=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pidsMax"))
        }
    }

    @Test
    fun `memoryHighBytes must be less than or equal to memoryMaxBytes`() {
        // Equal is allowed (the high can be exactly the max).
        CgroupSpec(memoryHighBytes = 1_000_000L, memoryMaxBytes = 1_000_000L)
        // High < max is allowed.
        CgroupSpec(memoryHighBytes = 1_000_000L, memoryMaxBytes = 2_000_000L)
        // High > max is not.
        try {
            CgroupSpec(memoryHighBytes = 2_000_000L, memoryMaxBytes = 1_000_000L)
            org.junit.Assert.fail("expected IllegalArgumentException for high > max")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memoryHighBytes"))
        }
    }

    @Test
    fun `BACKGROUND preset is non-empty and well-formed`() {
        val bg = CgroupSpec.BACKGROUND
        assertFalse(bg.isEmpty)
        assertEquals("cpu,memory,io,pids", bg.controllerList())
        // Sanity-check the actual values
        assertEquals(100, bg.cpuWeight)
        assertEquals(1_610_612_736L, bg.memoryHighBytes)
        assertEquals(2_147_483_648L, bg.memoryMaxBytes)
        assertEquals(100, bg.ioWeight)
        assertEquals(256, bg.pidsMax)
    }

    @Test
    fun `NONE preset has no controllers`() {
        assertTrue(CgroupSpec.NONE.isEmpty)
        assertEquals("", CgroupSpec.NONE.controllerList())
    }
}
