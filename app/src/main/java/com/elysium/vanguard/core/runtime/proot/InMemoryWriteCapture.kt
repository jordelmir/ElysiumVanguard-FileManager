package com.elysium.vanguard.core.runtime.proot

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 72 — the in-memory [WriteCapture] for tests + dev builds.
 *
 * The capture is pre-populated via [record] / [seed] for tests;
 * the production path uses [AndroidFileObserverWriteCapture]
 * (which delegates to `android.os.FileObserver`).
 *
 * The in-memory impl is intentionally minimal: a set of
 * "watched" paths, a list of captured writes, and a running
 * flag. It is the same shape as the production impl's public
 * surface, so the `ProotBackendReal` can use either one
 * interchangeably.
 *
 * The [writes] list is a [CopyOnWriteArrayList] so the
 * orchestrator can call `writes()` from one thread while the
 * capture is being populated from another (a defensive default;
 * the proot backend serializes its own start/stop, but a
 * concurrent read is safe here).
 */
class InMemoryWriteCapture : WriteCapture {

    private val watched = mutableSetOf<String>()
    private val capturedWrites = CopyOnWriteArrayList<String>()
    private var running: Boolean = false

    @Synchronized
    override fun start(watching: Set<String>) {
        stop()
        watched.clear()
        watched.addAll(watching)
        capturedWrites.clear()
        running = true
    }

    @Synchronized
    override fun stop() {
        running = false
        // Clear the watched set on stop to match the
        // production [AndroidFileObserverWriteCapture]'s
        // behavior (the observers are torn down on
        // stop). The captured writes are preserved
        // until the next `start()` call — `start` is
        // the only path that clears the writes list.
        watched.clear()
    }

    override fun writes(): List<String> = capturedWrites.toList()

    /**
     * Record a write to the given path. Idempotent if not running
     * (silently dropped — the capture was stopped, no audit).
     * The path is **only** recorded if it starts with one of the
     * watched paths (matches the production impl's "events outside
     * the watched set are not captured" semantics).
     */
    fun record(path: String) {
        if (!running) return
        if (watched.none { path.startsWith("$it/") || path == it }) return
        capturedWrites.add(path)
    }

    /**
     * Test-only: pre-seed the capture with writes (bypasses the
     * "watched paths" filter). Useful for asserting orchestrator-
     * level audit logic without spinning up a real FileObserver.
     */
    fun seed(writes: List<String>) {
        capturedWrites.clear()
        capturedWrites.addAll(writes)
        running = true
    }

    /**
     * Test-only: the set of paths the capture is currently
     * watching. Empty when not running.
     */
    fun watchedPaths(): Set<String> = watched.toSet()
}
