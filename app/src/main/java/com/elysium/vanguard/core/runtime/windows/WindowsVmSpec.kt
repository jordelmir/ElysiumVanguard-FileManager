package com.elysium.vanguard.core.runtime.windows

/**
 * Phase 22 — what a Windows VM looks like.
 *
 * Master order §20: "Windows guests run under a QEMU/KVM-backed
 * VM". The VM is heavier than a proot Linux guest (the guest
 * kernel is independent) but more isolated. The spec captures
 * what the runtime needs to know to:
 *
 *   - download the boot ISO + virtio driver ISO (Phase 22
 *     ships the typed spec; the downloader is a follow-up
 *     phase),
 *   - allocate memory / disk / CPU,
 *   - boot the guest via QMP (the QEMU machine protocol
 *     over a Unix socket — Phase 23 wires the actual
 *     QEMU process spawn),
 *   - expose the guest to the runtime's network bridge +
 *     USB passthrough.
 *
 * The spec is signed the same way the Linux distros' layer
 * manifests are (Ed25519, Phase 12.4). A future phase adds
 * the signer / verifier wiring; for now the spec carries
 * a placeholder signature that the build host fills in.
 */
data class WindowsVmSpec(
    /**
     * Stable id, e.g. `win10-pro-22h2` or `win11-pro-23h2`.
     * Reverse-DNS is NOT required (Windows product keys are
     * not reverse-DNS); the spec's [signature] is the
     * authority.
     */
    val id: String,
    val displayName: String,
    val family: WindowsVmFamily,
    /**
     * Windows version, e.g. "22H2" (Win10), "23H2" (Win11),
     * "1809" (Server 2019), "21H2" (Server 2022). The
     * runtime uses this to pick a virtio driver version
     * and to surface the version in the catalog UI.
     */
    val version: String,
    /**
     * Phase 75 — the runtime kind the agent's
     * `createWindowsEnvironment` parses from the user's
     * goal text. The values mirror the master vision
     * section 3 ("Wine + Box64", "Wine + FEX", "QEMU VM").
     *
     * - `QEMU_VM` (the default for all current
     *   `WindowsVmSpec`s) — the runtime launches a
     *   QEMU-backed Windows VM. The agent stages the
     *   binary to the VM's directory and starts the
     *   VM; the user installs + runs the binary
     *   manually inside the guest.
     * - `WINE_BOX64` / `WINE_FEX` — not currently
     *   supported by any spec; the catalog's
     *   `findByRuntimeKind` returns null and the
     *   manager returns a typed "Wine runtime not
     *   supported" failure. A future phase adds
     *   Wine-aware specs (these are Linux guests with
     *   Wine, not real Windows VMs).
     */
    val runtimeKind: String = "QEMU_VM",
    /** Minimum RAM in MiB; the runtime refuses to start the
     *  VM below this. */
    val minRamMb: Int,
    /** Minimum disk in GiB; the runtime refuses to start the
     *  VM below this. */
    val minDiskGb: Int,
    val minCpuCores: Int,
    /** Recommended RAM / disk / CPU. The runtime surfaces
     *  these in the catalog UI as the default allocation. */
    val recommendedRamMb: Int,
    val recommendedDiskGb: Int,
    val recommendedCpuCores: Int,
    /** URL to the official Windows install ISO. The runtime
     *  downloads (or references a pre-downloaded) ISO from
     *  this URL. */
    val bootIsoUrl: String,
    /** URL to the virtio driver ISO. Required because the
     *  default Windows install ISO has no virtio drivers;
     *  the runtime injects them at install time. */
    val virtioIsoUrl: String,
    /** Optional pre-installed disk image URL. When present,
     *  the runtime can skip the Windows install step and
     *  boot directly. */
    val diskImageUrl: String? = null,
    /** True for x86_64 guests; the runtime needs KVM
     *  acceleration. */
    val requiresKvm: Boolean = true,
    /**
     * True for Windows 11 and Server 2022+ which require a
     * virtual TPM. The runtime's QEMU integration includes
     * `swtpm` for these builds.
     */
    val requiresSwtpm: Boolean = false,
    /** Ed25519 signature over the canonical spec bytes.
     *  Phase 23 wires the signer; for now a placeholder
     *  is acceptable for unit tests. */
    val signature: String,
    /** Optional human-readable note for the catalog UI. */
    val notes: String = ""
) {
    init {
        require(id.isNotBlank()) { "spec id must not be blank" }
        require(displayName.isNotBlank()) { "spec displayName must not be blank" }
        require(version.isNotBlank()) { "spec version must not be blank" }
        require(minRamMb > 0) { "minRamMb must be positive" }
        require(minDiskGb > 0) { "minDiskGb must be positive" }
        require(minCpuCores > 0) { "minCpuCores must be positive" }
        require(recommendedRamMb >= minRamMb) {
            "recommendedRamMb must be >= minRamMb"
        }
        require(recommendedDiskGb >= minDiskGb) {
            "recommendedDiskGb must be >= minDiskGb"
        }
        require(recommendedCpuCores >= minCpuCores) {
            "recommendedCpuCores must be >= minCpuCores"
        }
        require(bootIsoUrl.isNotBlank()) { "bootIsoUrl must not be blank" }
        require(virtioIsoUrl.isNotBlank()) { "virtioIsoUrl must not be blank" }
        require(signature.isNotBlank()) { "spec signature must not be blank" }
    }
}

enum class WindowsVmFamily {
    WIN_10,
    WIN_11,
    WIN_SERVER_2019,
    WIN_SERVER_2022
}
