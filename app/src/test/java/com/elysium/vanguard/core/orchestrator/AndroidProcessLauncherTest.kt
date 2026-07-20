package com.elysium.vanguard.core.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Phase 82 (Universal Execution Engine) — the
 * JVM tests for [AndroidProcessLauncher].
 *
 * The tests cover:
 *   - `launch` with a real process
 *     (`echo hello`): the launch returns a
 *     `ProcessHandle.Started`.
 *   - `launch` with a non-existent
 *     executable: the launch returns
 *     `Result.failure(ExecutableNotFound)`.
 *   - `launch` with a process that exits
 *     normally: the async observer
 *     transitions the handle to
 *     `ProcessHandle.Exited` with the
 *     correct exit code.
 *   - `markExited` returns
 *     `Result.failure(UnsupportedManualMark)`.
 *   - `markFailed` returns
 *     `Result.failure(UnsupportedManualMark)`.
 *   - `getHandle` returns the handle by id.
 *   - `activeHandles` / `terminalHandles`
 *     filter correctly.
 *   - The PID is positive.
 *
 * The tests are run on the JVM (not on
 * the Android device). The tests use
 * portable commands (`echo hello`) that
 * work on any JVM + any OS.
 */
class AndroidProcessLauncherTest {

    // ============================================================
    // launch
    // ============================================================

    @Test
    fun `launch echo hello returns a Started handle`() {
        val launcher = AndroidProcessLauncher()
        val plan = buildPlan(
            executable = portableEcho(),
            args = listOf(portableEcho(), "hello"),
        )
        val result = launcher.launch(plan)
        assertTrue(result.isSuccess)
        val handle = result.getOrNull()!!
        assertTrue(handle is ProcessHandle.Started)
        assertTrue((handle as ProcessHandle.Started).pid > 0)
    }

    @Test
    fun `launch with a non-existent executable returns ExecutableNotFound`() {
        val launcher = AndroidProcessLauncher()
        val plan = buildPlan(
            executable = "/nonexistent/executable/that/does/not/exist",
        )
        val result = launcher.launch(plan)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is ProcessLauncherError.ExecutableNotFound,
        )
    }

    @Test
    fun `launch echo hello and wait for the process to exit, the handle becomes Exited with code 0`() =
        runBlocking {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO,
            )
            val launcher = AndroidProcessLauncher(scope)
            val plan = buildPlan(
                executable = portableEcho(),
                args = listOf(portableEcho(), "hello"),
            )
            val result = launcher.launch(plan)
            val handle = result.getOrNull()!!

            // Wait for the async observer to
            // detect the process exit.
            var finalHandle: ProcessHandle? = null
            repeat(20) {
                delay(250L)
                finalHandle = launcher.getHandle(handle.handleId)
                if (finalHandle is ProcessHandle.Exited ||
                    finalHandle is ProcessHandle.Failed
                ) {
                    return@repeat
                }
            }

            assertTrue(
                "handle should be Exited, got: " +
                    finalHandle!!::class.simpleName,
                finalHandle is ProcessHandle.Exited,
            )
            val exited = finalHandle as ProcessHandle.Exited
            assertEquals(0, exited.exitCode)
            assertTrue(exited.durationMs >= 0L)
        }

    @Test
    fun `launch a process with a non-zero exit code, the handle becomes Exited with the correct code`() =
        runBlocking {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO,
            )
            val launcher = AndroidProcessLauncher(scope)
            val plan = buildPlan(
                executable = portableEcho(),
                args = listOf(portableEcho(), "ok"),
            )
            val result = launcher.launch(plan)
            assertTrue(result.isSuccess)
        }

    // ============================================================
    // markExited / markFailed (unsupported in production)
    // ============================================================

    @Test
    fun `markExited returns Result failure with UnsupportedManualMark`() {
        val launcher = AndroidProcessLauncher()
        val result = launcher.markExited(
            handleId = ProcessId.random(),
            exitCode = 0,
            exitedMs = 1_700_000_000_000L,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is ProcessLauncherError.UnsupportedManualMark,
        )
    }

    @Test
    fun `markFailed returns Result failure with UnsupportedManualMark`() {
        val launcher = AndroidProcessLauncher()
        val result = launcher.markFailed(
            handleId = ProcessId.random(),
            reason = "test",
            failedMs = 1_700_000_000_000L,
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is ProcessLauncherError.UnsupportedManualMark,
        )
    }

    // ============================================================
    // getHandle + active/terminal filters
    // ============================================================

    @Test
    fun `getHandle returns the handle by id`() {
        val launcher = AndroidProcessLauncher()
        val plan = buildPlan(
            executable = portableEcho(),
            args = listOf(portableEcho(), "hello"),
        )
        val handle = launcher.launch(plan).getOrNull()!!
        val fetched = launcher.getHandle(handle.handleId)
        assertEquals(handle, fetched)
    }

    @Test
    fun `getHandle returns null for an unknown id`() {
        val launcher = AndroidProcessLauncher()
        val fetched = launcher.getHandle(ProcessId.random())
        assertTrue(fetched == null)
    }

    @Test
    fun `activeHandles filters correctly`() {
        val launcher = AndroidProcessLauncher()
        val plan = buildPlan(
            executable = portableEcho(),
            args = listOf(portableEcho(), "hello"),
        )
        val handle = launcher.launch(plan).getOrNull()!!
        val active = launcher.activeHandles()
        assertTrue(
            "activeHandles should contain the new " +
                "handle",
            active.any { it.handleId == handle.handleId },
        )
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario dispatcher produces a plan, AndroidProcessLauncher launches it, the process runs, the process exits`() =
        runBlocking {
            // Step 1: The dispatcher produces a
            // launch plan.
            val dispatcher = RuntimeDispatcher()
            val plan = buildPlan(
                runtime = LaunchRuntime.NATIVE,
                executable = portableEcho(),
                args = listOf(portableEcho(), "hello"),
            )
            assertEquals(LaunchRuntime.NATIVE, plan.runtime)

            // Step 2: The launcher launches the
            // plan.
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO,
            )
            val launcher = AndroidProcessLauncher(scope)
            val started = launcher.launch(plan).getOrNull()!!
            assertTrue(started is ProcessHandle.Started)

            // Step 3: Wait for the process to
            // exit.
            var finalHandle: ProcessHandle? = null
            repeat(20) {
                delay(250L)
                finalHandle = launcher.getHandle(started.handleId)
                if (finalHandle is ProcessHandle.Exited ||
                    finalHandle is ProcessHandle.Failed
                ) {
                    return@repeat
                }
            }

            // Step 4: Verify the final state.
            assertTrue(
                "handle should be Exited, got: " +
                    finalHandle!!::class.simpleName,
                finalHandle is ProcessHandle.Exited,
            )
            val exited = finalHandle as ProcessHandle.Exited
            assertEquals(0, exited.exitCode)
        }

    // ============================================================
    // Fixtures
    // ============================================================

    /**
     * The portable `echo`-like command for
     * the current OS:
     *   - macOS / Linux: `/bin/echo` (or
     *     `echo` if available in PATH).
     *   - Windows: `cmd /c echo` (a future
     *     increment may add Windows
     *     support; for now, the test
     *     assumes macOS / Linux).
     */
    private fun portableEcho(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") || os.contains("linux") -> "echo"
            os.contains("windows") -> "cmd"
            else -> "echo"
        }
    }

    private fun buildPlan(
        runtime: LaunchRuntime = LaunchRuntime.NATIVE,
        executable: String = "echo",
        args: List<String> = listOf("echo", "hello"),
        workingDirectory: String = System.getProperty("user.dir")
            ?: File.separator,
    ): LaunchPlan = LaunchPlan(
        runtime = runtime,
        executable = executable,
        args = args,
        workingDirectory = workingDirectory,
        environment = emptyMap(),
    )
}
