package com.elysium.vanguard.core.graphics

/**
 * The platform's view of a Vulkan ICD
 * (Installable Client Driver). An ICD is
 * the native library that implements the
 * Vulkan API; the platform loads the ICD
 * + uses it to render.
 *
 * The Phase 1 ICD interface is **JVM-side**:
 * it provides the capability report +
 * the path to the native library. The
 * Phase 2 interface adds the actual
 * Vulkan call marshaling (via JNI).
 *
 * The ICD lifecycle is:
 *   1. The `VulkanICDFactory.detect` method
 *      determines the GPU vendor.
 *   2. The factory creates the appropriate
 *      ICD for the vendor.
 *   3. The platform loads the ICD's native
 *      library (`System.loadLibrary`).
 *   4. The platform calls the ICD's
 *      `loadCapability` to get the
 *      capability report.
 *   5. The platform uses the capability to
 *      decide which features to render.
 */
interface VulkanICD {

    /** The vendor this ICD is for. */
    val vendor: GPUVendor

    /**
     * The native library name (without the
     * `lib` prefix and the `.so` suffix).
     * For Mesa Turnip, this is `vulkan.adreno`.
     * For ARM Mali, this is `vulkan.mali`.
     */
    val nativeLibraryName: String

    /**
     * The display name (for the diagnostic
     * + the UI). For Mesa Turnip, this is
     * "Mesa Turnip (freedreno)".
     */
    val displayName: String

    /**
     * The version of the driver (semver
     * string). For Mesa Turnip, this is the
     * Mesa version + the freedreno version.
     */
    val driverVersion: String

    /**
     * Load the capability report. The
     * implementation calls the native
     * library to query the Vulkan version
     * + the supported extensions.
     *
     * Phase 1: returns a stub capability
     * (the values are hard-coded for
     * testing; the native call is wired in
     * Phase 2).
     */
    fun loadCapability(): VulkanCapability
}
