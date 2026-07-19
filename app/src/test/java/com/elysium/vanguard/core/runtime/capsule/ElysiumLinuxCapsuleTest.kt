package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.core.runtime.market.ElysiumLinuxDistroListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 74 (second half) — the JVM tests for
 * [ElysiumLinuxCapsule].
 *
 * The tests cover:
 *   - Capsule identity (id, name, version).
 *   - Runtime + architecture (Linux + ARM64).
 *   - Distribution id matches the listing's id.
 *   - Entrypoint (elysium-pm init).
 *   - GPU config (Vulkan + Turnip).
 *   - Permissions (network=true, storage=[]).
 *   - Placeholder content hash + signature are
 *     non-blank.
 *   - build() returns a valid Capsule with all
 *     fields populated.
 */
class ElysiumLinuxCapsuleTest {

    // ============================================================
    // Capsule identity
    // ============================================================

    @Test
    fun `Capsule id is the reverse-DNS namespace`() {
        assertEquals(
            "com.elysium.linux",
            ElysiumLinuxCapsule.ID.value,
        )
    }

    @Test
    fun `Capsule name is Elysium Linux`() {
        assertEquals("Elysium Linux", ElysiumLinuxCapsule.NAME)
    }

    @Test
    fun `Capsule version is 1-0-0`() {
        assertEquals("1.0.0", ElysiumLinuxCapsule.VERSION)
    }

    @Test
    fun `Capsule apiVersion is V1`() {
        assertEquals("elysium.capsule/v1", ElysiumLinuxCapsule.API_VERSION.value)
    }

    // ============================================================
    // Runtime + architecture
    // ============================================================

    @Test
    fun `Capsule runtime is LINUX`() {
        assertEquals(Runtime.LINUX, ElysiumLinuxCapsule.RUNTIME)
    }

    @Test
    fun `Capsule architecture is ARM64`() {
        assertEquals(Architecture.ARM64, ElysiumLinuxCapsule.ARCHITECTURE)
    }

    // ============================================================
    // Distribution linkage
    // ============================================================

    @Test
    fun `Capsule distribution id matches the listing id`() {
        // The Capsule's distribution id MUST
        // match the listing's id. The orchestrator
        // matches the listing + the capsule by
        // distribution id; a mismatched pair is
        // a deployment error.
        assertEquals(
            ElysiumLinuxDistroListing.ID,
            ElysiumLinuxCapsule.DISTRIBUTION_ID,
        )
    }

    // ============================================================
    // Entrypoint
    // ============================================================

    @Test
    fun `Capsule entrypoint executable is elysium-pm`() {
        assertEquals(
            "/usr/bin/elysium-pm",
            ElysiumLinuxCapsule.ENTRYPOINT.executable,
        )
    }

    @Test
    fun `Capsule entrypoint args is the init command`() {
        assertEquals(listOf("init"), ElysiumLinuxCapsule.ENTRYPOINT.args)
    }

    @Test
    fun `Capsule entrypoint workingDirectory is the rootfs root`() {
        assertEquals("/", ElysiumLinuxCapsule.ENTRYPOINT.workingDirectory)
    }

    // ============================================================
    // GPU config
    // ============================================================

    @Test
    fun `Capsule gpu api is VULKAN`() {
        assertEquals(GpuApi.VULKAN, ElysiumLinuxCapsule.GPU.api)
    }

    @Test
    fun `Capsule gpu driver is TURNIP`() {
        assertEquals(GpuDriver.TURNIP, ElysiumLinuxCapsule.GPU.driver)
    }

    // ============================================================
    // Permissions
    // ============================================================

    @Test
    fun `Capsule permissions network is true`() {
        assertTrue(ElysiumLinuxCapsule.PERMISSIONS.network)
    }

    @Test
    fun `Capsule permissions storage is empty`() {
        // Storage is per-workspace; the Capsule
        // itself does not need any storage.
        assertEquals(emptyList<StorageScope>(), ElysiumLinuxCapsule.PERMISSIONS.storage)
    }

    // ============================================================
    // Placeholder content + signature
    // ============================================================

    @Test
    fun `Capsule contentHash is a non-blank placeholder`() {
        assertNotNull(ElysiumLinuxCapsule.CONTENT_HASH)
        assertTrue(
            "expected non-blank content hash, got: ${ElysiumLinuxCapsule.CONTENT_HASH.value}",
            ElysiumLinuxCapsule.CONTENT_HASH.value.isNotBlank(),
        )
    }

    @Test
    fun `Capsule signature is a non-blank placeholder`() {
        assertNotNull(ElysiumLinuxCapsule.SIGNATURE)
        assertTrue(
            "expected non-blank signature, got: ${ElysiumLinuxCapsule.SIGNATURE.value}",
            ElysiumLinuxCapsule.SIGNATURE.value.isNotBlank(),
        )
    }

    // ============================================================
    // build() factory
    // ============================================================

    @Test
    fun `build returns a valid Capsule with all fields populated`() {
        val capsule = ElysiumLinuxCapsule.build()
        assertEquals(ElysiumLinuxCapsule.API_VERSION, capsule.apiVersion)
        assertEquals(ElysiumLinuxCapsule.ID, capsule.id)
        assertEquals(ElysiumLinuxCapsule.NAME, capsule.name)
        assertEquals(ElysiumLinuxCapsule.VERSION, capsule.version)
        assertEquals(ElysiumLinuxCapsule.DESCRIPTION, capsule.description)
        assertEquals(ElysiumLinuxCapsule.RUNTIME, capsule.runtime)
        assertEquals(ElysiumLinuxCapsule.ARCHITECTURE, capsule.architecture)
        assertEquals(
            ElysiumLinuxCapsule.DISTRIBUTION_ID,
            capsule.distribution.id,
        )
        assertEquals(ElysiumLinuxCapsule.ENTRYPOINT, capsule.entrypoint)
        assertEquals(ElysiumLinuxCapsule.GPU, capsule.gpu)
        assertEquals(ElysiumLinuxCapsule.PERMISSIONS, capsule.permissions)
        assertEquals(ElysiumLinuxCapsule.SIGNATURE, capsule.signature)
        assertEquals(ElysiumLinuxCapsule.CONTENT_HASH, capsule.contentHash)
    }
}
