package com.elysium.vanguard.core.runtime.runner

import java.io.File
import java.io.IOException

/**
 * Phase 36 — the production [ProcessLauncher] for Android.
 *
 * The launcher wraps [ProcessBuilder] (the JVM's standard
 * way to fork a child process on Android) and exposes a
 * [LaunchedProcess] whose `pid` is the child OS pid and
 * whose `stop()` callback destroys the process. The
 * [LinuxProotSessionRunner] holds one of these per running
 * session.
 *
 * Android-process notes:
 *
 *   - The child pid is read via reflection on
 *     [Process.toHandle] (a Java 9+ API that libcore
 *     exposes on Android API 26+). The reflection is
 *     needed because the JVM unit-test classpath ships
 *     the Android stub `Process` (no `toHandle()`) and
 *     would otherwise fail to compile.
 *   - The child is destroyed via [Process.destroy], which
 *     on Android sends `SIGKILL`. The runner then reaps
 *     the child with [Process.waitFor] to read the exit
 *     code; the wait happens on the runner's per-session
 *     stop path, not here.
 *   - We do NOT call [ProcessBuilder.redirectErrorStream]
 *     here; the distro layer's [com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher]
 *     owns the stdin / stdout / stderr wiring (the runner
 *     only sees pid + stop).
 *
 * Thread safety: the [Process] returned by
 * [ProcessBuilder.start] is itself thread-safe for
 * [Process.destroy] + [Process.waitFor]. The launcher
 * returns a new [Process] per call; no shared state.
 */
class AndroidProcessLauncher : ProcessLauncher {

    override fun start(
        command: List<String>,
        env: List<Pair<String, String>>,
        cwd: File
    ): LaunchedProcess {
        require(command.isNotEmpty()) { "command list must not be empty" }
        if (!cwd.isDirectory) {
            throw IOException("cwd is not a directory: $cwd")
        }
        val builder = ProcessBuilder(command)
            .directory(cwd)
        // Replace the entire env with what the caller
        // passed. The host's PATH / HOME are NOT
        // inherited unless they appear in `env` (per
        // the interface contract).
        val envMap = builder.environment()
        envMap.clear()
        for ((k, v) in env) envMap[k] = v
        val process: Process = try {
            builder.start()
        } catch (e: IOException) {
            throw IOException("Failed to start process '${command.first()}': ${e.message}", e)
        } catch (e: SecurityException) {
            throw IOException("Process start denied by SecurityManager: ${e.message}", e)
        }
        val pid: Int = readPid(process)
        return LaunchedProcess(
            pid = pid,
            stop = { process.destroy() },
            // PHASE 117 — the production `waitFor` delegates to
            // `Process.waitFor()` (Android-compatible; Java 1.0+).
            // The fileaction backends (`ProcessLauncherDiskImageBackend`,
            // `ProcessLauncherPackageInstaller`) call this in place of
            // the 60-second polling loop they used before.
            waitFor = { process.waitFor() }
        )
    }

    /**
     * Read the OS pid of a spawned [process] via the
     * Java 9+ [Process.toHandle] API. Falls back to
     * -1 when the API is unavailable (pre-26 Android
     * or the JVM unit-test classpath, which ships the
     * Android stub `Process`). The pid is mostly
     * informational — the [LaunchedProcess.stop]
     * callback closes over the [Process] reference
     * directly, so a missing pid does not prevent
     * shutdown.
     */
    private fun readPid(process: Process): Int = try {
        val toHandle = Process::class.java.getMethod("toHandle")
        val handle = toHandle.invoke(process)
        val pidMethod = handle.javaClass.getMethod("pid")
        (pidMethod.invoke(handle) as Long).toInt()
    } catch (e: NoSuchMethodException) {
        -1
    } catch (e: ReflectiveOperationException) {
        -1
    }
}

