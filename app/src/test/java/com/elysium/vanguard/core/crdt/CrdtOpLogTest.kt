package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 9.9.5 — Tests for the CRDT op log + replay engine.
 */
class CrdtOpLogTest {

    private fun ts(ms: Long, counter: Int = 0, node: String = "n1") =
        HybridLogicalClock(ms, counter, node)

    @Test
    fun `empty log replays to empty state`() {
        val log = CrdtOpLog()
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        log.replay(doc, seq)
        assertEquals(0, doc.size)
        assertEquals(0, seq.size)
    }

    @Test
    fun `DSET op replays into the doc`() {
        val log = CrdtOpLog()
        log.record(CrdtOp.SetProperty(ts(1), "title", "Hello"))
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        log.replay(doc, seq)
        assertEquals("Hello", doc.get("title"))
    }

    @Test
    fun `DDEL op replays into the doc`() {
        val log = CrdtOpLog()
        log.record(CrdtOp.SetProperty(ts(1), "k", "v"))
        log.record(CrdtOp.DeleteProperty(ts(2), "k"))
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        log.replay(doc, seq)
        assertEquals(null, doc.get("k"))
    }

    @Test
    fun `SINS op replays into the sequence`() {
        val log = CrdtOpLog()
        log.record(CrdtSeqOp.Insert(ts(1), "H"))
        log.record(CrdtSeqOp.Insert(ts(2), "i"))
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        log.replay(doc, seq)
        assertEquals("Hi", seq.asString())
    }

    @Test
    fun `SDEL op replays into the sequence`() {
        val log = CrdtOpLog()
        val a = ts(1)
        val b = ts(2)
        log.record(CrdtSeqOp.Insert(a, "A"))
        log.record(CrdtSeqOp.Insert(b, "B"))
        log.record(CrdtSeqOp.Delete(ts(3), a))
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        log.replay(doc, seq)
        assertEquals("B", seq.asString())
    }

    @Test
    fun `serialize round-trips through parse`() {
        val log = CrdtOpLog()
        log.record(CrdtOp.SetProperty(ts(1), "title", "Hello"))
        log.record(CrdtSeqOp.Insert(ts(2), "X"))
        log.record(CrdtSeqOp.Insert(ts(3), "Y"))
        val text = log.serialize()
        val parsed = CrdtOpLog().parse(text)
        assertNotNull(parsed)
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        parsed!!.replay(doc, seq)
        assertEquals("Hello", doc.get("title"))
        assertEquals("XY", seq.asString())
    }

    @Test
    fun `replay is idempotent`() {
        val log = CrdtOpLog()
        log.record(CrdtOp.SetProperty(ts(1), "k", "v"))
        log.record(CrdtSeqOp.Insert(ts(2), "x"))
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        log.replay(doc, seq)
        val snap1doc = doc.snapshot()
        val snap1seq = seq.asString()
        log.replay(doc, seq)
        assertEquals(snap1doc, doc.snapshot())
        assertEquals(snap1seq, seq.asString())
    }

    @Test
    fun `parse returns null on malformed input`() {
        // Empty input is a valid empty log, not malformed.
        assertNotNull(CrdtOpLog().parse(""))
        // Truly malformed input is rejected.
        assertNull(CrdtOpLog().parse("garbage"))
        assertNull(CrdtOpLog().parse("DSET badhlc k v"))
        assertNull(CrdtOpLog().parse("UNKNOWN 1:0:n1 x"))
    }

    @Test
    fun `serialize sorts lines by HLC`() {
        val log = CrdtOpLog()
        // Insert in reverse-HLC order on purpose.
        log.record(CrdtSeqOp.Insert(ts(3), "c"))
        log.record(CrdtSeqOp.Insert(ts(1), "a"))
        log.record(CrdtSeqOp.Insert(ts(2), "b"))
        val text = log.serialize()
        // First SINS line should be HLC 1, second 2, third 3.
        val lines = text.split('\n').filter { it.isNotEmpty() }
        assertTrue(lines[0].contains("1:0:n1"))
        assertTrue(lines[1].contains("2:0:n1"))
        assertTrue(lines[2].contains("3:0:n1"))
    }

    @Test
    fun `escape and unescape preserve newlines in values`() {
        val log = CrdtOpLog()
        log.record(CrdtOp.SetProperty(ts(1), "k", "line1\nline2"))
        val text = log.serialize()
        val parsed = CrdtOpLog().parse(text)
        assertNotNull(parsed)
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        parsed!!.replay(doc, seq)
        assertTrue(doc.get("k")!!.contains('\n'))
    }

    @Test
    fun `merge dedups identical entries`() {
        val a = CrdtOpLog()
        a.record(CrdtOp.SetProperty(ts(1), "k", "v"))
        val b = CrdtOpLog()
        b.record(CrdtOp.SetProperty(ts(1), "k", "v")) // same op
        b.record(CrdtSeqOp.Insert(ts(2), "x"))
        val merged = a.merge(b)
        assertEquals(2, merged.size)
    }

    @Test
    fun `replay a merged log from two nodes converges`() {
        // Alice's log
        val alice = CrdtOpLog()
        alice.record(CrdtOp.SetProperty(ts(1, node = "a"), "shared", "from-a"))
        alice.record(CrdtSeqOp.Insert(ts(2, node = "a"), "A1"))
        // Bob's log
        val bob = CrdtOpLog()
        bob.record(CrdtOp.SetProperty(ts(1, node = "b"), "shared", "from-b"))
        bob.record(CrdtSeqOp.Insert(ts(2, node = "b"), "B1"))
        // Merge
        val merged = alice.merge(bob)
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        merged.replay(doc, seq)
        // The doc's "shared" should be the higher-HLC entry (b's
        // (1,b) wins over (1,a) by nodeId tiebreaker... wait, both
        // are HLC (1,0); tiebreaker is nodeId; "b" > "a", so b wins).
        assertEquals("from-b", doc.get("shared"))
        // The sequence has both A1 and B1.
        assertEquals(2, seq.size)
        assertTrue(seq.value().contains("A1"))
        assertTrue(seq.value().contains("B1"))
    }
}