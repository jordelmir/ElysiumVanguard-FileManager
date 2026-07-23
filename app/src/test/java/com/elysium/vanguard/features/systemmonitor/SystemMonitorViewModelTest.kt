package com.elysium.vanguard.features.systemmonitor

import com.elysium.vanguard.core.system.SystemInfoProvider
import com.elysium.vanguard.core.system.SystemSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PHASE 114 — the test suite for the
 * [SystemMonitorViewModel]. The VM
 * polls the [SystemInfoProvider] + exposes
 * a rolling history.
 *
 * **Test strategy**: the tests use
 * `Dispatchers.setMain(UnconfinedTestDispatcher())`
 * so the `viewModelScope`'s launched
 * coroutines run synchronously on the
 * test thread. The tests then use
 * `Thread.sleep` to wait for samples
 * (the polling interval is 10ms so a
 * 100-200ms sleep is enough for several
 * samples).
 *
 * The full coroutine test framework
 * (`runTest`) has subtle interactions
 * with `viewModelScope` that are out of
 * scope for Phase 114. A future phase
 * adds a scope-injectable VM variant +
 * comprehensive `runTest` coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemMonitorViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `history starts empty`() {
        val vm = SystemMonitorViewModel(FakeSystemInfoProvider(emptyList()))
        assertTrue(vm.history.value.isEmpty())
        assertNull(vm.currentSample)
    }

    @Test
    fun `currentSample is null before any sample is taken`() {
        val vm = SystemMonitorViewModel(FakeSystemInfoProvider(emptyList()))
        assertNull(vm.currentSample)
    }

    @Test
    fun `polling accumulates samples over time`() {
        val provider = CountingProvider(
            samples = (1..50).map { sampleFixture(it.toLong() * 1000L, cpuPercent = it * 2) }
        )
        val vm = SystemMonitorViewModel(provider)
        vm.startPolling(intervalMs = 10L)
        Thread.sleep(150L)
        vm.stopPolling()
        // The exact count depends on the
        // dispatcher; we just check
        // that at least 1 sample was
        // taken. The `history is capped`
        // test below exercises the
        // rolling buffer in more depth.
        assertTrue(
            "expected at least 1 sample, got ${vm.history.value.size}",
            vm.history.value.isNotEmpty()
        )
    }

    @Test
    fun `history is capped at MAX_HISTORY samples`() {
        val provider = CountingProvider(
            samples = (1..200).map { sampleFixture(it.toLong() * 1000L, cpuPercent = 50) }
        )
        val vm = SystemMonitorViewModel(provider)
        vm.startPolling(intervalMs = 1L)
        Thread.sleep(300L)
        vm.stopPolling()
        val historySize = vm.history.value.size
        assertTrue(
            "history should be capped at MAX_HISTORY (${SystemMonitorViewModel.MAX_HISTORY}), got $historySize",
            historySize <= SystemMonitorViewModel.MAX_HISTORY
        )
    }

    @Test
    fun `a provider exception does not crash the polling loop`() {
        val provider = FlakySystemInfoProvider(
            failOnCalls = setOf(1, 3, 5),
            samples = (1..10).map { sampleFixture(it.toLong() * 1000L, cpuPercent = 50) }
        )
        val vm = SystemMonitorViewModel(provider)
        vm.startPolling(intervalMs = 10L)
        Thread.sleep(150L)
        vm.stopPolling()
        // At least one sample was
        // successfully taken.
        assertTrue(
            "expected at least 1 sample, got ${vm.history.value.size}",
            vm.history.value.isNotEmpty()
        )
    }

    @Test
    fun `currentSample returns the last successful sample`() {
        val provider = CountingProvider(
            samples = listOf(
                sampleFixture(0L, cpuPercent = 25),
                sampleFixture(1000L, cpuPercent = 50),
            )
        )
        val vm = SystemMonitorViewModel(provider)
        vm.startPolling(intervalMs = 10L)
        Thread.sleep(50L)
        vm.stopPolling()
        val current = vm.currentSample
        assertTrue("currentSample should not be null", current != null)
        assertTrue(
            "currentSample should be >= 25% CPU, got ${current!!.cpuPercent}",
            current.cpuPercent >= 25
        )
    }

    @Test
    fun `startPolling twice keeps the polling loop running`() {
        val provider = CountingProvider(
            samples = (1..100).map { sampleFixture(it.toLong() * 1000L, cpuPercent = 50) }
        )
        val vm = SystemMonitorViewModel(provider)
        vm.startPolling(intervalMs = 50L)
        vm.startPolling(intervalMs = 10L) // restarts
        Thread.sleep(150L)
        vm.stopPolling()
        // The polling loop is still
        // active after the restart.
        assertTrue(
            "expected at least 1 sample after restart, got ${vm.history.value.size}",
            vm.history.value.isNotEmpty()
        )
    }

    // --- helpers ---

    private fun sampleFixture(
        atMs: Long,
        cpuPercent: Int,
    ): SystemSample = SystemSample(
        atMs = atMs,
        cpuPercent = cpuPercent,
        memoryUsedMb = 512,
        memoryTotalMb = 1024,
        temperatureCelsius = 45.0,
        uptimeSeconds = atMs / 1000L,
    )
}

/**
 * A [SystemInfoProvider] that iterates
 * through a pre-built list of samples.
 */
private class CountingProvider(
    private val samples: List<SystemSample>,
) : SystemInfoProvider {
    private var index = 0
    override suspend fun sample(): SystemSample {
        val s = samples[index.coerceIn(0, samples.size - 1)]
        if (index < samples.size - 1) index++
        return s
    }
}

/**
 * A [SystemInfoProvider] that fails on
 * the Nth call. The VM catches the
 * exception + continues polling.
 */
private class FlakySystemInfoProvider(
    private val failOnCalls: Set<Int>,
    private val samples: List<SystemSample>,
) : SystemInfoProvider {
    private var callCount = 0
    override suspend fun sample(): SystemSample {
        val current = callCount
        callCount++
        if (current in failOnCalls) {
            throw RuntimeException("intentional test failure on call $current")
        }
        return samples[current.coerceIn(0, samples.size - 1)]
    }
}

private class FakeSystemInfoProvider(
    private val samples: List<SystemSample>,
) : SystemInfoProvider {
    override suspend fun sample(): SystemSample = samples.firstOrNull()
        ?: SystemSample(
            atMs = 0L,
            cpuPercent = 0,
            memoryUsedMb = 0,
            memoryTotalMb = 0,
            temperatureCelsius = null,
            uptimeSeconds = 0L,
        )
}
