package com.elysium.vanguard.core.orchestrator

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 79 (Universal Execution Engine) — the
 * **Process Watcher**, the observer that records
 * the lifecycle events emitted by the
 * [ProcessLauncher] (Phase 78).
 *
 * Per the master vision's Universal Execution
 * Engine (section 6), the dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   Process Supervisor (Phase 78)
 *     ↓
 *   **Telemetry and Recovery**  ← this phase
 *
 * The [ProcessWatcher] is the **telemetry
 * collector**. The watcher's input is a stream
 * of [ProcessEvent]s emitted by the
 * [ProcessLauncher] (or by the OS in the
 * production case); the watcher's output is a
 * queryable list of events per handle.
 *
 * The watcher is the **typed bridge** between
 * the launcher's state transitions + the
 * downstream consumers (the orchestrator's
 * recovery policy, the UI's process monitor,
 * the audit log, the analytics).
 *
 * The watcher is **pure-domain** (no I/O, no
 * Android dependencies). The test impl is the
 * `InMemoryProcessWatcher`. The production
 * impl is the `AndroidProcessWatcher` (a
 * future increment that uses
 * `Process.onExit()` + a coroutine scope to
 * collect events from the real process).
 *
 * The watcher is **thread-safe** (the
 * underlying event list is
 * `CopyOnWriteArrayList` for safe iteration
 * during query + safe mutation during
 * `emit`).
 */
sealed class ProcessWatcher {

    /**
     * The watcher's current state. The
     * state is the list of all events
     * (in emit order) + the set of
     * watched handle ids.
     */
    abstract val events: List<ProcessEvent>

    /**
     * The set of handle ids the watcher
     * is currently watching.
     */
    abstract val watchedHandles: Set<ProcessId>

    /**
     * Subscribe to events for a handle.
     * The subscription is **idempotent**
     * (watching the same handle twice
     * has no effect).
     *
     * Returns a `Result.failure(ProcessWatcherError.HandleNotFound)`
     * if the handle is not in the
     * launcher's registry. The watcher
     * validates against the launcher
     * before subscribing.
     */
    abstract fun watch(
        handleId: ProcessId,
        launcher: ProcessLauncher,
    ): Result<Unit>

    /**
     * Unsubscribe from events for a
     * handle. The unsubscription is
     * **idempotent** (unwatching a
     * non-watched handle has no effect).
     */
    abstract fun unwatch(handleId: ProcessId)

    /**
     * Emit a new event. The event is
     * recorded in the events list.
     *
     * The emit is **append-only**; the
     * events list is never reordered.
     */
    abstract fun emit(event: ProcessEvent)

    /**
     * Get all events for a specific
     * handle. The events are in emit
     * order. Returns an empty list if
     * the handle has no events.
     */
    abstract fun eventsForHandle(handleId: ProcessId): List<ProcessEvent>

    /**
     * Get the most recent event for a
     * specific handle. Returns `null`
     * if the handle has no events.
     */
    abstract fun latestEventForHandle(
        handleId: ProcessId,
    ): ProcessEvent?

    /**
     * Count the events for a specific
     * handle. Returns 0 if the handle
     * has no events.
     */
    abstract fun countEventsForHandle(
        handleId: ProcessId,
    ): Int
}

/**
 * The typed lifecycle event. The event
 * is emitted by the [ProcessLauncher]
 * when a state transition happens.
 *
 * The event is a sealed class with 4
 * cases:
 *   - **`Started`** — the process was
 *     launched.
 *   - **`Exited`** — the process exited
 *     normally (with an exit code).
 *   - **`Failed`** — the process failed
 *     to launch OR crashed.
 *   - **`Heartbeat`** — periodic
 *     heartbeat (the process is still
 *     running).
 */
sealed class ProcessEvent {

    /**
     * The handle id the event is for.
     * The id is the join key the
     * consumer uses to filter events.
     */
    abstract val handleId: ProcessId

    /**
     * The event's timestamp. The
     * timestamp is the millis since
     * epoch the event was emitted.
     */
    abstract val timestampMs: Long

    /**
     * A started event. The process
     * was launched successfully.
     */
    data class Started(
        override val handleId: ProcessId,
        /**
         * The process's PID (process
         * id, assigned by the OS).
         */
        val pid: Int,
        override val timestampMs: Long,
    ) : ProcessEvent() {
        init {
            require(pid > 0) {
                "ProcessEvent.Started.pid must be > 0, got $pid"
            }
            require(timestampMs > 0) {
                "ProcessEvent.Started.timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * An exited event. The process
     * exited normally.
     */
    data class Exited(
        override val handleId: ProcessId,
        /**
         * The process's exit code. The
         * exit code is the integer the
         * process returned to the OS
         * (per Unix convention: 0 =
         * success, non-zero = error).
         */
        val exitCode: Int,
        /**
         * The process's duration. The
         * duration is the number of
         * millis the process ran
         * (exitedMs - startedMs).
         */
        val durationMs: Long,
        override val timestampMs: Long,
    ) : ProcessEvent() {
        init {
            require(durationMs > 0) {
                "ProcessEvent.Exited.durationMs must be > 0, " +
                    "got $durationMs"
            }
            require(timestampMs > 0) {
                "ProcessEvent.Exited.timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A failed event. The process
     * failed to launch OR crashed.
     */
    data class Failed(
        override val handleId: ProcessId,
        /**
         * The failure reason. The
         * reason is a human-readable
         * string.
         */
        val failureReason: String,
        /**
         * The process's duration. The
         * duration is the number of
         * millis the process ran
         * before failing.
         */
        val durationMs: Long,
        override val timestampMs: Long,
    ) : ProcessEvent() {
        init {
            require(failureReason.isNotBlank()) {
                "ProcessEvent.Failed.failureReason must not be blank"
            }
            require(durationMs >= 0) {
                "ProcessEvent.Failed.durationMs must be >= 0, " +
                    "got $durationMs"
            }
            require(timestampMs > 0) {
                "ProcessEvent.Failed.timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }

    /**
     * A heartbeat event. The process
     * is still running; the watcher
     * emits periodic heartbeats to
     * confirm liveness.
     */
    data class Heartbeat(
        override val handleId: ProcessId,
        /**
         * The process's uptime. The
         * uptime is the number of
         * millis the process has run
         * since launch.
         */
        val uptimeMs: Long,
        override val timestampMs: Long,
    ) : ProcessEvent() {
        init {
            require(uptimeMs >= 0) {
                "ProcessEvent.Heartbeat.uptimeMs must be >= 0, " +
                    "got $uptimeMs"
            }
            require(timestampMs > 0) {
                "ProcessEvent.Heartbeat.timestampMs must be > 0, " +
                    "got $timestampMs"
            }
        }
    }
}

/**
 * The in-memory [ProcessWatcher] for
 * testing. The watcher is the stateless
 * composition of:
 *   - A list of events (in emit order).
 *   - A set of watched handle ids.
 *
 * The watcher is **thread-safe** (the
 * underlying list is a
 * `CopyOnWriteArrayList` for safe
 * iteration during query + safe mutation
 * during `emit`; the watched set is a
 * `ConcurrentHashMap.newKeySet()` for
 * thread-safe add/remove/contains).
 */
class InMemoryProcessWatcher : ProcessWatcher() {

    private val mutableEvents: CopyOnWriteArrayList<ProcessEvent> =
        CopyOnWriteArrayList()

    private val mutableWatchedHandles:
        MutableSet<ProcessId> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    override val events: List<ProcessEvent>
        get() = mutableEvents.toList()

    override val watchedHandles: Set<ProcessId>
        get() = mutableWatchedHandles.toSet()

    override fun watch(
        handleId: ProcessId,
        launcher: ProcessLauncher,
    ): Result<Unit> {
        if (launcher.getHandle(handleId) == null) {
            return Result.failure(
                ProcessWatcherError.HandleNotFound(handleId),
            )
        }
        mutableWatchedHandles.add(handleId)
        return Result.success(Unit)
    }

    override fun unwatch(handleId: ProcessId) {
        mutableWatchedHandles.remove(handleId)
    }

    override fun emit(event: ProcessEvent) {
        mutableEvents.add(event)
    }

    override fun eventsForHandle(
        handleId: ProcessId,
    ): List<ProcessEvent> =
        mutableEvents.filter { it.handleId == handleId }

    override fun latestEventForHandle(
        handleId: ProcessId,
    ): ProcessEvent? =
        mutableEvents
            .filter { it.handleId == handleId }
            .lastOrNull()

    override fun countEventsForHandle(
        handleId: ProcessId,
    ): Int =
        mutableEvents.count { it.handleId == handleId }
}

/**
 * The typed error envelope for the
 * process watcher. The error extends
 * `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the
 * `orchestrator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class ProcessWatcherError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The handle id is not in the
     * launcher's registry. The client
     * must launch a process before
     * subscribing to its events.
     */
    data class HandleNotFound(
        val handleId: ProcessId,
    ) : ProcessWatcherError(
        message = "Process handle not found in launcher: " +
            "${handleId.value}",
        code = "WATCHER_HANDLE_NOT_FOUND",
    )
}
