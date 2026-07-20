package com.elysium.vanguard.core.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 78 (Universal Execution Engine, Process
 * Supervisor step) — the JVM tests for
 * [ProcessLauncher].
 *
 * The tests cover:
 *   - ProcessHandle.Started invariants
 *     (zero pid, non-positive startedMs).
 *   - ProcessHandle.Exited invariants (zero
 *     pid, non-positive startedMs,
 *     non-positive exitedMs, exitedMs <
 *     startedMs, durationMs).
 *   - ProcessHandle.Failed invariants (blank
 *     failureReason, non-positive startedMs,
 *     non-positive failedMs, failedMs <
 *     startedMs).
 *   - InMemoryProcessLauncher (launch,
 *     getHandle, activeHandles,
 *     terminalHandles, markExited,
 *     markFailed).
 *   - Realistic scenario: the dispatcher
 *     produces a plan; the launcher launches
 *     the plan; the process runs; the
 *     process exits; the launcher tracks
 *     the full lifecycle.
 */
class ProcessLauncherTest {

    // ============================================================
    // ProcessHandle.Started invariants
    // ============================================================

    @Test
    fun `ProcessHandle Started accepts a well-formed configuration`() {
        val started = buildStarted()
        assertTrue(started.pid > 0)
    }

    @Test
    fun `ProcessHandle Started rejects zero pid`() {
        try {
            buildStarted(pid = 0)
            fail("expected IllegalArgumentException for zero pid")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("pid"))
        }
    }

    @Test
    fun `ProcessHandle Started rejects non-positive startedMs`() {
        try {
            buildStarted(startedMs = 0L)
            fail("expected IllegalArgumentException for zero startedMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("startedMs"))
        }
    }

    // ============================================================
    // ProcessHandle.Exited invariants
    // ============================================================

    @Test
    fun `ProcessHandle Exited accepts a well-formed configuration`() {
        val exited = buildExited()
        assertEquals(0, exited.exitCode)
        assertTrue(exited.durationMs > 0)
    }

    @Test
    fun `ProcessHandle Exited rejects non-positive startedMs`() {
        try {
            buildExited(startedMs = 0L)
            fail("expected IllegalArgumentException for zero startedMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("startedMs"))
        }
    }

    @Test
    fun `ProcessHandle Exited rejects non-positive exitedMs`() {
        try {
            buildExited(exitedMs = 0L)
            fail("expected IllegalArgumentException for zero exitedMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exitedMs"))
        }
    }

    @Test
    fun `ProcessHandle Exited rejects exitedMs less than startedMs`() {
        try {
            buildExited(startedMs = 2000L, exitedMs = 1000L)
            fail(
                "expected IllegalArgumentException for " +
                    "exitedMs < startedMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exitedMs"))
        }
    }

    @Test
    fun `ProcessHandle Exited durationMs is exitedMs minus startedMs`() {
        val exited = buildExited(startedMs = 1000L, exitedMs = 5000L)
        assertEquals(4000L, exited.durationMs)
    }

    // ============================================================
    // ProcessHandle.Failed invariants
    // ============================================================

    @Test
    fun `ProcessHandle Failed accepts a well-formed configuration`() {
        val failed = buildFailed()
        assertEquals("executable not found", failed.failureReason)
    }

    @Test
    fun `ProcessHandle Failed rejects blank failureReason`() {
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
    fun `ProcessHandle Failed rejects non-positive startedMs`() {
        try {
            buildFailed(startedMs = 0L)
            fail("expected IllegalArgumentException for zero startedMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("startedMs"))
        }
    }

    @Test
    fun `ProcessHandle Failed rejects non-positive failedMs`() {
        try {
            buildFailed(failedMs = 0L)
            fail("expected IllegalArgumentException for zero failedMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("failedMs"))
        }
    }

    @Test
    fun `ProcessHandle Failed rejects failedMs less than startedMs`() {
        try {
            buildFailed(startedMs = 2000L, failedMs = 1000L)
            fail(
                "expected IllegalArgumentException for " +
                    "failedMs < startedMs",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("failedMs"))
        }
    }

    // ============================================================
    // InMemoryProcessLauncher — launch + getHandle
    // ============================================================

    @Test
    fun `launch returns a Started handle`() {
        val launcher = InMemoryProcessLauncher()
        val result = launcher.launch(buildPlan())
        assertTrue(result.isSuccess)
        val handle = result.getOrNull()!!
        assertTrue(handle is ProcessHandle.Started)
    }

    @Test
    fun `launch records the handle in the handles list`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        assertEquals(1, launcher.handles.size)
        assertEquals(handle, launcher.handles[0])
    }

    @Test
    fun `launch produces a unique handle id per launch`() {
        val launcher = InMemoryProcessLauncher()
        val h1 = launcher.launch(buildPlan()).getOrNull()!!
        val h2 = launcher.launch(buildPlan()).getOrNull()!!
        assertTrue(h1.handleId != h2.handleId)
    }

    @Test
    fun `getHandle returns the handle by id`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        val fetched = launcher.getHandle(handle.handleId)
        assertEquals(handle, fetched)
    }

    @Test
    fun `getHandle returns null for an unknown id`() {
        val launcher = InMemoryProcessLauncher()
        val fetched = launcher.getHandle(ProcessId.random())
        assertNull(fetched)
    }

    // ============================================================
    // InMemoryProcessLauncher — active + terminal filters
    // ============================================================

    @Test
    fun `activeHandles returns only Started handles`() {
        val launcher = InMemoryProcessLauncher()
        val h1 = launcher.launch(buildPlan()).getOrNull()!!
        val h2 = launcher.launch(buildPlan()).getOrNull()!!
        launcher.markExited(
            h2.handleId,
            exitCode = 0,
            exitedMs = h2.startedMs + 1000L,
        )
        val active = launcher.activeHandles()
        assertEquals(1, active.size)
        assertEquals(h1.handleId, active[0].handleId)
    }

    @Test
    fun `terminalHandles returns only Exited and Failed handles`() {
        val launcher = InMemoryProcessLauncher()
        launcher.launch(buildPlan())
        val h2 = launcher.launch(buildPlan()).getOrNull()!!
        val h3 = launcher.launch(buildPlan()).getOrNull()!!
        launcher.markExited(
            h2.handleId,
            exitCode = 0,
            exitedMs = h2.startedMs + 1000L,
        )
        launcher.markFailed(
            h3.handleId,
            reason = "crashed",
            failedMs = h3.startedMs + 1000L,
        )
        val terminal = launcher.terminalHandles()
        assertEquals(2, terminal.size)
    }

    // ============================================================
    // InMemoryProcessLauncher — markExited
    // ============================================================

    @Test
    fun `markExited transitions Started to Exited`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        val result = launcher.markExited(
            handleId = handle.handleId,
            exitCode = 0,
            exitedMs = handle.startedMs + 5000L,
        )
        assertTrue(result.isSuccess)
        val updated = launcher.getHandle(handle.handleId)
        assertTrue(updated is ProcessHandle.Exited)
        assertEquals(0, (updated as ProcessHandle.Exited).exitCode)
    }

    @Test
    fun `markExited rejects an unknown handle`() {
        val launcher = InMemoryProcessLauncher()
        val now = System.currentTimeMillis()
        val result = launcher.markExited(
            handleId = ProcessId.random(),
            exitCode = 0,
            exitedMs = now + 5000L,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is ProcessLauncherError.HandleNotFound)
    }

    @Test
    fun `markExited rejects a handle not in Started state`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        launcher.markExited(
            handle.handleId,
            exitCode = 0,
            exitedMs = handle.startedMs + 1000L,
        )
        // Now the handle is in Exited state.
        val result = launcher.markExited(
            handleId = handle.handleId,
            exitCode = 0,
            exitedMs = handle.startedMs + 2000L,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is ProcessLauncherError.HandleNotStarted)
    }

    // ============================================================
    // InMemoryProcessLauncher — markFailed
    // ============================================================

    @Test
    fun `markFailed transitions Started to Failed`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        val result = launcher.markFailed(
            handleId = handle.handleId,
            reason = "segmentation fault",
            failedMs = handle.startedMs + 1000L,
        )
        assertTrue(result.isSuccess)
        val updated = launcher.getHandle(handle.handleId)
        assertTrue(updated is ProcessHandle.Failed)
        assertEquals(
            "segmentation fault",
            (updated as ProcessHandle.Failed).failureReason,
        )
    }

    @Test
    fun `markFailed rejects an unknown handle`() {
        val launcher = InMemoryProcessLauncher()
        val now = System.currentTimeMillis()
        val result = launcher.markFailed(
            handleId = ProcessId.random(),
            reason = "crashed",
            failedMs = now + 5000L,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is ProcessLauncherError.HandleNotFound)
    }

    @Test
    fun `markFailed rejects a handle not in Started state`() {
        val launcher = InMemoryProcessLauncher()
        val handle = launcher.launch(buildPlan()).getOrNull()!!
        launcher.markFailed(
            handleId = handle.handleId,
            reason = "crashed",
            failedMs = handle.startedMs + 1000L,
        )
        // Now the handle is in Failed state.
        val result = launcher.markFailed(
            handleId = handle.handleId,
            reason = "crashed again",
            failedMs = handle.startedMs + 2000L,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is ProcessLauncherError.HandleNotStarted)
    }

    // ============================================================
    // Realistic scenario: dispatcher + launcher lifecycle
    // ============================================================

    @Test
    fun `realistic scenario dispatcher produces a plan, launcher launches it, the process runs, the process exits`() {
        // Step 1: The dispatcher produces a launch
        // plan (Box64 + a guest x86_64 app).
        val dispatcher = RuntimeDispatcher()
        val plan = buildPlan()
        assertEquals(LaunchRuntime.BOX64, plan.runtime)

        // Step 2: The launcher launches the plan.
        val launcher = InMemoryProcessLauncher()
        val started = launcher.launch(plan).getOrNull()!!
        assertTrue(started is ProcessHandle.Started)
        assertEquals(1, launcher.activeHandles().size)

        // Step 3: The process runs for 5 seconds.
        // Step 4: The process exits with code 0.
        val markResult = launcher.markExited(
            handleId = started.handleId,
            exitCode = 0,
            exitedMs = started.startedMs + 5000L,
        )
        assertTrue(markResult.isSuccess)

        // Step 5: The launcher reports the
        // final state.
        val finalHandle = launcher.getHandle(started.handleId)
        assertTrue(finalHandle is ProcessHandle.Exited)
        val exited = finalHandle as ProcessHandle.Exited
        assertEquals(0, exited.exitCode)
        assertEquals(5000L, exited.durationMs)
        assertEquals(0, launcher.activeHandles().size)
        assertEquals(1, launcher.terminalHandles().size)
    }

    @Test
    fun `realistic scenario multiple processes can be launched and tracked concurrently`() {
        val launcher = InMemoryProcessLauncher()
        val h1 = launcher.launch(buildPlan()).getOrNull()!!
        val h2 = launcher.launch(buildPlan()).getOrNull()!!
        val h3 = launcher.launch(buildPlan()).getOrNull()!!
        assertEquals(3, launcher.activeHandles().size)

        // h2 exits; h3 fails; h1 keeps running.
        launcher.markExited(
            h2.handleId,
            exitCode = 0,
            exitedMs = h2.startedMs + 1000L,
        )
        launcher.markFailed(
            h3.handleId,
            reason = "executable not found",
            failedMs = h3.startedMs + 1000L,
        )

        val active = launcher.activeHandles()
        assertEquals(1, active.size)
        assertEquals(h1.handleId, active[0].handleId)
        assertEquals(2, launcher.terminalHandles().size)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildPlan(
        runtime: LaunchRuntime = LaunchRuntime.BOX64,
        executable: String = "/usr/bin/box64",
        args: List<String> = listOf("/usr/bin/box64", "/opt/steam/steam"),
        workingDirectory: String = "/opt/steam",
    ): LaunchPlan = LaunchPlan(
        runtime = runtime,
        executable = executable,
        args = args,
        workingDirectory = workingDirectory,
        environment = emptyMap(),
    )

    private fun buildStarted(
        handleId: ProcessId = ProcessId.random(),
        plan: LaunchPlan = buildPlan(),
        startedMs: Long = 1_700_000_000_000L,
        pid: Int = 12345,
    ): ProcessHandle.Started = ProcessHandle.Started(
        handleId = handleId,
        plan = plan,
        startedMs = startedMs,
        pid = pid,
    )

    private fun buildExited(
        handleId: ProcessId = ProcessId.random(),
        plan: LaunchPlan = buildPlan(),
        startedMs: Long = 1_700_000_000_000L,
        pid: Int = 12345,
        exitCode: Int = 0,
        exitedMs: Long = 1_700_000_005_000L,
    ): ProcessHandle.Exited = ProcessHandle.Exited(
        handleId = handleId,
        plan = plan,
        startedMs = startedMs,
        pid = pid,
        exitCode = exitCode,
        exitedMs = exitedMs,
    )

    private fun buildFailed(
        handleId: ProcessId = ProcessId.random(),
        plan: LaunchPlan = buildPlan(),
        startedMs: Long = 1_700_000_000_000L,
        failureReason: String = "executable not found",
        failedMs: Long = 1_700_000_005_000L,
    ): ProcessHandle.Failed = ProcessHandle.Failed(
        handleId = handleId,
        plan = plan,
        startedMs = startedMs,
        failureReason = failureReason,
        failedMs = failedMs,
    )
}
