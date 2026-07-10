package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.server.Json
import com.elysium.vanguard.core.server.LocalFileServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files

/**
 * PHASE 9.17 — End-to-end test for the CRDT sync HTTP transport.
 *
 * We spin up a real `com.sun.net.httpserver.HttpServer` on
 * `127.0.0.1:0` (ephemeral port) that mocks the
 * [CrdtSyncRouteRegistrar] route semantics, then drive a
 * [LocalServerSyncAdapter] against it. This proves:
 *   1. The adapter sends the local companion as the request
 *      body.
 *   2. The server's JSON envelope round-trips back into an
 *      [ElysiumSyncFile].
 *   3. The remote ops land in the local session's body.
 *
 * We test the registrar's pure-Kotlin pieces directly too,
 * without HTTP, so we don't need an emulator just to cover
 * the parsing logic.
 */
class LocalServerSyncAdapterTest {

    private var server: LocalFileServer? = null
    private var serverDir: File? = null
    private val authToken = "test-token-xyz"

    @Before
    fun setUp() {
        serverDir = Files.createTempDirectory("local-server-sync-test").toFile()
    }

    @After
    fun tearDown() {
        server?.stop()
        serverDir?.deleteRecursively()
    }

    /**
     * Stand up [LocalFileServer] on localhost with the
     * [CrdtSyncRouteRegistrar] route mounted. Returns the bound
     * port.
     */
    private fun startServerWithSyncRoute(): Int {
        val srv = LocalFileServer(
            port = 0,
            bindAddress = "127.0.0.1",
            authTokenSupplier = { authToken },
            rootDir = { serverDir!!.absolutePath }
        )
        // Pre-seed the server-side companion for "doc.elysium.word"
        // with the `X` op used by the round-trip test.
        val workDir = serverDir!!
        val companionFile = File(workDir, "doc.elysium.word.server.elysium.sync")
        val aliceLog = CrdtOpLog()
        val aliceHlc = HybridLogicalClock(100L, 0, "node-alice")
        aliceLog.record(CrdtSeqOp.Insert(aliceHlc, "X"))
        val initial = ElysiumSyncFile(
            documentFile = File(workDir, "doc.elysium.word"),
            log = aliceLog,
            lastSeen = aliceHlc,
            nodeId = "server"
        ).serialize()
        companionFile.writeText(initial)
        // Register the route.
        CrdtSyncRouteRegistrar.register(
            srv = srv,
            fsRoot = { workDir },
            authTokenSupplier = { authToken }
        )
        srv.start()
        server = srv
        return srv.currentStatus().port
    }

    @Test
    fun `CrdtSyncRouteRegistrar isAuthorized accepts Bearer with matching token`() {
        // Direct exercise of the route's auth check using a tiny
        // HttpRequest stub.
        val req = com.elysium.vanguard.core.server.HttpRequest(
            method = "POST",
            path = "/api/crdt/sync",
            query = emptyMap(),
            headers = mapOf("Authorization" to "Bearer $authToken"),
            body = ByteArray(0)
        )
        assertTrue(CrdtSyncRouteRegistrar.isAuthorized(req) { authToken })
    }

    @Test
    fun `CrdtSyncRouteRegistrar parseLog tolerates blank input`() {
        val log = CrdtSyncRouteRegistrar.parseLog("")
        assertNotNull("blank input → empty log", log)
        assertEquals(0, log!!.size)
    }

    @Test
    fun `CrdtSyncRouteRegistrar resolveDocument rejects path traversal`() {
        val root = serverDir!!
        val ok = CrdtSyncRouteRegistrar.resolveDocument(root, "child/doc.elysium.word")
        assertNotNull("relative path resolves", ok)
        val traversal = CrdtSyncRouteRegistrar.resolveDocument(root, "../escape.elysium.word")
        assertNull("path traversal rejected", traversal)
    }

    @Test
    fun `resolver keeps absolute-style paths that live under root`() {
        val root = serverDir!!
        val abs = CrdtSyncRouteRegistrar.resolveDocument(root, "/nested/foo.elysium.word")
        assertNotNull(abs)
    }

    @Test
    fun `adapter buildUrl encodes the path query parameter`() {
        val adapter = LocalServerSyncAdapter(
            baseUrl = "http://localhost:8080/",
            authToken = "t",
            relativePath = "/path/with spaces/&special?.elysium.word"
        )
        val url = adapter.buildUrl("http://localhost:8080/", "/path/with spaces/&special?.elysium.word")
        // The path is URL-encoded so spaces/special chars survive.
        // URLEncoder emits `+` for space (form-encoding rule);
        // %20 is the strict RFC 3986 equivalent. Either is valid
        // here because the server uses URLDecoder.decode which
        // accepts both.
        assertTrue("URL should contain /api/crdt/sync: $url", url.contains("/api/crdt/sync"))
        assertTrue(
            "URL should contain encoded chars (path%2F or + for space, &special): $url",
            url.contains("path%2Fwith+spaces") || url.contains("path%2Fwith%20spaces")
        )
    }

    @Test
    fun `adapter parseAndAbsorb applies the JSON envelope into the session`() {
        // Set up a local session that just has an "A" insert.
        val workDir = serverDir!!
        val aliceFile = File(workDir, "alice.elysium.word").also { it.writeBytes(ByteArray(0)) }
        val alice = CrdtDocumentSession.create(aliceFile, com.elysium.vanguard.core.office.ElysiumDocument.Kind.WORD, "node-alice")
        alice.insertCharacter("A")

        // Build an envelope containing a "B" insert.
        val serverLog = CrdtOpLog()
        val bHlc = HybridLogicalClock(2L, 0, "server")
        serverLog.record(CrdtSeqOp.Insert(bHlc, "B"))
        val envelope = Json.encode(
            mapOf(
                "nodeId" to "server",
                "lastSeen" to bHlc.serialize(),
                "log" to serverLog.serialize()
            )
        )

        val adapter = LocalServerSyncAdapter(
            baseUrl = "http://unused",
            authToken = "t",
            relativePath = "/unused"
        )
        val absorbed = adapter.parseAndAbsorb(envelope, alice)
        assertEquals(1, absorbed)
        // Body now has Alice's "A" + server's "B" (or interleaved).
        val body = alice.bodyAsString()
        val chars = body.toSortedSet()
        assertTrue("body should have A: '$body'", chars.contains('A'))
        assertTrue("body should have B: '$body'", chars.contains('B'))
        assertEquals(2, body.length)
    }

    @Test
    fun `end-to-end sync round-trips through a real HTTP server`() {
        // Server side: stand up LocalFileServer + CrdtSyncRouteRegistrar
        // with a pre-seeded companion.
        val workDir = serverDir!!
        val port = startServerWithSyncRoute()

        // Client side: a fresh session for Bob that contains "Y".
        val bobFile = File(workDir, "bob_doc.elysium.word").also { it.writeBytes(ByteArray(0)) }
        val bob = CrdtDocumentSession.create(bobFile, com.elysium.vanguard.core.office.ElysiumDocument.Kind.WORD, "node-bob")
        bob.insertCharacter("Y")
        // Save so companion exists locally.
        bob.save()

        // Drive the adapter.
        val adapter = LocalServerSyncAdapter(
            baseUrl = "http://127.0.0.1:$port",
            authToken = authToken,
            relativePath = "doc.elysium.word"
        )
        val absorbed = adapter.syncWith(bob)
        assertEquals("expected 1 absorbed (X from server)", 1, absorbed)
        // Bob's body now has both "X" (server) and "Y" (own).
        val body = bob.bodyAsString()
        val chars = body.toSortedSet()
        assertTrue("body should contain X: '$body'", chars.contains('X'))
        assertTrue("body should contain Y: '$body'", chars.contains('Y'))
        assertEquals("body length 2: '$body'", 2, body.length)
    }

    @Test
    fun `wrong token produces lastError and 0 absorbed`() {
        val workDir = serverDir!!
        val port = startServerWithSyncRoute()
        val bobFile = File(workDir, "bob.elysium.word").also { it.writeBytes(ByteArray(0)) }
        val bob = CrdtDocumentSession.create(bobFile, com.elysium.vanguard.core.office.ElysiumDocument.Kind.WORD, "node-bob")
        bob.insertCharacter("Y")
        val adapter = LocalServerSyncAdapter(
            baseUrl = "http://127.0.0.1:$port",
            authToken = "wrong-token",
            relativePath = "doc.elysium.word"
        )
        val absorbed = adapter.syncWith(bob)
        assertEquals(0, absorbed)
        assertNotNull("expected lastError to be set", adapter.lastError)
        assertTrue(
            "lastError should mention status: ${adapter.lastError}",
            adapter.lastError!!.contains("401")
        )
    }

    private fun entriesEqual(a: CrdtOpLog.Entry, b: CrdtOpLog.Entry): Boolean {
        if (a.hlc != b.hlc) return false
        return when {
            a is CrdtOpLog.DocSet && b is CrdtOpLog.DocSet ->
                a.key == b.key && a.value == b.value
            a is CrdtOpLog.DocDel && b is CrdtOpLog.DocDel -> a.key == b.key
            a is CrdtOpLog.SeqIns && b is CrdtOpLog.SeqIns -> a.value == b.value
            a is CrdtOpLog.SeqDel && b is CrdtOpLog.SeqDel -> a.targetHlc == b.targetHlc
            else -> false
        }
    }
}
