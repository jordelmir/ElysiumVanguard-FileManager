package com.elysium.vanguard.features.crdteditor

import com.elysium.vanguard.core.crdt.CrdtDocumentSession
import com.elysium.vanguard.core.crdt.ElysiumSyncFile
import com.elysium.vanguard.core.office.ElysiumDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.14 — Tests for [CrdtDocumentEditorEngine].
 *
 * The engine is the pure-logic heart of the CRDT editor; the
 * ViewModel is a thin Hilt shell on top. These tests run the
 * engine through its public API and assert the observable state
 * matches the intent that was dispatched.
 *
 * We also exercise the persistence loop end-to-end: edit in one
 * engine, save to disk, reopen in a fresh engine, assert the
 * body and metadata round-trip safely.
 */
class CrdtDocumentEditorEngineTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("crdt-editor-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `engine opens empty file as Ready with empty fields`() {
        val file = File(tempDir, "empty.elysium.word").also { it.writeBytes(ByteArray(0)) }
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-alice")

        val state = engine.state.value
        assertTrue("Expected Ready, was $state", state is EditorState.Ready)
        val ready = state as EditorState.Ready
        assertEquals("", ready.title)
        assertEquals("", ready.author)
        assertEquals("", ready.body)
        assertFalse(ready.isDirty)
        assertNull(ready.lastResult)
    }

    @Test
    fun `engine opens existing file and re-snapshots body and title`() {
        // Seed a real ElysiumDocument so the file has a body.
        val file = File(tempDir, "seeded.elysium.word")
        ElysiumDocument(
            kind = ElysiumDocument.Kind.WORD,
            style = ElysiumDocument.StyleHints(),
            body = "# Seeded Title\n_by SeedAuthor_\n\nHello world".toByteArray(Charsets.UTF_8)
        ).writeTo(file)

        val engine = CrdtDocumentEditorEngine.forFile(file, "node-bob")

        val ready = engine.state.value as EditorState.Ready
        assertEquals("Seeded Title", ready.title)
        assertEquals("SeedAuthor", ready.author)
        assertEquals("Hello world", ready.body)
    }

    @Test
    fun `SetTitle and SetAuthor intents mutate state`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-carol")

        engine.dispatchSync(EditorIntent.SetTitle("Doc title"))
        engine.dispatchSync(EditorIntent.SetAuthor("me"))

        val ready = engine.state.value as EditorState.Ready
        assertEquals("Doc title", ready.title)
        assertEquals("me", ready.author)
        assertTrue("mutating intents should mark dirty", ready.isDirty)
    }

    @Test
    fun `AppendChar and AppendString build the body one char at a time`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-dave")
        for (c in "hi") engine.dispatchSync(EditorIntent.AppendChar(c.toString()))
        engine.dispatchSync(EditorIntent.AppendString("!"))

        val ready = engine.state.value as EditorState.Ready
        assertEquals("hi!", ready.body)
        assertEquals(3, ready.body.length)
    }

    @Test
    fun `AppendChar with multi-char string is rejected`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-eve")
        try {
            engine.dispatchSync(EditorIntent.AppendChar("ab"))
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `Backspace removes the last char`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-frank")
        engine.dispatchSync(EditorIntent.AppendString("hello"))
        val before = (engine.state.value as EditorState.Ready).body
        assertEquals("hello", before)

        engine.dispatchSync(EditorIntent.Backspace())
        assertEquals("hell", (engine.state.value as EditorState.Ready).body)

        engine.dispatchSync(EditorIntent.Backspace())
        engine.dispatchSync(EditorIntent.Backspace())
        assertEquals("he", (engine.state.value as EditorState.Ready).body)
    }

    @Test
    fun `Backspace on empty body is a no-op`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-grace")
        engine.dispatchSync(EditorIntent.Backspace())
        assertEquals("", (engine.state.value as EditorState.Ready).body)
    }

    @Test
    fun `saveSync persists file and reports Saved result`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-henry")
        engine.dispatchSync(EditorIntent.SetTitle("Saved"))
        engine.dispatchSync(EditorIntent.AppendString("payload"))

        engine.saveSync()
        val ready = engine.state.value as EditorState.Ready
        assertFalse("save clears dirty", ready.isDirty)
        assertEquals(EditorResult.Saved, ready.lastResult)

        // Companion sync file should also exist.
        val companion = ElysiumSyncFile.readFor(file, "node-henry")
        assertNotNull("companion file must exist after save", companion)
        assertTrue(
            "companion log should have entries",
            companion!!.log.rawEntries().isNotEmpty()
        )
    }

    @Test
    fun `save and reopen round-trips title, author, and body`() {
        val file = newFile()
        val alice = CrdtDocumentEditorEngine.forFile(file, "node-iris")
        alice.dispatchSync(EditorIntent.SetTitle("Round-trip"))
        alice.dispatchSync(EditorIntent.SetAuthor("iris"))
        alice.dispatchSync(EditorIntent.AppendString("Round-trip body content"))
        alice.saveSync()

        // Reopen as a different node id — simulates a different
        // process picking up where the previous one left off.
        val bob = CrdtDocumentEditorEngine.forFile(file, "node-jean")
        val ready = bob.state.value as EditorState.Ready
        assertEquals("Round-trip", ready.title)
        assertEquals("iris", ready.author)
        assertEquals("Round-trip body content", ready.body)
    }

    @Test
    fun `sync with no adapter reports SyncNoPeer and absorbs 0`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-kim", syncAdapter = null)
        engine.dispatchSync(EditorIntent.SetTitle("A"))
        val absorbed = engine.syncSync()
        assertEquals(0, absorbed)
        val ready = engine.state.value as EditorState.Ready
        assertEquals(EditorResult.SyncNoPeer, ready.lastResult)
    }

    @Test
    fun `sync via SyncHost absorbs remote ops and refreshes body`() {
        val aliceFile = newFile("alice.elysium.word")
        val bobFile = newFile("bob.elysium.word")

        // Alice writes "Alice 1, 2".
        val alice = CrdtDocumentEditorEngine.forFile(aliceFile, "node-alice")
        alice.dispatchSync(EditorIntent.AppendString("Alice 1, 2"))
        alice.saveSync()

        // Sanity check: alice's companion should now exist with ops.
        val aliceSync = ElysiumSyncFile.readFor(aliceFile, "node-alice")
        assertNotNull("alice companion must exist after saveSync", aliceSync)
        val aliceOps = aliceSync!!.log.size
        assertTrue("alice companion should have ops, got $aliceOps", aliceOps > 0)

        // Bob writes "Bob X" on his own file.
        val bob = CrdtDocumentEditorEngine.forFile(bobFile, "node-bob")
        bob.dispatchSync(EditorIntent.AppendString("Bob X"))
        bob.saveSync()
        // Sanity: Bob's body is "Bob X" before any sync.
        assertEquals("Bob X", (bob.state.value as EditorState.Ready).body)

        // Bob syncs with Alice: Absorb Alice's log into Bob's doc.
        val aliceAdapter = object : CrdtDocumentEditorEngine.SyncHost {
            override fun syncWith(session: CrdtDocumentSession): Int {
                val remoteSync = ElysiumSyncFile.readFor(aliceFile, "node-alice")
                    ?: return 0
                return session.absorbRemote(remoteSync)
            }
        }

        // Apply the same adapter on Bob's session.
        val bobWithAdapter = CrdtDocumentEditorEngine(bob.session, aliceAdapter)
        val absorbed = bobWithAdapter.syncSync()

        // After sync, both authors' chars must survive on Bob's doc.
        // The exact ORDER depends on HLC tiebreaking — when
        // alice's and bob's clocks tie on (ms, counter), the
        // smaller nodeId wins alphabetically, so the chars
        // interleave (alice's A → bob's B → alice's l → bob's
        // o → …) rather than grouping by author. Asserting over
        // the multiset (not a substring) is the right invariant.
        val ready = bobWithAdapter.state.value as EditorState.Ready
        val body = ready.body
        val aliceChars = "Alice 1, 2".toSortedSet()
        val bobChars = "Bob X".toSortedSet()
        val bodyChars = body.toSortedSet()
        assertEquals(
            "expected alice chars in body '$body', absorbed=$absorbed",
            aliceChars,
            bodyChars.intersect(aliceChars)
        )
        assertEquals(
            "expected bob chars in body '$body', absorbed=$absorbed",
            bobChars,
            bodyChars.intersect(bobChars)
        )
        // Body must hold all 15 distinct chars.
        assertEquals("expected 15 chars total, got ${body.length}", 15, body.length)
        // We absorbed exactly the entries from alice's log.
        assertTrue("expected positive absorption, got $absorbed", absorbed > 0)
    }

    @Test
    fun `setTitle to empty string clears the title`() {
        val file = newFile()
        val engine = CrdtDocumentEditorEngine.forFile(file, "node-liam")
        engine.dispatchSync(EditorIntent.SetTitle("first"))
        engine.dispatchSync(EditorIntent.SetTitle(""))
        assertEquals("", (engine.state.value as EditorState.Ready).title)
    }

    @Test
    fun `sync round trip preserves body with embedded single spaces`() {
        // Regression: a body that contains single-space characters
        // must round-trip through the companion file unchanged.
        // Phase 9.9.5 had a parse bug where trimming the line ate
        // trailing single-space values, dropping them from the log
        // (so the document came back shorter than written).
        val file = newFile()
        val writer = CrdtDocumentEditorEngine.forFile(file, "node-mia")
        writer.dispatchSync(EditorIntent.AppendString("a b c"))
        writer.saveSync()

        val reader = CrdtDocumentEditorEngine.forFile(file, "node-mia-reader")
        val ready = reader.state.value as EditorState.Ready
        assertEquals("a b c", ready.body)
    }

    @Test
    fun `kindFor picks the right Kind from the file extension`() {
        assertEquals(
            ElysiumDocument.Kind.WORD,
            CrdtDocumentEditorEngine.kindFor(File(tempDir, "x.elysium.word"))
        )
        assertEquals(
            ElysiumDocument.Kind.SHEET,
            CrdtDocumentEditorEngine.kindFor(File(tempDir, "x.elysium.sheet"))
        )
        assertEquals(
            ElysiumDocument.Kind.DECK,
            CrdtDocumentEditorEngine.kindFor(File(tempDir, "x.elysium.deck"))
        )
        // Unknown extension defaults to WORD.
        assertEquals(
            ElysiumDocument.Kind.WORD,
            CrdtDocumentEditorEngine.kindFor(File(tempDir, "x.elysium.qwert"))
        )
    }

    private fun newFile(name: String = "doc.elysium.word"): File =
        File(tempDir, name).also { it.writeBytes(ByteArray(0)) }
}
