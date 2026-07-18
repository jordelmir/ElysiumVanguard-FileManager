package com.elysium.vanguard.core.graphics

/**
 * The Vulkan capabilities exposed by a
 * `VulkanICD`. The capability is the
 * **highest Vulkan version** the driver
 * supports + the **list of optional
 * extensions** the driver exposes.
 *
 * The platform uses the capability to:
 *   - Decide which `MarketListing` versions
 *     to install (a listing may require
 *     Vulkan 1.3).
 *   - Decide which features the desktop
 *     shell can render (raytracing,
 *     mesh shaders, etc.).
 *   - Decide which fallback to use when a
 *     feature is not available.
 */
data class VulkanCapability(
    val apiVersion: VulkanApiVersion,
    val vendor: GPUVendor,
    val driverName: String,
    val driverVersion: String,
    val supportedExtensions: Set<String>,
) {
    val supportsVulkan13: Boolean
        get() = apiVersion == VulkanApiVersion.VK_1_3 || apiVersion == VulkanApiVersion.VK_1_4

    val supportsVulkan12: Boolean
        get() = supportsVulkan13 || apiVersion == VulkanApiVersion.VK_1_2

    val supportsVulkan11: Boolean
        get() = supportsVulkan12 || apiVersion == VulkanApiVersion.VK_1_1
}

/**
 * The Vulkan API version. Encoded as
 * `(major << 22) | (minor << 12) | patch` per
 * the Vulkan spec. Phase 1 uses the major +
 * minor pair; the patch is reserved.
 */
enum class VulkanApiVersion(val major: Int, val minor: Int) {
    VK_1_0(1, 0),
    VK_1_1(1, 1),
    VK_1_2(1, 2),
    VK_1_3(1, 3),
    VK_1_4(1, 4),
    UNKNOWN(0, 0);

    companion object {
        /**
         * Decode a Vulkan API version (the
         * `VK_MAKE_API_VERSION` macro) into a
         * `VulkanApiVersion` enum value.
         */
        fun fromEncoded(encoded: Int): VulkanApiVersion {
            val major = (encoded shr 22) and 0x7F
            val minor = (encoded shr 12) and 0x3FF
            return when {
                major == 1 && minor == 0 -> VK_1_0
                major == 1 && minor == 1 -> VK_1_1
                major == 1 && minor == 2 -> VK_1_2
                major == 1 && minor == 3 -> VK_1_3
                major == 1 && minor == 4 -> VK_1_4
                else -> UNKNOWN
            }
        }
    }
}
