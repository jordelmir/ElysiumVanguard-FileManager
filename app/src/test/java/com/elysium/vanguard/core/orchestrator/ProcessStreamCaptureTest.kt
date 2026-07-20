package com.elysium.vanguard.core.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 90 (Universal Execution Engine) — the
 * JVM tests for [ProcessStreamCapture].
 *
 * The tests cover:
 *   - StreamChunk invariants (timestampMs > 0,
 *     StreamClosed blank reason rejected).
 *   - InMemoryProcessStreamCapture:
 *     - append adds a chunk to the capture.
 *     - append preserves the append order.
 *     - stdoutChunksForHandle returns only
 *       stdout chunks for the handle.
 *     - stderrChunksForHandle returns only
 *       stderr chunks for the handle.
 *     - stdoutAsString concatenates the
 *       stdout chunks for a handle.
 *     - stderrAsString concatenates the
 *       stderr chunks for a handle.
 *   - Realistic scenario: a process
 *     emits interleaved stdout + stderr
 *     chunks; the capture records all of
 *     them in order; the stdout and stderr
 *     are independently queryable.
 */
class ProcessStreamCaptureTest {

    // ============================================================
    // StreamChunk invariants
    // ============================================================

    @Test
    fun `StreamChunk StdoutChunk rejects non-positive timestampMs`() {
        try {
            StreamChunk.StdoutChunk(
                handleId = ProcessId.random(),
                data = "hello",
                timestampMs = 0L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    @Test
    fun `StreamChunk StderrChunk rejects non-positive timestampMs`() {
        try {
            StreamChunk.StderrChunk(
                handleId = ProcessId.random(),
                data = "error",
                timestampMs = 0L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    @Test
    fun `StreamChunk StreamClosed rejects blank reason`() {
        try {
            StreamChunk.StreamClosed(
                handleId = ProcessId.random(),
                reason = "",
                timestampMs = 1_700_000_000_000L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "blank reason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun `StreamChunk StreamClosed rejects non-positive timestampMs`() {
        try {
            StreamChunk.StreamClosed(
                handleId = ProcessId.random(),
                reason = "test",
                timestampMs = 0L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // InMemoryProcessStreamCapture
    // ============================================================

    @Test
    fun `append adds a chunk to the capture`() {
        val capture = InMemoryProcessStreamCapture()
        val chunk = buildStdoutChunk("hello")
        capture.append(chunk)
        assertEquals(1, capture.size)
        assertEquals(chunk, capture.chunks[0])
    }

    @Test
    fun `append preserves the append order`() {
        val capture = InMemoryProcessStreamCapture()
        val handleId = ProcessId.random()
        val c1 = buildStdoutChunk(
            "hello",
            handleId = handleId,
            timestampMs = 1_000L,
        )
        val c2 = buildStderrChunk(
            "warning",
            handleId = handleId,
            timestampMs = 2_000L,
        )
        val c3 = buildStdoutChunk(
            "world",
            handleId = handleId,
            timestampMs = 3_000L,
        )
        capture.append(c1)
        capture.append(c2)
        capture.append(c3)
        assertEquals(3, capture.size)
        assertEquals(c1, capture.chunks[0])
        assertEquals(c2, capture.chunks[1])
        assertEquals(c3, capture.chunks[2])
    }

    @Test
    fun `stdoutChunksForHandle returns only stdout chunks for the handle`() {
        val capture = InMemoryProcessStreamCapture()
        val handleId1 = ProcessId.random()
        val handleId2 = ProcessId.random()
        capture.append(
            buildStdoutChunk("h1 stdout", handleId = handleId1),
        )
        capture.append(
            buildStderrChunk("h1 stderr", handleId = handleId1),
        )
        capture.append(
            buildStdoutChunk("h2 stdout", handleId = handleId2),
        )
        val h1Stdout = capture.stdoutChunksForHandle(handleId1)
        assertEquals(1, h1Stdout.size)
        assertEquals("h1 stdout", h1Stdout[0].data)
    }

    @Test
    fun `stderrChunksForHandle returns only stderr chunks for the handle`() {
        val capture = InMemoryProcessStreamCapture()
        val handleId1 = ProcessId.random()
        val handleId2 = ProcessId.random()
        capture.append(
            buildStdoutChunk("h1 stdout", handleId = handleId1),
        )
        capture.append(
            buildStderrChunk("h1 stderr", handleId = handleId1),
        )
        capture.append(
            buildStderrChunk("h2 stderr", handleId = handleId2),
        )
        val h1Stderr = capture.stderrChunksForHandle(handleId1)
        assertEquals(1, h1Stderr.size)
        assertEquals("h1 stderr", h1Stderr[0].data)
    }

    @Test
    fun `stdoutAsString concatenates the stdout chunks for a handle`() {
        val capture = InMemoryProcessStreamCapture()
        val handleId = ProcessId.random()
        capture.append(
            buildStdoutChunk("hello ", handleId = handleId),
        )
        capture.append(
            buildStdoutChunk("world", handleId = handleId),
        )
        assertEquals("hello world", capture.stdoutAsString(handleId))
    }

    @Test
    fun `stderrAsString concatenates the stderr chunks for a handle`() {
        val capture = InMemoryProcessStreamCapture()
        val handleId = ProcessId.random()
        capture.append(
            buildStderrChunk("error: ", handleId = handleId),
        )
        capture.append(
            buildStderrChunk("file not found", handleId = handleId),
        )
        assertEquals(
            "error: file not found",
            capture.stderrAsString(handleId),
        )
    }

    @Test
    fun `size returns the number of chunks`() {
        val capture = InMemoryProcessStreamCapture()
        assertEquals(0, capture.size)
        capture.append(buildStdoutChunk("a"))
        assertEquals(1, capture.size)
        capture.append(buildStderrChunk("b"))
        assertEquals(2, capture.size)
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario a process emits interleaved stdout and stderr chunks, the capture records all of them in order`() {
        val capture = InMemoryProcessStreamCapture()
        val handleId = ProcessId.random()

        // Simulate a process emitting
        // interleaved stdout and stderr
        // chunks.
        capture.append(
            buildStdoutChunk(
                "Starting process...\n",
                handleId = handleId,
                timestampMs = 1_700_000_000_000L,
            ),
        )
        capture.append(
            buildStderrChunk(
                "warning: deprecated option\n",
                handleId = handleId,
                timestampMs = 1_700_000_000_001L,
            ),
        )
        capture.append(
            buildStdoutChunk(
                "Processing data...\n",
                handleId = handleId,
                timestampMs = 1_700_000_000_002L,
            ),
        )
        capture.append(
            buildStdoutChunk(
                "Done.\n",
                handleId = handleId,
                timestampMs = 1_700_000_000_003L,
            ),
        )
        capture.append(
            buildStderrChunk(
                "cleanup\n",
                handleId = handleId,
                timestampMs = 1_700_000_000_004L,
            ),
        )
        capture.append(
            StreamChunk.StreamClosed(
                handleId = handleId,
                reason = "process exited with code 0",
                timestampMs = 1_700_000_000_005L,
            ),
        )

        // Verify: The capture has 6
        // chunks.
        assertEquals(6, capture.size)

        // Verify: The stdout is the
        // concatenation of the 3
        // stdout chunks.
        assertEquals(
            "Starting process...\n" +
                "Processing data...\n" +
                "Done.\n",
            capture.stdoutAsString(handleId),
        )

        // Verify: The stderr is the
        // concatenation of the 2
        // stderr chunks.
        assertEquals(
            "warning: deprecated option\n" +
                "cleanup\n",
            capture.stderrAsString(handleId),
        )

        // Verify: The last chunk is the
        // StreamClosed.
        val lastChunk = capture.chunks.last()
        assertTrue(lastChunk is StreamChunk.StreamClosed)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildStdoutChunk(
        data: String,
        handleId: ProcessId = ProcessId.random(),
        timestampMs: Long = 1_700_000_000_000L,
    ): StreamChunk.StdoutChunk = StreamChunk.StdoutChunk(
        handleId = handleId,
        data = data,
        timestampMs = timestampMs,
    )

    private fun buildStderrChunk(
        data: String,
        handleId: ProcessId = ProcessId.random(),
        timestampMs: Long = 1_700_000_000_000L,
    ): StreamChunk.StderrChunk = StreamChunk.StderrChunk(
        handleId = handleId,
        data = data,
        timestampMs = timestampMs,
    )
}
