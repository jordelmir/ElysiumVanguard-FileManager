package com.elysium.vanguard.core.runtime.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 72 — the JVM-side tests for
 * [InMemoryWriteCapture].
 *
 * The in-memory capture is the test seam: tests
 * can pre-seed writes via [InMemoryWriteCapture.seed]
 * to assert the orchestrator's audit logic without
 * spinning up a real [AndroidFileObserverWriteCapture]
 * (which needs the device's filesystem + a real
 * `FileObserver`).
 *
 * The capture is small enough that exhaustive unit
 * tests are cheap. Every public method + every
 * documented contract is asserted:
 *  - `start` clears previous state
 *  - `record` only records paths inside the
 *    watched set
 *  - `stop` halts capture (subsequent `record`
 *    calls are dropped)
 *  - `writes` returns the captured list
 *  - `seed` pre-populates (bypasses the watched-
 *    paths filter)
 *  - Sequential `start` calls reset the captured
 *    list (a stale write from session A never
 *    bleeds into session B's audit)
 */
class WriteCaptureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `start records the watched paths and clears prior writes`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a", "/data/b"))
        assertEquals(setOf("/data/a", "/data/b"), capture.watchedPaths())
        assertEquals(emptyList<String>(), capture.writes())
    }

    @Test
    fun `start clears the previous session's writes`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        assertEquals(listOf("/data/a/file1.txt"), capture.writes())

        // New session with the same watched set.
        capture.start(watching = setOf("/data/a"))
        assertEquals(emptyList<String>(), capture.writes())
    }

    @Test
    fun `start with a different watched set replaces the prior set`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        capture.start(watching = setOf("/data/b", "/data/c"))
        assertEquals(setOf("/data/b", "/data/c"), capture.watchedPaths())
        assertEquals(emptyList<String>(), capture.writes())
    }

    @Test
    fun `record captures paths inside a watched directory`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        capture.record("/data/a/subdir/file2.txt")
        assertEquals(
            listOf("/data/a/file1.txt", "/data/a/subdir/file2.txt"),
            capture.writes(),
        )
    }

    @Test
    fun `record rejects paths outside the watched directories`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/b/file1.txt")
        capture.record("/etc/passwd")
        assertEquals(emptyList<String>(), capture.writes())
    }

    @Test
    fun `record requires a path prefix (exact match counts)`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        // Exact match: the watched directory itself
        // counts as "in the watched set".
        capture.record("/data/a")
        // Prefix match: a file inside.
        capture.record("/data/a/file.txt")
        // NOT a prefix match: "/data/abc" starts with
        // "/data/a" but is a different directory.
        capture.record("/data/abc/file.txt")
        assertEquals(
            listOf("/data/a", "/data/a/file.txt"),
            capture.writes(),
        )
    }

    @Test
    fun `record is silently dropped after stop`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        capture.stop()
        capture.record("/data/a/file2.txt")
        assertEquals(listOf("/data/a/file1.txt"), capture.writes())
    }

    @Test
    fun `stop is idempotent`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.stop()
        capture.stop()
        // No exception, no change.
        assertEquals(emptyList<String>(), capture.writes())
    }

    @Test
    fun `stop preserves the captured writes for a later read`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        capture.stop()
        // `writes` still returns the list — stop does
        // not clear it; only `start` does.
        assertEquals(listOf("/data/a/file1.txt"), capture.writes())
    }

    @Test
    fun `writes returns a snapshot (the list is not aliased)`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        val firstRead = capture.writes()
        capture.record("/data/a/file2.txt")
        // The first snapshot does not see the new write.
        assertEquals(listOf("/data/a/file1.txt"), firstRead)
        // The second snapshot sees both.
        assertEquals(
            listOf("/data/a/file1.txt", "/data/a/file2.txt"),
            capture.writes(),
        )
    }

    @Test
    fun `seed pre-populates the capture bypassing the watched-paths filter`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.seed(listOf("/somewhere/else/file.txt"))
        assertEquals(listOf("/somewhere/else/file.txt"), capture.writes())
        // The watched set is unchanged by seed.
        assertEquals(setOf("/data/a"), capture.watchedPaths())
    }

    @Test
    fun `writes returns empty when called before start`() {
        val capture = InMemoryWriteCapture()
        assertEquals(emptyList<String>(), capture.writes())
    }

    @Test
    fun `start twice with the same set is safe and clears the list`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.record("/data/a/file1.txt")
        capture.start(watching = setOf("/data/a"))
        assertEquals(emptyList<String>(), capture.writes())
        assertTrue(
            "watched set should be the same as before",
            capture.watchedPaths() == setOf("/data/a"),
        )
    }

    @Test
    fun `record preserves insertion order`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        val paths = listOf(
            "/data/a/01.txt",
            "/data/a/02.txt",
            "/data/a/03.txt",
            "/data/a/04.txt",
            "/data/a/05.txt",
        )
        for (p in paths) capture.record(p)
        assertEquals(paths, capture.writes())
    }

    @Test
    fun `after stop the watched set is empty`() {
        val capture = InMemoryWriteCapture()
        capture.start(watching = setOf("/data/a"))
        capture.stop()
        assertFalse(
            "watched set should be empty after stop",
            capture.watchedPaths().isNotEmpty(),
        )
    }
}
