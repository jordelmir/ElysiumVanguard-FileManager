package com.elysium.vanguard.core.runtime.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 117 — the test suite for the new
 * `waitFor` callback on [LaunchedProcess].
 *
 * The `waitFor` callback replaces the
 * 60-second polling loop the fileaction
 * backends (disk image, package installer)
 * used before. The previous loop checked
 * `pid <= 0` to detect exit — but PIDs are
 * assigned at fork time, so the check was
 * a no-op and the loop always timed out.
 *
 * The new contract:
 *   - `waitFor` is a `() -> Int` callback.
 *   - The default returns `-1` (legacy
 *     test fakes that only supplied `pid`
 *     + `stop` keep compiling).
 *   - The production [AndroidProcessLauncher]
 *     wires `waitFor = { process.waitFor() }`
 *     so callers get the real OS exit code.
 *   - `stop` and `waitFor` are independent
 *     — `stop` sends SIGKILL, `waitFor`
 *     blocks until the child exits.
 */
class LaunchedProcessWaitForTest {

    @Test
    fun `default waitFor returns -1 for legacy fakes`() {
        val legacy = LaunchedProcess(pid = 1, stop = {})
        assertEquals(-1, legacy.waitFor())
    }

    @Test
    fun `custom waitFor returns the configured exit code`() {
        val lp = LaunchedProcess(
            pid = 42,
            stop = {},
            waitFor = { 7 }
        )
        assertEquals(42, lp.pid)
        assertEquals(7, lp.waitFor())
    }

    @Test
    fun `waitFor is called once per invocation (no internal caching)`() {
        var calls = 0
        val lp = LaunchedProcess(
            pid = 1,
            stop = {},
            waitFor = { calls += 1; calls }
        )
        assertEquals(1, lp.waitFor())
        assertEquals(2, lp.waitFor())
        assertEquals(3, lp.waitFor())
        assertEquals(3, calls)
    }

    @Test
    fun `stop and waitFor are independent callbacks`() {
        var stopped = 0
        val lp = LaunchedProcess(
            pid = 9,
            stop = { stopped += 1 },
            waitFor = { 0 }
        )
        lp.stop()
        lp.stop()
        assertEquals(2, stopped)
        // waitFor still works after stop.
        assertEquals(0, lp.waitFor())
    }

    @Test
    fun `waitFor is the Android production contract`() {
        // The AndroidProcessLauncher wires
        // `waitFor = { process.waitFor() }` —
        // simulating a real OS exit code.
        val osExitCode = 137 // SIGKILL
        val lp = LaunchedProcess(
            pid = 4242,
            stop = { /* process.destroy() */ },
            waitFor = { osExitCode }
        )
        assertEquals(137, lp.waitFor())
    }

    /**
     * The most important regression guard for
     * Phase 117: callers MUST be able to
     * distinguish "process exited with this
     * code" from "waitFor was never called".
     * The 60-second polling loop conflated
     * the two by always returning -1. With
     * the real waitFor, the exit code is
     * preserved verbatim.
     */
    @Test
    fun `exit code from the child is preserved verbatim through waitFor`() {
        listOf(0, 1, 2, 127, 130, 137, 255).forEach { code ->
            val lp = LaunchedProcess(
                pid = 1,
                stop = {},
                waitFor = { code }
            )
            assertEquals("waitFor must preserve exit code $code", code, lp.waitFor())
        }
        // Sanity: a default (no override) returns -1, NOT 0.
        // 0 is the success code, so the default
        // must NOT be confusable with a real exit.
        assertTrue(
            "default waitFor must be distinguishable from a real exit-0",
            LaunchedProcess(pid = 1, stop = {}).waitFor() != 0
        )
    }
}
