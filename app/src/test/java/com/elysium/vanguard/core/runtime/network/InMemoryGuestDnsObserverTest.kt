package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11.x — DNS observer unit tests.
 *
 * The master order §10.1 requires the guest's resolver to follow the device's
 * active network across Wi-Fi/data/VPN/private-DNS transitions. The pure-JVM
 * [InMemoryGuestDnsObserver] is the testable seam that backs the production
 * [AndroidGuestDnsObserver]; these tests pin its contract.
 *
 * The codebase does not depend on `kotlinx-coroutines-test`, so we exercise
 * the suspend boundary through `runBlocking` — the same pattern used by
 * the other coroutine-bearing tests in this module.
 */
class InMemoryGuestDnsObserverTest {

    @Test
    fun `observe emits the initial snapshot on subscribe`() = runBlocking {
        val expected = GuestDnsConfig(
            nameservers = listOf("192.0.2.10", "192.0.2.11"),
            searchDomains = listOf("home.example")
        )
        val observer = InMemoryGuestDnsObserver(snapshot = { expected })

        val actual = observer.observe().first()

        assertEquals(expected, actual)
    }

    @Test
    fun `observe re-emits the new snapshot after a network change`() = runBlocking {
        val wifi = GuestDnsConfig(nameservers = listOf("192.0.2.10"))
        val mobile = GuestDnsConfig(nameservers = listOf("198.51.100.7"))
        val config = ArrayConfig().apply { current = wifi }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })

        val emissions = mutableListOf<GuestDnsConfig>()
        val first = observer.observe().first()
        emissions += first
        config.current = mobile
        emissions += observer.signalChange()
        config.current = wifi
        emissions += observer.signalChange()

        assertEquals(listOf(wifi, mobile, wifi), emissions)
    }

    @Test
    fun `current returns the empty config when the snapshot is empty`() {
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })

        // We never call [observe], so the in-memory cache is still null
        // and [current] falls back to EMPTY.
        assertEquals(GuestDnsConfig.EMPTY, observer.current())
    }

    @Test
    fun `refresh re-reads the snapshot and updates current`() = runBlocking {
        val fresh = GuestDnsConfig(nameservers = listOf("192.0.2.20"))
        val config = ArrayConfig()
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })

        config.current = fresh
        observer.refresh()
        val tail = observer.observe().first()

        assertSame(fresh, tail)
    }

    @Test
    fun `observe keeps the same value when the snapshot is unchanged`() = runBlocking {
        val config = ArrayConfig().apply {
            current = GuestDnsConfig(nameservers = listOf("192.0.2.30"))
        }
        val observer = InMemoryGuestDnsObserver(snapshot = { config.current })

        // Take two emissions with the same snapshot; the observer must
        // still emit (one per signal) — conflation lives in the consumer.
        val emissions = mutableListOf<GuestDnsConfig>()
        emissions += observer.signalChange()
        emissions += observer.signalChange()

        assertEquals(2, emissions.size)
        assertEquals(emissions[0], emissions[1])
    }

    @Test
    fun `observe does not throw when the snapshot returns empty nameservers`() = runBlocking {
        val observer = InMemoryGuestDnsObserver(snapshot = { GuestDnsConfig.EMPTY })

        // The constructor validates the empty config, so we should be
        // able to push it through [observe] without an exception.
        val value = observer.observe().first()
        assertTrue(value.nameservers.isEmpty())
    }

    /**
     * Mutable holder that lets the snapshot lambda observe a change
     * between calls. We cannot reassign a `val` in a captured lambda.
     */
    private class ArrayConfig {
        var current: GuestDnsConfig = GuestDnsConfig.EMPTY
    }
}
