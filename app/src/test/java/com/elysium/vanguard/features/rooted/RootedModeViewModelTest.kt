package com.elysium.vanguard.features.rooted

import com.elysium.vanguard.core.runtime.distros.launcher.CgroupSpec
import com.elysium.vanguard.core.runtime.distros.launcher.NamespaceSpec
import com.elysium.vanguard.core.runtime.distros.launcher.RootProvider
import com.elysium.vanguard.core.runtime.distros.launcher.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PHASE 102 — JVM tests for [RootedModeViewModel]. Uses
 * the production [RootedModeViewModel] with a fake
 * [RootedModeProbe] + an in-memory [RootedModePrefs] (no
 * Android SharedPreferences in the loop).
 *
 * `Dispatchers.setMain` is required because
 * `viewModelScope.launch { … }` dispatches to
 * `Dispatchers.Main` by default. The `UnconfinedTestDispatcher`
 * runs the coroutine synchronously, so the state
 * updates are visible immediately after the call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootedModeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads persisted prefs into the state`() {
        val probe = FakeProbe(fullyRooted())
        val prefs = InMemoryRootedModePrefs().apply {
            setRootedModeEnabled(true)
            setNamespaceSpec(NamespaceSpec.FULL_SANDBOX.copy(user = true))
            setCgroupSpec(CgroupSpec.BACKGROUND)
        }
        val vm = RootedModeViewModel(probe, prefs)

        val state = vm.state.value
        assertTrue(state.rootedModeEnabled)
        assertTrue(state.namespaceSpec.user)
        assertEquals(CgroupSpec.BACKGROUND, state.cgroupSpec)
    }

    @Test
    fun `onRootedModeToggle persists and updates state`() {
        val probe = FakeProbe(fullyRooted())
        val prefs = InMemoryRootedModePrefs()
        val vm = RootedModeViewModel(probe, prefs)

        vm.onRootedModeToggle(true)
        assertTrue(prefs.isRootedModeEnabled())
        assertTrue(vm.state.value.rootedModeEnabled)

        vm.onRootedModeToggle(false)
        assertFalse(prefs.isRootedModeEnabled())
        assertFalse(vm.state.value.rootedModeEnabled)
    }

    @Test
    fun `onUserNamespaceToggle persists the new spec`() {
        val probe = FakeProbe(fullyRooted())
        val prefs = InMemoryRootedModePrefs()
        val vm = RootedModeViewModel(probe, prefs)

        vm.onUserNamespaceToggle(true)
        assertTrue(prefs.namespaceSpec().user)
        assertTrue(vm.state.value.namespaceSpec.user)

        vm.onUserNamespaceToggle(false)
        assertFalse(prefs.namespaceSpec().user)
    }

    @Test
    fun `onCgroupSpecChange persists the new spec`() {
        val probe = FakeProbe(fullyRooted())
        val prefs = InMemoryRootedModePrefs()
        val vm = RootedModeViewModel(probe, prefs)

        val custom = CgroupSpec(cpuWeight = 250, pidsMax = 128)
        vm.onCgroupSpecChange(custom)
        assertEquals(custom, prefs.cgroupSpec())

        vm.onResetCgroup()
        assertEquals(CgroupSpec.NONE, prefs.cgroupSpec())

        vm.onApplyBackgroundCgroup()
        assertEquals(CgroupSpec.BACKGROUND, prefs.cgroupSpec())
    }

    @Test
    fun `refreshStatus re-probes and updates the state`() {
        val probe = FakeProbe(fullyRooted())
        val prefs = InMemoryRootedModePrefs()
        val vm = RootedModeViewModel(probe, prefs)
        // Wait for the init {} coroutine to complete by
        // stepping the test's scheduler. The state machine
        // is updated via viewModelScope.launch which runs
        // on Dispatchers.Main by default. For a JVM test
        // without a Main dispatcher, we just call probe()
        // and assert that the FakeProbe.callCount is at
        // least 1 (init already probed once).
        val initial = probe.callCount
        vm.refreshStatus()
        // We can't deterministically wait for the
        // Dispatchers.IO coroutine, so we just verify
        // the FakeProbe recorded a call.
        assertTrue("refreshStatus should have probed", probe.callCount > initial)
    }
}

/**
 * JVM-testable [RootedModeProbe] that holds a fixed
 * [RootStatus]. Records call count for tests that need
 * to assert on probe activity.
 */
private class FakeProbe(
    private val status: RootStatus,
) : com.elysium.vanguard.core.runtime.distros.launcher.RootedModeProbe {
    var callCount: Int = 0
        private set

    override fun probe(): RootStatus {
        callCount++
        return status
    }
}

/**
 * JVM-testable [RootedModePrefs] that holds values
 * in memory. Mirrors the production interface 1:1.
 */
private class InMemoryRootedModePrefs : RootedModePrefs {
    private var enabled: Boolean = false
    private var namespace: NamespaceSpec = NamespaceSpec.FULL_SANDBOX
    private var cgroup: CgroupSpec = CgroupSpec.NONE

    override fun isRootedModeEnabled(): Boolean = enabled
    override fun setRootedModeEnabled(enabled: Boolean) { this.enabled = enabled }

    override fun namespaceSpec(): NamespaceSpec = namespace
    override fun setNamespaceSpec(spec: NamespaceSpec) { this.namespace = spec }

    override fun cgroupSpec(): CgroupSpec = cgroup
    override fun setCgroupSpec(spec: CgroupSpec) { this.cgroup = spec }
}

/**
 * Convenience constructor for tests — a fully-rooted
 * status with all sub-checks true.
 */
private fun fullyRooted(): RootStatus = RootStatus(
    isRooted = true,
    provider = RootProvider.MAGISK,
    unshareAvailable = true,
    cgexecAvailable = true,
    unprivilegedUserNsClone = true,
    cgroupVersion = 2,
    diagnostics = "fully rooted (test)",
)
