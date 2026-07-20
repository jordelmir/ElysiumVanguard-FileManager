package com.elysium.vanguard.core.orchestrator

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 78 (Universal Execution Engine) — the
 * **Process Launcher**, the orchestrator that
 * actually launches the process based on a
 * [LaunchPlan] (the [RuntimeDispatcher]'s
 * output).
 *
 * Per the master vision's Universal Execution
 * Engine (section 6), the dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   **Process Supervisor**  ← this phase
 *     ↓
 *   Process Running
 *
 * The [RuntimeSelector] decides **WHAT to
 * do**; the [RuntimeDispatcher] **DOES it**
 * (produces the launch plan); the
 * [ProcessLauncher] **EXECUTES it** (launches
 * the process + tracks the handle).
 *
 * The launcher is the **typed bridge** from
 * "what to launch" to "the running process".
 * The launcher's input is a [LaunchPlan]
 * (from [RuntimeDispatcher.dispatch]); the
 * launcher's output is a [ProcessHandle]
 * (the typed identity + state of the
 * running process).
 *
 * The launcher is **pure-domain** for the
 * test impl. The test impl is the
 * `InMemoryProcessLauncher`. The production
 * impl is the `AndroidProcessLauncher`
 * (a future increment that uses
 * `java.lang.Process` + `ProcessBuilder`
 * under the hood). The production impl is
 * **Android-only** (the launcher uses the
 * Android `Process` API which is not
 * available in the JVM unit test classpath).
 *
 * The launcher is **thread-safe** (the
 * underlying list is `CopyOnWriteArrayList`
 * for safe iteration during query).
 */
sealed class ProcessLauncher {

    /**
     * The launcher's current state. The
     * state is the list of all process
     * handles (in launch order).
     */
    abstract val handles: List<ProcessHandle>

    /**
     * Launch a new process from a
     * [LaunchPlan]. The launcher executes
     * the plan (in the test impl, this
     * records the launch attempt; in the
     * production impl, this calls
     * `ProcessBuilder.start()`).
     *
     * Returns a `Result.success(ProcessHandle.Started)`
     * if the launch succeeded. Returns
     * `Result.failure(ProcessLauncherError)`
     * if the launch failed (executable not
     * found, permission denied, working
     * directory not found).
     */
    abstract fun launch(plan: LaunchPlan): Result<ProcessHandle>

    /**
     * Get a process handle by id. Returns
     * `null` if the handle is not tracked.
     */
    abstract fun getHandle(handleId: ProcessId): ProcessHandle?

    /**
     * Get the active (running) handles.
     * The active handles are the
     * `ProcessHandle.Started` instances
     * (the process is still running).
     */
    abstract fun activeHandles(): List<ProcessHandle>

    /**
     * Get the terminal handles. The
     * terminal handles are the
     * `ProcessHandle.Exited` instances
     * (the process exited normally) + the
     * `ProcessHandle.Failed` instances
     * (the process failed to launch OR
     * crashed).
     */
    abstract fun terminalHandles(): List<ProcessHandle>

    /**
     * Mark a handle as exited. The
     * launcher transitions the handle
     * from `Started` → `Exited` (with
     * the exit code).
     *
     * This is **test-only**. The
     * production Android impl observes
     * the process lifecycle via
     * `Process.onExit()` (Java 9+); the
     * production impl does not have a
     * `markExited` method.
     *
     * Returns a `Result.failure(ProcessLauncherError.HandleNotFound)`
     * if the handle is not tracked.
     */
    abstract fun markExited(
        handleId: ProcessId,
        exitCode: Int,
        exitedMs: Long,
    ): Result<Unit>

    /**
     * Mark a handle as failed. The
     * launcher transitions the handle
     * from `Started` → `Failed` (with
     * the failure reason).
     *
     * This is **test-only**. The
     * production Android impl observes
     * the process lifecycle via
     * `Process.onExit()` (Java 9+); the
     * production impl does not have a
     * `markFailed` method.
     *
     * Returns a `Result.failure(ProcessLauncherError.HandleNotFound)`
     * if the handle is not tracked.
     */
    abstract fun markFailed(
        handleId: ProcessId,
        reason: String,
        failedMs: Long,
    ): Result<Unit>
}

/**
 * The typed identity of a launched
 * process. The id is a UUID (per the
 * Foundry id convention).
 *
 * The id is distinct from a Linux `pid`
 * (process id); the launcher can track
 * the same logical process across
 * restarts (a future increment may
 * support process restart with the same
 * id).
 */
@JvmInline
value class ProcessId(val value: UUID) {
    companion object {
        fun random(): ProcessId = ProcessId(UUID.randomUUID())
        fun from(raw: String): Result<ProcessId> = try {
            Result.success(ProcessId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(
                ProcessLauncherError.InvalidProcessIdFormat(raw, e),
            )
        }
    }
}

/**
 * The typed handle to a launched process.
 * The handle is a sealed class with 3
 * cases:
 *   - **`Started`** — the process is
 *     running.
 *   - **`Exited`** — the process exited
 *     normally (with an exit code).
 *   - **`Failed`** — the process failed to
 *     launch OR crashed (with a failure
 *     reason).
 *
 * The handle is **immutable** (a sealed
 * class with data class cases; no
 * setters). A new handle state is a new
 * `ProcessHandle` value, not a mutation
 * of the existing one.
 */
sealed class ProcessHandle {

    /**
     * The handle's unique id. The id is
     * the join key the launcher uses to
     * track the process.
     */
    abstract val handleId: ProcessId

    /**
     * The plan the process was launched
     * from. The plan is the launch
     * command + the runtime arguments.
     */
    abstract val plan: LaunchPlan

    /**
     * The handle's start timestamp. The
     * timestamp is the millis since epoch
     * the process was launched.
     */
    abstract val startedMs: Long

    /**
     * The handle's runtime. The runtime
     * is the same as the plan's runtime
     * (a convenience accessor for
     * filtering).
     */
    val runtime: LaunchRuntime
        get() = plan.runtime

    /**
     * A started process. The process is
     * running; the launch was successful.
     */
    data class Started(
        override val handleId: ProcessId,
        override val plan: LaunchPlan,
        override val startedMs: Long,
        /**
         * The process's PID (process id,
         * assigned by the OS). The PID
         * is the join key the OS uses to
         * reference the process.
         */
        val pid: Int,
    ) : ProcessHandle() {
        init {
            require(pid > 0) {
                "ProcessHandle.Started.pid must be > 0, got $pid"
            }
            require(startedMs > 0) {
                "ProcessHandle.Started.startedMs must be > 0, " +
                    "got $startedMs"
            }
        }
    }

    /**
     * An exited process. The process
     * exited normally (with an exit
     * code).
     */
    data class Exited(
        override val handleId: ProcessId,
        override val plan: LaunchPlan,
        override val startedMs: Long,
        /**
         * The process's PID (process id,
         * assigned by the OS).
         */
        val pid: Int,
        /**
         * The process's exit code. The
         * exit code is the integer the
         * process returned to the OS
         * (per Unix convention: 0 =
         * success, non-zero = error).
         */
        val exitCode: Int,
        /**
         * The handle's exit timestamp.
         * The timestamp is the millis
         * since epoch the process
         * exited.
         */
        val exitedMs: Long,
    ) : ProcessHandle() {
        init {
            require(pid > 0) {
                "ProcessHandle.Exited.pid must be > 0, got $pid"
            }
            require(startedMs > 0) {
                "ProcessHandle.Exited.startedMs must be > 0, " +
                    "got $startedMs"
            }
            require(exitedMs > 0) {
                "ProcessHandle.Exited.exitedMs must be > 0, " +
                    "got $exitedMs"
            }
            require(exitedMs >= startedMs) {
                "ProcessHandle.Exited.exitedMs ($exitedMs) must be " +
                    ">= startedMs ($startedMs)"
            }
        }

        /**
         * Compute the process's
         * duration. The duration is the
         * number of millis the process
         * ran (exitedMs - startedMs).
         */
        val durationMs: Long
            get() = exitedMs - startedMs
    }

    /**
     * A failed process. The process
     * failed to launch OR crashed (with
     * a failure reason).
     */
    data class Failed(
        override val handleId: ProcessId,
        override val plan: LaunchPlan,
        override val startedMs: Long,
        /**
         * The failure reason. The
         * reason is a human-readable
         * string (e.g. "executable not
         * found", "permission denied",
         * "segmentation fault").
         */
        val failureReason: String,
        /**
         * The handle's failure
         * timestamp. The timestamp is
         * the millis since epoch the
         * process failed.
         */
        val failedMs: Long,
    ) : ProcessHandle() {
        init {
            require(failureReason.isNotBlank()) {
                "ProcessHandle.Failed.failureReason must not be blank"
            }
            require(startedMs > 0) {
                "ProcessHandle.Failed.startedMs must be > 0, " +
                    "got $startedMs"
            }
            require(failedMs > 0) {
                "ProcessHandle.Failed.failedMs must be > 0, " +
                    "got $failedMs"
            }
            require(failedMs >= startedMs) {
                "ProcessHandle.Failed.failedMs ($failedMs) must be " +
                    ">= startedMs ($startedMs)"
            }
        }
    }
}

/**
 * The in-memory [ProcessLauncher] for
 * testing. The launcher is the stateless
 * composition of:
 *   - A list of process handles (in launch
 *     order).
 *
 * The launcher is **thread-safe** (the
 * underlying list is a
 * `CopyOnWriteArrayList` for safe iteration
 * during query + safe mutation during
 * `markExited`/`markFailed`).
 *
 * The launcher's `launch` method records the
 * launch attempt with a fake PID (the
 * handle id's least-significant 31 bits)
 * and returns a `ProcessHandle.Started`.
 * The test then calls `markExited` or
 * `markFailed` to simulate the process
 * lifecycle.
 */
class InMemoryProcessLauncher : ProcessLauncher() {

    private val mutableHandles: CopyOnWriteArrayList<ProcessHandle> =
        CopyOnWriteArrayList()

    /**
     * A fast lookup of handle by id. The
     * map is rebuilt on every mutation.
     */
    private val handlesById:
        MutableMap<ProcessId, ProcessHandle> =
        java.util.concurrent.ConcurrentHashMap()

    override val handles: List<ProcessHandle>
        get() = mutableHandles.toList()

    override fun launch(plan: LaunchPlan): Result<ProcessHandle> {
        val handleId = ProcessId.random()
        val startedMs = System.currentTimeMillis()
        val pid = fakePidForHandle(handleId)
        val started = ProcessHandle.Started(
            handleId = handleId,
            plan = plan,
            startedMs = startedMs,
            pid = pid,
        )
        mutableHandles.add(started)
        handlesById[handleId] = started
        return Result.success(started)
    }

    override fun getHandle(handleId: ProcessId): ProcessHandle? =
        handlesById[handleId]

    override fun activeHandles(): List<ProcessHandle> =
        mutableHandles.filterIsInstance<ProcessHandle.Started>()

    override fun terminalHandles(): List<ProcessHandle> =
        mutableHandles.filter {
            it is ProcessHandle.Exited || it is ProcessHandle.Failed
        }

    override fun markExited(
        handleId: ProcessId,
        exitCode: Int,
        exitedMs: Long,
    ): Result<Unit> {
        val current = handlesById[handleId]
            ?: return Result.failure(
                ProcessLauncherError.HandleNotFound(handleId),
            )
        if (current !is ProcessHandle.Started) {
            return Result.failure(
                ProcessLauncherError.HandleNotStarted(handleId),
            )
        }
        val exited = ProcessHandle.Exited(
            handleId = handleId,
            plan = current.plan,
            startedMs = current.startedMs,
            pid = current.pid,
            exitCode = exitCode,
            exitedMs = exitedMs,
        )
        replaceHandle(current, exited)
        return Result.success(Unit)
    }

    override fun markFailed(
        handleId: ProcessId,
        reason: String,
        failedMs: Long,
    ): Result<Unit> {
        val current = handlesById[handleId]
            ?: return Result.failure(
                ProcessLauncherError.HandleNotFound(handleId),
            )
        if (current !is ProcessHandle.Started) {
            return Result.failure(
                ProcessLauncherError.HandleNotStarted(handleId),
            )
        }
        val failed = ProcessHandle.Failed(
            handleId = handleId,
            plan = current.plan,
            startedMs = current.startedMs,
            failureReason = reason,
            failedMs = failedMs,
        )
        replaceHandle(current, failed)
        return Result.success(Unit)
    }

    /**
     * Replace a handle in the underlying
     * list. The replace is atomic from
     * the caller's perspective.
     */
    private fun replaceHandle(
        oldHandle: ProcessHandle,
        newHandle: ProcessHandle,
    ) {
        val index = mutableHandles.indexOf(oldHandle)
        if (index >= 0) {
            mutableHandles[index] = newHandle
        }
        handlesById[newHandle.handleId] = newHandle
    }

    /**
     * Generate a fake PID for a handle.
     * The fake PID is the handle id's
     * least-significant 31 bits (PIDs are
     * 31-bit positive integers on
     * Linux/Android).
     */
    private fun fakePidForHandle(handleId: ProcessId): Int {
        val msb = handleId.value.mostSignificantBits
        val lsb = handleId.value.leastSignificantBits
        // Combine the two longs into a
        // single long, then mask to 31
        // bits + add 1 (avoid PID 0).
        val combined = msb xor lsb
        val masked = (combined and 0x7FFFFFFFL).toInt()
        return if (masked == 0) 1 else masked
    }
}

/**
 * The typed error envelope for the
 * process launcher. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the
 * `orchestrator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 *
 * Per `.ai/STANDARDS.md` section 7 +
 * `.ai/AGENTS.md` section 24.1:
 *   - A free-form string is never the
 *     value of an error.
 */
sealed class ProcessLauncherError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The process id string was not a
     * valid UUID. Raised at the boundary
     * (per `.ai/AGENTS.md` 24.1) — never
     * inside the domain.
     */
    data class InvalidProcessIdFormat(
        val rawInput: String,
        val parseFailure: Throwable,
    ) : ProcessLauncherError(
        message = "Invalid UUID format for ProcessId: $rawInput",
        code = "INVALID_PROCESS_ID_FORMAT",
    )

    /**
     * The handle id is not tracked. The
     * client must launch a process
     * before querying / mutating the
     * handle.
     */
    data class HandleNotFound(
        val handleId: ProcessId,
    ) : ProcessLauncherError(
        message = "Process handle not found: ${handleId.value}",
        code = "HANDLE_NOT_FOUND",
    )

    /**
     * The handle is not in the `Started`
     * state. The client can only mark a
     * started handle as exited / failed.
     * Marking an already-terminal handle
     * is rejected (the lifecycle is
     * append-only).
     */
    data class HandleNotStarted(
        val handleId: ProcessId,
    ) : ProcessLauncherError(
        message = "Process handle not in Started state: " +
            "${handleId.value}",
        code = "HANDLE_NOT_STARTED",
    )
}
