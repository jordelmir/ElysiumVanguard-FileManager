package com.elysium.vanguard.core.crdt

import com.elysium.vanguard.core.server.LocalFileServer
import com.elysium.vanguard.features.crdteditor.CrdtDocumentEditorEngine
import com.elysium.vanguard.features.crdteditor.EditorIntent
import com.elysium.vanguard.features.crdteditor.EditorResult
import com.elysium.vanguard.features.crdteditor.EditorState
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
 * PHASE 10.1 — Tests for [EditorSyncHost].
 *
 * Coverage:
 *   1. Pure `relativePathFor` math (root prefix, traversal).
 *   2. `isAvailable` mirrors the [EditorSyncHost.Source] state.
 *   3. `adapterFor` returns null when the source is stopped or
 *      the document escapes the sandbox.
 *   4. End-to-end: spin up a real [LocalFileServer] bound to
 *      an ephemeral port with the [CrdtSyncRouteRegistrar]
 *      route mounted, point the [EditorSyncHost] at it via a
 *      hand-rolled [Source], run the editor's `syncSync()`,
 *      and assert ops round-trip.
 *
 * The end-to-end test is the keystone: it proves the wiring
 * will actually work once the real `LocalServerOrchestrator`
 * in the running app reaches `State.RUNNING`.
 */
class EditorSyncHostTest {

    private lateinit var tempDir: File
    private var server: LocalFileServer? = null

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("editor-sync-host-test").toFile()
    }

    @After
    fun tearDown() {
        server?.stop()
        tempDir.deleteRecursively()
    }

    // ---- pure math ----

    @Test
    fun `relativePathFor strips the root prefix`() {
        val root = File(tempDir, "root")
        root.mkdirs()
        val file = File(root, "sub/doc.elysium.word")
            .also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(0)) }
        val rel = EditorSyncHost.relativePathFor(root, file)
        assertEquals("sub/doc.elysium.word", rel)
    }

    @Test
    fun `relativePathFor returns null when the file is outside the root`() {
        val root = File(tempDir, "root").also { it.mkdirs() }
        val outside = File(tempDir, "escape/doc.elysium.word")
            .also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(0)) }
        val rel = EditorSyncHost.relativePathFor(root, outside)
        assertNull(rel)
    }

    @Test
    fun `relativePathFor returns blank for the root itself`() {
        val root = File(tempDir, "root").also { it.mkdirs() }
        val rel = EditorSyncHost.relativePathFor(root, root)
        // Either the bare root or an empty trimmed result is
        // acceptable — both round-trip through the server.
        assertTrue("got: '$rel'", rel == "" || rel == ".")
    }

    // ---- availability / adapterFor gating ----

    @Test
    fun `adapterFor returns null when source reports stopped`() {
        val source = stubSource(running = false, port = 0)
        val host = EditorSyncHost(source, fsRoot = { tempDir })
        val file = File(tempDir, "doc.elysium.word").also { it.writeBytes(ByteArray(0)) }
        assertNull(host.adapterFor(file))
        assertEquals(false, host.isAvailable())
    }

    @Test
    fun `adapterFor returns null when the document escapes the sandbox`() {
        val source = stubSource(running = true, port = 8080, lanIp = "127.0.0.1")
        val host = EditorSyncHost(source, fsRoot = { File(tempDir, "sandbox").also { it.mkdirs() } })
        val outside = File(tempDir, "escape/doc.elysium.word")
            .also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(0)) }
        assertNull(host.adapterFor(outside))
    }

    @Test
    fun `adapterFor returns null when the source has no LAN IP`() {
        val source = stubSource(running = true, port = 8080, lanIp = null)
        val host = EditorSyncHost(source, fsRoot = { tempDir })
        val file = File(tempDir, "doc.elysium.word").also { it.writeBytes(ByteArray(0)) }
        assertNull(host.adapterFor(file))
    }

    @Test
    fun `adapterFor returns null when the source has a 0 port`() {
        val source = stubSource(running = true, port = 0, lanIp = "127.0.0.1")
        val host = EditorSyncHost(source, fsRoot = { tempDir })
        val file = File(tempDir, "doc.elysium.word").also { it.writeBytes(ByteArray(0)) }
        assertNull(host.adapterFor(file))
    }

    // ---- end-to-end with a real server ----

    @Test
    fun `end-to-end sync via EditorSyncHost round-trips ops through the server`() {
        // 1) Spin up LocalFileServer with the CRDT route.
        val workDir = tempDir
        val srv = LocalFileServer(
            port = 0,
            bindAddress = "127.0.0.1",
            authTokenSupplier = { TEST_TOKEN },
            rootDir = { workDir.absolutePath }
        )
        CrdtSyncRouteRegistrar.register(
            srv = srv,
            fsRoot = { workDir },
            authTokenSupplier = { TEST_TOKEN }
        )
        // Seed the server-side companion with "X".
        val serverCompanion = File(workDir, "shared.elysium.word.server.elysium.sync")
        val aliceLog = CrdtOpLog()
        val aliceHlc = HybridLogicalClock(200L, 0, "server")
        aliceLog.record(CrdtSeqOp.Insert(aliceHlc, "X"))
        serverCompanion.writeText(
            ElysiumSyncFile(
                documentFile = File(workDir, "shared.elysium.word"),
                log = aliceLog,
                lastSeen = aliceHlc,
                nodeId = "server"
            ).serialize()
        )
        srv.start()
        server = srv
        val port = srv.currentStatus().port
        assertTrue("server should be running on ephemeral port", port > 0)

        // 2) Build a Source that points at the running server.
        val source = stubSource(running = true, port = port, lanIp = "127.0.0.1", authToken = TEST_TOKEN)
        val editorHost = EditorSyncHost(source, fsRoot = { workDir })

        // 3) Open the editor against the shared document and
        //    sync through the EditorSyncHost.
        val doc = File(workDir, "shared.elysium.word").also { it.writeBytes(ByteArray(0)) }
        val editor = CrdtDocumentEditorEngine.forFile(doc, "node-bob")
        editor.dispatchSync(EditorIntent.AppendString("Y"))
        editor.saveSync()
        val adapter = editorHost.adapterFor(doc)
        assertNotNull("EditorSyncHost must produce an adapter when running", adapter)
        editor.setSyncHost(adapter)
        val absorbed = editor.syncSync()

        // 4) Verify ops round-tripped.
        assertEquals("expected 1 op absorbed (X from server)", 1, absorbed)
        val ready = editor.state.value as EditorState.Ready
        val body = ready.body
        val chars = body.toSortedSet()
        assertTrue("body must contain X: '$body'", chars.contains('X'))
        assertTrue("body must contain Y: '$body'", chars.contains('Y'))
        assertEquals(2, body.length)
        assertEquals(
            "lastResult should report a successful sync",
            EditorResult.Synced(1),
            ready.lastResult
        )
    }

    @Test
    fun `end-to-end fresh document with no companion still syncs via server`() {
        // Same shape as the previous test but the local file
        // has no companion yet — verifies the server is happy
        // to start from a peer with no prior state.
        val workDir = tempDir
        val srv = LocalFileServer(
            port = 0,
            bindAddress = "127.0.0.1",
            authTokenSupplier = { TEST_TOKEN },
            rootDir = { workDir.absolutePath }
        )
        CrdtSyncRouteRegistrar.register(
            srv = srv,
            fsRoot = { workDir },
            authTokenSupplier = { TEST_TOKEN }
        )
        // Pre-seed: server already has "A" in its companion.
        val companion = File(workDir, "alpha.elysium.word.server.elysium.sync")
        val log = CrdtOpLog()
        val hlc = HybridLogicalClock(100L, 0, "server")
        log.record(CrdtSeqOp.Insert(hlc, "A"))
        companion.writeText(
            ElysiumSyncFile(
                documentFile = File(workDir, "alpha.elysium.word"),
                log = log,
                lastSeen = hlc,
                nodeId = "server"
            ).serialize()
        )
        srv.start()
        server = srv
        val port = srv.currentStatus().port

        val source = stubSource(running = true, port = port, lanIp = "127.0.0.1", authToken = TEST_TOKEN)
        val editorHost = EditorSyncHost(source, fsRoot = { workDir })

        val doc = File(workDir, "alpha.elysium.word").also { it.writeBytes(ByteArray(0)) }
        val editor = CrdtDocumentEditorEngine.forFile(doc, "node-bob")
        // Bob has nothing yet — but the server's companion
        // does, so a sync should still absorb "A".
        val adapter = editorHost.adapterFor(doc)
        assertNotNull(adapter)
        editor.setSyncHost(adapter)
        val absorbed = editor.syncSync()
        assertEquals(1, absorbed)
        val body = (editor.state.value as EditorState.Ready).body
        assertTrue("body should contain A: '$body'", body.contains('A'))
    }

    // ---- helpers ----

    /**
     * Hand-rolled [EditorSyncHost.Source] for tests. We don't
     * need the real `LocalServerOrchestrator` (which requires
     * an Android Context) for the host's logic — the
     * orchestrator is adapted to this interface in the Hilt
     * module, so the host itself only needs the values that
     * interface exposes.
     */
    private fun stubSource(
        running: Boolean,
        port: Int,
        lanIp: String? = "127.0.0.1",
        authToken: String = TEST_TOKEN
    ): EditorSyncHost.Source {
        return object : EditorSyncHost.Source {
            override fun isRunning(): Boolean = running
            override fun serviceBaseUrl(): String? {
                if (!running) return null
                val ip = lanIp ?: return null
                if (port <= 0) return null
                return "http://$ip:$port"
            }
            override fun authToken(): String = authToken
        }
    }

    companion object {
        private const val TEST_TOKEN = "editor-sync-host-test-token"
    }
}
