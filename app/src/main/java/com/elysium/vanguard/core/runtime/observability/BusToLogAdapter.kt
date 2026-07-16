package com.elysium.vanguard.core.runtime.observability

/**
 * Phase 25 — the bus-to-log adapter.
 *
 * The [BusToLogAdapter] is the seam that connects the
 * in-memory [RuntimeEventBus] to the persistent
 * [RuntimeEventLog]. Producers (network broker, hardware
 * enforcer, workspace manager, ...) call [publish] on
 * the bus; the adapter's subscribed handler appends each
 * event to the log file.
 *
 * The adapter is a single subscriber; other consumers
 * (UI, observability dashboard, crash reporter) can
 * subscribe independently. The adapter does NOT block
 * the publisher's thread: a slow log write can back up
 * the bus (the [RuntimeEventBus.publish] is synchronous).
 * A future phase swaps the synchronous append for a
 * background queue.
 */
class BusToLogAdapter(
    private val bus: RuntimeEventBus,
    private val log: RuntimeEventLog
) : AutoCloseable {
    private val subscription: AutoCloseable = bus.subscribe { event ->
        runCatching { log.append(event) }
    }

    override fun close() = subscription.close()
}
