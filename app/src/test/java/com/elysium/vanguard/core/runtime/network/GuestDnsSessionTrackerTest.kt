package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 11.1 — Tracker unit tests.
 *
 * The tracker subscribes once to the [GuestDnsObserver] and re-dispatches
 * to the [ActiveRootfsRegistry] on every network change. We verify:
 *
 *   - `start()` runs an initial refresh pass that applies the observer's
 *     current snapshot (catches a network flip between launch and start);
 *   - subsequent `observe` emissions trigger one extra pass each;
 *   - duplicate consecutive configs do not re-trigger (`distinctUntilChanged`);
 *   - a fresh rootfs registered *after* the tracker started is picked up
 *     by the next change;
 *   - `stop()` prevents further refreshes until `start()` is called again.
 *
 * `UnconfinedTestDispatcher` (sharing the test scheduler) keeps the
 * flow collector and the test on the same logical thread, so
 * `advanceUntilIdle` deterministically flushes the queued coroutines.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuestDnsSessionTrackerTest {

    @Test
    fun `start is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)

        assertTrue(tracker.start())
        assertTrue(tracker.start()) // second call must be a no-op
        assertTrue(tracker.isRunning())
        tracker.stop()
    }

    @Test
    fun `stop cancels the subscription and is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)

        tracker.start()
        tracker.stop()
        assertFalse(tracker.isRunning())
        tracker.stop() // must not throw
    }

    @Test
    fun `start runs an initial refresh pass`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val rootfs = Files.createTempDirectory("elysium-tracker-init").toFile()
        try {
            val counter = AtomicInteger(0)
            registry.register(rootfs) { counter.incrementAndGet() }

            tracker.start()
            advanceUntilIdle()
            assertEquals("start() must apply the current snapshot once", 1, counter.get())
        } finally {
            rootfs.deleteRecursively()
            tracker.stop()
        }
    }

    @Test
    fun `start after stop re-attaches and re-syncs`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val rootfs = Files.createTempDirectory("elysium-tracker-restart").toFile()
        try {
            val counter = AtomicInteger(0)
            registry.register(rootfs) { counter.incrementAndGet() }

            tracker.start()
            advanceUntilIdle()
            assertEquals(1, counter.get())

            tracker.stop()
            // A network change while stopped must NOT trigger a refresh.
            observer.signalChange()
            advanceUntilIdle()
            assertEquals("stop must prevent further refreshes", 1, counter.get())

            tracker.start()
            advanceUntilIdle()
            // Re-start applies the current snapshot again.
            assertEquals(2, counter.get())
        } finally {
            rootfs.deleteRecursively()
            tracker.stop()
        }
    }

    @Test
    fun `network change with new content triggers a refresh pass`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val config = ArrayConfig().apply {
            current = GuestDnsConfig(nameservers = listOf("192.0.2.1"))
        }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val rootfs = Files.createTempDirectory("elysium-tracker-change").toFile()
        try {
            val counter = AtomicInteger(0)
            registry.register(rootfs) { counter.incrementAndGet() }

            tracker.start()
            advanceUntilIdle()
            val baseline = counter.get() // 1 from the initial sync

            config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
            observer.signalChange()
            advanceUntilIdle()
            assertEquals(baseline + 1, counter.get())

            config.current = GuestDnsConfig(nameservers = listOf("203.0.113.4"))
            observer.signalChange()
            advanceUntilIdle()
            assertEquals(baseline + 2, counter.get())
        } finally {
            rootfs.deleteRecursively()
            tracker.stop()
        }
    }

    @Test
    fun `duplicate consecutive configs do not double-refresh`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val config = ArrayConfig().apply {
            current = GuestDnsConfig(nameservers = listOf("192.0.2.1"))
        }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val rootfs = Files.createTempDirectory("elysium-tracker-dup").toFile()
        try {
            val counter = AtomicInteger(0)
            registry.register(rootfs) { counter.incrementAndGet() }

            tracker.start()
            advanceUntilIdle()
            val baseline = counter.get() // 1 from the initial sync

            // Same value re-emitted: distinctUntilChanged drops it.
            observer.signalChange()
            observer.signalChange()
            advanceUntilIdle()
            assertEquals("duplicate signalChange must not trigger a refresh", baseline, counter.get())
        } finally {
            rootfs.deleteRecursively()
            tracker.stop()
        }
    }

    @Test
    fun `rootfs registered after start is refreshed on the next change`() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val config = ArrayConfig().apply {
            current = GuestDnsConfig(nameservers = listOf("192.0.2.1"))
        }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry, dispatcher)
        val rootfs = Files.createTempDirectory("elysium-tracker-late").toFile()
        try {
            val counter = AtomicInteger(0)
            // Register BEFORE start so the initial sync picks it up.
            registry.register(rootfs) { counter.incrementAndGet() }

            tracker.start()
            advanceUntilIdle()
            assertEquals(1, counter.get())

            config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
            observer.signalChange()
            advanceUntilIdle()
            assertEquals(2, counter.get())
        } finally {
            rootfs.deleteRecursively()
            tracker.stop()
        }
    }

    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
