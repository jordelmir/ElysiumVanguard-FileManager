package com.elysium.vanguard.core.crdt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PHASE 9.11 — Tests for the companion sync file format.
 */
class ElysiumSyncFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `empty sync file has an empty log`() {
        val docFile = File(tmp.root, "notes.elysium.word")
        val sync = ElysiumSyncFile.empty(docFile, "alice")
        assertEquals(0, sync.log.size)
        assertNull(sync.lastSeen)
    }

    @Test
    fun `companion file path uses document name + node id`() {
        val docFile = File(tmp.root, "notes.elysium.word")
        val sync = ElysiumSyncFile.empty(docFile, "alice")
        val cf = sync.companionFile()
        assertEquals("notes.elysium.word.alice.elysium.sync", cf.name)
    }

    @Test
    fun `save then read round-trips the log`() {
        val docFile = File(tmp.root, "notes.elysium.word")
        val sync = ElysiumSyncFile.empty(docFile, "alice")
        sync.log.record(CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "alice"), "title", "Notes"))
        sync.log.record(CrdtSeqOp.Insert(HybridLogicalClock(1001, 0, "alice"), "H"))
        sync.lastSeen = HybridLogicalClock(1001, 0, "alice")
        sync.save()

        val reloaded = ElysiumSyncFile.readFor(docFile, "alice")
        assertNotNull(reloaded)
        assertEquals(2, reloaded!!.log.size)
        assertEquals(HybridLogicalClock(1001, 0, "alice"), reloaded.lastSeen)
        assertEquals("alice", reloaded.nodeId)
    }

    @Test
    fun `read returns null when the companion file does not exist`() {
        val docFile = File(tmp.root, "notes.elysium.word")
        assertNull(ElysiumSyncFile.readFor(docFile, "alice"))
    }

    @Test
    fun `parse handles missing lastSeen line`() {
        val text = """
            # Elysium sync log
            # node: alice
            DSET 1000:0:alice title Notes
        """.trimIndent()
        val parsed = ElysiumSyncFile.parse(text, File("dummy"))
        assertNotNull(parsed)
        assertEquals("alice", parsed!!.nodeId)
        assertNull(parsed.lastSeen)
        assertEquals(1, parsed.log.size)
    }

    @Test
    fun `parse handles explicit null lastSeen`() {
        val text = """
            # Elysium sync log
            # node: bob
            # lastSeen: null
        """.trimIndent()
        val parsed = ElysiumSyncFile.parse(text, File("dummy"))
        assertNotNull(parsed)
        assertEquals("bob", parsed!!.nodeId)
        assertNull(parsed.lastSeen)
        assertEquals(0, parsed.log.size)
    }

    @Test
    fun `two nodes with separate companion files keep independent logs`() {
        val docFile = File(tmp.root, "notes.elysium.word")
        // Alice writes her log.
        val aliceSync = ElysiumSyncFile.empty(docFile, "alice")
        aliceSync.log.record(
            CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "alice"), "title", "From Alice")
        )
        aliceSync.save()
        // Bob writes his log to a separate companion file.
        val bobSync = ElysiumSyncFile.empty(docFile, "bob")
        bobSync.log.record(
            CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "bob"), "title", "From Bob")
        )
        bobSync.save()

        // Both companion files coexist.
        val aliceReloaded = ElysiumSyncFile.readFor(docFile, "alice")!!
        val bobReloaded = ElysiumSyncFile.readFor(docFile, "bob")!!
        assertEquals("From Alice", runOpSetGet(aliceReloaded))
        assertEquals("From Bob", runOpSetGet(bobReloaded))
    }

    @Test
    fun `serialize output starts with a comment header`() {
        val docFile = File(tmp.root, "notes.elysium.word")
        val sync = ElysiumSyncFile.empty(docFile, "alice")
        sync.log.record(
            CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "alice"), "k", "v")
        )
        val text = sync.serialize()
        assertTrue("expected comment header: $text", text.startsWith("# Elysium sync log"))
        assertTrue("expected node line: $text", text.contains("# node: alice"))
    }

    @Test
    fun `absorbed remote log is persisted to companion file`() {
        val docFile = File(tmp.root, "shared.elysium.word")
        val aliceSync = ElysiumSyncFile.empty(docFile, "alice")
        aliceSync.log.record(
            CrdtOp.SetProperty(HybridLogicalClock(1000, 0, "alice"), "k", "from-alice")
        )
        aliceSync.save()

        val bobSync = ElysiumSyncFile.readFor(docFile, "alice")!!
        val bobLocalSync = ElysiumSyncFile.empty(docFile, "bob")
        bobLocalSync.log.merge(bobSync.log)
        bobLocalSync.lastSeen = HybridLogicalClock(1000, 0, "alice")
        bobLocalSync.save()
        // Direct file inspection: read the companion file directly.
        val rawBobCompanion = File(docFile.parentFile, "${docFile.name}.bob.elysium.sync")
        println("DEBUG rawBobCompanion exists=${rawBobCompanion.exists()}, size=${rawBobCompanion.length()}")
        val bytes = rawBobCompanion.readBytes()
        println("DEBUG rawBobCompanion bytes length=${bytes.size}")
        println("DEBUG rawBobCompanion bytes hex = ${bytes.joinToString("") { "%02x".format(it) }}")

        // Bob's companion file now contains alice's op.
        val bobReloaded = ElysiumSyncFile.readFor(docFile, "bob")!!
        println("DEBUG bobReloaded.log.size = ${bobReloaded.log.size}")
        println("DEBUG bobReloaded.lastSeen = ${bobReloaded.lastSeen}")
        assertEquals(1, bobReloaded.log.size)
        assertEquals(HybridLogicalClock(1000, 0, "alice"), bobReloaded.lastSeen)
    }

    private fun runOpSetGet(sync: ElysiumSyncFile): String? {
        val doc = CrdtDoc()
        val seq = CrdtSequence()
        sync.log.replay(doc, seq)
        return doc.get("title")
    }
}