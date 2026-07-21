package com.elysium.vanguard.core.runtime.workspace_def

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 104 — JVM tests for the new workspace
 * policy specs: [GpuAccessSpec], [NetworkPolicySpec],
 * [BackupPolicySpec]. Each spec has invariants
 * enforced in `init { }`; the tests pin the
 * truth table so a future refactor cannot silently
 * weaken them.
 *
 * Companion round-trip + golden-file tests for the
 * new fields live in [WorkspaceDefinitionTest] and
 * the codec test; this file is just the per-spec
 * invariants.
 */
class WorkspacePolicySpecsTest {

    // ====================================================================
    // GpuAccessSpec
    // ====================================================================

    @Test
    fun `GpuAccessSpec NONE default is no GPU and no vendor`() {
        val spec = GpuAccessSpec.NONE
        assertEquals(GpuAccessKind.NONE, spec.kind)
        assertNull("NONE must not declare a vendor", spec.vendor)
        assertTrue("NONE must not declare driver env overrides",
            spec.driverEnvOverrides.isEmpty())
    }

    @Test
    fun `GpuAccessSpec FULL_3D with a vendor is allowed`() {
        val spec = GpuAccessSpec(
            kind = GpuAccessKind.FULL_3D,
            vendor = GpuVendor.ADRENO,
        )
        assertEquals(GpuAccessKind.FULL_3D, spec.kind)
        assertEquals(GpuVendor.ADRENO, spec.vendor)
    }

    @Test
    fun `GpuAccessSpec NONE with a vendor is rejected at construction time`() {
        try {
            GpuAccessSpec(kind = GpuAccessKind.NONE, vendor = GpuVendor.MALI)
            fail("expected IllegalArgumentException for NONE + vendor")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the vendor field: ${e.message}",
                e.message!!.contains("vendor")
            )
        }
    }

    @Test
    fun `GpuAccessSpec NONE with driver env overrides is rejected at construction time`() {
        try {
            GpuAccessSpec(
                kind = GpuAccessKind.NONE,
                driverEnvOverrides = mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "panfrost"),
            )
            fail("expected IllegalArgumentException for NONE + driverEnvOverrides")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention driver env: ${e.message}",
                e.message!!.contains("driver")
            )
        }
    }

    @Test
    fun `GpuAccessKind fromToken round trips known values`() {
        for (kind in GpuAccessKind.values()) {
            assertEquals(kind, GpuAccessKind.fromToken(kind.name))
            // Case-insensitive
            assertEquals(kind, GpuAccessKind.fromToken(kind.name.lowercase()))
        }
        // Unknown values are null, NOT NONE.
        assertNull(GpuAccessKind.fromToken("INVALID"))
    }

    @Test
    fun `GpuVendor fromToken round trips known values`() {
        for (vendor in GpuVendor.values()) {
            assertEquals(vendor, GpuVendor.fromToken(vendor.name))
        }
        assertNull(GpuVendor.fromToken("DOES_NOT_EXIST"))
    }

    // ====================================================================
    // NetworkPolicySpec
    // ====================================================================

    @Test
    fun `NetworkPolicySpec DEFAULT denies everything`() {
        val spec = NetworkPolicySpec.DEFAULT
        assertEquals(NetworkAccessMode.DENY_ALL, spec.mode)
        assertTrue("DENY_ALL must have empty allowedHosts", spec.allowedHosts.isEmpty())
        assertTrue("DENY_ALL must have empty allowedPorts", spec.allowedPorts.isEmpty())
        assertFalse("DENY_ALL must not allow DNS", spec.dnsAllowed)
    }

    @Test
    fun `NetworkPolicySpec ALLOW_LIST with non-empty hosts is accepted`() {
        val spec = NetworkPolicySpec(
            mode = NetworkAccessMode.ALLOW_LIST,
            allowedHosts = listOf("api.example.com", "*.cdn.example.com"),
            allowedPorts = setOf(443),
            dnsAllowed = true,
        )
        assertEquals(NetworkAccessMode.ALLOW_LIST, spec.mode)
        assertEquals(2, spec.allowedHosts.size)
        assertEquals(setOf(443), spec.allowedPorts)
        assertTrue(spec.dnsAllowed)
    }

    @Test
    fun `NetworkPolicySpec ALLOW_LIST with empty allowedHosts is rejected`() {
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = emptyList(),
            )
            fail("expected IllegalArgumentException for ALLOW_LIST + empty allowedHosts")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention allowedHosts: ${e.message}",
                e.message!!.contains("allowedHost")
            )
        }
    }

    @Test
    fun `NetworkPolicySpec DENY_ALL with allowedHosts is rejected`() {
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.DENY_ALL,
                allowedHosts = listOf("api.example.com"),
            )
            fail("expected IllegalArgumentException for DENY_ALL + allowedHosts")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the misconfiguration: ${e.message}",
                e.message!!.contains("DENY_ALL")
            )
        }
    }

    @Test
    fun `NetworkPolicySpec DENY_ALL with allowedPorts is rejected`() {
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.DENY_ALL,
                allowedPorts = setOf(443),
            )
            fail("expected IllegalArgumentException for DENY_ALL + allowedPorts")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the misconfiguration: ${e.message}",
                e.message!!.contains("DENY_ALL")
            )
        }
    }

    @Test
    fun `NetworkPolicySpec ALLOW_ALL is the open-internet preset`() {
        val spec = NetworkPolicySpec.OPEN
        assertEquals(NetworkAccessMode.ALLOW_ALL, spec.mode)
        assertTrue("ALLOW_ALL must allow DNS for hostname resolution",
            spec.dnsAllowed)
    }

    @Test
    fun `NetworkPolicySpec rejects ports outside 1-65535`() {
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com"),
                allowedPorts = setOf(0),
            )
            fail("expected IllegalArgumentException for port 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the port range: ${e.message}",
                e.message!!.contains("65535") || e.message!!.contains("port")
            )
        }
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com"),
                allowedPorts = setOf(70000),
            )
            fail("expected IllegalArgumentException for port 70000")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the port range: ${e.message}",
                e.message!!.contains("65535")
            )
        }
    }

    @Test
    fun `NetworkPolicySpec rejects blank allowedHosts entries`() {
        try {
            NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com", ""),
            )
            fail("expected IllegalArgumentException for blank host")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention blank: ${e.message}",
                e.message!!.contains("blank")
            )
        }
    }

    @Test
    fun `NetworkAccessMode fromToken round trips known values`() {
        for (mode in NetworkAccessMode.values()) {
            assertEquals(mode, NetworkAccessMode.fromToken(mode.name))
            assertEquals(mode, NetworkAccessMode.fromToken(mode.name.lowercase()))
        }
        assertNull(NetworkAccessMode.fromToken("MAYBE"))
    }

    // ====================================================================
    // BackupPolicySpec
    // ====================================================================

    @Test
    fun `BackupPolicySpec NONE has sensible defaults`() {
        val spec = BackupPolicySpec.NONE
        assertEquals(BackupStrategy.NONE, spec.strategy)
        // Default interval + count are documented; we
        // don't pin their exact values but we verify
        // they are within the allowed range.
        assertTrue(
            "default scheduleIntervalMinutes must be in range",
            spec.scheduleIntervalMinutes in 1..BackupPolicySpec.MAX_SCHEDULE_MINUTES
        )
        assertTrue(
            "default maxSnapshotCount must be in range",
            spec.maxSnapshotCount in 1..BackupPolicySpec.MAX_SNAPSHOTS
        )
    }

    @Test
    fun `BackupPolicySpec ON_EXIT is accepted`() {
        val spec = BackupPolicySpec.ON_EXIT
        assertEquals(BackupStrategy.ON_EXIT, spec.strategy)
    }

    @Test
    fun `BackupPolicySpec SCHEDULED_15MIN is accepted`() {
        val spec = BackupPolicySpec.SCHEDULED_15MIN
        assertEquals(BackupStrategy.SCHEDULED, spec.strategy)
        assertEquals(15, spec.scheduleIntervalMinutes)
        assertEquals(3, spec.maxSnapshotCount)
    }

    @Test
    fun `BackupPolicySpec rejects scheduleIntervalMinutes above MAX_SCHEDULE_MINUTES`() {
        try {
            BackupPolicySpec(
                strategy = BackupStrategy.SCHEDULED,
                scheduleIntervalMinutes = BackupPolicySpec.MAX_SCHEDULE_MINUTES + 1,
            )
            fail("expected IllegalArgumentException for too-large interval")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the max: ${e.message}",
                e.message!!.contains(BackupPolicySpec.MAX_SCHEDULE_MINUTES.toString())
            )
        }
    }

    @Test
    fun `BackupPolicySpec rejects scheduleIntervalMinutes below 1`() {
        try {
            BackupPolicySpec(
                strategy = BackupStrategy.SCHEDULED,
                scheduleIntervalMinutes = 0,
            )
            fail("expected IllegalArgumentException for interval 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the range: ${e.message}",
                e.message!!.contains("1")
            )
        }
    }

    @Test
    fun `BackupPolicySpec rejects maxSnapshotCount above MAX_SNAPSHOTS`() {
        try {
            BackupPolicySpec(
                strategy = BackupStrategy.SCHEDULED,
                maxSnapshotCount = BackupPolicySpec.MAX_SNAPSHOTS + 1,
            )
            fail("expected IllegalArgumentException for too-many snapshots")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the max: ${e.message}",
                e.message!!.contains(BackupPolicySpec.MAX_SNAPSHOTS.toString())
            )
        }
    }

    @Test
    fun `BackupPolicySpec rejects maxSnapshotCount below 1`() {
        try {
            BackupPolicySpec(
                strategy = BackupStrategy.SCHEDULED,
                maxSnapshotCount = 0,
            )
            fail("expected IllegalArgumentException for 0 snapshots")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "error must mention the range: ${e.message}",
                e.message!!.contains("1")
            )
        }
    }

    @Test
    fun `BackupStrategy fromToken round trips known values`() {
        for (strategy in BackupStrategy.values()) {
            assertEquals(strategy, BackupStrategy.fromToken(strategy.name))
            assertEquals(strategy, BackupStrategy.fromToken(strategy.name.lowercase()))
        }
        assertNull(BackupStrategy.fromToken("DAILY"))
    }

    // ====================================================================
    // WorkspaceDefinition default values
    // ====================================================================

    @Test
    fun `WorkspaceDefinition defaults to NONE GPU DENY_ALL network and NONE backup`() {
        val def = sampleMinimal()
        assertEquals(GpuAccessSpec.NONE, def.gpu)
        assertEquals(NetworkPolicySpec.DEFAULT, def.network)
        assertEquals(BackupPolicySpec.NONE, def.backup)
    }

    @Test
    fun `WorkspaceDefinition accepts a FULL_3D GPU ALLOW_LIST network and SCHEDULED backup`() {
        val def = sampleMinimal().copy(
            gpu = GpuAccessSpec(kind = GpuAccessKind.FULL_3D, vendor = GpuVendor.ADRENO),
            network = NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com"),
                allowedPorts = setOf(443),
                dnsAllowed = true,
            ),
            backup = BackupPolicySpec.SCHEDULED_15MIN,
        )
        assertEquals(GpuAccessKind.FULL_3D, def.gpu.kind)
        assertEquals(GpuVendor.ADRENO, def.gpu.vendor)
        assertEquals(NetworkAccessMode.ALLOW_LIST, def.network.mode)
        assertEquals(BackupStrategy.SCHEDULED, def.backup.strategy)
    }

    private fun sampleMinimal(): WorkspaceDefinition = WorkspaceDefinition(
        apiVersion = ApiVersion.V1,
        id = "minimal-test",
        name = "Minimal Test",
        description = "Minimal workspace for policy spec tests",
        runtime = RuntimeKind.LINUX_PROOT,
        mounts = listOf(
            MountSpec(
                hostPath = "/sdcard/test",
                containerPath = "/workspace",
            ),
        ),
        env = emptyList(),
        launcher = LauncherSpec(command = "/bin/sh"),
        resources = ResourceSpec.DEFAULT,
        createdAtMs = 1_700_000_000_000L,
    )
}
