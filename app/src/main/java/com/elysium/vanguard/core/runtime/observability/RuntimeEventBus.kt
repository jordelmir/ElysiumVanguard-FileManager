package com.elysium.vanguard.core.runtime.observability

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 25 — the event bus seam.
 *
 * The [RuntimeEventBus] is the runtime's pub/sub seam.
 * Producers (network broker, hardware enforcer, workspace
 * manager, Windows VM manager, distro manager) call
 * [publish] with a [RuntimeEvent]. Consumers (UI,
 * observability dashboard, crash reporter, audit log)
 * call [subscribe] and receive a callback for every
 * event.
 *
 * Splitting the bus from the consumers + producers
 * keeps the contract JVM-testable end-to-end. The
 * [RecordingEventBus] is the test impl; the production
 * impl is a thin wrapper that fans events out to the
 * subscribed handlers.
 *
 * Subscribers wrap their handler in a try-catch (the
 * bus catches any exception a handler throws and logs
 * it, so a buggy subscriber does not break the
 * publisher).
 */
interface RuntimeEventBus {
    /** Publish [event] to every subscriber. */
    fun publish(event: RuntimeEvent)

    /**
     * Subscribe [handler] to all events. The returned
     * [AutoCloseable] unsubscribes when closed. The
     * handler is invoked on the publisher's thread
     * (the bus does not marshal to a different
     * thread; the production backend's Hilt
     * configuration wires a coroutine-friendly
     * channel adapter when needed).
     */
    fun subscribe(handler: (RuntimeEvent) -> Unit): AutoCloseable

    /** Number of currently-subscribed handlers. */
    fun subscriberCount(): Int
}

/**
 * Production-style bus backed by a `CopyOnWriteArrayList`
 * of handlers. Thread-safe; iteration is a snapshot.
 * Handlers that throw are caught and ignored (the bus
 * logs the exception via the standard error stream
 * in production; tests can verify no handler crashed).
 */
class SynchronizedEventBus : RuntimeEventBus {
    private val handlers = CopyOnWriteArrayList<(RuntimeEvent) -> Unit>()
    @Volatile private var lastCrash: Throwable? = null

    override fun publish(event: RuntimeEvent) {
        for (handler in handlers) {
            try {
                handler(event)
            } catch (e: Throwable) {
                // A buggy subscriber must not break the
                // publisher. We record the crash for
                // tests; production logs it.
                lastCrash = e
            }
        }
    }

    override fun subscribe(handler: (RuntimeEvent) -> Unit): AutoCloseable {
        handlers += handler
        return AutoCloseable { handlers.remove(handler) }
    }

    override fun subscriberCount(): Int = handlers.size

    fun lastCrash(): Throwable? = lastCrash
    fun clearLastCrash() { lastCrash = null }
}

/**
 * Test impl. Records every published event into an
 * in-memory list. The test asserts on [events] and
 * resets via [clear].
 *
 * Also supports per-event subscription via
 * [subscribe] — the recording bus fans events to the
 * subscribers AND records them in the list, so a test
 * can assert on both the "what was recorded" and the
 * "what the subscriber received" channels.
 */
class RecordingEventBus : RuntimeEventBus {
    private val recorded = mutableListOf<RuntimeEvent>()
    private val lock = Any()
    private val subscribers = mutableListOf<(RuntimeEvent) -> Unit>()

    val events: List<RuntimeEvent>
        get() = synchronized(lock) { recorded.toList() }

    fun clear() = synchronized(lock) { recorded.clear() }

    fun size(): Int = synchronized(lock) { recorded.size }

    override fun publish(event: RuntimeEvent) {
        synchronized(lock) {
            recorded += event
            for (sub in subscribers) sub(event)
        }
    }

    override fun subscribe(handler: (RuntimeEvent) -> Unit): AutoCloseable {
        synchronized(lock) { subscribers += handler }
        return AutoCloseable { synchronized(lock) { subscribers.remove(handler) } }
    }

    override fun subscriberCount(): Int = synchronized(lock) { subscribers.size }
}
