package com.elysium.vanguard.core.graphics

/**
 * The GPU vendor + family detected on the device.
 * The detection is used by `VulkanICDFactory` to
 * select the right ICD (Mesa Turnip for Adreno,
 * Mali driver for ARM, etc.).
 *
 * The detection is **best-effort**: a device that
 * doesn't match a known vendor is reported as
 * `UNKNOWN`. The `UNKNOWN` case falls back to a
 * stub ICD that reports no Vulkan capability.
 *
 * Phase 1 detection: `Build.MANUFACTURER` +
 * `Build.MODEL` + a small heuristic on the GPU
 * model name. Phase 2 detection: a JNI call to
 * `eglGetDisplay` + `eglQueryString` for the
 * renderer string.
 */
enum class GPUVendor {
    /** Qualcomm Adreno (Snapdragon SoCs). Mesa Turnip is the Vulkan driver. */
    ADRENO,

    /** ARM Mali. The ARM-provided Vulkan driver is the default. */
    MALI,

    /** Imagination PowerVR. The IMG-provided Vulkan driver is the default. */
    POWER_VR,

    /** Intel (x86 emulators, Chromebooks). Mesa ANV is the driver. */
    INTEL,

    /** AMD (some emulators, Steam Deck). Mesa RADV is the driver. */
    AMD,

    /** NVIDIA (Tegra, Shield). The NVIDIA-provided Vulkan driver is the default. */
    NVIDIA,

    /** Apple (M-series, when running on macOS via an emulator). */
    APPLE,

    /** Unknown vendor. The ICD is a stub. */
    UNKNOWN;

    companion object {
        /**
         * Best-effort detection from a manufacturer +
         * model string. The detection is intentionally
         * loose: better to over-detect and pick a
         * reasonable ICD than to under-detect and
         * fall back to the stub.
         */
        fun detect(manufacturer: String, model: String): GPUVendor {
            val mfg = manufacturer.lowercase()
            val mdl = model.lowercase()
            return when {
                "qualcomm" in mfg || "adreno" in mdl -> ADRENO
                "mediatek" in mfg && ("mali" in mdl || "dimensity" in mdl) -> MALI
                "samsung" in mfg && "mali" in mdl -> MALI
                "imagination" in mfg || "powervr" in mdl -> POWER_VR
                "intel" in mfg || "atom" in mdl -> INTEL
                "amd" in mfg || "ryzen" in mdl -> AMD
                "nvidia" in mfg || "tegra" in mdl || "shield" in mdl -> NVIDIA
                "apple" in mfg || "mac" in mdl -> APPLE
                else -> UNKNOWN
            }
        }
    }
}
