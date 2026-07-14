package com.elysium.vanguard.core.runtime.network

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Set of guest rootfses that currently have at least one live process,
 * each paired with the callback that re-derives its DNS bind mount.
 *
 * Master order §10.1: the guest's resolver must follow Android's active
 * network. The registry is the bridge between "a session is alive on
 * this rootfs" and "this is how to refresh its resolv.conf without
 * re-launching the process".
 *
 * Lifecycle:
 *
 *   1. The launcher (or whoever owns the rootfs at runtime) calls
 *      [register] when the first session on a rootfs starts.
 *   2. [GuestDnsSessionTracker] calls [refreshAll] on every observed
 *      network change. The closure runs on the caller's coroutine
 *      context; failures are isolated so one bad rootfs does not block
 *      the rest.
 *   3. The launcher calls [unregister] when the last session on the
 *      rootfs exits.
 *
 * Thread-safety: all public methods hold the same internal monitor.
 * Refresher closures may run on any thread; the registry does not own
 * their dispatch.
 */
@Singleton
class ActiveRootfsRegistry @Inject constructor() {
    private val lock = Any()
    private val refreshers = mutableMapOf<File, () -> Unit>()

    /**
     * Bind a rootfs to its DNS refresh closure. The [refresher] is
     * invoked with [rootfs] itself, so the caller can write
     * `register(rootfs, launcher::refreshDnsForRootfs)` without a
     * manual `let` lambda. The closure is stored as `() -> Unit`
     * internally so the existing tracker / test paths can keep
     * using the parameterless shape.
     *
     * Re-registering the same rootfs replaces the previous closure
     * (idempotent for repeated start events on the same rootfs).
     */
    fun register(rootfs: File, refresher: (File) -> Unit) {
        require(rootfs.isDirectory) { "rootfs must be an existing directory: $rootfs" }
        synchronized(lock) { refreshers[rootfs] = { refresher(rootfs) } }
    }

    /**
     * Remove a rootfs from the active set. No-op if it was not
     * registered. Safe to call from any thread.
     */
    fun unregister(rootfs: File) {
        synchronized(lock) { refreshers.remove(rootfs) }
    }

    /**
     * Snapshot of the currently active rootfses. The returned set is a
     * defensive copy — callers may iterate without holding the lock.
     */
    fun activeRootfses(): Set<File> = synchronized(lock) { refreshers.keys.toSet() }

    /**
     * Run the refresh closure for every active rootfs. Failures from
     * one rootfs are swallowed (and returned in the result list) so a
     * broken guest cannot starve the others.
     *
     * Returns a list of rootfs paths whose refresher threw, so callers
     * can surface them to observability without rethrowing.
     */
    fun refreshAll(): List<File> {
        val toRun = synchronized(lock) { refreshers.toMap() }
        val failures = mutableListOf<File>()
        for ((rootfs, refresher) in toRun) {
            try {
                refresher()
            } catch (failure: Throwable) {
                failures += rootfs
            }
        }
        return failures
    }

    /** Number of active rootfses; primarily for diagnostics. */
    fun size(): Int = synchronized(lock) { refreshers.size }
}
