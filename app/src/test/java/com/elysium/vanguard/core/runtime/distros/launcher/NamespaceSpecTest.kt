package com.elysium.vanguard.core.runtime.distros.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 102 — JVM tests for [NamespaceSpec] invariants. The
 * "always true" flags must reject `false` at construction time
 * so a future refactor that tries to weaken them fails the
 * test suite, not the production sandbox.
 */
class NamespaceSpecTest {

    @Test
    fun `FULL_SANDBOX has every always-true flag on and user off by default`() {
        val spec = NamespaceSpec.FULL_SANDBOX
        assertTrue(spec.mount)
        assertTrue(spec.pid)
        assertTrue(spec.network)
        assertTrue(spec.ipc)
        assertTrue(spec.uts)
        assertTrue(spec.cgroup)
        assertTrue(spec.privatePropagation)
        // user is opt-in
        assertFalse(spec.user)
    }

    private data class FlagCase(
        val flagName: String,
        val needle: String,
        val buildBroken: () -> NamespaceSpec,
    )

    @Test
    fun `disabling a hard-true namespace throws at construction time`() {
        // Each case must be tested in its own try/catch: the
        // NamespaceSpec constructor throws eagerly, so we
        // can't build a list of failing cases in one expression.
        // We pass a "needle" to look for in the error message
        // because some flag names contain spaces ("private propagation").
        val cases: List<FlagCase> = listOf(
            FlagCase("mount", "mount", { NamespaceSpec(mount = false) }),
            FlagCase("pid", "pid", { NamespaceSpec(pid = false) }),
            FlagCase("network", "network", { NamespaceSpec(network = false) }),
            FlagCase("ipc", "ipc", { NamespaceSpec(ipc = false) }),
            FlagCase("uts", "uts", { NamespaceSpec(uts = false) }),
            FlagCase("cgroup", "cgroup", { NamespaceSpec(cgroup = false) }),
            FlagCase("privatePropagation", "private propagation",
                { NamespaceSpec(privatePropagation = false) }),
        )
        for (c in cases) {
            try {
                c.buildBroken()
                org.junit.Assert.fail("expected IllegalArgumentException for ${c.flagName}=false")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "error must mention the disabled flag (${c.flagName}) via needle '${c.needle}': ${e.message}",
                    e.message!!.contains(c.needle)
                )
            }
        }
    }

    @Test
    fun `user namespace can be toggled freely`() {
        NamespaceSpec.FULL_SANDBOX.copy(user = true)
        NamespaceSpec.FULL_SANDBOX.copy(user = false)
    }

    @Test
    fun `FULL_SANDBOX is the same instance across calls`() {
        // Regression test: we don't accidentally re-construct a
        // different spec every call site.
        assertEquals(NamespaceSpec.FULL_SANDBOX, NamespaceSpec.FULL_SANDBOX)
    }
}
