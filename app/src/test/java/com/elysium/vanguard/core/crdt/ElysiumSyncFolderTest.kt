package com.elysium.vanguard.core.crdt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.18 — Tests for [ElysiumSyncFolder].
 *
 * Covers manifest IO (create/lookup/parse), the glob matcher,
 * and the `syncAll` end-to-end against an in-memory peer (we
 * don't spin up a real HTTP server here — the transport layer
 * is exercised in [LocalServerSyncAdapterTest]).
 */
class ElysiumSyncFolderTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("sync-folder-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `lookup returns null when no manifest exists`() {
        assertNull(ElysiumSyncFolder.lookup(tempDir))
    }

    @Test
    fun `create writes a manifest file and lookup parses it back`() {
        val folder = ElysiumSyncFolder.create(
            directory = tempDir,
            patterns = listOf("*.elysium.word", "*.elysium.sheet"),
            peers = listOf(
                ElysiumSyncFolder.PeerSpec(
                    name = "Laptop",
                    baseUrl = "http://192.168.1.20:8765",
                    authToken = "token-xyz"
                )
            )
        )
        assertTrue(
            "manifest file should exist",
            File(tempDir, ElysiumSyncFolder.MANIFEST_FILENAME).isFile
        )
        val parsed = ElysiumSyncFolder.lookup(tempDir)
        assertNotNull(parsed)
        val parsedFolder = parsed!!
        assertEquals(folder.patterns, parsedFolder.patterns)
        assertEquals(1, parsedFolder.peers.size)
        assertEquals("Laptop", parsedFolder.peers[0].name)
        assertEquals("http://192.168.1.20:8765", parsedFolder.peers[0].baseUrl)
        assertEquals("token-xyz", parsedFolder.peers[0].authToken)
    }

    @Test
    fun `default pattern falls back to elysium word when caller passes empty list`() {
        val folder = ElysiumSyncFolder.create(
            directory = tempDir,
            patterns = emptyList(),
            peers = emptyList()
        )
        assertEquals(listOf("*.elysium.word"), folder.patterns)
    }

    @Test
    fun `matchGlob handles star and question-mark wildcards`() {
        assertTrue(ElysiumSyncFolder.matchGlob("foo.elysium.word", "*.elysium.word"))
        assertTrue(ElysiumSyncFolder.matchGlob("a.elysium.sheet", "?.elysium.sheet"))
        assertTrue(ElysiumSyncFolder.matchGlob(".elysium.word", "*.elysium.word"))
        // Negative cases
        assertEquals(
            false,
            ElysiumSyncFolder.matchGlob("foo.elysium.deck", "*.elysium.word")
        )
        assertEquals(
            false,
            ElysiumSyncFolder.matchGlob("ab.elysium.word", "?.elysium.word")
        )
    }

    @Test
    fun `listDocuments returns only matching files`() {
        ElysiumSyncFolder.create(
            directory = tempDir,
            patterns = listOf("*.elysium.word"),
            peers = emptyList()
        )
        File(tempDir, "alpha.elysium.word").writeBytes(ByteArray(0))
        File(tempDir, "beta.elysium.sheet").writeBytes(ByteArray(0))
        File(tempDir, "README.txt").writeBytes(ByteArray(0))

        val folder = ElysiumSyncFolder.lookup(tempDir)!!
        val matched = folder.listDocuments().map { it.name }.toSet()
        assertEquals(setOf("alpha.elysium.word"), matched)
    }

    @Test
    fun `syncAll invokes adapter for each matching document against each peer`() {
        // Stand up a manifest with two peers. We provide a
        // transport builder whose result records every URL the
        // adapter would POST to; that's enough to assert the
        // routing without firing HTTP.
        ElysiumSyncFolder.create(
            directory = tempDir,
            patterns = listOf("*.elysium.word"),
            peers = listOf(
                ElysiumSyncFolder.PeerSpec("laptop", "http://laptop:8765", "laptopToken"),
                ElysiumSyncFolder.PeerSpec("phone", "http://phone:8765", "phoneToken")
            )
        )
        // Seed two *valid* Elysium documents so CrdtDocumentSession.open
        // can parse the empty companion + body without barfing.
        val docKind = com.elysium.vanguard.core.office.ElysiumDocument.Kind.WORD
        com.elysium.vanguard.core.office.ElysiumDocument(
            kind = docKind,
            style = com.elysium.vanguard.core.office.ElysiumDocument.StyleHints(),
            body = "alpha".toByteArray()
        ).writeTo(File(tempDir, "alpha.elysium.word"))
        com.elysium.vanguard.core.office.ElysiumDocument(
            kind = docKind,
            style = com.elysium.vanguard.core.office.ElysiumDocument.StyleHints(),
            body = "beta".toByteArray()
        ).writeTo(File(tempDir, "beta.elysium.word"))
        val transportCalls = mutableListOf<String>()
        val transportBuilder: () -> HttpSyncTransport = {
            object : HttpSyncTransport {
                override fun post(
                    url: String,
                    body: ByteArray,
                    headers: Map<String, String>
                ): HttpSyncTransport.Response {
                    transportCalls += url
                    // Return a "no remote ops" envelope so the
                    // adapter doesn't complain.
                    val payload = "{\"nodeId\":\"peer\",\"lastSeen\":null,\"log\":\"\"}"
                    return HttpSyncTransport.Response(200, payload.toByteArray())
                }
            }
        }
        val folder = ElysiumSyncFolder.lookup(tempDir)!!
        val total = folder.syncAll(
            sessionFactory = { f -> CrdtDocumentSession.open(f, "node-test") },
            transportBuilder = { transportBuilder() }
        )
        // 2 docs * 2 peers = 4 sync calls.
        assertEquals(4, transportCalls.size)
        // Each POST hits /api/crdt/sync with the document name in
        // the query string.
        for (call in transportCalls) {
            assertTrue(
                "transport call should hit /api/crdt/sync: $call",
                call.contains("/api/crdt/sync")
            )
        }
        val docNames = transportCalls.map {
            java.net.URLDecoder.decode(it.substringAfter("path="), "UTF-8")
        }.toSet()
        assertEquals(setOf("alpha.elysium.word", "beta.elysium.word"), docNames)
        // Adapter returned 0 (empty log), so total absorbed is 0.
        assertEquals(0, total)
    }

    @Test
    fun `lookup falls back to null on malformed manifest`() {
        File(tempDir, ElysiumSyncFolder.MANIFEST_FILENAME).writeText("not json at all")
        assertNull(ElysiumSyncFolder.lookup(tempDir))
    }

    @Test
    fun `fromJsonText tolerates peers list missing fields`() {
        // Malformed peer entries are skipped rather than throwing.
        val parsed = ElysiumSyncFolder.fromJsonText(
            directory = tempDir,
            manifestFile = File(tempDir, "missing"),
            text = """
                {
                  "patterns": ["*.elysium.word"],
                  "peers": [
                    { "name": "Good", "baseUrl": "http://x", "authToken": "t" },
                    { "name": "Bad", "baseUrl": "http://x" }
                  ]
                }
            """.trimIndent()
        )
        assertNotNull(parsed)
        // Bad peer (missing authToken) is dropped.
        assertEquals(1, parsed!!.peers.size)
        assertEquals("Good", parsed.peers[0].name)
    }
}
