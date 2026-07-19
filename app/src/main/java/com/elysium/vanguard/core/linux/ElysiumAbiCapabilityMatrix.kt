package com.elysium.vanguard.core.linux

import com.elysium.vanguard.core.graphics.GPUVendor

/**
 * Phase 73 third half (I-73.3.3) — the **Elysium ABI
 * Capability Matrix**.
 *
 * The matrix is the **typed answer** to the
 * orchestrator's question: "Which runtime layers
 * are available on this device?". The matrix
 * maps:
 *   - `ElysiumAbi` (the device's CPU architecture)
 *     to `Set<ElysiumRuntimeLayerId>` (the layers
 *     the device can run).
 *   - `GPUVendor` (the device's GPU vendor) to
 *     `Set<ElysiumRuntimeLayerId>` (the GPU layers
 *     the device can run).
 *
 * The matrix is **declarative** (a data class, not
 * a procedure). The matrix is **read-only** (a
 * device's capabilities are fixed at install time
 * + the orchestrator reads the matrix, never
 * writes).
 *
 * The matrix is the **canonical document** of
 * Elysium Linux's runtime support. A new runtime
 * layer is added to the matrix when it ships;
 * a new ABI is added when the Elysium Linux
 * distribution team starts supporting it.
 *
 * The matrix is the **only** source the
 * orchestrator uses to decide whether a capsule
 * can run on a device. A capsule's `architecture`
 * + `gpu` fields are matched against the matrix;
 * a mismatch is a typed `UnsupportedCapability`
 * error.
 */
data class ElysiumAbiCapabilityMatrix(
    /**
     * The ABI → layers mapping. The matrix is
     * keyed by ABI; the value is the set of
     * runtime layer ids the ABI supports.
     *
     * Example: `ARM64` supports `NATIVE`,
     * `MESA_TURNIP` (on Adreno), `BOX64`, `FEX`,
     * `WINE`. `X86` supports `NATIVE` only.
     */
    val abiLayers: Map<ElysiumAbi, Set<ElysiumRuntimeLayerId>>,

    /**
     * The GPU vendor → layers mapping. The matrix
     * is keyed by GPU vendor; the value is the set
     * of runtime layer ids the GPU vendor
     * supports.
     *
     * Example: `ADRENO` supports `MESA_TURNIP`.
     * `MALI` supports a future `MESA_PANFROST`
     * (not in the default catalog; a Phase 73
     * future increment).
     */
    val gpuVendorLayers: Map<GPUVendor, Set<ElysiumRuntimeLayerId>>,
) {
    init {
        require(abiLayers.isNotEmpty()) {
            "ElysiumAbiCapabilityMatrix.abiLayers must not be empty"
        }
        require(ElysiumAbi.ARM64 in abiLayers) {
            "ElysiumAbiCapabilityMatrix.abiLayers must include ARM64 " +
                "(every Elysium Linux install has ARM64)"
        }
        // Every ABI's set is non-empty (an ABI
        // with no layers is a misconfiguration).
        for ((abi, layers) in abiLayers) {
            require(layers.isNotEmpty()) {
                "ElysiumAbiCapabilityMatrix: ABI ${ElysiumAbi.canonicalName(abi)} " +
                    "has no layers (a misconfiguration)"
            }
            // Every ABI has the NATIVE layer
            // (the baseline; every Elysium Linux
            // install runs native binaries).
            require(ElysiumRuntimeLayerId.NATIVE in layers) {
                "ElysiumAbiCapabilityMatrix: ABI ${ElysiumAbi.canonicalName(abi)} " +
                    "must include the NATIVE layer"
            }
        }
    }

    /**
     * The runtime layer ids available for a
     * specific ABI. The result is the set union
     * of the ABI's layers + the GPU vendor's
     * layers.
     */
    fun layersFor(abi: ElysiumAbi, gpuVendor: GPUVendor? = null): Set<ElysiumRuntimeLayerId> {
        val abiSet = abiLayers[abi] ?: emptySet()
        val gpuSet = if (gpuVendor != null) {
            gpuVendorLayers[gpuVendor] ?: emptySet()
        } else emptySet()
        return abiSet + gpuSet
    }

    /**
     * Check whether a specific layer is
     * available for a specific ABI.
     */
    fun isLayerAvailable(
        layerId: ElysiumRuntimeLayerId,
        abi: ElysiumAbi,
    ): Boolean = layerId in (abiLayers[abi] ?: emptySet())

    /**
     * Check whether a specific layer is
     * available for a specific ABI + GPU vendor.
     */
    fun isLayerAvailable(
        layerId: ElysiumRuntimeLayerId,
        abi: ElysiumAbi,
        gpuVendor: GPUVendor?,
    ): Boolean = layerId in layersFor(abi, gpuVendor)

    /**
     * The missing capabilities for a capsule.
     * A capsule requires a set of layers; this
     * method returns the layers the device does
     * NOT have.
     */
    fun missingLayers(
        required: Set<ElysiumRuntimeLayerId>,
        abi: ElysiumAbi,
        gpuVendor: GPUVendor? = null,
    ): Set<ElysiumRuntimeLayerId> {
        val available = layersFor(abi, gpuVendor)
        return required - available
    }

    /**
     * The default matrix for Elysium Linux on
     * Android ARM64 devices. This is the **most
     * common case** — every Android phone runs
     * ARM64.
     *
     * The matrix documents which layers the
     * Elysium Linux distribution team **officially
     * supports** on the device class. A future
     * increment may add `X86_64` (for Chromebooks
     * and emulators) and `X86` (for legacy
     * 32-bit x86 desktops).
     */
    companion object {

        /**
         * The default Android ARM64 matrix. Every
         * layer the user can install on an Android
         * ARM64 device is in this matrix.
         */
        val DEFAULT_ANDROID_ARM64: ElysiumAbiCapabilityMatrix = ElysiumAbiCapabilityMatrix(
            abiLayers = mapOf(
                ElysiumAbi.ARM64 to setOf(
                    ElysiumRuntimeLayerId.NATIVE,
                    ElysiumRuntimeLayerId.BOX64,
                    ElysiumRuntimeLayerId.FEX,
                    ElysiumRuntimeLayerId.WINE,
                ),
                // ARM32 devices: native only
                // (the legacy 32-bit ARM phones;
                // no Vulkan acceleration).
                ElysiumAbi.ARM32 to setOf(
                    ElysiumRuntimeLayerId.NATIVE,
                ),
                // x86_64 emulators: native + Box64
                // (which is a no-op on x86_64 hosts)
                // + FEX (also a no-op) + Wine.
                ElysiumAbi.X86_64 to setOf(
                    ElysiumRuntimeLayerId.NATIVE,
                    ElysiumRuntimeLayerId.BOX64,
                    ElysiumRuntimeLayerId.FEX,
                    ElysiumRuntimeLayerId.WINE,
                ),
                // x86 (32-bit) emulators: native
                // only.
                ElysiumAbi.X86 to setOf(
                    ElysiumRuntimeLayerId.NATIVE,
                ),
            ),
            gpuVendorLayers = mapOf(
                // Qualcomm Adreno: Mesa Turnip.
                GPUVendor.ADRENO to setOf(
                    ElysiumRuntimeLayerId.MESA_TURNIP,
                ),
                // ARM Mali: a future Panfrost
                // layer (not in the current
                // default catalog; Phase 73
                // future increment).
                GPUVendor.MALI to emptySet(),
                // PowerVR: no Vulkan acceleration
                // in the default catalog.
                GPUVendor.POWER_VR to emptySet(),
                // Intel: no Vulkan acceleration
                // in the default catalog (the
                // user can install Mesa ANV
                // separately).
                GPUVendor.INTEL to emptySet(),
                // AMD: no Vulkan acceleration
                // in the default catalog.
                GPUVendor.AMD to emptySet(),
                // NVIDIA: no Vulkan acceleration
                // in the default catalog.
                GPUVendor.NVIDIA to emptySet(),
                // Apple: no Vulkan acceleration
                // in the default catalog.
                GPUVendor.APPLE to emptySet(),
                // Unknown: no Vulkan acceleration.
                GPUVendor.UNKNOWN to emptySet(),
            ),
        )
    }
}
