package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.1 — Tests for the hybrid logical clock.
 */
class HybridLogicalClockTest {

    @Test
    fun `compareTo orders by ms first`() {
        val a = HybridLogicalClock(100, 0, "n1")
        val b = HybridLogicalClock(200, 0, "n1")
        assertTrue(a < b)
        assertTrue(b > a)
    }

    @Test
    fun `compareTo orders by counter when ms is equal`() {
        val a = HybridLogicalClock(100, 0, "n1")
        val b = HybridLogicalClock(100, 1, "n1")
        assertTrue(a < b)
    }

    @Test
    fun `compareTo orders by nodeId as tiebreaker`() {
        val a = HybridLogicalClock(100, 0, "alpha")
        val b = HybridLogicalClock(100, 0, "beta")
        assertTrue(a < b)
    }

    @Test
    fun `serialize round-trips through parse`() {
        val ts = HybridLogicalClock(1234, 56, "node-7")
        assertEquals(ts, HybridLogicalClock.parse(ts.serialize()))
    }

    @Test
    fun `parse returns null on malformed input`() {
        assertNull(HybridLogicalClock.parse(""))
        assertNull(HybridLogicalClock.parse("garbage"))
        assertNull(HybridLogicalClock.parse("123:notanint:node"))
        assertNull(HybridLogicalClock.parse("123:4:"))
    }

    @Test
    fun `issue advances the counter when the wall clock is unchanged`() {
        val clock = HlcClock("n1")
        val a = clock.issue(1000)
        val b = clock.issue(1000)
        val c = clock.issue(1000)
        assertTrue(a < b)
        assertTrue(b < c)
        assertEquals(0, a.counter)
        assertEquals(1, b.counter)
        assertEquals(2, c.counter)
    }

    @Test
    fun `issue resets the counter when wall clock advances`() {
        val clock = HlcClock("n1")
        val a = clock.issue(1000)
        val b = clock.issue(2000)
        assertEquals(0, a.counter)
        assertEquals(0, b.counter)
        assertTrue(a < b)
    }

    @Test
    fun `observe pulls the counter ahead when remote is ahead`() {
        val local = HlcClock("local")
        local.issue(1000)
        val remote = HybridLogicalClock(1000, 5, "remote")
        val out = local.observe(remote, nowMs = 1000)
        // Result must be strictly greater than the remote.
        assertTrue(out > remote)
        assertEquals("local", out.nodeId)
    }

    @Test
    fun `observe picks max when both ms and counter collide`() {
        val local = HlcClock("local")
        local.issue(1000) // counter=0
        val remote = HybridLogicalClock(1000, 3, "remote")
        val out = local.observe(remote, nowMs = 1000)
        assertTrue(out > remote)
        assertEquals(4, out.counter)
    }

    @Test
    fun `observe with future wall clock wins on ms`() {
        val local = HlcClock("local")
        local.issue(1000)
        val remote = HybridLogicalClock(2000, 0, "remote")
        val out = local.observe(remote, nowMs = 3000)
        assertEquals(3000, out.ms)
        assertEquals(0, out.counter)
    }

    @Test
    fun `seed aligns the local clock`() {
        val clock = HlcClock("n1")
        clock.seed(HybridLogicalClock(5000, 7, "n1"))
        val a = clock.issue(5000) // same ms; counter should be 8
        assertEquals(8, a.counter)
    }

    @Test
    fun `two different nodeIds with same ms counter never compare equal`() {
        val a = HybridLogicalClock(100, 0, "alpha")
        val b = HybridLogicalClock(100, 0, "beta")
        assertNotEquals(a, b)
        assertTrue(a < b || b < a)
    }
}