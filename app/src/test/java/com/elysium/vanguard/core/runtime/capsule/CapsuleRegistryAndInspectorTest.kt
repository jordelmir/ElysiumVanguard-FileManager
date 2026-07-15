package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.core.runtime.network.policy.NetworkMode
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 14 — capsule + registry + inspector tests.
 *
 * The tests pin three contracts:
 *
 *   - [ApplicationCapsule]'s init block catches malformed
 *     capsules (blank id, missing architecture, etc.) before
 *     they ever enter the runtime.
 *   - [CapsuleRegistry] is a thread-safe Map<String, Capsule>
 *     that survives concurrent install / uninstall.
 *   - [CapsuleInspector] produces a typed [Result] that the UI
 *     and the provisioning pipeline can branch on without
 *     parsing free-form strings.
 */
class CapsuleRegistryAndInspectorTest {

    private fun validSignature() = "0".repeat(128) + "0".repeat(64)

    private fun validCapsule(
        id: String = "org.elysium.gimp",
        name: String = "GIMP",
        version: String = "2.10.0",
        arch: Set<CpuArch> = setOf(CpuArch.ARM64),
        runtime: RuntimeRequirement = RuntimeRequirement(
            preferred = RuntimeId("linux-direct-arm64"),
            fallbacks = listOf(RuntimeId("linux-vm"))
        ),
        storage: StoragePolicy = StoragePolicy(StoragePolicy.Mode.PRIVATE),
        network: NetworkPolicy = NetworkPolicy(mode = NetworkMode.OUTBOUND_ONLY),
        compat: CompatibilityState = CompatibilityState.VERIFIED,
        gpu: GpuProfile = GpuProfile.VULKAN,
        audio: AudioProfile = AudioProfile.FULL,
        memMb: Int = 2048,
        perms: CapsulePermissions = CapsulePermissions(
            files = FilePermission.USER_SELECTED,
            clipboard = ClipboardPermission.TEXT,
            network = true
        ),
        source: PackageSource = PackageSource.OFFICIAL_REPO,
        entrypoint: String = "/opt/gimp/bin/gimp"
    ) = ApplicationCapsule(
        id = id,
        displayName = name,
        description = "GNU Image Manipulation Program",
        version = version,
        runtime = runtime,
        architecture = arch,
        entrypoint = entrypoint,
        environment = emptyMap(),
        permissions = perms,
        storage = storage,
        network = network,
        display = DisplayMode.SEAMLESS,
        gpu = gpu,
        audio = audio,
        resources = CapsuleResources(memoryRecommendedMb = memMb),
        compatibility = compat,
        signature = validSignature(),
        source = source
    )

    // --- capsule init ---

    @Test
    fun `capsule init accepts a well-formed capsule`() {
        val c = validCapsule()
        assertEquals("org.elysium.gimp", c.id)
        assertEquals("GIMP", c.displayName)
        assertEquals("2.10.0", c.version)
    }

    @Test
    fun `capsule init rejects a blank id`() {
        try {
            validCapsule(id = "")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // id must not be blank
        }
    }

    @Test
    fun `capsule init rejects a non reverse-DNS id`() {
        try {
            validCapsule(id = "GIMP")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // id must match the reverse-DNS regex
        }
    }

    @Test
    fun `capsule init rejects an empty architecture set`() {
        try {
            validCapsule(arch = emptySet())
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // at least one CPU arch required
        }
    }

    @Test
    fun `capsule init rejects a bad signature`() {
        try {
            validCapsule().copy(signature = "not-hex")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // signature must be 128/192-char hex
        }
    }

    @Test
    fun `capsule init rejects memoryRecommendedMb of 0`() {
        try {
            validCapsule(memMb = 0)
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // memoryRecommendedMb must be positive
        }
    }

    @Test
    fun `capsule init rejects environment keys with equals or NUL`() {
        val base = validCapsule()
        try {
            base.copy(environment = mapOf("BAD=KEY" to "value"))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* key with = */ }
        try {
            base.copy(environment = mapOf("BAD\u0000KEY" to "value"))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* key with NUL */ }
        try {
            base.copy(environment = mapOf("OK" to "bad\u0000value"))
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* value with NUL */ }
    }

    @Test
    fun `runtime requirement rejects a preferred in fallbacks`() {
        try {
            RuntimeRequirement(
                preferred = RuntimeId("linux-direct-arm64"),
                fallbacks = listOf(RuntimeId("linux-direct-arm64"))
            )
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // preferred must not appear in fallbacks
        }
    }

    @Test
    fun `storage policy requires a path for SHARED and BOUND`() {
        try {
            StoragePolicy(StoragePolicy.Mode.SHARED, path = null)
            fail("expected IllegalArgumentException for SHARED without path")
        } catch (expected: IllegalArgumentException) { /* */ }
        try {
            StoragePolicy(StoragePolicy.Mode.BOUND, path = "")
            fail("expected IllegalArgumentException for BOUND with blank path")
        } catch (expected: IllegalArgumentException) { /* */ }
        // PRIVATE is OK with no path.
        StoragePolicy(StoragePolicy.Mode.PRIVATE)
    }

    // --- registry ---

    @Test
    fun `registry install and find round-trip`() {
        val registry = CapsuleRegistry()
        val capsule = validCapsule(id = "org.elysium.firefox")
        registry.install(capsule)
        assertEquals(capsule, registry.find("org.elysium.firefox"))
        assertEquals(1, registry.size())
    }

    @Test
    fun `registry list returns capsules sorted by id`() {
        val registry = CapsuleRegistry()
        registry.install(validCapsule(id = "org.elysium.z"))
        registry.install(validCapsule(id = "org.elysium.a"))
        registry.install(validCapsule(id = "org.elysium.m"))
        val ids = registry.list().map { it.id }
        assertEquals(listOf("org.elysium.a", "org.elysium.m", "org.elysium.z"), ids)
    }

    @Test
    fun `registry uninstall removes and reports the result`() {
        val registry = CapsuleRegistry()
        registry.install(validCapsule(id = "org.elysium.x"))
        assertTrue(registry.uninstall("org.elysium.x"))
        assertNull(registry.find("org.elysium.x"))
        assertFalse(registry.uninstall("org.elysium.x"))
    }

    @Test
    fun `registry install overwrites a previous capsule with the same id`() {
        val registry = CapsuleRegistry()
        val v1 = validCapsule(id = "org.elysium.x", version = "1.0.0")
        val v2 = validCapsule(id = "org.elysium.x", version = "2.0.0")
        registry.install(v1)
        registry.install(v2)
        assertEquals("2.0.0", registry.find("org.elysium.x")?.version)
    }

    @Test
    fun `registry clear empties the registry`() {
        val registry = CapsuleRegistry()
        registry.install(validCapsule(id = "org.elysium.a"))
        registry.install(validCapsule(id = "org.elysium.b"))
        registry.clear()
        assertEquals(0, registry.size())
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun `registry is thread-safe under concurrent install`() {
        val registry = CapsuleRegistry()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { threadIndex ->
            Thread {
                start.await()
                repeat(50) { i ->
                    registry.install(
                        validCapsule(id = "org.elysium.thread$threadIndex.$i")
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals(8 * 50, registry.size())
    }

    // --- inspector ---

    @Test
    fun `inspector returns OK for a compatible capsule on a capable device`() {
        val capsule = validCapsule()
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 8192,
            hasVulkan = true,
            hasOpenGL = true
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `inspector rejects a capsule whose architecture is not on the device`() {
        val capsule = validCapsule(arch = setOf(CpuArch.X86_64))
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 8192,
            hasVulkan = true,
            hasOpenGL = true
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertFalse(result.isValid)
        assertTrue(
            "issue must mention the architecture mismatch",
            result.issues.any { it.message.contains("x86_64") || it.message.contains("ARM64") }
        )
    }

    @Test
    fun `inspector rejects an UNSUPPORTED capsule on any device`() {
        val capsule = validCapsule(compat = CompatibilityState.UNSUPPORTED)
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 8192,
            hasVulkan = true,
            hasOpenGL = true
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertFalse(result.isValid)
    }

    @Test
    fun `inspector rejects a REQUIRES_VM capsule without a VM fallback`() {
        val capsule = validCapsule(
            compat = CompatibilityState.REQUIRES_VM,
            runtime = RuntimeRequirement(
                preferred = RuntimeId("linux-direct-arm64"),
                fallbacks = emptyList() // no linux-vm
            )
        )
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 8192,
            hasVulkan = true,
            hasOpenGL = true
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertFalse(result.isValid)
    }

    @Test
    fun `inspector accepts a REQUIRES_VM capsule with a linux-vm fallback`() {
        val capsule = validCapsule(
            compat = CompatibilityState.REQUIRES_VM,
            runtime = RuntimeRequirement(
                preferred = RuntimeId("linux-direct-arm64"),
                fallbacks = listOf(RuntimeId("linux-vm"))
            )
        )
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 8192,
            hasVulkan = true,
            hasOpenGL = true
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertTrue(result.isValid)
    }

    @Test
    fun `inspector warns when the device is short on memory`() {
        val capsule = validCapsule(memMb = 4096)
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 2048, // less than the capsule wants
            hasVulkan = true,
            hasOpenGL = true
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertTrue(result.isValid) // WARNING, not ERROR
        assertTrue(result.issues.any {
            it.severity == CapsuleInspector.Issue.Severity.WARNING &&
                it.message.contains("memory")
        })
    }

    @Test
    fun `inspector warns when the capsule requests OpenGL on a device without it`() {
        val capsule = validCapsule(gpu = GpuProfile.OPENGL)
        val device = DeviceCapabilities(
            cpuArch = setOf(CpuArch.ARM64),
            totalMemoryMb = 8192,
            hasVulkan = true,
            hasOpenGL = false
        )
        val result = CapsuleInspector().inspect(capsule, device)
        assertTrue(result.isValid)
        assertTrue(result.issues.any { it.message.contains("OpenGL") })
    }

    @Test
    fun `inspector rejects a device with zero memory as an invalid input`() {
        try {
            DeviceCapabilities(
                cpuArch = setOf(CpuArch.ARM64),
                totalMemoryMb = 0,
                hasVulkan = true,
                hasOpenGL = true
            )
            fail("expected IllegalArgumentException for totalMemoryMb = 0")
        } catch (expected: IllegalArgumentException) {
            // totalMemoryMb must be positive
        }
    }
}
