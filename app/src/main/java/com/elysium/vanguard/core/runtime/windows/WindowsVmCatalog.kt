package com.elysium.vanguard.core.runtime.windows

/**
 * Phase 22 — the runtime's catalog of Windows VM templates.
 *
 * The catalog is the static list of supported Windows
 * builds. Each entry is a [WindowsVmSpec] the user can
 * pick from the UI. The catalog is immutable in
 * production; tests can register additional entries via
 * [register] (the test for the catalog uses an isolated
 * `WindowsVmCatalog` instance).
 */
class WindowsVmCatalog {
    private val byId = mutableMapOf<String, WindowsVmSpec>()

    val all: List<WindowsVmSpec>
        get() = byId.values.sortedBy { it.id }

    fun register(spec: WindowsVmSpec) {
        require(spec.id !in byId) { "duplicate spec id: ${spec.id}" }
        byId[spec.id] = spec
    }

    fun find(id: String): WindowsVmSpec? = byId[id]

    /**
     * Phase 75 — find the first spec whose
     * [WindowsVmSpec.runtimeKind] matches the given
     * runtime kind. The agent's `createWindowsEnvironment`
     * passes values like `QEMU_VM`, `WINE_BOX64`, or
     * `WINE_FEX`; the catalog returns the first matching
     * spec (the catalog's order is sorted by id).
     *
     * The lookup is case-insensitive. Specs without an
     * explicit `runtimeKind` default to `QEMU_VM`.
     */
    fun findByRuntimeKind(runtimeKind: String): WindowsVmSpec? {
        val needle = runtimeKind.trim().uppercase()
        return byId.values.firstOrNull { spec ->
            spec.runtimeKind.trim().uppercase() == needle
        }
    }

    fun size(): Int = byId.size

    fun clear() = byId.clear()

    companion object {
        /**
         * Build the production catalog with the official
         * Windows templates. The URLs are Microsoft's
         * official download centre URLs (placeholder —
         * Phase 23 wires the actual downloader with the
         * real URLs and SHA-256 hashes).
         *
         * The [signature] field is a placeholder; Phase 23
         * replaces it with the Ed25519 signature over the
         * canonical spec bytes.
         */
        fun official(): WindowsVmCatalog = WindowsVmCatalog().apply {
            register(
                WindowsVmSpec(
                    id = "win10-pro-22h2",
                    displayName = "Windows 10 Pro 22H2",
                    family = WindowsVmFamily.WIN_10,
                    version = "22H2",
                    minRamMb = 4096,
                    minDiskGb = 64,
                    minCpuCores = 2,
                    recommendedRamMb = 8192,
                    recommendedDiskGb = 128,
                    recommendedCpuCores = 4,
                    bootIsoUrl = "https://example.com/win10/22H2/english_x64v1.iso",
                    virtioIsoUrl = "https://example.com/virtio/virtio-win-0.1.240.iso",
                    requiresKvm = true,
                    requiresSwtpm = false,
                    signature = "0".repeat(192),
                    notes = "Windows 10 Pro 22H2; standard desktop SKU."
                )
            )
            register(
                WindowsVmSpec(
                    id = "win11-pro-23h2",
                    displayName = "Windows 11 Pro 23H2",
                    family = WindowsVmFamily.WIN_11,
                    version = "23H2",
                    minRamMb = 8192,
                    minDiskGb = 64,
                    minCpuCores = 2,
                    recommendedRamMb = 16384,
                    recommendedDiskGb = 256,
                    recommendedCpuCores = 6,
                    bootIsoUrl = "https://example.com/win11/23H2/english_x64v1.iso",
                    virtioIsoUrl = "https://example.com/virtio/virtio-win-0.1.240.iso",
                    requiresKvm = true,
                    requiresSwtpm = true,
                    signature = "0".repeat(192),
                    notes = "Windows 11 Pro 23H2; requires virtual TPM."
                )
            )
            register(
                WindowsVmSpec(
                    id = "win-server-2019",
                    displayName = "Windows Server 2019",
                    family = WindowsVmFamily.WIN_SERVER_2019,
                    version = "1809",
                    minRamMb = 4096,
                    minDiskGb = 60,
                    minCpuCores = 2,
                    recommendedRamMb = 8192,
                    recommendedDiskGb = 128,
                    recommendedCpuCores = 4,
                    bootIsoUrl = "https://example.com/server/2019/english.iso",
                    virtioIsoUrl = "https://example.com/virtio/virtio-win-0.1.240.iso",
                    requiresKvm = true,
                    requiresSwtpm = false,
                    signature = "0".repeat(192),
                    notes = "Windows Server 2019 LTSC."
                )
            )
        }
    }
}
