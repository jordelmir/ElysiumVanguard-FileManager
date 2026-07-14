package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the reactive [GuestDnsObserver] flow to the per-session
 * `resolv.conf` refreshes held by [ActiveRootfsRegistry].
 *
 * Master order §10.1: the PRoot guest's resolver must follow Android's
 * active network across Wi-Fi / data / VPN / private-DNS transitions.
 * The tracker is the single point that subscribes once at process
 * start and dispatches the resulting "DNS may have changed" signal to
 * every active session.
 *
 * Lifecycle: [start] is idempotent and safe to call from `Application.onCreate`
 * (or Hilt's `@Singleton` first-access). [stop] cancels the subscription
 * and prevents further refreshes until the next [start]. The tracker
 * uses its own [SupervisorJob] so a failure in one refresh does not
 * cancel the whole flow collection.
 *
 * Flow pipeline:
 *
 *   observe → distinctUntilChanged → drop(1) → refreshAll
 *
 *   - `distinctUntilChanged` first so we have a baseline value to
 *     compare against duplicate emissions (the observer occasionally
 *     re-publishes an identical config on a capabilities change).
 *   - `drop(1)` then drops the *initial replay* — the value the flow
 *     had when the tracker subscribed. We don't need to re-apply it
 *     because [start] already called [ActiveRootfsRegistry.refreshAll]
 *     once with the observer's current snapshot.
 *
 * This combination handles three cases:
 *   1. Network stable since launch — initial sync applies the same
 *      value the launcher wrote; the replay is dropped; no extra
 *      refresh.
 *   2. Network changed between launch and tracker start — initial
 *      sync applies the new value; the replay carries the same new
 *      value and is dropped after the distinct comparison.
 *   3. Network changes after the tracker is up — the new emission
 *      is distinct, passes through, and triggers a refresh.
 *
 * Dispatcher: the production Hilt module binds the dispatcher to
 * [kotlinx.coroutines.Dispatchers.Default]. Tests inject a virtual-time
 * dispatcher (e.g. `UnconfinedTestDispatcher`) so the flow collector
 * and the test share the same logical thread.
 */
@Singleton
class GuestDnsSessionTracker constructor(
    private val observer: GuestDnsObserver,
    private val registry: ActiveRootfsRegistry,
    dispatcher: CoroutineDispatcher
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val job = AtomicReference<Job?>(null)

    /**
     * Hilt entry point. Production code uses this constructor; the
     * module provides the default `Dispatchers.Default` for the
     * dispatcher argument.
     */
    @Inject
    constructor(observer: GuestDnsObserver, registry: ActiveRootfsRegistry) :
        this(observer, registry, kotlinx.coroutines.Dispatchers.Default)

    /**
     * Begin collecting network changes. Idempotent: a second call while
     * the tracker is already running is a no-op. Returns true if the
     * tracker is active after the call.
     */
    fun start(): Boolean {
        while (true) {
            val current = job.get()
            if (current?.isActive == true) return true
            // Initial sync: apply whatever the observer has right now.
            // Catches the case where the network changed between the
            // launcher's first write and the tracker subscribing.
            registry.refreshAll()
            val newJob = scope.launch {
                observer.observe()
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { registry.refreshAll() }
            }
            if (job.compareAndSet(current, newJob)) {
                return true
            } else {
                newJob.cancel()
            }
        }
    }

    /**
     * Cancel the subscription. Idempotent. The tracker can be
     * [start]ed again afterwards; the underlying coroutine scope is
     * intentionally never cancelled so the registry stays usable.
     */
    fun stop() {
        job.getAndSet(null)?.cancel()
    }

    /** Diagnostic: whether the tracker is currently collecting. */
    fun isRunning(): Boolean = job.get()?.isActive == true
}
