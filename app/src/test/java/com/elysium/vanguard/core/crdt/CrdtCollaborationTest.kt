package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.4 — Multi-node collaborative-edit scenarios.
 *
 * The CRDT correctness proofs we want to demonstrate:
 *
 *   - Two nodes independently inserting characters converge to the
 *     same text after a single round of merge.
 *   - A delete on one node is visible on the other after merge.
 *   - Concurrent inserts from many nodes all survive a merge.
 *   - Late-arriving inserts (from a node that didn't see the latest
 *     state) don't clobber newer state.
 *
 * Each test simulates a "node" as a `CrdtSequence` plus an `HlcClock`
 * so that HLCs reflect causal ordering rather than wall-clock time.
 *
 * Phase 9.9.4 — first build; intentionally minimal.
 */
class CrdtCollaborationTest {

    /**
     * Convenience: a single "node" — a sequence + its clock + a way to
     * issue inserts and deletes with proper HLCs.
     */
    private class Node(val nodeId: String) {
        val clock = HlcClock(nodeId)
        val seq = CrdtSequence()
        private var fakeNow = 1000L

        init {
            // Ensure clock starts at fakeNow so the first call works.
            clock.issue(1000L)
        }

        fun insert(value: String, atMs: Long = ++fakeNow): HybridLogicalClock {
            val hlc = clock.issue(atMs)
            seq.apply(CrdtSeqOp.Insert(hlc, value))
            return hlc
        }

        fun delete(target: HybridLogicalClock, atMs: Long = ++fakeNow): HybridLogicalClock {
            val hlc = clock.observe(target, atMs)
            seq.apply(CrdtSeqOp.Delete(hlc, target))
            return hlc
        }

        fun syncFrom(remote: Node) {
            // Observe the remote's most recent HLC (if any) so our clock
            // advances past it before applying any ops.
            val remoteHlc = remote.seq.debugLastHlc()
            if (remoteHlc != null) clock.observe(remoteHlc, ++fakeNow)
            seq.merge(remote.seq)
        }
    }

    @Test
    fun `two nodes both insert independently and converge`() {
        val alice = Node("alice")
        val bob = Node("bob")
        alice.insert("A")
        alice.insert("A2")
        bob.insert("B")
        bob.insert("B2")

        // Both nodes sync from each other.
        alice.syncFrom(bob)
        bob.syncFrom(alice)

        assertEquals(alice.seq.asString(), bob.seq.asString())
        assertEquals(4, alice.seq.size)
        assertEquals(4, bob.seq.size)
    }

    @Test
    fun `deletion on one node is visible after merge`() {
        val alice = Node("alice")
        val bob = Node("bob")
        alice.insert("hello")
        val spaceHlc = alice.insert(" ")
        alice.insert("world")
        bob.syncFrom(alice)

        // Alice deletes the space.
        alice.delete(spaceHlc)

        bob.syncFrom(alice)
        assertEquals("helloworld", alice.seq.asString())
        assertEquals("helloworld", bob.seq.asString())
    }

    @Test
    fun `concurrent edits from many nodes all survive`() {
        val nodes = (0..7).map { Node("n$it") }
        for ((i, n) in nodes.withIndex()) {
            n.insert("n$i-")
        }

        // Pairwise sync.
        for (i in nodes.indices) {
            for (j in nodes.indices) {
                if (i != j) nodes[i].syncFrom(nodes[j])
            }
        }

        val canonical = nodes.first().seq.asString()
        for (n in nodes) {
            assertEquals(canonical, n.seq.asString())
        }
        // Every node's insert is present in the merged sequence.
        for (i in nodes.indices) {
            assertTrue("missing n$i- in $canonical", canonical.contains("n$i-"))
        }
    }

    @Test
    fun `late-arriving inserts from a stale node don't clobber newer state`() {
        val alice = Node("alice")
        val bob = Node("bob")
        val carol = Node("carol")
        alice.insert("v1")
        bob.syncFrom(alice)
        carol.syncFrom(alice)

        // Alice moves on to v2; Carol knows about v1 but Alice doesn't
        // know about Carol's hypothetical late insert.
        alice.insert("v2")
        carol.insert("carol-was-here")

        // Carol syncs from Alice (gets v2).
        carol.syncFrom(alice)
        // Alice syncs from Carol (gets Carol's late insert + v2).
        alice.syncFrom(carol)

        val aliceText = alice.seq.asString()
        val carolText = carol.seq.asString()
        assertEquals(aliceText, carolText)
        assertTrue(aliceText.contains("v1"))
        assertTrue(aliceText.contains("v2"))
        assertTrue(aliceText.contains("carol-was-here"))
    }

    @Test
    fun `concurrent delete of the same element converges to deleted`() {
        val alice = Node("alice")
        val bob = Node("bob")
        val h = alice.insert("only")
        bob.syncFrom(alice)

        // Both nodes delete the same element concurrently.
        alice.delete(h)
        bob.delete(h)

        alice.syncFrom(bob)
        bob.syncFrom(alice)

        assertEquals("", alice.seq.asString())
        assertEquals("", bob.seq.asString())
    }

    @Test
    fun `three-way merge converges to a single state`() {
        val alice = Node("alice")
        val bob = Node("bob")
        val carol = Node("carol")
        alice.insert("a1")
        alice.insert("a2")
        bob.insert("b1")
        bob.insert("b2")
        carol.insert("c1")
        carol.insert("c2")

        alice.syncFrom(bob)
        alice.syncFrom(carol)
        bob.syncFrom(alice)
        bob.syncFrom(carol)
        carol.syncFrom(alice)
        carol.syncFrom(bob)

        val canonical = alice.seq.asString()
        assertEquals(canonical, bob.seq.asString())
        assertEquals(canonical, carol.seq.asString())
        assertEquals(6, alice.seq.size)
    }

    @Test
    fun `repeated sync of the same source is idempotent`() {
        val alice = Node("alice")
        val bob = Node("bob")
        alice.insert("1")
        alice.insert("2")
        bob.syncFrom(alice)
        val firstSync = bob.seq.asString()

        // Sync 100 more times.
        for (i in 0 until 100) {
            bob.syncFrom(alice)
        }
        assertEquals(firstSync, bob.seq.asString())
        assertEquals(2, bob.seq.size)
    }
}

/**
 * Internal accessor used only by the test helpers. The production
 * code already exposes [CrdtSequence.hlcAt]; this is just the
 * "highest HLC" lookup needed to advance the local clock when syncing.
 */
private fun CrdtSequence.debugLastHlc(): HybridLogicalClock? {
    var highest: HybridLogicalClock? = null
    for (i in 0 until this.size) {
        val hlc = this.hlcAt(i) ?: break
        if (highest == null || hlc > highest) highest = hlc
    }
    return highest
}