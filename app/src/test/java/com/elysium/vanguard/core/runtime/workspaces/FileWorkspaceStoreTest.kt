package com.elysium.vanguard.core.runtime.workspaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 35 — tests for the production [FileWorkspaceStore].
 *
 * The store is the persistence seam for the workspace
 * layer. The tests pin:
 *
 *   - Round-trip: a saved workspace loads back
 *     unchanged (every field including state + every
 *     session by kind).
 *   - `load` returns null for an unknown id.
 *   - `list` returns every saved workspace; ignores
 *     unrelated files in the baseDir.
 *   - `delete` removes the file; returns true on the
 *     first call, false on the second.
 *   - Corrupt JSON in a file is treated as missing
 *     (the store does not crash the cold start).
 *   - The baseDir is created lazily on construction.
 *   - Atomic write: a `.json.tmp` left over from a
 *     crash is cleaned up on the next delete or
 *     load.
 *   - Thread-safety under concurrent save / load /
 *     list / delete from many threads.
 */
class FileWorkspaceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newStore(): FileWorkspaceStore =
        FileWorkspaceStore(tempFolder.newFolder("workspaces"))

    private fun linuxWorkspace(
        id: String = "ws-1",
        name: String = "Work",
        createdAtMs: Long = 1_700_000_000_000L,
        sessions: List<WorkspaceSession> = listOf(
            WorkspaceSession.LinuxProot("s-1", "Debian", "debian-latest", "balanced"),
            WorkspaceSession.LinuxProot("s-2", "Arch", "arch-latest", "lite")
        ),
        state: WorkspaceState = WorkspaceState.Active
    ): Workspace = Workspace(
        id = id,
        name = name,
        createdAtMs = createdAtMs,
        sessions = sessions,
        state = state
    )

    // --- round-trip ---

    @Test
    fun `save then load returns an equal workspace`() {
        val store = newStore()
        val original = linuxWorkspace()
        store.save(original)
        val loaded = store.load("ws-1")
        assertNotNull(loaded)
        assertEquals(original, loaded)
    }

    @Test
    fun `save then load round-trips a workspace with a Windows session`() {
        val store = newStore()
        val original = Workspace(
            id = "ws-2",
            name = "Win",
            createdAtMs = 2L,
            sessions = listOf(
                WorkspaceSession.WindowsVm("w-1", "Win11", "win11-pro-23h2")
            ),
            state = WorkspaceState.Active
        )
        store.save(original)
        val loaded = store.load("ws-2")
        assertEquals(original, loaded)
    }

    @Test
    fun `save then load round-trips a mixed Linux + Windows workspace`() {
        val store = newStore()
        val original = Workspace(
            id = "ws-3",
            name = "Mixed",
            createdAtMs = 3L,
            sessions = listOf(
                WorkspaceSession.LinuxProot("s-1", "Debian", "d", "balanced"),
                WorkspaceSession.WindowsVm("w-1", "Win11", "win11")
            )
        )
        store.save(original)
        val loaded = store.load("ws-3")
        assertEquals(original, loaded)
    }

    @Test
    fun `save then load round-trips the Paused state`() {
        val store = newStore()
        val original = linuxWorkspace(state = WorkspaceState.Paused)
        store.save(original)
        val loaded = store.load("ws-1")
        assertEquals(WorkspaceState.Paused, loaded?.state)
    }

    @Test
    fun `save then load round-trips the Closed state`() {
        val store = newStore()
        val original = linuxWorkspace(state = WorkspaceState.Closed)
        store.save(original)
        val loaded = store.load("ws-1")
        assertEquals(WorkspaceState.Closed, loaded?.state)
    }

    @Test
    fun `save then load preserves session order`() {
        val store = newStore()
        val s1 = WorkspaceSession.LinuxProot("s-1", "A", "d", "balanced")
        val s2 = WorkspaceSession.LinuxProot("s-2", "B", "d", "balanced")
        val s3 = WorkspaceSession.LinuxProot("s-3", "C", "d", "balanced")
        val original = linuxWorkspace(sessions = listOf(s1, s2, s3))
        store.save(original)
        val loaded = store.load("ws-1")
        assertEquals(listOf(s1, s2, s3), loaded?.sessions)
    }

    // --- load / list / delete ---

    @Test
    fun `load returns null for an unknown id`() {
        val store = newStore()
        assertNull(store.load("does-not-exist"))
    }

    @Test
    fun `list returns every saved workspace`() {
        val store = newStore()
        store.save(linuxWorkspace(id = "ws-a", name = "A"))
        store.save(linuxWorkspace(id = "ws-b", name = "B"))
        store.save(linuxWorkspace(id = "ws-c", name = "C"))
        val listed = store.list()
        assertEquals(3, listed.size)
        assertEquals(setOf("ws-a", "ws-b", "ws-c"), listed.map { it.id }.toSet())
    }

    @Test
    fun `list ignores non-json files in the baseDir`() {
        val store = newStore()
        store.save(linuxWorkspace(id = "ws-1"))
        // A user-managed file (e.g. a lock, a debug dump)
        // must not break the list.
        File(store.let { tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" } }, "README.md")
            .writeText("don't read me")
        File(store.let { tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" } }, "ws-1.json.tmp")
            .writeText("not a workspace")
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("ws-1", listed.single().id)
    }

    @Test
    fun `delete removes the file and returns true - second call returns false`() {
        val store = newStore()
        store.save(linuxWorkspace(id = "ws-1"))
        assertTrue("first delete must return true", store.delete("ws-1"))
        assertFalse("second delete must return false", store.delete("ws-1"))
        assertNull(store.load("ws-1"))
    }

    @Test
    fun `delete cleans up a stale tmp file from a crashed write`() {
        val store = newStore()
        val baseDir = tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" }
        // A crashed write may leave a .json.tmp without
        // the matching .json. delete() on the id must
        // return false (the .json is gone) but still clean
        // up the stale .json.tmp so the next save does
        // not race with the leftover.
        File(baseDir, "ws-1.json.tmp").writeText("garbage from a crash")
        assertFalse("no .json exists yet, delete must return false", store.delete("ws-1"))
        assertFalse("stale .json.tmp must be cleaned up", File(baseDir, "ws-1.json.tmp").exists())
    }

    // --- baseDir lifecycle ---

    @Test
    fun `store creates the baseDir on construction`() {
        val base = tempFolder.newFolder("nested")
        val target = File(base, "workspaces")
        assertFalse("baseDir must not exist before construction", target.exists())
        FileWorkspaceStore(target)
        assertTrue("baseDir must exist after construction", target.exists())
        assertTrue("baseDir must be a directory", target.isDirectory)
    }

    // --- corruption ---

    @Test
    fun `load treats a corrupt file as missing`() {
        val store = newStore()
        val baseDir = tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" }
        File(baseDir, "ws-1.json").writeText("{ this is not valid json")
        // The store must not throw on cold start; the
        // caller (the manager) re-creates the workspace
        // on the next save.
        assertNull(store.load("ws-1"))
    }

    @Test
    fun `list treats corrupt files as missing`() {
        val store = newStore()
        store.save(linuxWorkspace(id = "ws-1"))
        val baseDir = tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" }
        File(baseDir, "ws-2.json").writeText("garbage")
        File(baseDir, "ws-3.json").writeText("{ broken")
        // ws-1 must still come back; ws-2 / ws-3 are
        // skipped.
        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals("ws-1", listed.single().id)
    }

    // --- atomicity ---

    @Test
    fun `save does not leave a tmp file on success`() {
        val store = newStore()
        store.save(linuxWorkspace(id = "ws-1"))
        val baseDir = tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" }
        assertFalse("no tmp file on success", File(baseDir, "ws-1.json.tmp").exists())
        assertTrue("the real file exists", File(baseDir, "ws-1.json").exists())
    }

    @Test
    fun `save overwrites an existing workspace`() {
        val store = newStore()
        store.save(linuxWorkspace(id = "ws-1", name = "First"))
        store.save(linuxWorkspace(id = "ws-1", name = "Second"))
        val loaded = store.load("ws-1")
        assertEquals("Second", loaded?.name)
    }

    // --- on-disk format ---

    @Test
    fun `on-disk format is human-readable JSON`() {
        val store = newStore()
        store.save(linuxWorkspace())
        val baseDir = tempFolder.root.listFiles()!!.first { it.isDirectory && it.name == "workspaces" }
        val raw = File(baseDir, "ws-1.json").readText(Charsets.UTF_8)
        // Spot-checks: the JSON contains the discriminator
        // string "LINUX_PROOT" + the field names so a
        // human (or a `cat` in adb shell) can read it.
        assertTrue("must include the workspace id", raw.contains("\"id\":\"ws-1\""))
        assertTrue("must include the kind discriminator", raw.contains("\"LINUX_PROOT\""))
        assertTrue("must include the state key", raw.contains("\"state\":\"Active\""))
    }

    // --- thread safety ---

    @Test
    fun `store is thread-safe under concurrent save load list delete`() {
        val store = newStore()
        val start = CountDownLatch(1)
        val writers = CountDownLatch(4)
        val readers = CountDownLatch(4)
        val mutators = CountDownLatch(4)

        // Writers use `write-*` ids; mutators (load +
        // delete) use a disjoint `delete-*` id space so
        // the two paths do not race on the same file.
        // The post-condition asserts every `write-*` is
        // present at the end.
        repeat(4) { i ->
            Thread {
                start.await()
                repeat(25) { j ->
                    val ws = linuxWorkspace(
                        id = "write-$i-$j",
                        name = "write-$i-$j",
                        createdAtMs = (i * 100L + j)
                    )
                    store.save(ws)
                }
                writers.countDown()
            }.start()
        }
        repeat(4) {
            Thread {
                start.await()
                repeat(50) {
                    store.load("write-0-0")
                    store.list()
                }
                readers.countDown()
            }.start()
        }
        repeat(4) { i ->
            Thread {
                start.await()
                repeat(10) { j ->
                    val id = "delete-$i-$j"
                    // delete- may or may not exist; either
                    // path is fine for the test.
                    store.load(id)
                    store.delete(id)
                }
                mutators.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(writers.await(15, TimeUnit.SECONDS))
        assertTrue(readers.await(15, TimeUnit.SECONDS))
        assertTrue(mutators.await(15, TimeUnit.SECONDS))
        // After all writers are done, every workspace
        // they wrote must be present (the mutators
        // touched a disjoint id space).
        repeat(4) { i ->
            repeat(25) { j ->
                val id = "write-$i-$j"
                val ws = store.load(id)
                assertNotNull("writer $i save $j must be present: $id", ws)
            }
        }
    }

    // --- init validation on a non-Workspace arg ---

    @Test
    fun `save rejects a workspace with a blank id via Workspace init`() {
        val store = newStore()
        try {
            linuxWorkspace(id = "")
            fail("expected IllegalArgumentException for blank id")
        } catch (expected: IllegalArgumentException) { /* */ }
    }
}
