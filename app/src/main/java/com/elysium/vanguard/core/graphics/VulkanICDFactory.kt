package com.elysium.vanguard.core.graphics

/**
 * The factory that selects the right
 * `VulkanICD` for the device's GPU. The
 * factory is the **only legitimate way** to
 * create a `VulkanICD`; the consumer does
 * not instantiate the ICDs directly.
 *
 * The factory's decision is deterministic:
 *   - `GPUVendor.ADRENO` -> `TurnipICD`
 *     (Mesa Turnip, the open-source
 *     freedreno-based Vulkan driver).
 *   - `GPUVendor.MALI` -> `MaliICD` (the
 *     ARM-provided driver; Phase 1 ships a
 *     stub with the ARM metadata; the
 *     native library is shipped with the
 *     Android system image).
 *   - `GPUVendor.POWER_VR` -> `PowerVRICD`
 *     (the IMG-provided driver; Phase 1
 *     stub).
 *   - `GPUVendor.INTEL` -> `IntelICD`
 *     (Mesa ANV; Phase 1 stub; native
 *     library built from Mesa source).
 *   - `GPUVendor.AMD` -> `AmdICD`
 *     (Mesa RADV; Phase 1 stub; native
 *     library built from Mesa source).
 *   - `GPUVendor.NVIDIA` -> `NvidiaICD`
 *     (the NVIDIA-provided driver; Phase 1
 *     stub).
 *   - `GPUVendor.APPLE` -> `AppleICD`
 *     (the Apple-provided driver; Phase 1
 *     stub; the platform only runs on
 *     Apple Silicon when emulated).
 *   - `GPUVendor.UNKNOWN` -> `NullICD`
 *     (the stub that reports no Vulkan
 *     capability).
 */
object VulkanICDFactory {

    fun create(vendor: GPUVendor): VulkanICD = when (vendor) {
        GPUVendor.ADRENO -> TurnipICD()
        GPUVendor.MALI -> MaliICD()
        GPUVendor.POWER_VR -> PowerVRICD()
        GPUVendor.INTEL -> IntelICD()
        GPUVendor.AMD -> AmdICD()
        GPUVendor.NVIDIA -> NvidiaICD()
        GPUVendor.APPLE -> AppleICD()
        GPUVendor.UNKNOWN -> NullICD()
    }
}

/** ARM Mali stub. The native driver ships with the system. */
class MaliICD : VulkanICD by StubICD(
    vendor = GPUVendor.MALI,
    nativeLibraryName = "vulkan.mali",
    displayName = "ARM Mali",
    apiVersion = VulkanApiVersion.VK_1_3,
)

/** Imagination PowerVR stub. The native driver ships with the system. */
class PowerVRICD : VulkanICD by StubICD(
    vendor = GPUVendor.POWER_VR,
    nativeLibraryName = "vulkan.pvr",
    displayName = "Imagination PowerVR",
    apiVersion = VulkanApiVersion.VK_1_3,
)

/** Intel stub. Mesa ANV is the production driver (Phase 2). */
class IntelICD : VulkanICD by StubICD(
    vendor = GPUVendor.INTEL,
    nativeLibraryName = "vulkan.intel",
    displayName = "Mesa ANV (Intel)",
    apiVersion = VulkanApiVersion.VK_1_3,
)

/** AMD stub. Mesa RADV is the production driver (Phase 2). */
class AmdICD : VulkanICD by StubICD(
    vendor = GPUVendor.AMD,
    nativeLibraryName = "vulkan.radeon",
    displayName = "Mesa RADV (AMD)",
    apiVersion = VulkanApiVersion.VK_1_3,
)

/** NVIDIA stub. The native driver ships with the system. */
class NvidiaICD : VulkanICD by StubICD(
    vendor = GPUVendor.NVIDIA,
    nativeLibraryName = "vulkan.nvidia",
    displayName = "NVIDIA",
    apiVersion = VulkanApiVersion.VK_1_3,
)

/** Apple stub. Phase 2. */
class AppleICD : VulkanICD by StubICD(
    vendor = GPUVendor.APPLE,
    nativeLibraryName = "vulkan.apple",
    displayName = "Apple",
    apiVersion = VulkanApiVersion.VK_1_2,
)

/** Null ICD. Reports no Vulkan capability. */
class NullICD : VulkanICD by StubICD(
    vendor = GPUVendor.UNKNOWN,
    nativeLibraryName = "",
    displayName = "No Vulkan ICD",
    apiVersion = VulkanApiVersion.UNKNOWN,
)

/**
 * A delegate-based stub for the non-Turnip
 * ICDs. Each stub exposes the metadata
 * (vendor + library + display name + API
 * version) without the actual native
 * library call. The Phase 2 implementation
 * replaces the stub with a real JNI call.
 */
private class StubICD(
    override val vendor: GPUVendor,
    override val nativeLibraryName: String,
    override val displayName: String,
    private val apiVersion: VulkanApiVersion,
) : VulkanICD {

    override val driverVersion: String = "stub-1.0.0"

    override fun loadCapability(): VulkanCapability = VulkanCapability(
        apiVersion = apiVersion,
        vendor = vendor,
        driverName = displayName,
        driverVersion = driverVersion,
        supportedExtensions = emptySet(),
    )
}
