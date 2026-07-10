package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.6 — Tests for the anti-entropy sync protocol.
 */
class CrdtSyncNodeTest {

    @Test
    fun `local edits are visible immediately`() {
        val alice = CrdtSyncNode("alice")
        alice.setProperty("title", "Hello")
        alice.insertSequence("A")
        assertEquals("Hello", alice.doc.get("title"))
        assertEquals("A", alice.seq.asString())
    }

    @Test
    fun `absorb applies a remote log into the local doc and seq`() {
        val alice = CrdtSyncNode("alice")
        val bob = CrdtSyncNode("bob")
        alice.setProperty("title", "Hi")
        alice.insertSequence("Hello")

        // Bob pulls alice's log.
        val absorbed = bob.absorb(alice.log)
        assertEquals(2, absorbed)
        assertEquals("Hi", bob.doc.get("title"))
        assertEquals("Hello", bob.seq.asString())
    }

    @Test
    fun `absorb is idempotent — pulling the same log twice is a no-op`() {
        val alice = CrdtSyncNode("alice")
        val bob = CrdtSyncNode("bob")
        alice.setProperty("k", "v")
        bob.absorb(alice.log)
        val secondAbsorb = bob.absorb(alice.log)
        assertEquals(0, secondAbsorb)
        assertEquals("v", bob.doc.get("k"))
    }

    @Test
    fun `entriesSince returns only entries newer than the given HLC`() {
        val alice = CrdtSyncNode("alice")
        val a1 = alice.setProperty("a", "1")
        alice.insertSequence("X")
        alice.setProperty("b", "2")
        // Entries newer than a1 are the second and third.
        val delta = alice.entriesSince(a1.hlc)
        assertEquals(2, delta.size)
    }

    @Test
    fun `entriesSince(null) returns the entire log`() {
        val alice = CrdtSyncNode("alice")
        alice.setProperty("a", "1")
        alice.insertSequence("X")
        val all = alice.entriesSince(null)
        assertEquals(2, all.size)
    }

    @Test
    fun `two nodes converge via round-trip absorb`() {
        val alice = CrdtSyncNode("alice")
        val bob = CrdtSyncNode("bob")
        alice.setProperty("title", "from-alice")
        alice.insertSequence("Hi")
        bob.setProperty("title", "from-bob")
        bob.insertSequence("Yo")

        // Each side absorbs the other's log.
        alice.absorb(bob.log)
        bob.absorb(alice.log)

        // Convergence: both nodes have the same doc + seq state.
        assertEquals(alice.doc.snapshot(), bob.doc.snapshot())
        assertEquals(alice.seq.asString(), bob.seq.asString())
        // Both inserts survived (4 chars total: 2 from each).
        assertEquals(2, alice.seq.size)
        // Doc has just "title" key.
        assertEquals(1, alice.doc.size)
    }

    @Test
    fun `concurrent deletes on the same sequence element converge`() {
        val alice = CrdtSyncNode("alice")
        val bob = CrdtSyncNode("bob")
        val h = alice.insertSequence("only").hlc
        bob.absorb(alice.log)
        alice.deleteSequence(h)
        bob.deleteSequence(h)
        alice.absorb(bob.log)
        bob.absorb(alice.log)
        assertEquals("", alice.seq.asString())
        assertEquals("", bob.seq.asString())
    }

    @Test
    fun `late-arriving node still converges after multiple round-trips`() {
        var aliceMs = 1000L
        var bobMs = 1000L
        val alice = CrdtSyncNode("alice")
        val bob = CrdtSyncNode("bob")
        val carol = CrdtSyncNode("carol")
        alice.setProperty("a", "1", nowMs = ++aliceMs)
        alice.insertSequence("alpha", nowMs = ++aliceMs)
        bob.setProperty("b", "2", nowMs = ++bobMs)
        bob.insertSequence("beta", nowMs = ++bobMs)
        carol.absorb(alice.log)
        carol.absorb(bob.log)
        alice.absorb(carol.log)
        bob.absorb(carol.log)
        alice.absorb(bob.log)
        bob.absorb(alice.log)
        // All three converge. We compare sorted entries because the
        // CRDT map preserves insertion order, which can differ
        // across nodes even when the content converges.
        val canonical = alice.doc.snapshot().toSortedMap()
        val canonicalSeq = alice.seq.asString()
        assertEquals(canonical, bob.doc.snapshot().toSortedMap())
        assertEquals(canonical, carol.doc.snapshot().toSortedMap())
        assertEquals(canonicalSeq, bob.seq.asString())
        assertEquals(canonicalSeq, carol.seq.asString())
    }

    @Test
    fun `serialized log round-trips through a transport`() {
        val alice = CrdtSyncNode("alice")
        alice.setProperty("k", "v")
        alice.insertSequence("hello")
        val serialized = alice.log.serialize()
        // Simulate transport over a wire by parsing on the other end.
        val bob = CrdtSyncNode("bob")
        val parsed = CrdtOpLog().parse(serialized)
        assertTrue(parsed != null)
        bob.absorb(parsed!!)
        assertEquals("v", bob.doc.get("k"))
        assertEquals("hello", bob.seq.asString())
    }

    @Test
    fun `lastSeen returns the highest absorbed HLC`() {
        val alice = CrdtSyncNode("alice")
        val bob = CrdtSyncNode("bob")
        alice.setProperty("a", "1")
        alice.insertSequence("X")
        bob.absorb(alice.log)
        val bobLast = bob.lastSeen()
        assertNotEquals(null, bobLast)
        // The lastSeen should be the highest HLC alice issued, since
        // bob absorbed both ops. We compare to the highest HLC among
        // alice's recorded entries (alice.lastSeen() stays null
        // because alice never absorbed anything; the comparison
        // target is alice's log).
        val aliceHighestHlc = alice.log.sortedEntries().last().hlc
        assertEquals(aliceHighestHlc, bobLast)
    }
}