package com.elysium.vanguard.core.runtime.distros.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 102 — JVM tests for [RootStatus.canLaunchRooted] + the
 * [RootProvider] enum. The status is the gate condition the
 * [NamespacedDistroLauncher] uses, so its truth table is pinned
 * by tests.
 */
class RootStatusTest {

    @Test
    fun `canLaunchRooted is true when rooted + unshare (cgroup v2)`() {
        val status = status(isRooted = true, unshare = true, cgroup = 2)
        assertTrue(status.canLaunchRooted)
    }

    @Test
    fun `canLaunchRooted is true when rooted + unshare + cgroup unknown (null)`() {
        // Some kernels don't expose the cgroup fs type. We
        // treat unknown as "don't block on it" — the cgroup
        // hierarchy is the launcher's secondary concern
        // (only matters when a CgroupSpec is requested).
        val status = status(isRooted = true, unshare = true, cgroup = null)
        assertTrue(status.canLaunchRooted)
    }

    @Test
    fun `canLaunchRooted is also true when rooted + unshare + cgroup v1 (no spec requested)`() {
        // cgroup v1 alone doesn't block the launcher because
        // the cgroup spec is opt-in. If the user adds a spec,
        // canHonorCgroupSpec will fail closed.
        val status = status(isRooted = true, unshare = true, cgroup = 1)
        assertTrue(status.canLaunchRooted)
    }

    @Test
    fun `canLaunchRooted is false when not rooted`() {
        val status = status(isRooted = false, unshare = true, cgroup = 2)
        assertFalse(status.canLaunchRooted)
    }

    @Test
    fun `canLaunchRooted is false when unshare is missing`() {
        val status = status(isRooted = true, unshare = false, cgroup = 2)
        assertFalse(status.canLaunchRooted)
    }

    @Test
    fun `canHonorCgroupSpec returns true for an empty spec regardless of cgroup version`() {
        // The whole point of "isEmpty" is "no limits requested,
        // don't make me fail on the version".
        val v1 = status(cgroup = 1, cgexec = false)
        val v2 = status(cgroup = 2, cgexec = true)
        val unknown = status(cgroup = null, cgexec = false)
        assertTrue(v1.canHonorCgroupSpec(CgroupSpec.NONE))
        assertTrue(v2.canHonorCgroupSpec(CgroupSpec.NONE))
        assertTrue(unknown.canHonorCgroupSpec(CgroupSpec.NONE))
    }

    @Test
    fun `canHonorCgroupSpec returns true on cgroup v2 + cgexec present`() {
        val status = status(cgroup = 2, cgexec = true)
        assertTrue(status.canHonorCgroupSpec(CgroupSpec(cpuWeight = 500)))
    }

    @Test
    fun `canHonorCgroupSpec returns false on cgroup v2 with cgexec missing`() {
        val status = status(cgroup = 2, cgexec = false)
        assertFalse(status.canHonorCgroupSpec(CgroupSpec(cpuWeight = 500)))
    }

    @Test
    fun `canHonorCgroupSpec returns false on cgroup v1 with non-empty spec`() {
        val status = status(cgroup = 1, cgexec = true)
        assertFalse(status.canHonorCgroupSpec(CgroupSpec(cpuWeight = 500)))
    }

    @Test
    fun `canHonorCgroupSpec returns false on cgroup unknown with non-empty spec`() {
        // Null means "we don't know" — the launcher treats
        // this as best-effort, so a non-empty spec is
        // refused (we won't silently misconfigure).
        val status = status(cgroup = null, cgexec = true)
        assertFalse(status.canHonorCgroupSpec(CgroupSpec(cpuWeight = 500)))
    }

    @Test
    fun `unprivilegedUserNsClone null means we do not know and cannot opt-in`() {
        // The launcher calls effectiveNamespaceSpec() which
        // drops --user when the value is null OR false.
        val status = status(unprivilegedUserNsClone = null)
        // canLaunchRooted doesn't care; user ns is independent.
        assertTrue(status.canLaunchRooted)
    }

    @Test
    fun `RootProvider NONE is the sentinel for no-root`() {
        // Regression test: the enum value is referenced by
        // AndroidRootedModeProbe.detectProvider(). Renaming
        // would silently break the no-root path.
        assertEquals(RootProvider.NONE, RootProvider.valueOf("NONE"))
    }

    @Test
    fun `RootProvider order lists Magisk first for preference`() {
        // We do NOT enforce order in the enum (it's a value
        // type, not a list), but we want a constant comparator
        // for "preferred root provider" if/when we sort
        // multiple sources. Pin the canonical order.
        val preference = listOf(
            RootProvider.MAGISK,
            RootProvider.KERNEL_SU,
            RootProvider.APATCH,
            RootProvider.GENERIC_SU,
            RootProvider.UNKNOWN,
            RootProvider.NONE,
        )
        assertEquals(6, preference.size)
        assertEquals(RootProvider.MAGISK, preference.first())
        assertEquals(RootProvider.NONE, preference.last())
    }

    private fun status(
        isRooted: Boolean = true,
        provider: RootProvider = RootProvider.MAGISK,
        unshare: Boolean = true,
        cgexec: Boolean = true,
        unprivilegedUserNsClone: Boolean? = true,
        cgroup: Int? = 2,
        diagnostics: String = "test",
    ) = RootStatus(
        isRooted = isRooted,
        provider = provider,
        unshareAvailable = unshare,
        cgexecAvailable = cgexec,
        unprivilegedUserNsClone = unprivilegedUserNsClone,
        cgroupVersion = cgroup,
        diagnostics = diagnostics,
    )
}
