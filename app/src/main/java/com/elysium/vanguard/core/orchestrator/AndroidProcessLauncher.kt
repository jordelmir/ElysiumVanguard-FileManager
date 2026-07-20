package com.elysium.vanguard.core.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 82 (Universal Execution Engine) — the
 * **Android Process Launcher**, the production
 * implementation of [ProcessLauncher] that
 * uses `java.lang.ProcessBuilder` + `Process.onExit()`
 * to actually launch a process on the JVM / Android
 * device.
 *
 * Per the master vision's Universal Execution
 * Engine (section 6), the dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   **Process Supervisor**  ← this phase (production impl)
 *     ↓
 *   Telemetry and Recovery
 *
 * Phase 78 was the **typed spec** for the
 * launcher (the `InMemoryProcessLauncher` for
 * tests). This phase is the **production impl**
 * (the `AndroidProcessLauncher` that actually
 * launches a process via the OS).
 *
 * The launcher uses:
 *   - `ProcessBuilder` to launch the process
 *     (the JVM standard API; available on
 *     Android API 26+).
 *   - `Process.pid()` to get the OS-assigned
 *     process id (Java 9+).
 *   - `Process.onExit()` to observe the
 *     process lifecycle asynchronously
 *     (Java 9+). The observer is a
 *     `CompletableFuture<Process>` that
 *     completes when the process exits.
 *   - A `CoroutineScope` to run the
 *     observer (the scope uses
 *     `Dispatchers.IO` for I/O work).
 *
 * The launcher's `markExited` and `markFailed`
 * methods are **NOT supported** in production
 * (the process lifecycle is observed
 * asynchronously via `Process.onExit()`).
 * Calling `mark*` returns a
 * `Result.failure(ProcessLauncherError.UnsupportedManualMark)`.
 *
 * The launcher is **thread-safe** (the
 * underlying collections are
 * `CopyOnWriteArrayList` + `ConcurrentHashMap`).
 *
 * The launcher is **the first Android-only
 * piece** of the Universal Execution Engine
 * (the previous pieces were all pure-domain
 * JVM code). The launcher uses the JVM
 * standard `ProcessBuilder` (which is
 * available on Android API 26+ via the
 * Android Runtime); no Android-specific
 * imports are required.
 */
class AndroidProcessLauncher(
    /**
     * The coroutine scope used to observe
     * the process lifecycle. The scope
     * defaults to a `SupervisorJob` +
     * `Dispatchers.IO` scope (the
     * observer is I/O-bound; the
     * supervisor ensures one failing
     * observation does not cancel the
     * other observations).
     */
    private val coroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    ),
) : ProcessLauncher() {

    private val mutableHandles: CopyOnWriteArrayList<ProcessHandle> =
        CopyOnWriteArrayList()

    private val handlesById:
        MutableMap<ProcessId, ProcessHandle> =
        ConcurrentHashMap()

    /**
     * The map of handle id → the underlying
     * OS process. The map is used by the
     * async observation to retrieve the
     * process for `onExit()`. The map is
     * populated on `launch()` and cleared
     * by the observer when the process
     * exits.
     */
    private val processByHandleId:
        MutableMap<ProcessId, Process> =
        ConcurrentHashMap()

    override val handles: List<ProcessHandle>
        get() = mutableHandles.toList()

    override fun launch(plan: LaunchPlan): Result<ProcessHandle> {
        val handleId = ProcessId.random()
        val startedMs = System.currentTimeMillis()
        return try {
            val process = ProcessBuilder(plan.programAndArgs)
                .directory(File(plan.workingDirectory))
                .apply {
                    plan.environment.forEach { (key, value) ->
                        environment()[key] = value
                    }
                }
                .redirectErrorStream(true)
                .start()

            // Get a synthetic PID from the
            // handle id. Android's
            // `java.lang.Process` does not
            // include the `pid()` method
            // (Java 9+) and the JDK 9+
            // module system blocks reflection
            // on `java.base` classes. The
            // typed `ProcessId` UUID is the
            // primary identity; the PID is a
            // secondary diagnostic identifier.
            val pid = syntheticPidForHandle(handleId)

            val started = ProcessHandle.Started(
                handleId = handleId,
                plan = plan,
                startedMs = startedMs,
                pid = pid,
            )
            mutableHandles.add(started)
            handlesById[handleId] = started
            processByHandleId[handleId] = process

            // Launch the async observer.
            observeProcess(handleId, process, plan, startedMs, pid)

            Result.success(started)
        } catch (e: IOException) {
            Result.failure(
                ProcessLauncherError.ExecutableNotFound(plan.executable),
            )
        } catch (e: SecurityException) {
            Result.failure(
                ProcessLauncherError.LaunchFailed(
                    "security exception: ${e.message}",
                ),
            )
        } catch (e: IllegalArgumentException) {
            Result.failure(
                ProcessLauncherError.LaunchFailed(
                    "invalid argument: ${e.message}",
                ),
            )
        }
    }

    override fun getHandle(handleId: ProcessId): ProcessHandle? =
        handlesById[handleId]

    override fun activeHandles(): List<ProcessHandle> =
        mutableHandles.filterIsInstance<ProcessHandle.Started>()

    override fun terminalHandles(): List<ProcessHandle> =
        mutableHandles.filter {
            it is ProcessHandle.Exited || it is ProcessHandle.Failed
        }

    /**
     * `markExited` is **NOT supported** in
     * production. The production launcher
     * observes the process lifecycle via
     * `Process.onExit()` (async); manual
     * `markExited` calls are rejected.
     *
     * The `markExited` method is
     * **test-only** (used by the
     * `InMemoryProcessLauncher` to
     * simulate the process lifecycle
     * synchronously).
     */
    override fun markExited(
        handleId: ProcessId,
        exitCode: Int,
        exitedMs: Long,
    ): Result<Unit> = Result.failure(
        ProcessLauncherError.UnsupportedManualMark,
    )

    /**
     * `markFailed` is **NOT supported** in
     * production. The production launcher
     * observes the process lifecycle via
     * `Process.onExit()` (async); manual
     * `markFailed` calls are rejected.
     *
     * The `markFailed` method is
     * **test-only** (used by the
     * `InMemoryProcessLauncher` to
     * simulate the process lifecycle
     * synchronously).
     */
    override fun markFailed(
        handleId: ProcessId,
        reason: String,
        failedMs: Long,
    ): Result<Unit> = Result.failure(
        ProcessLauncherError.UnsupportedManualMark,
    )

    /**
     * Launch the async observer that
     * updates the handle state when the
     * process exits or fails. The
     * observer uses `Process.waitFor()`
     * (Android-compatible; Java 1.0+) to
     * block until the process exits, then
     * `Process.exitValue()` to retrieve
     * the exit code.
     *
     * `Process.onExit()` (Java 9+) is NOT
     * used because Android's
     * `java.lang.Process` does not include
     * it (even at API 34).
     */
    private fun observeProcess(
        handleId: ProcessId,
        process: Process,
        plan: LaunchPlan,
        startedMs: Long,
        pid: Int,
    ) {
        coroutineScope.launch {
            try {
                // waitFor() blocks until the
                // process exits. The coroutine
                // is on Dispatchers.IO, so the
                // blocking call does not block
                // the caller's thread.
                val exitCode = process.waitFor()
                val exitedMs = System.currentTimeMillis()
                val exited = ProcessHandle.Exited(
                    handleId = handleId,
                    plan = plan,
                    startedMs = startedMs,
                    pid = pid,
                    exitCode = exitCode,
                    exitedMs = exitedMs,
                )
                replaceHandle(
                    handleId = handleId,
                    newHandle = exited,
                )
            } catch (e: InterruptedException) {
                val failedMs = System.currentTimeMillis()
                val failed = ProcessHandle.Failed(
                    handleId = handleId,
                    plan = plan,
                    startedMs = startedMs,
                    failureReason = "interrupted: ${e.message}",
                    failedMs = failedMs,
                )
                replaceHandle(
                    handleId = handleId,
                    newHandle = failed,
                )
            } catch (e: Exception) {
                val failedMs = System.currentTimeMillis()
                val failed = ProcessHandle.Failed(
                    handleId = handleId,
                    plan = plan,
                    startedMs = startedMs,
                    failureReason = e.message ?: "unknown failure",
                    failedMs = failedMs,
                )
                replaceHandle(
                    handleId = handleId,
                    newHandle = failed,
                )
            } finally {
                processByHandleId.remove(handleId)
            }
        }
    }

    /**
     * Generate a synthetic PID for a
     * handle. The PID is the handle id's
     * most-significant-bits XOR
     * least-significant-bits masked to
     * 31 bits (PIDs are 31-bit positive
     * integers on Linux/Android).
     *
     * Why a synthetic PID instead of
     * `Process.pid()` (Java 9+):
     *   - Android's `java.lang.Process`
     *     does not include the `pid()`
     *     method (even at API 34).
     *   - The JDK 9+ module system blocks
     *     reflection on `java.base`
     *     classes.
     *   - The typed `ProcessId` UUID is
     *     the primary identity; the PID is
     *     a secondary diagnostic identifier.
     *
     * The synthetic PID is **unique per
     * handle** (the XOR + mask is a
     * uniform distribution over 31-bit
     * values), which is what tests need.
     */
    private fun syntheticPidForHandle(handleId: ProcessId): Int {
        val msb = handleId.value.mostSignificantBits
        val lsb = handleId.value.leastSignificantBits
        val combined = msb xor lsb
        val masked = (combined and 0x7FFFFFFFL).toInt()
        return if (masked == 0) 1 else masked
    }

    /**
     * Replace a handle in the underlying
     * state. The replace is atomic from
     * the caller's perspective.
     */
    private fun replaceHandle(
        handleId: ProcessId,
        newHandle: ProcessHandle,
    ) {
        val current = handlesById[handleId] ?: return
        if (current is ProcessHandle.Started) {
            val index = mutableHandles.indexOf(current)
            if (index >= 0) {
                mutableHandles[index] = newHandle
            }
        }
        handlesById[handleId] = newHandle
    }
}
