package com.elysium.vanguard.core.orchestrator

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 90 (Universal Execution Engine) —
 * the **Process Stream Capture**, the
 * typed capture of process stdout/stderr
 * chunks.
 *
 * Per the master vision's Universal
 * Execution Engine (section 6), the
 * dispatch flow is:
 *
 *   Runtime Selection
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   Process Supervisor
 *     ↓
 *   **Telemetry and Recovery**  ← this phase
 *
 * Phase 79 was the **process state
 * tracker** (the lifecycle events).
 * This phase is the **stream capture**
 * (the stdout/stderr chunks). The
 * stream capture is the **read side**
 * of the process I/O.
 *
 * The stream capture is **pure-domain**
 * (no I/O, no Android dependencies). The
 * test impl is the
 * `InMemoryProcessStreamCapture`. The
 * production impl may be the same (the
 * stream capture is a typed record of
 * the chunks; the actual stream reading
 * is the OS executor's responsibility).
 *
 * The stream capture is **thread-safe**
 * (the underlying list is a
 * `CopyOnWriteArrayList` for safe
 * iteration during query + safe mutation
 * during `append`).
 */
sealed class ProcessStreamCapture {

    /**
     * The capture's current state. The
     * state is the list of all chunks
     * (in append order).
     */
    abstract val chunks: List<StreamChunk>

    /**
     * Append a new chunk to the capture.
     * The chunk is added to the end of
     * the list; the existing chunks are
     * preserved. The capture is
     * **append-only**; existing chunks
     * are never modified.
     */
    abstract fun append(chunk: StreamChunk)

    /**
     * Get the stdout chunks for a
     * specific handle. The chunks are
     * in append order. Returns an empty
     * list if the handle has no stdout
     * chunks.
     */
    abstract fun stdoutChunksForHandle(
        handleId: ProcessId,
    ): List<StreamChunk.StdoutChunk>

    /**
     * Get the stderr chunks for a
     * specific handle. The chunks are
     * in append order. Returns an empty
     * list if the handle has no stderr
     * chunks.
     */
    abstract fun stderrChunksForHandle(
        handleId: ProcessId,
    ): List<StreamChunk.StderrChunk>

    /**
     * Concatenate the stdout chunks for
     * a specific handle into a single
     * string. The chunks are
     * concatenated in append order. The
     * separator is the empty string (the
     * chunks are concatenated verbatim).
     */
    fun stdoutAsString(handleId: ProcessId): String =
        stdoutChunksForHandle(handleId)
            .joinToString(separator = "") { it.data }

    /**
     * Concatenate the stderr chunks for
     * a specific handle into a single
     * string. The chunks are
     * concatenated in append order. The
     * separator is the empty string (the
     * chunks are concatenated verbatim).
     */
    fun stderrAsString(handleId: ProcessId): String =
        stderrChunksForHandle(handleId)
            .joinToString(separator = "") { it.data }

    /**
     * The number of chunks in the
     * capture.
     */
    val size: Int
        get() = chunks.size
}

/**
 * The typed stream chunk. The chunk is a
 * sealed class with 3 cases:
 *   - **`StdoutChunk`** — a stdout
 *     chunk.
 *   - **`StderrChunk`** — a stderr
 *     chunk.
 *   - **`StreamClosed`** — the stream
 *     was closed.
 */
sealed class StreamChunk {

    /**
     * The handle id the chunk is for.
     * The id is the join key the
     * consumer uses to filter chunks.
     */
    abstract val handleId: ProcessId

    /**
     * The chunk's timestamp. The
     * timestamp is the millis since
     * epoch the chunk was captured.
     */
    abstract val timestampMs: Long

    /**
     * A stdout chunk. The chunk
     * contains the stdout data emitted
     * by the process.
     */
    data class StdoutChunk(
        override val handleId: ProcessId,
        val data: String,
        override val timestampMs: Long,
    ) : StreamChunk() {
        init {
            require(timestampMs > 0) {
                "StreamChunk.StdoutChunk.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * A stderr chunk. The chunk
     * contains the stderr data emitted
     * by the process.
     */
    data class StderrChunk(
        override val handleId: ProcessId,
        val data: String,
        override val timestampMs: Long,
    ) : StreamChunk() {
        init {
            require(timestampMs > 0) {
                "StreamChunk.StderrChunk.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }

    /**
     * The stream was closed. The
     * `reason` is a human-readable
     * string (e.g. "process exited with
     * code 0" or "process killed").
     */
    data class StreamClosed(
        override val handleId: ProcessId,
        val reason: String,
        override val timestampMs: Long,
    ) : StreamChunk() {
        init {
            require(reason.isNotBlank()) {
                "StreamChunk.StreamClosed.reason " +
                    "must not be blank"
            }
            require(timestampMs > 0) {
                "StreamChunk.StreamClosed.timestampMs " +
                    "must be > 0, got $timestampMs"
            }
        }
    }
}

/**
 * The in-memory [ProcessStreamCapture]
 * for testing + production. The capture
 * is the stateless composition of:
 *   - A list of chunks (in append order).
 *
 * The capture is **thread-safe** (the
 * underlying list is a
 * `CopyOnWriteArrayList` for safe
 * iteration during query + safe mutation
 * during `append`).
 */
class InMemoryProcessStreamCapture :
    ProcessStreamCapture() {

    private val mutableChunks:
        CopyOnWriteArrayList<StreamChunk> =
        CopyOnWriteArrayList()

    override val chunks: List<StreamChunk>
        get() = mutableChunks.toList()

    override fun append(chunk: StreamChunk) {
        mutableChunks.add(chunk)
    }

    override fun stdoutChunksForHandle(
        handleId: ProcessId,
    ): List<StreamChunk.StdoutChunk> =
        mutableChunks.filterIsInstance<StreamChunk.StdoutChunk>()
            .filter { it.handleId == handleId }

    override fun stderrChunksForHandle(
        handleId: ProcessId,
    ): List<StreamChunk.StderrChunk> =
        mutableChunks.filterIsInstance<StreamChunk.StderrChunk>()
            .filter { it.handleId == handleId }
}

/**
 * The typed error envelope for the
 * process stream capture. The error
 * extends `RuntimeException` (mirrors the
 * `FoundryError` contract with `code` +
 * `message`, but lives in the
 * `orchestrator` package because Kotlin
 * sealed classes only permit subclassing
 * in the same package where the base
 * class is declared).
 */
sealed class ProcessStreamError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The stream capture failed. The
     * `reason` is a human-readable
     * string.
     */
    data class StreamCaptureFailed(
        val reason: String,
    ) : ProcessStreamError(
        message = "Process stream capture failed: " +
            reason,
        code = "STREAM_CAPTURE_FAILED",
    )
}
