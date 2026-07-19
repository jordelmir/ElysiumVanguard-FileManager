package com.elysium.vanguard.core.orchestrator

import com.elysium.vanguard.core.graphics.GPUVendor
import com.elysium.vanguard.core.linux.ElysiumAbi
import com.elysium.vanguard.core.linux.ElysiumAbiCapabilityMatrix
import com.elysium.vanguard.core.linux.ElysiumRuntimeCapability
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayer
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerCatalog
import com.elysium.vanguard.core.linux.ElysiumRuntimeLayerId
import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.Runtime

/**
 * Phase 76 (Universal Execution Engine, section 6
 * of the Elysium Vanguard vision) — the
 * **Runtime Selector**, the typed component
 * that picks the optimal runtime layer for a
 * capsule + a device profile.
 *
 * Per the vision doc, the universal execution
 * engine is:
 *
 *   User Action
 *     ↓
 *   File Type / Manifest Detection
 *     ↓
 *   Compatibility Resolver
 *     ↓
 *   Architecture Detection
 *     ↓
 *   Runtime Selection
 *     ├── Android Runtime
 *     ├── Native ARM64 ELF
 *     ├── Linux PRoot/chroot
 *     ├── Wine + Box64/FEX
 *     ├── QEMU VM
 *     └── Remote Execution
 *     ↓
 *   Sandbox and Mount Policy
 *     ↓
 *   Process Supervisor
 *
 * The [RuntimeSelector] is the **Runtime
 * Selection** step. The selector takes a
 * [Capsule] (the runtime contract) + a
 * [DeviceProfile] (the device's capabilities) +
 * returns a typed [RuntimeSelection] with the
 * recommended translation layer.
 *
 * The selector is **declarative** (not
 * procedural). The selector computes the
 * selection from:
 *   - The capsule's `runtime` (LINUX / WINDOWS /
 *     MACOS / WEB).
 *   - The capsule's `architecture` (ARM64 /
 *     ARM32 / X86_64 / X86 / ANY).
 *   - The capsule's `gpu` (api + driver).
 *   - The device's `abi` + `gpuVendor` +
 *     `hasRoot` + `availableMemoryMb`.
 *   - The runtime layer catalog (per Phase 73
 *     third half I-73.3.1).
 *   - The ABI capability matrix (per Phase 73
 *     third half I-73.3.3).
 *
 * The selector is **pure-domain** (no I/O, no
 * Android dependencies). The selector is the
 * **typed heart** of the universal execution
 * engine; the orchestrator (Phase 67 +
 * CriticalE2EOrchestrator Phase 70) consumes
 * the selector's output to dispatch by
 * `RuntimeKind`.
 */
class RuntimeSelector(
    private val capabilityMatrix: ElysiumAbiCapabilityMatrix,
    private val runtimeLayerCatalog: ElysiumRuntimeLayerCatalog,
) {

    /**
     * Select the optimal runtime for a capsule
     * + a device profile. The function is
     * **total**: every well-formed input
     * produces exactly one [RuntimeSelection].
     */
    fun select(
        capsule: Capsule,
        device: DeviceProfile,
    ): RuntimeSelection {
        // Step 1: map the capsule's architecture
        // to ElysiumAbi.
        val targetAbi = mapArchitecture(capsule.architecture)
        if (targetAbi == null) {
            return RuntimeSelection.Unsupported(
                reason = "unsupported architecture: ${capsule.architecture}",
            )
        }
        // Step 2: check the device's ABI
        // compatibility. The device's ABI must
        // support the capsule's target ABI
        // (either directly or via translation).
        if (targetAbi != device.abi) {
            // The device's ABI differs from the
            // capsule's target. The selector
            // must find a translation layer
            // (Box64 / FEX / Wine) that supports
            // the target ABI on the device's ABI.
            return selectTranslation(
                capsule = capsule,
                device = device,
                targetAbi = targetAbi,
            )
        }
        // Step 3: same ABI. The selector must
        // find a native layer (or fail with
        // Unsupported).
        return selectNative(capsule, device)
    }

    /**
     * Select a native runtime for a capsule
     * whose target ABI matches the device's
     * ABI. The function is private (the
     * `select` method is the public API).
     */
    private fun selectNative(
        capsule: Capsule,
        device: DeviceProfile,
    ): RuntimeSelection {
        // Compute the available capabilities
        // from the catalog (the catalog's
        // installed layers, filtered by the
        // device's ABI + GPU support via the
        // matrix).
        val availableCapabilities = availableCapabilitiesFor(device)
        val required = requiredCapabilities(capsule)
        val missing = required - availableCapabilities
        if (missing.isNotEmpty()) {
            return RuntimeSelection.Unsupported(
                reason = "missing capabilities for ${device.abi} " +
                    "+ ${device.gpuVendor}: $missing",
            )
        }
        // The native layer is the optimal
        // runtime. The selector returns the
        // native layer as the recommended
        // translation (no translation needed).
        return RuntimeSelection.Native(
            layer = latestLayer(ElysiumRuntimeLayerId.NATIVE)
                ?: ElysiumRuntimeLayer.Native(
                    version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(1, 0, 0),
                ),
        )
    }

    /**
     * Compute the available capabilities for
     * a device. The function is private (the
     * `select` method is the public API).
     *
     * The function:
     *   1. Gets the list of installed layers
     *      from the catalog.
     *   2. Filters the layers by the device's
     *      ABI + GPU support (via the matrix).
     *   3. Returns the union of the supported
     *      layers' capabilities.
     */
    private fun availableCapabilitiesFor(
        device: DeviceProfile,
    ): Set<ElysiumRuntimeCapability> {
        val installedLayerIds = capabilityMatrix.layersFor(
            abi = device.abi,
            gpuVendor = device.gpuVendor,
        )
        val capabilities = mutableSetOf<ElysiumRuntimeCapability>()
        for (layerId in installedLayerIds) {
            val manifest = runtimeLayerCatalog.latestForAbi(layerId, device.abi)
                ?: continue
            // The layer is in the catalog + the
            // matrix says it's available. Add
            // its capabilities to the union.
            capabilities.addAll(manifest.capabilities)
        }
        return capabilities
    }

    /**
     * Select a translation runtime for a
     * capsule whose target ABI differs from
     * the device's ABI. The function is
     * private (the `select` method is the
     * public API).
     */
    private fun selectTranslation(
        capsule: Capsule,
        device: DeviceProfile,
        targetAbi: ElysiumAbi,
    ): RuntimeSelection {
        // The translation strategy depends on
        // the target ABI + the capsule's
        // runtime (LINUX / WINDOWS).
        when (capsule.runtime) {
            Runtime.LINUX -> {
                when (targetAbi) {
                    ElysiumAbi.X86_64 -> {
                        // x86_64 Linux on the
                        // device's ABI: Box64
                        // (user-mode x86_64
                        // translation).
                        return RuntimeSelection.Translated(
                            layer = latestLayer(ElysiumRuntimeLayerId.BOX64)
                                ?: defaultBox64Layer(),
                            translation = TranslationType.BOX64,
                            targetAbi = targetAbi,
                        )
                    }
                    ElysiumAbi.X86 -> {
                        // x86 (32-bit) Linux on
                        // the device's ABI:
                        // FEX (user-mode x86
                        // translation).
                        return RuntimeSelection.Translated(
                            layer = latestLayer(ElysiumRuntimeLayerId.FEX)
                                ?: defaultFexLayer(),
                            translation = TranslationType.FEX,
                            targetAbi = targetAbi,
                        )
                    }
                    ElysiumAbi.ARM32 -> {
                        // ARM32 Linux on the
                        // device's ABI (e.g.
                        // ARM64): no native
                        // translation layer in
                        // the default catalog.
                        return RuntimeSelection.Unsupported(
                            reason = "no ARM32-to-ARM64 translation " +
                                "layer in the default catalog",
                        )
                    }
                    else -> return RuntimeSelection.Unsupported(
                        reason = "no translation for $targetAbi " +
                            "Linux on ${device.abi}",
                    )
                }
            }
            Runtime.WINDOWS -> {
                // Windows on the device's ABI:
                // Wine (the only supported
                // runtime for Windows binaries).
                val wineForDevice = runtimeLayerCatalog.latestForAbi(
                    ElysiumRuntimeLayerId.WINE,
                    device.abi,
                )
                if (wineForDevice == null) {
                    return RuntimeSelection.Unsupported(
                        reason = "Wine is not available on ${device.abi} " +
                            "+ ${device.gpuVendor}",
                    )
                }
                return RuntimeSelection.Translated(
                    layer = runtimeLayerCatalog.asLayer(
                        ElysiumRuntimeLayerId.WINE,
                        wineForDevice.version,
                    ) ?: defaultWineLayer(),
                    translation = TranslationType.WINE,
                    targetAbi = targetAbi,
                )
            }
            else -> return RuntimeSelection.Unsupported(
                reason = "translation not supported for runtime " +
                    "${capsule.runtime}",
            )
        }
    }

    /**
     * Get the latest installed version of a
     * layer from the catalog. Returns `null`
     * when the layer is not in the catalog.
     */
    private fun latestLayer(
        layerId: ElysiumRuntimeLayerId,
    ): ElysiumRuntimeLayer? {
        val latest = runtimeLayerCatalog.latest(layerId) ?: return null
        return runtimeLayerCatalog.asLayer(layerId, latest.version)
    }

    /** Default Box64 layer (when the catalog
     *  doesn't have a Box64 manifest). */
    private fun defaultBox64Layer(): ElysiumRuntimeLayer.Box64 =
        ElysiumRuntimeLayer.Box64(
            version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(0, 3, 2),
        )

    /** Default FEX layer (when the catalog
     *  doesn't have a FEX manifest). */
    private fun defaultFexLayer(): ElysiumRuntimeLayer.Fex =
        ElysiumRuntimeLayer.Fex(
            version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(2404, 0, 0),
        )

    /** Default Wine layer (when the catalog
     *  doesn't have a Wine manifest). */
    private fun defaultWineLayer(): ElysiumRuntimeLayer.Wine =
        ElysiumRuntimeLayer.Wine(
            version = com.elysium.vanguard.core.linux.ElysiumPackageVersion(9, 0, 0),
        )

    /**
     * Compute the required capabilities for a
     * capsule. The capabilities are derived
     * from the capsule's runtime + GPU config.
     */
    private fun requiredCapabilities(capsule: Capsule): Set<ElysiumRuntimeCapability> {
        val required = mutableSetOf<ElysiumRuntimeCapability>()
        // EXECUTE_NATIVE is always required.
        required.add(ElysiumRuntimeCapability.EXECUTE_NATIVE)
        // The runtime determines the translation
        // capability.
        when (capsule.runtime) {
            Runtime.LINUX -> Unit // No additional capability
            Runtime.WINDOWS -> required.add(ElysiumRuntimeCapability.EXECUTE_WINDOWS)
            Runtime.MACOS -> Unit // Future
            Runtime.WEB -> Unit // Future
        }
        // The GPU API determines the GPU
        // capability.
        when (capsule.gpu.api) {
            com.elysium.vanguard.core.runtime.capsule.GpuApi.NONE -> Unit
            com.elysium.vanguard.core.runtime.capsule.GpuApi.VULKAN -> required.add(
                ElysiumRuntimeCapability.GPU_VULKAN,
            )
            com.elysium.vanguard.core.runtime.capsule.GpuApi.OPENGL_ES -> required.add(
                ElysiumRuntimeCapability.GPU_OPENGL_ES,
            )
            com.elysium.vanguard.core.runtime.capsule.GpuApi.OPENCL -> Unit // Future
        }
        return required
    }

    /**
     * Map the capsule's [Architecture] to the
     * corresponding [ElysiumAbi]. Returns
     * `null` for `ANY` (the consumer is
     * expected to pick an actual ABI based on
     * the device's capabilities).
     */
    private fun mapArchitecture(arch: Architecture): ElysiumAbi? = when (arch) {
        Architecture.ARM64 -> ElysiumAbi.ARM64
        Architecture.ARM32 -> ElysiumAbi.ARM32
        Architecture.X86_64 -> ElysiumAbi.X86_64
        Architecture.X86 -> ElysiumAbi.X86
        Architecture.ANY -> null
    }
}

/**
 * The device profile. The profile is the
 * **device-side** input to the runtime
 * selector; the capsule is the **capsule-side**
 * input.
 *
 * The profile has:
 *   - `abi` — the device's CPU architecture.
 *   - `gpuVendor` — the device's GPU vendor
 *     (null = unknown / no GPU).
 *   - `hasRoot` — whether the device has root
 *     access (matters for chroot vs proot).
 *   - `availableMemoryMb` — the available
 *     memory in MB (matters for resource-heavy
 *     runtimes like QEMU).
 */
data class DeviceProfile(
    val abi: ElysiumAbi,
    val gpuVendor: GPUVendor? = null,
    val hasRoot: Boolean = false,
    val availableMemoryMb: Long = 0L,
) {
    init {
        require(availableMemoryMb >= 0) {
            "DeviceProfile.availableMemoryMb must be >= 0, " +
                "got $availableMemoryMb"
        }
    }
}

/**
 * The typed result of a runtime selection.
 * The result is a sealed class with 3 cases
 * that capture the 3 distinct outcomes the
 * selector can produce.
 */
sealed class RuntimeSelection {

    /**
     * The capsule can run **natively** on the
     * device. No translation is needed.
     */
    data class Native(
        val layer: ElysiumRuntimeLayer,
    ) : RuntimeSelection()

    /**
     * The capsule needs a **translation
     * layer** to run on the device. The
     * translation is one of the supported
     * translation types (Box64, FEX, Wine, etc.).
     */
    data class Translated(
        val layer: ElysiumRuntimeLayer,
        val translation: TranslationType,
        val targetAbi: ElysiumAbi,
    ) : RuntimeSelection()

    /**
     * The capsule **cannot run** on the device.
     * The reason is a human-readable
     * description of why (unsupported
     * architecture, missing capability, etc.).
     */
    data class Unsupported(
        val reason: String,
    ) : RuntimeSelection() {
        init {
            require(reason.isNotBlank()) {
                "RuntimeSelection.Unsupported.reason must not be blank"
            }
        }
    }
}

/**
 * The translation type. The translation is the
 * **typed strategy** the orchestrator uses to
 * run a non-native capsule on the device.
 *
 * The translation type is distinct from the
 * runtime layer id (a translation type can be
 * supported by multiple layers; e.g. Box64 is
 * a translation type, and the Box64 layer is
 * the implementation).
 */
enum class TranslationType {
    /** x86_64 user-mode translation via Box64. */
    BOX64,

    /** x86 user-mode translation via FEX-Emu. */
    FEX,

    /** Windows API re-implementation via Wine. */
    WINE,

    /** PRoot-based filesystem root (no kernel
     *  isolation; user-mode syscalls). */
    PROOT,

    /** chroot-based filesystem root (kernel
     *  isolation; requires root). */
    CHROOT,

    /** Full system emulation via QEMU (slowest;
     *  fallback for incompatible binaries). */
    QEMU,

    /** Remote execution on the Oracle Free
     *  build server (for heavy / incompatible
     *  workloads). */
    REMOTE,
}
