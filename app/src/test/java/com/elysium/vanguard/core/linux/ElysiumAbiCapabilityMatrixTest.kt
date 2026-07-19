package com.elysium.vanguard.core.linux

import com.elysium.vanguard.core.graphics.GPUVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 73 third half (I-73.3.3) — the JVM tests
 * for [ElysiumAbiCapabilityMatrix].
 *
 * These tests cover:
 *   - Construction invariants (non-empty, ARM64
 *     required, every ABI's set is non-empty,
 *     every ABI has NATIVE).
 *   - layersFor(abi): returns the ABI's layers.
 *   - layersFor(abi, gpuVendor): returns ABI +
 *     GPU layers.
 *   - isLayerAvailable: positive + negative cases.
 *   - missingLayers: required set, available set,
 *     difference.
 *   - Default Android ARM64 matrix: the official
 *     layer set is correct.
 */
class ElysiumAbiCapabilityMatrixTest {

    // ============================================================
    // Construction invariants
    // ============================================================

    @Test
    fun `matrix rejects empty abiLayers`() {
        try {
            ElysiumAbiCapabilityMatrix(
                abiLayers = emptyMap(),
                gpuVendorLayers = mapOf(GPUVendor.ADRENO to emptySet()),
            )
            fail("expected IllegalArgumentException for empty abiLayers")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("abiLayers"))
        }
    }

    @Test
    fun `matrix requires ARM64 in abiLayers`() {
        try {
            ElysiumAbiCapabilityMatrix(
                abiLayers = mapOf(
                    ElysiumAbi.X86_64 to setOf(ElysiumRuntimeLayerId.NATIVE),
                ),
                gpuVendorLayers = emptyMap(),
            )
            fail("expected IllegalArgumentException for missing ARM64")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'ARM64', got: ${e.message}",
                e.message!!.contains("ARM64"),
            )
        }
    }

    @Test
    fun `matrix rejects an ABI with no layers`() {
        try {
            ElysiumAbiCapabilityMatrix(
                abiLayers = mapOf(
                    ElysiumAbi.ARM64 to setOf(ElysiumRuntimeLayerId.NATIVE),
                    ElysiumAbi.ARM32 to emptySet(),
                ),
                gpuVendorLayers = emptyMap(),
            )
            fail("expected IllegalArgumentException for ABI with no layers")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention the ABI, got: ${e.message}",
                e.message!!.contains("armeabi-v7a"),
            )
        }
    }

    @Test
    fun `matrix requires every ABI to have NATIVE layer`() {
        try {
            ElysiumAbiCapabilityMatrix(
                abiLayers = mapOf(
                    ElysiumAbi.ARM64 to setOf(ElysiumRuntimeLayerId.BOX64),
                ),
                gpuVendorLayers = emptyMap(),
            )
            fail("expected IllegalArgumentException for missing NATIVE")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'NATIVE', got: ${e.message}",
                e.message!!.contains("NATIVE"),
            )
        }
    }

    @Test
    fun `matrix accepts a well-formed configuration`() {
        val matrix = ElysiumAbiCapabilityMatrix(
            abiLayers = mapOf(
                ElysiumAbi.ARM64 to setOf(
                    ElysiumRuntimeLayerId.NATIVE,
                    ElysiumRuntimeLayerId.BOX64,
                ),
            ),
            gpuVendorLayers = emptyMap(),
        )
        assertEquals(
            setOf(ElysiumRuntimeLayerId.NATIVE, ElysiumRuntimeLayerId.BOX64),
            matrix.layersFor(ElysiumAbi.ARM64),
        )
    }

    // ============================================================
    // layersFor(abi)
    // ============================================================

    @Test
    fun `layersFor ARM64 returns the full layer set on the default matrix`() {
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.ARM64, GPUVendor.ADRENO)
        assertEquals(
            setOf(
                ElysiumRuntimeLayerId.NATIVE,
                ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumRuntimeLayerId.BOX64,
                ElysiumRuntimeLayerId.FEX,
                ElysiumRuntimeLayerId.WINE,
            ),
            layers,
        )
    }

    @Test
    fun `layersFor ARM64 without GPU vendor does NOT include MESA_TURNIP`() {
        // MESA_TURNIP is Adreno-specific; the
        // matrix without a GPU vendor returns
        // the ABI's layers only (MESA_TURNIP
        // is in the GPU vendor layer set,
        // not the ABI layer set).
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.ARM64)
        assertFalse(
            "expected MESA_TURNIP NOT in ${layers}",
            ElysiumRuntimeLayerId.MESA_TURNIP in layers,
        )
        // The other layers are still there.
        assertTrue(ElysiumRuntimeLayerId.NATIVE in layers)
        assertTrue(ElysiumRuntimeLayerId.BOX64 in layers)
        assertTrue(ElysiumRuntimeLayerId.WINE in layers)
    }

    @Test
    fun `layersFor ARM32 returns only NATIVE on the default matrix`() {
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.ARM32)
        assertEquals(setOf(ElysiumRuntimeLayerId.NATIVE), layers)
    }

    @Test
    fun `layersFor X86_64 returns NATIVE + Box64 + FEX + Wine`() {
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.X86_64)
        assertEquals(
            setOf(
                ElysiumRuntimeLayerId.NATIVE,
                ElysiumRuntimeLayerId.BOX64,
                ElysiumRuntimeLayerId.FEX,
                ElysiumRuntimeLayerId.WINE,
            ),
            layers,
        )
    }

    @Test
    fun `layersFor ANY returns empty set on the default matrix`() {
        // ANY is a sentinel; the default matrix
        // does not declare it (ANY is resolved
        // by the consumer based on the device's
        // actual ABI).
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.ANY)
        assertEquals(emptySet<ElysiumRuntimeLayerId>(), layers)
    }

    // ============================================================
    // layersFor(abi, gpuVendor)
    // ============================================================

    @Test
    fun `layersFor ARM64 with Adreno returns ABI plus MESA_TURNIP`() {
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.ARM64, GPUVendor.ADRENO)
        assertTrue(
            "expected MESA_TURNIP in ${layers}",
            ElysiumRuntimeLayerId.MESA_TURNIP in layers,
        )
        assertTrue(
            "expected NATIVE in ${layers}",
            ElysiumRuntimeLayerId.NATIVE in layers,
        )
    }

    @Test
    fun `layersFor ARM64 with Mali does NOT include MESA_TURNIP`() {
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.ARM64, GPUVendor.MALI)
        assertFalse(
            "expected MESA_TURNIP NOT in ${layers}",
            ElysiumRuntimeLayerId.MESA_TURNIP in layers,
        )
        // NATIVE is still there (Mali phones
        // still run native ARM64).
        assertTrue(
            "expected NATIVE in ${layers}",
            ElysiumRuntimeLayerId.NATIVE in layers,
        )
    }

    @Test
    fun `layersFor X86_64 with Intel does NOT include MESA_TURNIP`() {
        val layers = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
            .layersFor(ElysiumAbi.X86_64, GPUVendor.INTEL)
        assertFalse(
            "expected MESA_TURNIP NOT in ${layers}",
            ElysiumRuntimeLayerId.MESA_TURNIP in layers,
        )
    }

    // ============================================================
    // isLayerAvailable
    // ============================================================

    @Test
    fun `isLayerAvailable returns true for a layer in the ABI`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        assertTrue(matrix.isLayerAvailable(ElysiumRuntimeLayerId.BOX64, ElysiumAbi.ARM64))
    }

    @Test
    fun `isLayerAvailable returns false for a layer not in the ABI`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        // MESA_TURNIP is in the abiLayers for
        // ARM64, but not for X86_64 (Turnip is
        // Adreno-only).
        assertFalse(
            matrix.isLayerAvailable(
                ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumAbi.X86_64,
            ),
        )
    }

    @Test
    fun `isLayerAvailable returns false for an unknown ABI`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        // ANY is not in the abiLayers.
        assertFalse(
            matrix.isLayerAvailable(
                ElysiumRuntimeLayerId.NATIVE,
                ElysiumAbi.ANY,
            ),
        )
    }

    @Test
    fun `isLayerAvailable with GPU vendor returns true for Adreno + MESA_TURNIP`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        assertTrue(
            matrix.isLayerAvailable(
                ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumAbi.ARM64,
                GPUVendor.ADRENO,
            ),
        )
    }

    @Test
    fun `isLayerAvailable with GPU vendor returns false for Mali + MESA_TURNIP`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        assertFalse(
            matrix.isLayerAvailable(
                ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumAbi.ARM64,
                GPUVendor.MALI,
            ),
        )
    }

    // ============================================================
    // missingLayers
    // ============================================================

    @Test
    fun `missingLayers returns the layers not in the available set`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        val missing = matrix.missingLayers(
            required = setOf(
                ElysiumRuntimeLayerId.BOX64,
                ElysiumRuntimeLayerId.WINE,
            ),
            abi = ElysiumAbi.ARM64,
        )
        assertEquals(emptySet<ElysiumRuntimeLayerId>(), missing)
    }

    @Test
    fun `missingLayers returns the layers not supported by the ABI`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        // ARM32 only has NATIVE; requesting
        // NATIVE + BOX64 (an x86_64 translator
        // not supported on ARM32) returns the
        // missing set.
        val missing = matrix.missingLayers(
            required = setOf(
                ElysiumRuntimeLayerId.NATIVE,
                ElysiumRuntimeLayerId.BOX64,
            ),
            abi = ElysiumAbi.ARM32,
        )
        assertEquals(setOf(ElysiumRuntimeLayerId.BOX64), missing)
    }

    @Test
    fun `missingLayers with GPU vendor considers the GPU layer set`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        // Mali phone: ABI has MESA_TURNIP not
        // (Mesa Turnip is Adreno-only), so the
        // GPU layer set is empty.
        val missing = matrix.missingLayers(
            required = setOf(ElysiumRuntimeLayerId.MESA_TURNIP),
            abi = ElysiumAbi.ARM64,
            gpuVendor = GPUVendor.MALI,
        )
        assertEquals(setOf(ElysiumRuntimeLayerId.MESA_TURNIP), missing)
    }

    // ============================================================
    // Default matrix sanity
    // ============================================================

    @Test
    fun `default matrix supports every well-known ABI`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        for (abi in listOf(
            ElysiumAbi.ARM64,
            ElysiumAbi.ARM32,
            ElysiumAbi.X86_64,
            ElysiumAbi.X86,
        )) {
            assertTrue(
                "expected ABI $abi in default matrix",
                abi in matrix.abiLayers,
            )
        }
    }

    @Test
    fun `default matrix supports every GPU vendor`() {
        val matrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64
        for (vendor in GPUVendor.values()) {
            assertTrue(
                "expected GPU vendor $vendor in default matrix",
                vendor in matrix.gpuVendorLayers,
            )
        }
    }
}
