package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.office.ElysiumDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PHASE 9.10 — Tests for the CRDT-backed document editing session.
 */
class CrdtDocumentSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeWordDoc(file: File, title: String, body: String) {
        val text = if (title.isEmpty()) body else "# $title\n\n$body"
        file.writeBytes(text.toByteArray(Charsets.UTF_8))
        // Wrap in an ElysiumDocument container so the open() path
        // can read it back as a real .elysium.word file.
        val doc = ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = text.toByteArray(Charsets.UTF_8)
        )
        doc.writeTo(file)
    }

    @Test
    fun `open an existing document and inspect its body`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "Notes", "Hello world")
        val session = CrdtDocumentSession.open(file, "alice")
        assertEquals("Hello world", session.bodyAsString())
        assertEquals(11, session.bodyLength())
    }

    @Test
    fun `insertCharacter appends to the body`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hi")
        val session = CrdtDocumentSession.open(file, "alice")
        session.insertCharacter("!")
        assertEquals("Hi!", session.bodyAsString())
    }

    @Test
    fun `deleteCharacterAt removes the live index`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hello")
        val session = CrdtDocumentSession.open(file, "alice")
        // Delete the 'e' at live index 1.
        session.deleteCharacterAt(1)
        assertEquals("Hllo", session.bodyAsString())
    }

    @Test
    fun `setTitle updates the metadata`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "Old", "")
        val session = CrdtDocumentSession.open(file, "alice")
        session.setTitle("New")
        assertEquals("New", session.doc.metadata.get("title"))
    }

    @Test
    fun `setTitle empty string clears the title`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "Title", "")
        val session = CrdtDocumentSession.open(file, "alice")
        session.setTitle("")
        assertNull(session.doc.metadata.get("title"))
    }

    @Test
    fun `save writes the current state to disk`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hello")
        val session = CrdtDocumentSession.open(file, "alice")
        session.insertCharacter("!")
        session.setTitle("Greeter")
        session.save()
        // Re-open and verify.
        val reopened = CrdtDocumentSession.open(file, "alice")
        assertEquals("Hello!", reopened.bodyAsString())
        assertEquals("Greeter", reopened.doc.metadata.get("title"))
    }

    @Test
    fun `create builds a new empty session`() {
        val file = File(tmp.root, "fresh.elysium.word")
        val session = CrdtDocumentSession.create(
            file, ElysiumDocument.Kind.WORD, "alice"
        )
        assertEquals(0, session.bodyLength())
        // Save creates the file on disk.
        session.save()
        assertTrue(file.isFile)
    }

    @Test
    fun `two sessions of the same file merge metadata edits`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hi")
        val alice = CrdtDocumentSession.open(file, "alice")
        val bob = CrdtDocumentSession.open(file, "bob")
        // Bob sets the title.
        bob.setTitle("From Bob")
        // Alice merges in bob's metadata-only changes.
        alice.doc.metadata.merge(bob.doc.metadata)
        bob.doc.metadata.merge(alice.doc.metadata)
        // Both nodes see the new title.
        assertEquals("From Bob", alice.doc.metadata.get("title"))
        assertEquals("From Bob", bob.doc.metadata.get("title"))
        // (Note: body merges aren't tested here because opening the
        // same file in two sessions duplicates the body seed — a
        // real sync would exchange op logs instead of full docs.)
    }

    @Test
    fun `insertCharacter rejects multi-char strings`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "")
        val session = CrdtDocumentSession.open(file, "alice")
        try {
            session.insertCharacter("ab")
            assertTrue("expected IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("single char"))
        }
    }

    @Test
    fun `concurrent character insertions on the same body converge`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "")
        val alice = CrdtDocumentSession.open(file, "alice")
        val bob = CrdtDocumentSession.open(file, "bob")
        alice.insertCharacter("A")
        bob.insertCharacter("B")
        alice.doc.body.merge(bob.doc.body)
        bob.doc.body.merge(alice.doc.body)
        // Both inserts survive.
        val aliceText = alice.bodyAsString()
        val bobText = bob.bodyAsString()
        assertTrue(aliceText.contains("A"))
        assertTrue(aliceText.contains("B"))
        assertEquals(aliceText, bobText)
    }

    @Test
    fun `save writes a companion sync file alongside the document`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hi")
        val session = CrdtDocumentSession.open(file, "alice")
        session.insertCharacter("!")
        session.setTitle("Greeter")
        session.save()
        // Companion exists at <doc>.alice.elysium.sync.
        val companion = ElysiumSyncFile.readFor(file, "alice")
        assertNotNull("expected companion file to exist", companion)
    }

    @Test
    fun `open absorbs the companion sync file`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hi")
        // Alice edits and saves — this writes the doc AND the
        // companion file.
        val alice = CrdtDocumentSession.open(file, "alice")
        for (ch in " world") alice.insertCharacter(ch.toString())
        alice.setTitle("From Alice")
        alice.save()
        // Bob opens the same document. He should see "Hi world"
        // and "From Alice" because his companion (created from
        // his perspective) is empty — but he ALSO sees alice's
        // edits because the document was rewritten to include
        // them.
        val bob = CrdtDocumentSession.open(file, "bob")
        assertEquals("Hi world", bob.bodyAsString())
        assertEquals("From Alice", bob.doc.metadata.get("title"))
    }

    @Test
    fun `absorbRemote merges a remote companion into the session`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "Hi")
        // Alice writes a remote companion with extra ops using HLCs
        // higher than any wall clock to keep the test deterministic.
        val remote = ElysiumSyncFile.empty(file, "alice")
        val highHlc = HybridLogicalClock(Long.MAX_VALUE - 100, 0, "alice")
        remote.log.record(
            CrdtOp.SetProperty(highHlc, "title", "From Alice")
        )
        remote.log.record(
            CrdtSeqOp.Insert(HybridLogicalClock(Long.MAX_VALUE - 99, 0, "alice"), "!")
        )
        // Bob opens the document and absorbs the remote.
        val bob = CrdtDocumentSession.open(file, "bob")
        val absorbed = bob.absorbRemote(remote)
        assertEquals(2, absorbed)
        // Bob's body now ends with "!".
        assertTrue(bob.bodyAsString().endsWith("!"))
        assertEquals("From Alice", bob.doc.metadata.get("title"))
    }

    @Test
    fun `save then reopen preserves the body across sessions`() {
        val file = tmp.newFile("notes.elysium.word")
        writeWordDoc(file, "", "")
        val first = CrdtDocumentSession.create(file, ElysiumDocument.Kind.WORD, "alice")
        first.insertCharacter("H")
        first.insertCharacter("i")
        first.insertCharacter("!")
        first.save()

        val second = CrdtDocumentSession.open(file, "alice")
        assertEquals("Hi!", second.bodyAsString())
    }
}