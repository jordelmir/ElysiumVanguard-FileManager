package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.office.ElysiumDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PHASE 9.13 — Tests for the sync adapter and two-node sync flow.
 */
class CrdtSyncAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeWordDoc(file: java.io.File, title: String, body: String) {
        val text = if (title.isEmpty()) body else "# $title\n\n$body"
        val doc = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = text.toByteArray(Charsets.UTF_8)
        )
        doc.writeTo(file)
    }

    @Test
    fun `syncWith pushes local ops to peer and pulls peer ops`() {
        val aliceFile = tmp.newFile("shared.alice.elysium.word")
        val bobFile = tmp.newFile("shared.bob.elysium.word")
        writeWordDoc(aliceFile, "", "Hi")
        writeWordDoc(bobFile, "", "Hi")

        val alice = CrdtDocumentSession.open(aliceFile, "alice")
        val bob = CrdtDocumentSession.open(bobFile, "bob")

        // Alice inserts '!' and bob sets the title.
        alice.insertCharacter("!")
        bob.setTitle("From Bob")

        // Bidirectional sync: alice pushes to bob, bob pushes to alice.
        val aliceAdapter = InMemorySyncAdapter(bob)
        val bobAdapter = InMemorySyncAdapter(alice)

        val aliceAbsorbed = aliceAdapter.syncWith(alice)
        val bobAbsorbed = bobAdapter.syncWith(bob)

        // Both absorbed at least one op each (the other's title
        // and body change).
        assertTrue("alice should have absorbed at least 1 op", aliceAbsorbed >= 1)
        assertTrue("bob should have absorbed at least 1 op", bobAbsorbed >= 1)

        // Both nodes see the title set by bob.
        assertEquals("From Bob", alice.doc.metadata.get("title"))
        assertEquals("From Bob", bob.doc.metadata.get("title"))
        // Both nodes see the '!' inserted by alice.
        assertTrue(alice.bodyAsString().contains("!"))
        assertTrue(bob.bodyAsString().contains("!"))
    }

    @Test
    fun `sync with no remote ops is a no-op`() {
        val aliceFile = tmp.newFile("a.elysium.word")
        val bobFile = tmp.newFile("b.elysium.word")
        writeWordDoc(aliceFile, "", "")
        writeWordDoc(bobFile, "", "")

        val alice = CrdtDocumentSession.open(aliceFile, "alice")
        val bob = CrdtDocumentSession.open(bobFile, "bob")
        val adapter = InMemorySyncAdapter(bob)
        val absorbed = adapter.syncWith(alice)
        // Bob has nothing to push (empty log); alice absorbs 0.
        assertEquals(0, absorbed)
    }

    @Test
    fun `concurrent edits converge after a single sync round`() {
        val aliceFile = tmp.newFile("a.elysium.word")
        val bobFile = tmp.newFile("b.elysium.word")
        writeWordDoc(aliceFile, "", "Hi")
        writeWordDoc(bobFile, "", "Hi")

        val alice = CrdtDocumentSession.open(aliceFile, "alice")
        val bob = CrdtDocumentSession.open(bobFile, "bob")

        // Alice and bob both edit concurrently.
        alice.insertCharacter("!")
        bob.insertCharacter("?")

        // Sync in both directions.
        val aliceAdapter = InMemorySyncAdapter(bob)
        val bobAdapter = InMemorySyncAdapter(alice)
        aliceAdapter.syncWith(alice)
        bobAdapter.syncWith(bob)

        // Both nodes should now contain both '!' and '?'.
        val aliceText = alice.bodyAsString()
        val bobText = bob.bodyAsString()
        assertTrue("alice should contain '!': $aliceText", aliceText.contains("!"))
        assertTrue("alice should contain '?': $aliceText", aliceText.contains("?"))
        assertTrue("bob should contain '!': $bobText", bobText.contains("!"))
        assertTrue("bob should contain '?': $bobText", bobText.contains("?"))
    }

    @Test
    fun `repeated sync is idempotent`() {
        val aliceFile = tmp.newFile("a.elysium.word")
        val bobFile = tmp.newFile("b.elysium.word")
        writeWordDoc(aliceFile, "", "")
        writeWordDoc(bobFile, "", "")

        val alice = CrdtDocumentSession.open(aliceFile, "alice")
        val bob = CrdtDocumentSession.open(bobFile, "bob")
        alice.insertCharacter("A")

        // Bob syncs from alice's companion 5 times. The first call
        // absorbs alice's edit; the rest are no-ops.
        val adapter = InMemorySyncAdapter(alice)
        var absorbedTotal = 0
        for (i in 0 until 5) {
            absorbedTotal += adapter.syncWith(bob)
        }
        assertEquals(1, absorbedTotal)
        // Bob's body has alice's edit.
        assertTrue(bob.bodyAsString().contains("A"))
    }

    @Test
    fun `sync preserves CRDT semantics across many round-trips`() {
        val aliceFile = tmp.newFile("a.elysium.word")
        val bobFile = tmp.newFile("b.elysium.word")
        writeWordDoc(aliceFile, "", "")
        writeWordDoc(bobFile, "", "")

        val alice = CrdtDocumentSession.open(aliceFile, "alice")
        val bob = CrdtDocumentSession.open(bobFile, "bob")

        // 10 alternating edits + syncs.
        for (i in 0 until 10) {
            if (i % 2 == 0) {
                alice.insertCharacter("a")
            } else {
                bob.insertCharacter("b")
            }
            InMemorySyncAdapter(bob).syncWith(alice)
            InMemorySyncAdapter(alice).syncWith(bob)
        }

        // Both nodes should have all 10 characters.
        val aliceText = alice.bodyAsString()
        val bobText = bob.bodyAsString()
        assertEquals(10, alice.bodyLength())
        assertEquals(10, bob.bodyLength())
        // Both contain all chars (5 a's + 5 b's).
        val aCount = aliceText.count { it == 'a' }
        val bCount = aliceText.count { it == 'b' }
        assertEquals(5, aCount)
        assertEquals(5, bCount)
        assertEquals(aliceText, bobText)
    }
}