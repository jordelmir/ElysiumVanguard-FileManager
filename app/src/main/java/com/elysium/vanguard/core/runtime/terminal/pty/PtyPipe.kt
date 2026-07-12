package com.elysium.vanguard.core.runtime.terminal.pty

import java.io.InputStream
import java.io.OutputStream

/**
 * Legacy pipe harness retained for JVM tests.
 *
 * Production terminal sessions use [NativePty], backed by Elysium's Rust
 * runtime. This file models ordinary Java pipes only for deterministic tests;
 * it must not be selected by a production terminal session.
 */
interface PtyPipe {
    /**
     * Stream the child reads from (its stdin). We close this side when
     * the parent wants to signal EOF on the child's stdin.
     */
    fun slaveInput(): InputStream

    /**
     * Stream the child writes to (its stdout). The child's actual stdout
     * is connected here; the parent reads it indirectly via the
     * matching `masterOutput()`.
     */
    fun slaveOutput(): OutputStream

    /** Stream the parent reads from (mirrors the child's stdout). */
    fun masterOutput(): InputStream

    /** Stream the parent writes to (mirrors the child's stdin). */
    fun masterInput(): OutputStream

    /**
     * Set the window size (rows × cols). No-op for pipes; meaningful for
     * a real native PTY's TIOCSWINSZ path.
     */
    fun setWindowSize(rows: Int, cols: Int)

    /** Close all halves. Idempotent. */
    fun close()
}

/**
 * Pipe-backed test fixture.
 *
 * Builds two [java.io.PipedInputStream] / [PipedOutputStream] pairs and
 * exposes the right halves:
 *
 *   - Pair A: parent → child (parent's `masterInput()` writes, child's
 *     `slaveInput()` reads).
 *   - Pair B: child → parent (child's `slaveOutput()` writes, parent's
 *     `masterOutput()` reads).
 *
 * Pipes are unlimited-buffer; we cap at 64 KB so a runaway child
 * doesn't OOM the terminal UI thread.
 */
class PipePty private constructor(
    /**
     * Reader half of Pair A — connected to what the child reads from.
     * The parent writes into [masterInput] which connects to this reader.
     */
    private val parentToChildReader: java.io.PipedInputStream,

    /**
     * Writer half of Pair A — connected to what the parent writes into.
     * We expose this as `masterInput()`; the child reads from
     * [slaveInput] which is the same data.
     */
    private val parentToChildWriter: java.io.PipedOutputStream,

    /** Reader half of Pair B — connected to what the parent reads from. */
    private val childToParentReader: java.io.PipedInputStream,

    /**
     * Writer half of Pair B — connected to what the parent writes into.
     * We expose this as `masterOutput()` — that's a poor name but it
     * matches the semantic (the parent reads from what the child writes).
     */
    private val childToParentWriter: java.io.PipedOutputStream
) : PtyPipe {

    /**
     * The child reads from parentToChildReader; expose that as
     * [slaveInput].
     */
    override fun slaveInput(): InputStream = parentToChildReader

    /** The parent writes to parentToChildWriter; expose that as [masterInput]. */
    override fun masterInput(): OutputStream = parentToChildWriter

    /** The child writes to childToParentWriter; expose that as [slaveOutput]. */
    override fun slaveOutput(): OutputStream = childToParentWriter

    /** The parent reads from childToParentReader; expose that as [masterOutput]. */
    override fun masterOutput(): InputStream = childToParentReader

    override fun setWindowSize(rows: Int, cols: Int) {
        // Pipes have no window-size concept. A future NativePty will
        // route these bytes through TIOCSWINSZ ioctls.
    }

    @Synchronized
    override fun close() {
        for (s in arrayOf(parentToChildReader, parentToChildWriter, childToParentReader, childToParentWriter)) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val PIPE_BUF = 64 * 1024

        fun create(): PipePty {
            val (aReader, aWriter) = pipe(PIPE_BUF)
            val (bReader, bWriter) = pipe(PIPE_BUF)
            // Mapping in terms of "parent writes here" / "parent reads
            // there":
            //   - parent writes to (aWriter); child reads from (aReader)
            //     so `masterInput = aWriter` and `slaveInput = aReader`.
            //   - child writes to (bWriter); parent reads from (bReader)
            //     so `masterOutput = bReader` and `slaveOutput = bWriter`.
            return PipePty(
                parentToChildReader = aReader,
                parentToChildWriter = aWriter,
                childToParentReader = bReader,
                childToParentWriter = bWriter
            )
        }

        private fun pipe(buf: Int): Pair<java.io.PipedInputStream, java.io.PipedOutputStream> {
            val input = java.io.PipedInputStream(buf)
            val output = java.io.PipedOutputStream(input)
            return input to output
        }
    }
}

/**
 * Deprecated test-only factory. Production code must not route a terminal
 * through pipes and claim interactive TUI support.
 */
@Deprecated("Use NativePty for production terminal sessions")
object PtyFactory {
    fun create(): PtyPipe = PipePty.create()
}
