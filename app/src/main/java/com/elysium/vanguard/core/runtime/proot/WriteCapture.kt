package com.elysium.vanguard.core.runtime.proot

/**
 * Phase 72 — the seam for capturing writes to bind-mounted host
 * paths during a proot session.
 *
 * The capture is the **write-audit half** of the master vision's
 * Definition of Done ("confirme que no hubo escrituras fuera del
 * workspace autorizado"). Before Phase 72 the production
 * `ProotBackendReal` returned an empty writes list, so the
 * `CriticalE2EOrchestrator`'s step 9 was a silent no-op on a real
 * device — the audit passed because there were no writes to check.
 *
 * The capture is started with the set of host paths the orchestrator
 * authorized (the bindMounts' `hostPath`s). It records every write
 * (file create, file modify, file move-in, file close-after-write)
 * to those paths. The proot backend reads the captured writes after
 * the session ends (or when the orchestrator requires the audit) to
 * populate `LaunchResult.writes`.
 *
 * The interface is the seam that lets the capture be JVM-testable:
 * the production impl uses `android.os.FileObserver`; the in-memory
 * impl is a 5-line hand-rolled recorder that tests use to assert
 * the orchestrator's audit logic.
 *
 * Threading: `start()` and `stop()` are called from the proot
 * backend's `launch` / `stop` / `restoreSnapshot` paths (any
 * thread). `writes()` is called after `stop()` (or after launch
 * returns) to read the captured writes. Implementations must be
 * safe to call from multiple threads — the proot backend does not
 * serialize its own callers.
 */
interface WriteCapture {

    /**
     * Start watching the given host paths. Any previous watches
     * are cleared. Calls `stop()` first if already running so a
     * stale observer set never bleeds across sessions.
     *
     * `watching` is the set of host paths to watch. Paths that
     * do not exist or are not directories are silently skipped
     * (no event will fire for them, but no error is raised —
     * a missing mount is a user-authorized no-op).
     */
    fun start(watching: Set<String>)

    /**
     * Stop watching. No-op if not running. The captured writes
     * are **preserved** until the next `start()` call (the
     * orchestrator reads them via `writes()` after `stop()`).
     */
    fun stop()

    /**
     * Snapshot of all writes captured since the last `start()`.
     * Paths are absolute host paths. Order is implementation-
     * defined (Android FileObserver fires in event order; the
     * in-memory impl preserves insertion order).
     */
    fun writes(): List<String>
}
