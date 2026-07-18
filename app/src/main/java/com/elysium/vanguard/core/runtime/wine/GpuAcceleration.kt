package com.elysium.vanguard.core.runtime.wine

/**
 * Phase 55 — the GPU acceleration
 * configuration for Wine + Box64 sessions.
 *
 * Direct3D-based Windows apps (and games)
 * need a Vulkan translation layer to run
 * with hardware acceleration on an
 * Android device:
 *
 *   - **DXVK** translates Direct3D 9,
 *     Direct3D 10, and Direct3D 11 to
 *     Vulkan. A Snapdragon Adreno GPU
 *     paired with Mesa Turnip (the open-
 *     source Vulkan driver) is the
 *     common DXVK target on Android.
 *   - **VKD3D-Proton** translates
 *     Direct3D 12 to Vulkan. D3D 12 is
 *     the modern API; modern Windows games
 *     use it.
 *
 * The runtime ships both as optional
 * configurations on the
 * [com.elysium.vanguard.core.runtime.wine.WineSessionSpec].
 * When enabled, the [WineSessionRunner]
 * sets the right environment variables
 * (`DXVK_*` for DXVK, `VKD3D_*` for
 * VKD3D-Proton) on the spawned process.
 *
 * ## When to enable
 *
 * DXVK / VKD3D-Proton are NOT free. The
 * translation layer adds overhead even
 * when the app is not using Direct3D.
 * The default is OFF. The user opts in
 * for an app that needs it (typically
 * a Direct3D-based game).
 */
data class GpuAccelerationConfig(
    /** The DXVK configuration. `null`
     *  means DXVK is disabled. */
    val dxvk: DxvkConfig? = null,
    /** The VKD3D-Proton configuration.
     *  `null` means VKD3D-Proton is
     *  disabled. */
    val vkd3dProton: Vk3dProtonConfig? = null
) {
    /**
     * True iff at least one GPU translation
     * layer is enabled. The runner skips
     * the DXVK / VKD3D env-var setting when
     * both are disabled.
     */
    val isEnabled: Boolean
        get() = dxvk != null || vkd3dProton != null

    /**
     * Emit the environment variables the
     * [WineSessionRunner] sets on the
     * spawned process. The map is merged
     * with the manifest's existing
     * environment variables; DXVK /
     * VKD3D-Proton env vars take precedence.
     */
    fun toEnvironment(): Map<String, String> {
        val env = HashMap<String, String>()
        dxvk?.let { env.putAll(it.toEnvironment()) }
        vkd3dProton?.let { env.putAll(it.toEnvironment()) }
        return env
    }
}

/**
 * Phase 55 — the DXVK (Direct3D 9/10/11 →
 * Vulkan) configuration.
 *
 * DXVK is enabled by setting
 * `DXVK_ENABLE=1` on the spawned process.
 * DXVK reads its config from
 * `<prefix>/drive_c/users/<name>/AppData/Roaming/dxvk.conf`
 * (created by DXVK on first run).
 *
 * The [hudEnabled] flag enables the DXVK
 * HUD (heads-up display) — the on-screen
 * FPS / GPU / frame-time overlay. The
 * [asyncShader] flag enables async
 * pipeline compilation (less stutter at
 * the cost of a slightly longer initial
 * frame).
 */
data class DxvkConfig(
    val hudEnabled: Boolean = false,
    val asyncShader: Boolean = false,
    /** Custom `DXVK_*` env vars the user
     *  wants to set. The runner merges
     *  these with the runner's defaults;
     *  the user's vars win. */
    val customEnvironment: Map<String, String> = emptyMap()
) {
    init {
        // Defensive: the user-supplied
        // custom env is treated as a record;
        // we don't restrict the keys.
    }

    /**
     * Emit the DXVK environment variables.
     * The runner sets these on the spawned
     * process.
     */
    fun toEnvironment(): Map<String, String> {
        val env = HashMap<String, String>()
        env["DXVK_ENABLE"] = "1"
        env["DXVK_HUD"] = if (hudEnabled) "1" else "0"
        env["DXVK_ASYNC"] = if (asyncShader) "1" else "0"
        env.putAll(customEnvironment)
        return env
    }
}

/**
 * Phase 55 — the VKD3D-Proton (Direct3D 12 →
 * Vulkan) configuration.
 *
 * VKD3D-Proton is enabled by setting
 * `VKD3D_CONFIG=...` on the spawned
 * process. The config string is a simple
 * key=value list (e.g. `dxr,dxr11`).
 *
 * The [debugLayer] flag enables the
 * VKD3D-Proton debug layer (validation
 * layers; slower but useful for
 * development).
 */
data class Vk3dProtonConfig(
    /** Enable the VKD3D-Proton debug
     *  validation layer. Slower; useful
     *  during development. */
    val debugLayer: Boolean = false,
    /** Custom `VKD3D_*` env vars the user
     *  wants to set. The runner merges
     *  these with the runner's defaults;
     *  the user's vars win. */
    val customEnvironment: Map<String, String> = emptyMap()
) {
    /**
     * Emit the VKD3D-Proton environment
     * variables. The runner sets these on
     * the spawned process.
     */
    fun toEnvironment(): Map<String, String> {
        val env = HashMap<String, String>()
        // VKD3D-Proton is enabled by setting
        // VKD3D_CONFIG to a non-empty string.
        // We default to the simple "no
        // special config" form; the user
        // can override via customEnvironment.
        env["VKD3D_CONFIG"] = if (debugLayer) "debug" else ""
        env.putAll(customEnvironment)
        return env
    }
}
