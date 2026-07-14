package com.elysium.vanguard.core.runtime.network

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 11.3 — Lifecycle binder tests.
 *
 * The binder wires [GuestDnsSessionTracker] to
 * [androidx.lifecycle.ProcessLifecycleOwner] in production. We test
 * it against a hand-rolled [LifecycleRegistry] so the JVM test
 * classpath is enough.
 *
 * Properties we pin:
 *
 *   - `ON_START` starts the tracker; `isRunning()` flips to true.
 *   - `ON_STOP` stops the tracker; `isRunning()` flips to false.
 *   - A network change while in the foreground triggers a refresh.
 *   - A network change while in the background does NOT trigger a
 *     refresh (the binder paused the subscription).
 *   - Re-foregrounding re-syncs the tracker and resumes reacting to
 *     changes.
 *   - Repeated start events are idempotent.
 *
 * Note: we use [runBlocking] (not `runTest` with virtual time)
 * because `LifecycleRegistry` enforces main-thread access via
 * `ArchTaskExecutor`, and the in-process coroutine scheduling
 * is easier to reason about with real time on the test thread.
 */
class GuestDnsLifecycleBinderTest {

    @Test
    fun `ON_START starts the tracker`() = runBlocking {
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry)
        val binder = GuestDnsLifecycleBinder(tracker)
        val owner = TestLifecycleOwner()
        owner.registry.addObserver(binder)

        assertFalse("tracker must not be running until ON_START", tracker.isRunning())
        // LifecycleRegistry requires ON_CREATE before ON_START.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue("tracker must be running after ON_START", tracker.isRunning())
    }

    @Test
    fun `ON_STOP stops the tracker`() = runBlocking {
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry)
        val binder = GuestDnsLifecycleBinder(tracker)
        val owner = TestLifecycleOwner()
        owner.registry.addObserver(binder)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue("after ON_START", tracker.isRunning())
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertFalse("after ON_STOP", tracker.isRunning())
    }

    @Test
    fun `foreground-to-background-to-foreground cycle is clean`() = runBlocking {
        val config = ArrayConfig().apply { current = GuestDnsConfig(nameservers = listOf("192.0.2.1")) }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        // Use Unconfined so the flow collector runs synchronously
        // on the test thread. Default would schedule on a different
        // thread pool and the `runBlocking` test would race it.
        val tracker = GuestDnsSessionTracker(observer, registry, kotlinx.coroutines.Dispatchers.Unconfined)
        val binder = GuestDnsLifecycleBinder(tracker)
        val rootfs = Files.createTempDirectory("elysium-binder-cycle").toFile()
        val owner = TestLifecycleOwner()
        try {
            val counter = AtomicInteger(0)
            registry.register(rootfs) { counter.incrementAndGet() }
            owner.registry.addObserver(binder)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            val initial = counter.get() // 1 from start's initial sync

            // Network change in foreground → refresh.
            config.current = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
            observer.signalChange()
            assertTrue(
                "foreground network change must refresh",
                counter.get() > initial
            )
            val midCount = counter.get()

            // Move to background.
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertFalse(tracker.isRunning())

            // Network change in background → no refresh.
            config.current = GuestDnsConfig(nameservers = listOf("203.0.113.4"))
            observer.signalChange()
            assertEquals(
                "background network change must NOT refresh",
                midCount,
                counter.get()
            )

            // Back to foreground.
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertTrue(tracker.isRunning())
            // Re-start re-applies the initial sync fee.
            assertTrue(
                "re-foreground must re-sync (counter should grow)",
                counter.get() > midCount
            )
        } finally {
            rootfs.deleteRecursively()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    @Test
    fun `binder does not double-register or leak observers`() = runBlocking {
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry)
        val binder = GuestDnsLifecycleBinder(tracker)
        val owner = TestLifecycleOwner()
        owner.registry.addObserver(binder)
        owner.registry.addObserver(binder)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertTrue(tracker.isRunning())

        // After 5 rapid stop/start cycles, still exactly one logical run.
        repeat(5) {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        assertTrue(tracker.isRunning())
    }

    @Test
    fun `binder survives multiple start events without extra refreshes`() = runBlocking {
        val config = ArrayConfig().apply { current = GuestDnsConfig(nameservers = listOf("192.0.2.1")) }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })
        val registry = ActiveRootfsRegistry()
        val tracker = GuestDnsSessionTracker(observer, registry)
        val binder = GuestDnsLifecycleBinder(tracker)
        val rootfs = Files.createTempDirectory("elysium-binder-multi").toFile()
        val owner = TestLifecycleOwner()
        try {
            val counter = AtomicInteger(0)
            registry.register(rootfs) { counter.incrementAndGet() }
            owner.registry.addObserver(binder)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            // Three ON_START events in a row. Each one calls
            // tracker.start() but the tracker is idempotent — only
            // the first pays the initial-sync fee.
            repeat(3) { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
            // The initial sync fires once, even though start() was
            // called three times. Plus no spurious extra refreshes.
            assertEquals(
                "three ON_START events must yield exactly one initial sync",
                1,
                counter.get()
            )
        } finally {
            rootfs.deleteRecursively()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.INITIALIZED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUpArchExecutor() {
        // LifecycleRegistry enforces main-thread access; the JVM test
        // runtime has no main Looper, so we install a no-op executor
        // that pretends every thread is the main thread and runs
        // posted tasks inline.
        ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) { runnable.run() }
            override fun postToMainThread(runnable: Runnable) { runnable.run() }
            override fun isMainThread(): Boolean = true
        })
    }

    @After
    fun tearDownArchExecutor() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
