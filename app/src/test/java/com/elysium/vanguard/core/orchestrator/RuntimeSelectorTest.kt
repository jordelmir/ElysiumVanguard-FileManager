package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.graphics.GPUVendor
import com.elysium.vanguard.core.linux.ElysiumAbi
import com.elysium.vanguard.core.linux.ElysiumAbiCapabilityMatrix
import com.elysium.vanguard.core.linux.ElysiumPackageVersion
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerCatalog
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerDefaults
import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.CapsuleApiVersion
import com.elysium.vanguard.core.runtime.capsule.CapsuleId
import com.elysium.vanguard.core.runtime.capsule.Distribution
import com.elysium.vanguard.core.runtime.capsule.EntryPoint
import com.elysium.vanguard.core.runtime.capsule.GpuApi
import com.elysium.vanguard.core.runtime.capsule.GpuConfig
import com.elysium.vanguard.core.runtime.capsule.GpuDriver
import com.elysium.vanguard.core.runtime.capsule.Permissions
import com.elysium.vanguard.core.runtime.capsule.Runtime
import com.elysium.vanguard.core.runtime.capsule.StorageScope
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 76 (Universal Execution Engine, section 6
 * of the Elysium Vanguard vision) — the JVM
 * tests for [RuntimeSelector].
 *
 * The tests cover:
 *   - Native selection: capsule with same ABI
 *     as the device.
 *   - Translated selection: capsule with
 *     different ABI (Box64 / FEX / Wine).
 *   - Unsupported selection: capsule with
 *     unsupported architecture / missing
 *     capabilities.
 *   - GPU requirement: capsule with VULKAN
 *     requires a GPU vendor with Vulkan
 *     support.
 *   - Realistic scenarios: ARM64 device +
 *     x86_64 Windows capsule → Wine + DXVK.
 */
class RuntimeSelectorTest {

    // ============================================================
    // Native selection
    // ============================================================

    @Test
    fun `native selection when capsule ABI matches device ABI`() {
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.ARM64,
            gpuApi = GpuApi.NONE,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Native selection, got $selection",
            selection is RuntimeSelection.Native,
        )
    }

    @Test
    fun `native selection with Vulkan GPU on Adreno`() {
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.ARM64,
            gpuApi = GpuApi.VULKAN,
            gpuDriver = GpuDriver.TURNIP,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Native selection, got $selection",
            selection is RuntimeSelection.Native,
        )
    }

    // ============================================================
    // Translated selection
    // ============================================================

    @Test
    fun `translated selection with Box64 for x86_64 Linux on ARM64 device`() {
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.X86_64,
            gpuApi = GpuApi.NONE,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Translated selection, got $selection",
            selection is RuntimeSelection.Translated,
        )
        val translated = selection as RuntimeSelection.Translated
        assertEquals(TranslationType.BOX64, translated.translation)
        assertEquals(ElysiumAbi.X86_64, translated.targetAbi)
    }

    @Test
    fun `translated selection with FEX for x86 Linux on ARM64 device`() {
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.X86,
            gpuApi = GpuApi.NONE,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Translated selection, got $selection",
            selection is RuntimeSelection.Translated,
        )
        val translated = selection as RuntimeSelection.Translated
        assertEquals(TranslationType.FEX, translated.translation)
        assertEquals(ElysiumAbi.X86, translated.targetAbi)
    }

    @Test
    fun `translated selection with Wine for Windows on ARM64 device`() {
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.WINDOWS,
            architecture = Architecture.X86_64,
            gpuApi = GpuApi.VULKAN,
            gpuDriver = GpuDriver.DXVK,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Translated selection with Wine, got $selection",
            selection is RuntimeSelection.Translated,
        )
        val translated = selection as RuntimeSelection.Translated
        assertEquals(TranslationType.WINE, translated.translation)
        assertEquals(ElysiumAbi.X86_64, translated.targetAbi)
    }

    // ============================================================
    // Unsupported selection
    // ============================================================

    @Test
    fun `unsupported selection for ARM32 Linux on ARM64 device`() {
        // ARM32-to-ARM64 translation is not
        // in the default catalog.
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.ARM32,
            gpuApi = GpuApi.NONE,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Unsupported selection, got $selection",
            selection is RuntimeSelection.Unsupported,
        )
    }

    @Test
    fun `unsupported selection for Windows on x86 device without Wine`() {
        // x86 device has no Wine layer in the
        // default catalog.
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.WINDOWS,
            architecture = Architecture.X86_64,
            gpuApi = GpuApi.NONE,
        )
        val device = newDevice(abi = ElysiumAbi.X86, gpuVendor = null)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Unsupported selection, got $selection",
            selection is RuntimeSelection.Unsupported,
        )
    }

    // ============================================================
    // GPU requirement
    // ============================================================

    @Test
    fun `Vulkan capsule on Mali device is unsupported (no Vulkan layer for Mali)`() {
        // Mali phones don't have a Vulkan
        // layer in the default catalog
        // (Panfrost is a future increment).
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.LINUX,
            architecture = Architecture.ARM64,
            gpuApi = GpuApi.VULKAN,
            gpuDriver = GpuDriver.NONE,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.MALI)
        val selection = selector.select(capsule, device)
        // The capsule's GPU requirement is
        // Vulkan. The device has no Vulkan
        // capability (Mali's Panfrost is
        // future). The selection is
        // Unsupported.
        assertTrue(
            "expected Unsupported selection, got $selection",
            selection is RuntimeSelection.Unsupported,
        )
    }

    // ============================================================
    // Realistic scenarios
    // ============================================================

    @Test
    fun `realistic scenario Steam (x86_64 Windows) on Adreno ARM64 device`() {
        // Steam is a x86_64 Windows app. On an
        // Adreno ARM64 phone, Steam needs Wine
        // (for the Windows API) + DXVK (for
        // Direct3D 9-11 over Vulkan).
        val (selector, _) = newSelector()
        val capsule = newCapsule(
            runtime = Runtime.WINDOWS,
            architecture = Architecture.X86_64,
            gpuApi = GpuApi.VULKAN,
            gpuDriver = GpuDriver.DXVK,
        )
        val device = newDevice(abi = ElysiumAbi.ARM64, gpuVendor = GPUVendor.ADRENO)
        val selection = selector.select(capsule, device)
        assertTrue(
            "expected Translated selection with Wine, got $selection",
            selection is RuntimeSelection.Translated,
        )
        val translated = selection as RuntimeSelection.Translated
        assertEquals(TranslationType.WINE, translated.translation)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun newSelector(): Pair<RuntimeSelector, ElysiumRuntimeLayerCatalog> {
        val catalog = ElysiumRuntimeLayerCatalog()
        for (manifest in ElysiumRuntimeLayerDefaults.ALL) {
            catalog.addLayer(manifest, ElysiumRuntimeLayerDefaults.DEFAULT_SIGNING_KEY)
        }
        return RuntimeSelector(
            capabilityMatrix = ElysiumAbiCapabilityMatrix.DEFAULT_ANDROID_ARM64,
            runtimeLayerCatalog = catalog,
        ) to catalog
    }

    private fun newDevice(
        abi: ElysiumAbi,
        gpuVendor: GPUVendor?,
        hasRoot: Boolean = false,
        availableMemoryMb: Long = 8_192L,
    ): DeviceProfile = DeviceProfile(
        abi = abi,
        gpuVendor = gpuVendor,
        hasRoot = hasRoot,
        availableMemoryMb = availableMemoryMb,
    )

    private fun newCapsule(
        runtime: Runtime,
        architecture: Architecture,
        gpuApi: GpuApi,
        gpuDriver: GpuDriver = GpuDriver.NONE,
    ): Capsule = Capsule(
        apiVersion = CapsuleApiVersion.V1,
        id = CapsuleId("com.test.capsule"),
        name = "Test Capsule",
        version = "1.0.0",
        description = "Test",
        runtime = runtime,
        architecture = architecture,
        distribution = Distribution("test:1.0.0"),
        entrypoint = EntryPoint("/bin/test"),
        gpu = GpuConfig(api = gpuApi, driver = gpuDriver),
        permissions = Permissions(
            network = false,
            storage = listOf(StorageScope.APP_PRIVATE),
        ),
        signature = Signature("test-signature"),
        contentHash = ContentHash("0".repeat(64)),
    )
}
