package com.elysium.vanguard.core.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 79 (Universal Execution Engine) — the
 * JVM tests for [ProcessWatcher].
 *
 * The tests cover:
 *   - ProcessEvent.Started invariants
 *     (zero pid, non-positive
 *     timestampMs).
 *   - ProcessEvent.Exited invariants
 *     (non-positive durationMs,
 *     non-positive timestampMs).
 *   - ProcessEvent.Failed invariants
 *     (blank failureReason, negative
 *     durationMs, non-positive
 *     timestampMs).
 *   - ProcessEvent.Heartbeat invariants
 *     (negative uptimeMs, non-positive
 *     timestampMs).
 *   - InMemoryProcessWatcher (watch,
 *     unwatch, emit, eventsForHandle,
 *     latestEventForHandle,
 *     countEventsForHandle).
 *   - Realistic scenario: the launcher
 *     launches a process; the watcher
 *     subscribes; the launcher emits
 *     Started + Heartbeat + Exited
 *     events; the watcher records the
 *     full lifecycle.
 */
class ProcessWatcherTest {

    // ============================================================
    // ProcessEvent.Started invariants
    // ============================================================

    @Test
    fun `ProcessEvent Started accepts a well-formed configuration`() {
        val event = buildStarted()
        assertTrue(event.pid > 0)
    }

    @Test
    fun `ProcessEvent Started rejects zero pid`() {
        try {
            buildStarted(pid = 0)
            fail("expected IllegalArgumentException for zero pid")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pid"))
        }
    }

    @Test
    fun `ProcessEvent Started rejects non-positive timestampMs`() {
        try {
            buildStarted(timestampMs = 0L)
            fail("expected IllegalArgumentException for zero timestampMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // ProcessEvent.Exited invariants
    // ============================================================

    @Test
    fun `ProcessEvent Exited accepts a well-formed configuration`() {
        val event = buildExited()
        assertEquals(0, event.exitCode)
    }

    @Test
    fun `ProcessEvent Exited rejects non-positive durationMs`() {
        try {
            buildExited(durationMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive durationMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("durationMs"))
        }
    }

    @Test
    fun `ProcessEvent Exited rejects non-positive timestampMs`() {
        try {
            buildExited(timestampMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // ProcessEvent.Failed invariants
    // ============================================================

    @Test
    fun `ProcessEvent Failed accepts a well-formed configuration`() {
        val event = buildFailed()
        assertEquals("executable not found", event.failureReason)
    }

    @Test
    fun `ProcessEvent Failed rejects blank failureReason`() {
        try {
            buildFailed(failureReason = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank failureReason",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("failureReason"))
        }
    }

    @Test
    fun `ProcessEvent Failed rejects negative durationMs`() {
        try {
            buildFailed(durationMs = -1L)
            fail(
                "expected IllegalArgumentException for " +
                    "negative durationMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("durationMs"))
        }
    }

    @Test
    fun `ProcessEvent Failed rejects non-positive timestampMs`() {
        try {
            buildFailed(timestampMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // ProcessEvent.Heartbeat invariants
    // ============================================================

    @Test
    fun `ProcessEvent Heartbeat accepts a well-formed configuration`() {
        val event = buildHeartbeat()
        assertTrue(event.uptimeMs > 0)
    }

    @Test
    fun `ProcessEvent Heartbeat rejects negative uptimeMs`() {
        try {
            buildHeartbeat(uptimeMs = -1L)
            fail(
                "expected IllegalArgumentException for " +
                    "negative uptimeMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("uptimeMs"))
        }
    }

    @Test
    fun `ProcessEvent Heartbeat rejects non-positive timestampMs`() {
        try {
            buildHeartbeat(timestampMs = 0L)
            fail(
                "expected IllegalArgumentException for " +
                    "non-positive timestampMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampMs"))
        }
    }

    // ============================================================
    // InMemoryProcessWatcher — watch + unwatch
    // ============================================================

    @Test
    fun `watch subscribes to events for a launched handle`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        val watcher = InMemoryProcessWatcher()
        val result = watcher.watch(handle.handleId, launcher)
        assertTrue(result.isSuccess)
        assertEquals(1, watcher.watchedHandles.size)
    }

    @Test
    fun `watch rejects an unknown handle`() {
        val launcher = InMemoryProcessLauncher()
        val watcher = InMemoryProcessWatcher()
        val result = watcher.watch(ProcessId.random(), launcher)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is ProcessWatcherError.HandleNotFound)
    }

    @Test
    fun `watch is idempotent for the same handle`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        val watcher = InMemoryProcessWatcher()
        watcher.watch(handle.handleId, launcher)
        watcher.watch(handle.handleId, launcher)
        assertEquals(1, watcher.watchedHandles.size)
    }

    @Test
    fun `unwatch unsubscribes from a handle`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        val watcher = InMemoryProcessWatcher()
        watcher.watch(handle.handleId, launcher)
        watcher.unwatch(handle.handleId)
        assertTrue(watcher.watchedHandles.isEmpty())
    }

    @Test
    fun `unwatch is idempotent for an unwatched handle`() {
        val watcher = InMemoryProcessWatcher()
        watcher.unwatch(ProcessId.random())
        assertTrue(watcher.watchedHandles.isEmpty())
    }

    // ============================================================
    // InMemoryProcessWatcher — emit + query
    // ============================================================

    @Test
    fun `emit records an event in the events list`() {
        val watcher = InMemoryProcessWatcher()
        val event = buildStarted()
        watcher.emit(event)
        assertEquals(1, watcher.events.size)
        assertEquals(event, watcher.events[0])
    }

    @Test
    fun `emit preserves event order`() {
        val watcher = InMemoryProcessWatcher()
        val e1 = buildStarted(timestampMs = 1_000L)
        val e2 = buildHeartbeat(timestampMs = 2_000L)
        val e3 = buildExited(timestampMs = 3_000L)
        watcher.emit(e1)
        watcher.emit(e2)
        watcher.emit(e3)
        assertEquals(e1, watcher.events[0])
        assertEquals(e2, watcher.events[1])
        assertEquals(e3, watcher.events[2])
    }

    @Test
    fun `eventsForHandle returns only events for the handle`() {
        val watcher = InMemoryProcessWatcher()
        val h1 = ProcessId.random()
        val h2 = ProcessId.random()
        val e1 = buildStarted(handleId = h1)
        val e2 = buildStarted(handleId = h2)
        val e3 = buildHeartbeat(handleId = h1)
        watcher.emit(e1)
        watcher.emit(e2)
        watcher.emit(e3)
        val h1Events = watcher.eventsForHandle(h1)
        assertEquals(2, h1Events.size)
        assertEquals(e1, h1Events[0])
        assertEquals(e3, h1Events[1])
    }

    @Test
    fun `eventsForHandle returns empty for an unknown handle`() {
        val watcher = InMemoryProcessWatcher()
        watcher.emit(buildStarted())
        val events = watcher.eventsForHandle(ProcessId.random())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `latestEventForHandle returns the most recent event`() {
        val watcher = InMemoryProcessWatcher()
        val h = ProcessId.random()
        val e1 = buildStarted(handleId = h, timestampMs = 1_000L)
        val e2 = buildHeartbeat(handleId = h, timestampMs = 2_000L)
        val e3 = buildExited(handleId = h, timestampMs = 3_000L)
        watcher.emit(e1)
        watcher.emit(e2)
        watcher.emit(e3)
        val latest = watcher.latestEventForHandle(h)
        assertEquals(e3, latest)
    }

    @Test
    fun `latestEventForHandle returns null for an unknown handle`() {
        val watcher = InMemoryProcessWatcher()
        val latest = watcher.latestEventForHandle(ProcessId.random())
        assertNull(latest)
    }

    @Test
    fun `countEventsForHandle returns the event count`() {
        val watcher = InMemoryProcessWatcher()
        val h = ProcessId.random()
        watcher.emit(buildStarted(handleId = h))
        watcher.emit(buildHeartbeat(handleId = h))
        watcher.emit(buildStarted(handleId = ProcessId.random()))
        assertEquals(2, watcher.countEventsForHandle(h))
    }

    @Test
    fun `countEventsForHandle returns 0 for an unknown handle`() {
        val watcher = InMemoryProcessWatcher()
        assertEquals(0, watcher.countEventsForHandle(ProcessId.random()))
    }

    // ============================================================
    // Realistic scenario: full lifecycle
    // ============================================================

    @Test
    fun `realistic scenario the launcher launches a process, the watcher subscribes, the process emits Started Heartbeat Exited`() {
        // Step 1: The launcher launches a process.
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        assertTrue(handle is ProcessHandle.Started)

        // Step 2: The watcher subscribes to the
        // process.
        val watcher = InMemoryProcessWatcher()
        val watchResult = watcher.watch(handle.handleId, launcher)
        assertTrue(watchResult.isSuccess)

        // Step 3: The process emits a Started
        // event.
        watcher.emit(
            ProcessEvent.Started(
                handleId = handle.handleId,
                pid = (handle as ProcessHandle.Started).pid,
                timestampMs = handle.startedMs,
            ),
        )

        // Step 4: The process emits a Heartbeat
        // event (1 second in).
        watcher.emit(
            ProcessEvent.Heartbeat(
                handleId = handle.handleId,
                uptimeMs = 1_000L,
                timestampMs = handle.startedMs + 1_000L,
            ),
        )

        // Step 5: The process exits normally.
        val exitTs = handle.startedMs + 5_000L
        launcher.markExited(
            handleId = handle.handleId,
            exitCode = 0,
            exitedMs = exitTs,
        )
        watcher.emit(
            ProcessEvent.Exited(
                handleId = handle.handleId,
                exitCode = 0,
                durationMs = 5_000L,
                timestampMs = exitTs,
            ),
        )

        // Step 6: The watcher reports the full
        // lifecycle.
        val events = watcher.eventsForHandle(handle.handleId)
        assertEquals(3, events.size)
        assertTrue(events[0] is ProcessEvent.Started)
        assertTrue(events[1] is ProcessEvent.Heartbeat)
        assertTrue(events[2] is ProcessEvent.Exited)
        assertEquals(0, (events[2] as ProcessEvent.Exited).exitCode)
        assertEquals(5_000L, (events[2] as ProcessEvent.Exited).durationMs)
    }

    @Test
    fun `realistic scenario multiple processes can be watched concurrently`() {
        val launcher = InMemoryProcessLauncher()
        val h1 = launcher.launch(buildPlan()).getOrNull()!!
        val h2 = launcher.launch(buildPlan()).getOrNull()!!
        val watcher = InMemoryProcessWatcher()
        watcher.watch(h1.handleId, launcher)
        watcher.watch(h2.handleId, launcher)
        assertEquals(2, watcher.watchedHandles.size)

        // h1 emits Started + Exited.
        watcher.emit(buildStarted(handleId = h1.handleId))
        watcher.emit(
            buildExited(handleId = h1.handleId, durationMs = 1_000L),
        )
        // h2 emits Started + Failed.
        watcher.emit(buildStarted(handleId = h2.handleId))
        watcher.emit(
            buildFailed(handleId = h2.handleId, durationMs = 500L),
        )

        assertEquals(2, watcher.countEventsForHandle(h1.handleId))
        assertEquals(2, watcher.countEventsForHandle(h2.handleId))
        assertEquals(4, watcher.events.size)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildPlan(): LaunchPlan = LaunchPlan(
        runtime = LaunchRuntime.BOX64,
        executable = "/usr/bin/box64",
        args = listOf("/usr/bin/box64", "/opt/steam/steam"),
        workingDirectory = "/opt/steam",
        environment = emptyMap(),
    )

    private fun buildStarted(
        handleId: ProcessId = ProcessId.random(),
        pid: Int = 12345,
        timestampMs: Long = 1_700_000_000_000L,
    ): ProcessEvent.Started = ProcessEvent.Started(
        handleId = handleId,
        pid = pid,
        timestampMs = timestampMs,
    )

    private fun buildExited(
        handleId: ProcessId = ProcessId.random(),
        exitCode: Int = 0,
        durationMs: Long = 5_000L,
        timestampMs: Long = 1_700_000_005_000L,
    ): ProcessEvent.Exited = ProcessEvent.Exited(
        handleId = handleId,
        exitCode = exitCode,
        durationMs = durationMs,
        timestampMs = timestampMs,
    )

    private fun buildFailed(
        handleId: ProcessId = ProcessId.random(),
        failureReason: String = "executable not found",
        durationMs: Long = 0L,
        timestampMs: Long = 1_700_000_000_500L,
    ): ProcessEvent.Failed = ProcessEvent.Failed(
        handleId = handleId,
        failureReason = failureReason,
        durationMs = durationMs,
        timestampMs = timestampMs,
    )

    private fun buildHeartbeat(
        handleId: ProcessId = ProcessId.random(),
        uptimeMs: Long = 1_000L,
        timestampMs: Long = 1_700_000_001_000L,
    ): ProcessEvent.Heartbeat = ProcessEvent.Heartbeat(
        handleId = handleId,
        uptimeMs = uptimeMs,
        timestampMs = timestampMs,
    )
}
