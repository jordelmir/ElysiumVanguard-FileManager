package com.elysium.vanguard.core.graphics

/**
 * The Mesa Turnip ICD (the Vulkan driver for
 * Qualcomm Adreno GPUs). Mesa Turnip is the
 * open-source Vulkan driver based on the
 * `freedreno` project; it supports Vulkan 1.3
 * on Adreno 6xx and later.
 *
 * The ICD is the **JVM-side** wrapper. The
 * native library is a separate `.so` (built
 * from Mesa source via cross-compilation);
 * the JVM-side wrapper provides the
 * metadata (vendor + library name + display
 * name + driver version) + the capability
 * report.
 *
 * Building Mesa Turnip for Android:
 *   1. Get the Mesa source:
 *      `git clone https://gitlab.freedesktop.org/mesa/mesa.git`
 *   2. Set up the Android NDK cross-compile
 *      toolchain.
 *   3. Configure with:
 *      `meson setup build-android \
 *         --cross-file android-cross.ini \
 *         -Dvulkan-drivers=freedreno \
 *         -Dplatforms=android`
 *   4. Build with `ninja -C build-android`.
 *   5. The output is `libvulkan_adreno.so`;
 *      copy it to `app/src/main/jniLibs/<abi>/`.
 *   6. The JVM-side wrapper exposes it as
 *      `vulkan.adreno` (per Android's Vulkan
 *      loader convention).
 *
 * The Phase 1 capability report is a
 * placeholder. The Phase 2 implementation
 * calls the native library to get the real
 * capability (Vulkan version + supported
 * extensions).
 */
class TurnipICD(
    override val driverVersion: String = "23.1.0",
) : VulkanICD {

    override val vendor: GPUVendor = GPUVendor.ADRENO
    override val nativeLibraryName: String = "vulkan.adreno"
    override val displayName: String = "Mesa Turnip (freedreno)"

    override fun loadCapability(): VulkanCapability = VulkanCapability(
        apiVersion = VulkanApiVersion.VK_1_3,
        vendor = vendor,
        driverName = displayName,
        driverVersion = driverVersion,
        supportedExtensions = setOf(
            "VK_KHR_surface",
            "VK_KHR_swapchain",
            "VK_KHR_synchronization2",
            "VK_KHR_dynamic_rendering",
            "VK_KHR_create_renderpass2",
            "VK_EXT_descriptor_indexing",
            "VK_KHR_push_descriptor",
            "VK_KHR_shader_float16_int8",
        ),
    )
}
