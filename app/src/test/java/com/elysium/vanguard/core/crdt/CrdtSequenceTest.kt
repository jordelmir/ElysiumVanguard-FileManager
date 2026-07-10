package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.3 — Tests for the tombstone-sequence CRDT.
 */
class CrdtSequenceTest {

    private fun ts(ms: Long, counter: Int = 0, node: String = "n1") =
        HybridLogicalClock(ms, counter, node)

    @Test
    fun `empty sequence has no live elements`() {
        val seq = CrdtSequence()
        assertEquals(0, seq.size)
        assertEquals(emptyList<String>(), seq.value())
        assertEquals("", seq.asString())
    }

    @Test
    fun `insert adds an element in causal order`() {
        val seq = CrdtSequence()
        seq.apply(CrdtSeqOp.Insert(ts(1, node = "a"), "H"))
        seq.apply(CrdtSeqOp.Insert(ts(2, node = "a"), "i"))
        assertEquals(listOf("H", "i"), seq.value())
        assertEquals("Hi", seq.asString())
    }

    @Test
    fun `inserts from different nodes converge to one order`() {
        val a = CrdtSequence()
        a.apply(CrdtSeqOp.Insert(ts(1, node = "a"), "A"))
        val b = CrdtSequence()
        b.apply(CrdtSeqOp.Insert(ts(2, node = "b"), "B"))
        val merged = a.merge(b)
        assertEquals(listOf("A", "B"), merged.value())
    }

    @Test
    fun `delete tombstones a slot`() {
        val seq = CrdtSequence()
        seq.apply(CrdtSeqOp.Insert(ts(1), "a"))
        seq.apply(CrdtSeqOp.Insert(ts(2), "b"))
        seq.apply(CrdtSeqOp.Insert(ts(3), "c"))
        val middleHlc = seq.hlcAt(1)
        assertNotNull(middleHlc)
        seq.apply(CrdtSeqOp.Delete(ts(4), middleHlc!!))
        assertEquals(listOf("a", "c"), seq.value())
        assertEquals(2, seq.size)
        assertEquals(3, seq.totalSlots)
    }

    @Test
    fun `merge is commutative`() {
        val a = CrdtSequence()
        a.apply(CrdtSeqOp.Insert(ts(1, node = "a"), "1"))
        a.apply(CrdtSeqOp.Insert(ts(2, node = "a"), "2"))
        val b = CrdtSequence()
        b.apply(CrdtSeqOp.Insert(ts(1, node = "b"), "3"))
        b.apply(CrdtSeqOp.Insert(ts(2, node = "b"), "4"))
        val ab = a.merge(b)
        val ba = b.merge(a)
        assertEquals(ab.value(), ba.value())
        // The merged sequence is sorted by HLC: (1,a) < (1,b) < (2,a) < (2,b).
        // Hence the live order is the lex-sort of the HLCs, which for
        // "1,2,3,4" insertion-pair "1" → "3" → "2" → "4".
        assertEquals(listOf("1", "3", "2", "4"), ab.value())
    }

    @Test
    fun `merge is associative`() {
        val a = CrdtSequence()
        a.apply(CrdtSeqOp.Insert(ts(1, node = "a"), "x"))
        val b = CrdtSequence()
        b.apply(CrdtSeqOp.Insert(ts(1, node = "b"), "y"))
        val c = CrdtSequence()
        c.apply(CrdtSeqOp.Insert(ts(1, node = "c"), "z"))
        val left = a.merge(b).merge(c).value()
        val right = a.merge(b.merge(c)).value()
        assertEquals(left, right)
    }

    @Test
    fun `merge is idempotent`() {
        val a = CrdtSequence()
        a.apply(CrdtSeqOp.Insert(ts(1), "x"))
        a.apply(CrdtSeqOp.Insert(ts(2), "y"))
        val once = a.merge(a)
        val twice = once.merge(a)
        assertEquals(once.value(), twice.value())
    }

    @Test
    fun `delete propagates through merge`() {
        val a = CrdtSequence()
        a.apply(CrdtSeqOp.Insert(ts(1, node = "a"), "hello"))
        val targetHlc = a.hlcAt(0)!!
        a.apply(CrdtSeqOp.Delete(ts(2, node = "a"), targetHlc))

        val b = CrdtSequence()
        // b is unaware of the delete; it has the original insert.
        b.apply(CrdtSeqOp.Insert(ts(1, node = "b"), "world"))

        val merged = a.merge(b)
        // The delete (HLC ts2 from a) wins over the remote insert (HLC ts1 from b),
        // so the live list is just the b insert.
        assertEquals(listOf("world"), merged.value())
    }

    @Test
    fun `concurrent edits from two nodes keep both inserts`() {
        val a = CrdtSequence()
        a.apply(CrdtSeqOp.Insert(ts(1, node = "a"), "fromA"))
        val b = CrdtSequence()
        b.apply(CrdtSeqOp.Insert(ts(1, node = "b"), "fromB"))
        val merged = a.merge(b)
        assertEquals(2, merged.size)
        assertTrue(merged.value().contains("fromA"))
        assertTrue(merged.value().contains("fromB"))
    }

    @Test
    fun `hlcAt returns the correct HLC for each index`() {
        val seq = CrdtSequence()
        val a = ts(1, node = "a")
        val b = ts(2, node = "a")
        val c = ts(3, node = "a")
        seq.apply(CrdtSeqOp.Insert(a, "first"))
        seq.apply(CrdtSeqOp.Insert(b, "second"))
        seq.apply(CrdtSeqOp.Insert(c, "third"))
        assertEquals(a, seq.hlcAt(0))
        assertEquals(b, seq.hlcAt(1))
        assertEquals(c, seq.hlcAt(2))
        assertNull(seq.hlcAt(3))
    }

    @Test
    fun `hlcAt skips tombstoned elements`() {
        val seq = CrdtSequence()
        val a = ts(1)
        val b = ts(2)
        val c = ts(3)
        seq.apply(CrdtSeqOp.Insert(a, "A"))
        seq.apply(CrdtSeqOp.Insert(b, "B"))
        seq.apply(CrdtSeqOp.Insert(c, "C"))
        seq.apply(CrdtSeqOp.Delete(ts(4), b))
        assertEquals(a, seq.hlcAt(0))
        assertEquals(c, seq.hlcAt(1))
        assertNull(seq.hlcAt(2))
    }

    @Test
    fun `inserting with the same HLC is idempotent`() {
        val seq = CrdtSequence()
        val hlc = ts(42, node = "node-xyz")
        seq.apply(CrdtSeqOp.Insert(hlc, "first"))
        seq.apply(CrdtSeqOp.Insert(hlc, "duplicate"))
        // The second insert with the same HLC is a no-op.
        assertEquals(listOf("first"), seq.value())
        assertEquals(1, seq.size)
    }
}