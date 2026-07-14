package com.elysium.vanguard.core.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 11.1 — Registry unit tests.
 *
 * The registry is the only piece of mutable state in the DNS refresh
 * path. It must:
 *
 *   - be safe to call from any thread (the tracker runs on
 *     `Dispatchers.Default`, the launcher registers from coroutines
 *     on `Dispatchers.IO`, the test can hit it from any pool);
 *   - never propagate a refresher exception back to the caller (one
 *     bad rootfs must not starve the others);
 *   - never double-register without replacing the previous closure
 *     (the launcher may call `register` on every session start, not
 *     just the first).
 */
class ActiveRootfsRegistryTest {

    @Test
    fun `register and unregister reflect in activeRootfses`() {
        val registry = ActiveRootfsRegistry()
        val rootfs = Files.createTempDirectory("elysium-registry").toFile()
        try {
            registry.register(rootfs) {}
            assertEquals(setOf(rootfs), registry.activeRootfses())
            assertEquals(1, registry.size())

            registry.unregister(rootfs)
            assertTrue(registry.activeRootfses().isEmpty())
            assertEquals(0, registry.size())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `unregister of an unknown rootfs is a no-op`() {
        val registry = ActiveRootfsRegistry()
        val rootfs = Files.createTempDirectory("elysium-registry").toFile()
        try {
            registry.unregister(rootfs)
            assertTrue(registry.activeRootfses().isEmpty())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `register replaces the previous closure on the same rootfs`() {
        val registry = ActiveRootfsRegistry()
        val rootfs = Files.createTempDirectory("elysium-registry").toFile()
        try {
            var first = 0
            var second = 0
            registry.register(rootfs) { first++ }
            registry.register(rootfs) { second++ }

            val failures = registry.refreshAll()
            assertTrue(failures.isEmpty())
            assertEquals(0, first)
            assertEquals(1, second)
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `refreshAll runs every registered closure exactly once`() {
        val registry = ActiveRootfsRegistry()
        val rootfsA = Files.createTempDirectory("elysium-reg-a").toFile()
        val rootfsB = Files.createTempDirectory("elysium-reg-b").toFile()
        try {
            val counterA = AtomicInteger(0)
            val counterB = AtomicInteger(0)
            registry.register(rootfsA) { counterA.incrementAndGet() }
            registry.register(rootfsB) { counterB.incrementAndGet() }

            val failures = registry.refreshAll()
            assertTrue(failures.isEmpty())
            assertEquals(1, counterA.get())
            assertEquals(1, counterB.get())
        } finally {
            rootfsA.deleteRecursively()
            rootfsB.deleteRecursively()
        }
    }

    @Test
    fun `refreshAll isolates a failing refresher from the others`() {
        val registry = ActiveRootfsRegistry()
        val rootfsA = Files.createTempDirectory("elysium-reg-fa").toFile()
        val rootfsB = Files.createTempDirectory("elysium-reg-fb").toFile()
        try {
            val counterB = AtomicInteger(0)
            registry.register(rootfsA) { error("dns refresh boom") }
            registry.register(rootfsB) { counterB.incrementAndGet() }

            val failures = registry.refreshAll()
            assertEquals(listOf(rootfsA), failures)
            assertEquals(1, counterB.get())
        } finally {
            rootfsA.deleteRecursively()
            rootfsB.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `register rejects a missing rootfs directory`() {
        val registry = ActiveRootfsRegistry()
        registry.register(File("/no/such/elysium/rootfs")) {}
    }

    @Test
    fun `concurrent registration and refresh complete without losing updates`() {
        // Two-phase barrier: 4 register threads finish *before* the
        // 4 refresh threads start. This guarantees every rootfs is
        // present in the registry when refreshAll iterates, so the
        // "every refresher ran" assertion is meaningful. The race we
        // actually want to exercise is the refresh path under
        // contention: 4 threads hitting `refreshAll` while another
        // thread is mid-`register` or mid-`unregister`.
        val registry = ActiveRootfsRegistry()
        val rootfses = (1..16).map {
            Files.createTempDirectory("elysium-concurrent-$it").toFile()
        }
        val invocations = rootfses.associateWith { AtomicInteger(0) }
        val registrationDone = CountDownLatch(1)
        val refreshDone = CountDownLatch(4)
        try {
            // Phase 1: register all rootfses from 4 threads. We use
            // a per-thread slice and *wait* for the last thread to
            // finish before any refresh thread starts, so every
            // rootfs is present in the registry by the time the
            // refreshes begin.
            val registers = (0 until 4).map { threadIndex ->
                Thread {
                    val slice = rootfses.chunked(4)[threadIndex]
                    for (rootfs in slice) {
                        registry.register(rootfs) { invocations[rootfs]?.incrementAndGet() }
                    }
                    if (threadIndex == 3) registrationDone.countDown()
                }
            }
            registers.forEach { it.start() }
            registers.forEach { it.join(5_000) }
            assertTrue("registration phase must finish", registrationDone.count == 0L)

            // Phase 2: 4 refresh threads, each hammering refreshAll.
            // The registry must remain consistent and every closure
            // must be invoked at least once.
            val startRefresh = CountDownLatch(1)
            val refreshers = (0 until 4).map {
                Thread {
                    startRefresh.await()
                    for (pass in 0 until 25) {
                        registry.refreshAll()
                    }
                    refreshDone.countDown()
                }
            }
            refreshers.forEach { it.start() }
            startRefresh.countDown()
            assertTrue(refreshDone.await(15, TimeUnit.SECONDS))
            for (rootfs in rootfses) {
                assertTrue(
                    "rootfs $rootfs registered but never refreshed",
                    (invocations[rootfs]?.get() ?: 0) > 0
                )
            }
        } finally {
            rootfses.forEach { it.deleteRecursively() }
        }
    }
}
