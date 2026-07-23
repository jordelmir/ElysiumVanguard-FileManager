package com.elysium.vanguard.core.runtime.runner

import java.io.File

/**
 * Phase 30 — the JVM-testable seam for "spawn a host OS process".
 *
 * The [ProcessLauncher] is the only seam the [SessionRunner] uses to
 * actually start / stop a process. Production wires the Android
 * implementation (which calls `ProcessBuilder.start()` and exposes
 * `ProcessHandle.toHandle().pid()` + `Process.destroy()`); tests wire
 * a no-op implementation that records the call and returns a
 * hand-rolled [LaunchedProcess] with a fake pid + a no-op stop
 * callback.
 *
 * Why a small interface (not a wrapper around `ProcessBuilder`): the
 * runner only needs the pid (so it can record a `SessionState.Running`)
 * and a way to stop the process. Everything else (stdin / stdout /
 * stderr wiring, environment propagation, working directory) is the
 * launcher impl's job — the runner treats the launched process as a
 * black box with `pid` and `stop()`.
 *
 * The interface is `AutoCloseable`-free: a [LaunchedProcess] is a
 * value object, not a resource. The Android process handle is closed
 * when the OS reaps the process; the runner does not hold an
 * open file descriptor.
 */
interface ProcessLauncher {

    /**
     * Start a new process.
     *
     * @param command the argv the launcher will exec. The first entry
     *   is the binary; remaining entries are arguments. The launcher
     *   MUST not interpret shell metacharacters — the command list
     *   is the canonical form.
     * @param env environment variables in `KEY=VALUE` form. The
     *   launcher MUST set every key on the child process. (The
     *   host's `PATH` and `HOME` are NOT inherited unless they
     *   appear in this list — the runner passes the full env it
     *   wants the child to see.)
     * @param cwd the working directory for the child process.
     *
     * @return a [LaunchedProcess] whose `pid` is the OS process id
     *   of the spawned child.
     *
     * @throws java.io.IOException when the launcher could not start
     *   the process (binary missing, cwd missing, OOM, etc.). The
     *   caller treats this as a `SessionStartFailed` and rolls
     *   the session back to `Error`.
     */
    fun start(command: List<String>, env: List<Pair<String, String>>, cwd: File): LaunchedProcess
}

/**
 * The handle to a launched process. Captures the pid, a stop
 * callback, and (Phase 117+) a `waitFor` callback that blocks
 * until the child exits and returns the OS exit code.
 *
 * The [SessionRunner] holds one of these per running session
 * so a `stop()` call can find the right child, and the
 * fileaction backends (disk-image, package installer) call
 * `waitFor()` to reap short-lived helper processes
 * (`qemu-img convert`, `mount -o loop`, etc.).
 *
 * The `stop()` and `waitFor()` callbacks are intentionally
 * `() -> Unit` / `() -> Int`, not `Process` references — the
 * production launcher closes over the `Process` inside the
 * callbacks, the test launcher closes over counters / canned
 * values. The runner + backends treat the callbacks as opaque.
 *
 * Phase 117 added [waitFor] to replace the 60-second polling
 * loop the fileaction backends used before (the polling
 * could not actually detect process exit because PIDs are
 * assigned at fork time, so the loop always timed out).
 * The default is a no-op returning `-1` so old test fakes
 * that only supply `pid` + `stop` keep compiling.
 */
data class LaunchedProcess(
    val pid: Int,
    val stop: () -> Unit,
    /**
     * Block until the launched process exits, then return its
     * OS exit code. Implementations MUST be safe to call from
     * a background coroutine (the fileaction backends call this
     * from `Dispatchers.IO`); blocking on the caller's thread
     * is acceptable because the call sites are already on an
     * I/O dispatcher. Implementations MUST return a value even
     * if the process was already reaped (the typical contract
     * is `Process.waitFor()` returns the cached exit code, not
     * `IllegalThreadStateException`).
     *
     * The default returns `-1` so legacy test fakes (Phase 94
     * and earlier) that only supplied `pid` + `stop` keep
     * compiling. Production launchers (Phase 117+) populate
     * this with the underlying `Process.waitFor()`.
     */
    val waitFor: () -> Int = { -1 }
)
