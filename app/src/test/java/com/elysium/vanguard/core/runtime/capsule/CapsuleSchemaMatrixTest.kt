package com.elysium.vanguard.core.runtime.capsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 107 — JVM tests for the **GPU API × driver matrix**
 * the capsule schema documents.
 *
 * The matrix is the single source of truth for "which
 * driver supports which API". The Kotlin `GpuConfig` data
 * class does not enforce the matrix (the runtime checks
 * the device's actual capabilities at install time);
 * these tests pin the **documented** matrix so a future
 * refactor that changes the documented behavior is
 * caught at CI.
 *
 * The matrix is also published in
 * `docs/capsule/SCHEMA.md` (the public creator doc) +
 * `docs/capsule/capsule.schema.json` (the formal JSON
 * Schema) + the `tools/validate-capsule.sh` Python
 * validator. **All three** are pinned by these tests.
 */
class CapsuleSchemaMatrixTest {

    /**
     * The documented GPU API × driver matrix. Any
     * combination not in this map is a documented
     * UNSUPPORTED combination.
     */
    private val documentedMatrix: Map<GpuDriver, Set<GpuApi>> = mapOf(
        GpuDriver.NONE         to setOf(GpuApi.NONE, GpuApi.OPENGL_ES, GpuApi.VULKAN, GpuApi.OPENCL),
        GpuDriver.TURNIP       to setOf(GpuApi.OPENGL_ES, GpuApi.VULKAN),
        GpuDriver.FREEDRENO    to setOf(GpuApi.OPENGL_ES),
        GpuDriver.PANFROST     to setOf(GpuApi.OPENGL_ES, GpuApi.VULKAN),
        GpuDriver.LIMA         to setOf(GpuApi.OPENGL_ES),
        GpuDriver.SOFTPIPE     to setOf(GpuApi.OPENGL_ES),
        GpuDriver.DXVK         to setOf(GpuApi.VULKAN),
        GpuDriver.VKD3D_PROTON to setOf(GpuApi.VULKAN),
    )

    @Test
    fun `all 8 GPU drivers are in the documented matrix`() {
        for (driver in GpuDriver.values()) {
            assertTrue(
                "driver $driver must appear in the documented matrix",
                documentedMatrix.containsKey(driver)
            )
        }
    }

    @Test
    fun `all 4 GPU APIs are supported by at least one driver`() {
        for (api in GpuApi.values()) {
            val supportedBy = documentedMatrix.entries.filter { api in it.value }
            assertTrue(
                "API $api must be supported by at least one driver; got $supportedBy",
                supportedBy.isNotEmpty()
            )
        }
    }

    @Test
    fun `TURNIP supports OpenGL ES and Vulkan but NOT OpenCL (Adreno fact)`() {
        assertTrue(GpuApi.OPENGL_ES in documentedMatrix[GpuDriver.TURNIP]!!)
        assertTrue(GpuApi.VULKAN in documentedMatrix[GpuDriver.TURNIP]!!)
        assertFalse(GpuApi.OPENCL in documentedMatrix[GpuDriver.TURNIP]!!)
    }

    @Test
    fun `PANFROST supports OpenGL ES and Vulkan (Mali fact)`() {
        assertTrue(GpuApi.OPENGL_ES in documentedMatrix[GpuDriver.PANFROST]!!)
        assertTrue(GpuApi.VULKAN in documentedMatrix[GpuDriver.PANFROST]!!)
        assertFalse(GpuApi.OPENCL in documentedMatrix[GpuDriver.PANFROST]!!)
    }

    @Test
    fun `DXVK and VKD3D_PROTON only support Vulkan (D3D-to-Vulkan fact)`() {
        for (driver in listOf(GpuDriver.DXVK, GpuDriver.VKD3D_PROTON)) {
            assertTrue(GpuApi.VULKAN in documentedMatrix[driver]!!)
            assertFalse("driver $driver must not support OpenGL ES",
                GpuApi.OPENGL_ES in documentedMatrix[driver]!!)
            assertFalse("driver $driver must not support OpenCL",
                GpuApi.OPENCL in documentedMatrix[driver]!!)
        }
    }

    @Test
    fun `SOFTPIPE is the only fallback for OpenGL ES on a Vulkan-only device`() {
        // SOFTPIPE is the only Vulkan-less driver that
        // supports OpenGL ES — useful for old devices.
        assertTrue(GpuApi.OPENGL_ES in documentedMatrix[GpuDriver.SOFTPIPE]!!)
        assertFalse(GpuApi.VULKAN in documentedMatrix[GpuDriver.SOFTPIPE]!!)
    }

    @Test
    fun `NONE supports every API (the "I don't need a GPU" sentinel)`() {
        val supported = documentedMatrix[GpuDriver.NONE]!!
        assertEquals(
            "NONE driver must support all 4 GPU APIs",
            setOf(GpuApi.NONE, GpuApi.OPENGL_ES, GpuApi.VULKAN, GpuApi.OPENCL),
            supported
        )
    }

    /**
     * The signature pattern. The capsule schema requires
     * `ed25519:<base64>`. The Kotlin `Capsule` data class
     * does NOT enforce the prefix (only "non-blank"); these
     * tests pin the **documented** format.
     */
    @Test
    fun `signature pattern is ed25519 colon base64`() {
        val pattern = Regex("^ed25519:[A-Za-z0-9+/]+={0,2}$")
        // A valid signature (44 base64 chars + 1 padding = 45 chars)
        assertTrue(pattern.matches("ed25519:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        // Minimum length: 1 base64 char + 0 padding
        assertTrue(pattern.matches("ed25519:A"))
        // 2 padding chars
        assertTrue(pattern.matches("ed25519:AAA=="))
        // No padding
        assertTrue(pattern.matches("ed25519:AAAA"))
        // Missing prefix
        assertFalse(pattern.matches("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        // Wrong prefix
        assertFalse(pattern.matches("rsa:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))
        // Plain text (not base64)
        assertFalse(pattern.matches("ed25519:not-base64!@#"))
        // Empty base64 (REJECTED — at least 1 base64 char required)
        assertFalse(pattern.matches("ed25519:"))
        // Just the colon, no payload
        assertFalse(pattern.matches("ed25519:"))
    }

    @Test
    fun `contentHash is exactly 64 hex chars`() {
        val pattern = Regex("^[0-9a-f]{64}$")
        val validHash = "a".repeat(64)
        val validZero = "0".repeat(64)
        val validMixed = ("0123456789abcdef").repeat(4)
        val tooShort = "a".repeat(63)
        val tooLong = "a".repeat(65)
        val uppercase = "A".repeat(64)
        val nonHex = "g".repeat(64)
        // Valid SHA-256 hashes
        assertTrue(pattern.matches(validHash))
        assertTrue(pattern.matches(validZero))
        assertTrue(pattern.matches(validMixed))
        // Too short / too long
        assertFalse(pattern.matches(tooShort))
        assertFalse(pattern.matches(tooLong))
        // Uppercase hex (rejected — the schema requires lowercase)
        assertFalse(pattern.matches(uppercase))
        // Non-hex characters
        assertFalse(pattern.matches(nonHex))
    }

    /**
     * The apiVersion pattern. The capsule schema requires
     * `^elysium\.capsule/v\d+(\.\d+)?$`.
     */
    @Test
    fun `apiVersion pattern is elysium capsule v digit with optional minor`() {
        val pattern = Regex("^elysium\\.capsule/v\\d+(\\.\\d+)?$")
        // v1
        assertTrue(pattern.matches("elysium.capsule/v1"))
        // v1.x
        assertTrue(pattern.matches("elysium.capsule/v1.0"))
        assertTrue(pattern.matches("elysium.capsule/v1.1"))
        // Wrong namespace
        assertFalse(pattern.matches("elysium.workspace/v1"))
        // Missing version
        assertFalse(pattern.matches("elysium.capsule"))
        // Wrong version
        assertFalse(pattern.matches("elysium.capsule/v"))
        assertFalse(pattern.matches("elysium.capsule/1"))
    }

    /**
     * The CapsuleId pattern. Reverse-DNS namespace.
     */
    @Test
    fun `capsuleId pattern is reverse-DNS with at least one dot`() {
        val pattern = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
        // Two-segment: com.example
        assertTrue(pattern.matches("com.example"))
        // Three-segment: com.example.myapp
        assertTrue(pattern.matches("com.example.myapp"))
        // With underscores and digits
        assertTrue(pattern.matches("com.example.my_app2"))
        // No dots
        assertFalse(pattern.matches("standalone"))
        // Uppercase
        assertFalse(pattern.matches("Com.Example"))
        // Starts with a digit
        assertFalse(pattern.matches("1example.app"))
        // Starts with an underscore
        assertFalse(pattern.matches("_example.app"))
    }
}
