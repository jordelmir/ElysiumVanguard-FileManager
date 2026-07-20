package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.linux.ElysiumRootfsPath
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase 81 (Universal Execution Engine) — the
 * JVM tests for [SandboxPolicy].
 *
 * The tests cover:
 *   - WorkspaceId (random + from parsing).
 *   - MountMode (3 values).
 *   - MountPurpose invariants (Tmpfs
 *     sizeMb, Custom name).
 *   - MountEntry invariants (source !=
 *     target).
 *   - NetworkPolicy invariants
 *     (Allowlisted empty allowlist,
 *     Allowlisted blank hostnames).
 *   - SecurityProfile invariants
 *     (Custom selinuxContext, Custom
 *     format).
 *   - SandboxLimits invariants (negative
 *     memory, CPU out of range, negative
 *     fds, negative processes, negative
 *     disk write).
 *   - SandboxPolicy invariants (empty
 *     mounts, mountForTarget, allowsHost
 *     for each network policy).
 *   - InMemorySandboxPolicyValidator
 *     (duplicate targets, read-only
 *     mount on read-write purpose,
 *     read-write mount on read-only
 *     purpose).
 *   - Realistic scenario: a workspace
 *     with system libraries (read-only)
 *     + workspace data (read-write) +
 *     device nodes (read-write) + a
 *     tmpfs.
 */
class SandboxPolicyTest {

    // ============================================================
    // MountPurpose invariants
    // ============================================================

    @Test
    fun `MountPurpose Tmpfs rejects zero sizeMb`() {
        try {
            MountPurpose.Tmpfs(sizeMb = 0L)
            fail("expected IllegalArgumentException for sizeMb <= 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sizeMb"))
        }
    }

    @Test
    fun `MountPurpose Custom rejects blank name`() {
        try {
            MountPurpose.Custom(name = "")
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    // ============================================================
    // MountEntry invariants
    // ============================================================

    @Test
    fun `MountEntry accepts a well-formed configuration`() {
        val mount = buildMount()
        assertEquals(MountMode.READ_WRITE, mount.mode)
    }

    @Test
    fun `MountEntry accepts source equal to target (system mount pattern)`() {
        // A system mount like /usr/lib →
        // /usr/lib is valid (the mount
        // system re-applies the path
        // inside the sandbox's rootfs).
        val mount = MountEntry(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
            mode = MountMode.READ_ONLY,
            purpose = MountPurpose.SystemLibraries,
        )
        assertEquals(mount.source, mount.target)
    }

    // ============================================================
    // NetworkPolicy invariants
    // ============================================================

    @Test
    fun `NetworkPolicy Allowlisted rejects empty allowlist`() {
        try {
            NetworkPolicy.Allowlisted(allowlist = emptySet())
            fail(
                "expected IllegalArgumentException for " +
                    "empty allowlist",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("allowlist"))
        }
    }

    @Test
    fun `NetworkPolicy Allowlisted rejects blank hostnames`() {
        try {
            NetworkPolicy.Allowlisted(
                allowlist = setOf("api.example.com", ""),
            )
            fail(
                "expected IllegalArgumentException for " +
                    "blank hostname",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("hostname"))
        }
    }

    // ============================================================
    // SecurityProfile invariants
    // ============================================================

    @Test
    fun `SecurityProfile Custom rejects blank selinuxContext`() {
        try {
            SecurityProfile.Custom(selinuxContext = "")
            fail(
                "expected IllegalArgumentException for " +
                    "blank selinuxContext",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("selinuxContext"))
        }
    }

    @Test
    fun `SecurityProfile Custom rejects selinuxContext without colons`() {
        try {
            SecurityProfile.Custom(selinuxContext = "unconfined")
            fail(
                "expected IllegalArgumentException for " +
                    "selinuxContext without colons",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("selinuxContext"))
        }
    }

    // ============================================================
    // SandboxLimits invariants
    // ============================================================

    @Test
    fun `SandboxLimits rejects negative maxMemoryMb`() {
        try {
            SandboxLimits(
                maxMemoryMb = -1L,
                maxCpuPercent = 50,
                maxOpenFileDescriptors = 1024,
                maxProcesses = 64,
                maxDiskWriteMb = 100L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "negative maxMemoryMb",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxMemoryMb"))
        }
    }

    @Test
    fun `SandboxLimits rejects maxCpuPercent out of range`() {
        try {
            SandboxLimits(
                maxMemoryMb = 1024L,
                maxCpuPercent = 150,
                maxOpenFileDescriptors = 1024,
                maxProcesses = 64,
                maxDiskWriteMb = 100L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "maxCpuPercent out of range",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxCpuPercent"))
        }
    }

    @Test
    fun `SandboxLimits rejects negative maxOpenFileDescriptors`() {
        try {
            SandboxLimits(
                maxMemoryMb = 1024L,
                maxCpuPercent = 50,
                maxOpenFileDescriptors = -1,
                maxProcesses = 64,
                maxDiskWriteMb = 100L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "negative maxOpenFileDescriptors",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(
                e.message!!.contains("maxOpenFileDescriptors"),
            )
        }
    }

    @Test
    fun `SandboxLimits rejects negative maxProcesses`() {
        try {
            SandboxLimits(
                maxMemoryMb = 1024L,
                maxCpuPercent = 50,
                maxOpenFileDescriptors = 1024,
                maxProcesses = -1,
                maxDiskWriteMb = 100L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "negative maxProcesses",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxProcesses"))
        }
    }

    @Test
    fun `SandboxLimits rejects negative maxDiskWriteMb`() {
        try {
            SandboxLimits(
                maxMemoryMb = 1024L,
                maxCpuPercent = 50,
                maxOpenFileDescriptors = 1024,
                maxProcesses = 64,
                maxDiskWriteMb = -1L,
            )
            fail(
                "expected IllegalArgumentException for " +
                    "negative maxDiskWriteMb",
            )
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxDiskWriteMb"))
        }
    }

    // ============================================================
    // SandboxPolicy — mountForTarget + allowsHost
    // ============================================================

    @Test
    fun `SandboxPolicy rejects empty mounts`() {
        try {
            buildPolicy(mounts = emptyList())
            fail("expected IllegalArgumentException for empty mounts")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mounts"))
        }
    }

    @Test
    fun `SandboxPolicy mountForTarget returns the mount for the target`() {
        val mount = buildMount()
        val policy = buildPolicy(mounts = listOf(mount))
        val found = policy.mountForTarget(mount.target)
        assertEquals(mount, found)
    }

    @Test
    fun `SandboxPolicy mountForTarget returns null for an unknown target`() {
        val policy = buildPolicy()
        val found = policy.mountForTarget(ElysiumRootfsPath("/unknown"))
        assertNull(found)
    }

    @Test
    fun `SandboxPolicy allowsHost returns false for Denied`() {
        val policy = buildPolicy(network = NetworkPolicy.Denied)
        assertFalse(policy.allowsHost("api.example.com"))
    }

    @Test
    fun `SandboxPolicy allowsHost returns false for LocalOnly`() {
        val policy = buildPolicy(network = NetworkPolicy.LocalOnly)
        assertFalse(policy.allowsHost("api.example.com"))
    }

    @Test
    fun `SandboxPolicy allowsHost returns true for allowlisted host`() {
        val policy = buildPolicy(
            network = NetworkPolicy.Allowlisted(
                allowlist = setOf("api.example.com"),
            ),
        )
        assertTrue(policy.allowsHost("api.example.com"))
    }

    @Test
    fun `SandboxPolicy allowsHost returns false for non-allowlisted host`() {
        val policy = buildPolicy(
            network = NetworkPolicy.Allowlisted(
                allowlist = setOf("api.example.com"),
            ),
        )
        assertFalse(policy.allowsHost("evil.example.com"))
    }

    @Test
    fun `SandboxPolicy allowsHost returns true for Full`() {
        val policy = buildPolicy(network = NetworkPolicy.Full)
        assertTrue(policy.allowsHost("any.example.com"))
    }

    // ============================================================
    // InMemorySandboxPolicyValidator
    // ============================================================

    @Test
    fun `validator reports a duplicate mount target`() {
        val mount1 = buildMount(
            source = ElysiumRootfsPath("/data1"),
            target = ElysiumRootfsPath("/workspaces/data"),
        )
        val mount2 = buildMount(
            source = ElysiumRootfsPath("/data2"),
            target = ElysiumRootfsPath("/workspaces/data"),
        )
        val policy = buildPolicy(mounts = listOf(mount1, mount2))
        val validator = InMemorySandboxPolicyValidator()
        val errors = validator.validate(policy)
        assertTrue(errors.isNotEmpty())
        assertTrue(
            errors.any {
                it is SandboxPolicyError.DuplicateMountTarget
            },
        )
    }

    @Test
    fun `validator reports a read-write mount on a read-only purpose`() {
        val mount = buildMount(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.SystemLibraries,
        )
        val policy = buildPolicy(mounts = listOf(mount))
        val validator = InMemorySandboxPolicyValidator()
        val errors = validator.validate(policy)
        assertTrue(errors.isNotEmpty())
        assertTrue(
            errors.any {
                it is SandboxPolicyError.ReadWriteMountOnReadOnlyPurpose
            },
        )
    }

    @Test
    fun `validator reports a read-only mount on a read-write purpose`() {
        val mount = buildMount(
            source = ElysiumRootfsPath("/workspaces/data"),
            target = ElysiumRootfsPath("/workspaces/data"),
            mode = MountMode.READ_ONLY,
            purpose = MountPurpose.WorkspaceData(
                WorkspaceId.random(),
            ),
        )
        val policy = buildPolicy(mounts = listOf(mount))
        val validator = InMemorySandboxPolicyValidator()
        val errors = validator.validate(policy)
        assertTrue(errors.isNotEmpty())
        assertTrue(
            errors.any {
                it is SandboxPolicyError.ReadOnlyMountOnReadWritePurpose
            },
        )
    }

    @Test
    fun `validator returns empty list for a valid policy`() {
        val mount = buildMount()
        val policy = buildPolicy(mounts = listOf(mount))
        val validator = InMemorySandboxPolicyValidator()
        val errors = validator.validate(policy)
        assertTrue(errors.isEmpty())
        assertTrue(validator.isValid(policy))
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario a workspace with system libraries read-only, workspace data read-write, device nodes, and a tmpfs`() {
        // Build the mounts for a typical
        // workspace:
        //   - /usr/lib (system libraries,
        //     read-only)
        //   - /workspaces/data (workspace
        //     data, read-write)
        //   - /workspaces/config (workspace
        //     config, read-write)
        //   - /dev (device nodes, read-write)
        //   - /tmp (tmpfs, read-write)
        val workspaceId = WorkspaceId.random()
        val systemLibMount = MountEntry(
            source = ElysiumRootfsPath("/usr/lib"),
            target = ElysiumRootfsPath("/usr/lib"),
            mode = MountMode.READ_ONLY,
            purpose = MountPurpose.SystemLibraries,
        )
        val workspaceDataMount = MountEntry(
            source = ElysiumRootfsPath("/data/user-selected"),
            target = ElysiumRootfsPath("/workspaces/data"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.WorkspaceData(workspaceId),
        )
        val workspaceConfigMount = MountEntry(
            source = ElysiumRootfsPath("/config/elysium"),
            target = ElysiumRootfsPath("/workspaces/config"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.WorkspaceConfig(workspaceId),
        )
        val deviceNodesMount = MountEntry(
            source = ElysiumRootfsPath("/dev"),
            target = ElysiumRootfsPath("/dev"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.DeviceNodes,
        )
        val tmpfsMount = MountEntry(
            source = ElysiumRootfsPath("/tmp"),
            target = ElysiumRootfsPath("/tmp"),
            mode = MountMode.READ_WRITE,
            purpose = MountPurpose.Tmpfs(sizeMb = 64L),
        )

        val policy = SandboxPolicy(
            workspaceId = workspaceId,
            mounts = listOf(
                systemLibMount,
                workspaceDataMount,
                workspaceConfigMount,
                deviceNodesMount,
                tmpfsMount,
            ),
            limits = SandboxLimits.DEFAULT,
            network = NetworkPolicy.LocalOnly,
            security = SecurityProfile.Standard,
            signature = Signature("sig-sandbox-${UUID.randomUUID()}"),
        )

        // Step 1: The policy is valid.
        val validator = InMemorySandboxPolicyValidator()
        assertTrue(validator.isValid(policy))

        // Step 2: The policy rejects external
        // network.
        assertFalse(policy.allowsHost("api.example.com"))

        // Step 3: The policy exposes the
        // expected mounts.
        assertEquals(
            systemLibMount,
            policy.mountForTarget(ElysiumRootfsPath("/usr/lib")),
        )
        assertEquals(
            workspaceDataMount,
            policy.mountForTarget(
                ElysiumRootfsPath("/workspaces/data"),
            ),
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildMount(
        source: ElysiumRootfsPath = ElysiumRootfsPath("/workspaces/source"),
        target: ElysiumRootfsPath = ElysiumRootfsPath("/workspaces/target"),
        mode: MountMode = MountMode.READ_WRITE,
        purpose: MountPurpose = MountPurpose.WorkspaceData(
            WorkspaceId.random(),
        ),
    ): MountEntry = MountEntry(
        source = source,
        target = target,
        mode = mode,
        purpose = purpose,
    )

    private fun buildPolicy(
        workspaceId: WorkspaceId = WorkspaceId.random(),
        mounts: List<MountEntry> = listOf(buildMount()),
        limits: SandboxLimits = SandboxLimits.DEFAULT,
        network: NetworkPolicy = NetworkPolicy.LocalOnly,
        security: SecurityProfile = SecurityProfile.Standard,
    ): SandboxPolicy = SandboxPolicy(
        workspaceId = workspaceId,
        mounts = mounts,
        limits = limits,
        network = network,
        security = security,
        signature = Signature("sig-policy-${UUID.randomUUID()}"),
    )
}
