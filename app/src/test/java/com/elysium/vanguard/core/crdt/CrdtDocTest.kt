package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.2 — Tests for the LWW-Element-Map CRDT.
 */
class CrdtDocTest {

    private fun ts(ms: Long, counter: Int = 0, node: String = "n1") =
        HybridLogicalClock(ms, counter, node)

    @Test
    fun `empty doc contains no keys`() {
        val doc = CrdtDoc()
        assertEquals(0, doc.size)
        assertFalse(doc.contains("k"))
    }

    @Test
    fun `set property makes the key readable`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(1), "title", "Hello"))
        assertTrue(doc.contains("title"))
        assertEquals("Hello", doc.get("title"))
        assertEquals(1, doc.size)
    }

    @Test
    fun `later set wins over earlier set on the same key`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(1), "k", "old"))
        doc.apply(CrdtOp.SetProperty(ts(2), "k", "new"))
        assertEquals("new", doc.get("k"))
    }

    @Test
    fun `earlier set does not overwrite later set`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(2), "k", "new"))
        doc.apply(CrdtOp.SetProperty(ts(1), "k", "old"))
        assertEquals("new", doc.get("k"))
    }

    @Test
    fun `delete tombstones a key`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(1), "k", "v"))
        doc.apply(CrdtOp.DeleteProperty(ts(2), "k"))
        assertFalse(doc.contains("k"))
        assertNull(doc.get("k"))
    }

    @Test
    fun `later set resurrects a deleted key`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(1), "k", "v1"))
        doc.apply(CrdtOp.DeleteProperty(ts(2), "k"))
        doc.apply(CrdtOp.SetProperty(ts(3), "k", "v2"))
        assertEquals("v2", doc.get("k"))
    }

    @Test
    fun `earlier delete is overridden by later set`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(3), "k", "v"))
        doc.apply(CrdtOp.DeleteProperty(ts(1), "k")) // stale delete
        assertEquals("v", doc.get("k"))
    }

    @Test
    fun `merge is commutative`() {
        val a = CrdtDoc()
        a.apply(CrdtOp.SetProperty(ts(1, node = "a"), "k", "a"))
        a.apply(CrdtOp.SetProperty(ts(2, node = "a"), "shared", "from-a"))

        val b = CrdtDoc()
        b.apply(CrdtOp.SetProperty(ts(1, node = "b"), "shared", "from-b"))
        b.apply(CrdtOp.SetProperty(ts(2, node = "b"), "other", "from-b"))

        val ab = a.merge(b)
        val ba = b.merge(a)
        assertEquals(ab.snapshot(), ba.snapshot())
    }

    @Test
    fun `merge is associative`() {
        val a = CrdtDoc()
        a.apply(CrdtOp.SetProperty(ts(1, node = "a"), "x", "1"))
        val b = CrdtDoc()
        b.apply(CrdtOp.SetProperty(ts(1, node = "b"), "y", "2"))
        val c = CrdtDoc()
        c.apply(CrdtOp.SetProperty(ts(1, node = "c"), "z", "3"))

        val left = a.merge(b).merge(c).snapshot()
        val right = a.merge(b.merge(c)).snapshot()
        assertEquals(left, right)
        assertEquals(setOf("x", "y", "z"), left.keys)
    }

    @Test
    fun `merge is idempotent`() {
        val a = CrdtDoc()
        a.apply(CrdtOp.SetProperty(ts(1, node = "a"), "k", "v"))
        a.apply(CrdtOp.SetProperty(ts(2, node = "a"), "shared", "from-a"))

        val once = a.merge(a)
        val twice = once.merge(a)
        assertEquals(once.snapshot(), twice.snapshot())
    }

    @Test
    fun `merge picks the higher HLC for the same key`() {
        val a = CrdtDoc()
        a.apply(CrdtOp.SetProperty(ts(1, node = "a"), "k", "from-a"))
        val b = CrdtDoc()
        b.apply(CrdtOp.SetProperty(ts(2, node = "b"), "k", "from-b"))

        val merged = a.merge(b)
        assertEquals("from-b", merged.get("k"))
    }

    @Test
    fun `merge picks the higher HLC across node ids`() {
        val a = CrdtDoc()
        a.apply(CrdtOp.SetProperty(ts(100, 5, node = "beta"), "k", "beta-late"))
        val b = CrdtDoc()
        b.apply(CrdtOp.SetProperty(ts(100, 5, node = "alpha"), "k", "alpha-early"))

        val merged = a.merge(b)
        assertEquals("beta-late", merged.get("k"))
    }

    @Test
    fun `snapshot preserves insertion order`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.SetProperty(ts(1), "c", "3"))
        doc.apply(CrdtOp.SetProperty(ts(2), "a", "1"))
        doc.apply(CrdtOp.SetProperty(ts(3), "b", "2"))
        assertEquals(listOf("c", "a", "b"), doc.snapshot().keys.toList())
    }

    @Test
    fun `delete on missing key is recorded as tombstone`() {
        val doc = CrdtDoc()
        doc.apply(CrdtOp.DeleteProperty(ts(5), "ghost"))
        assertFalse(doc.contains("ghost"))
        // debugEntries exposes the tombstone so future merges can
        // observe that this node did issue a delete.
        val debug = doc.debugEntries()
        assertNotNull(debug["ghost"])
        assertNull(debug["ghost"]!!.second)
    }
}