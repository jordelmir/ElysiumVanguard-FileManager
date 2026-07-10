package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 9.6.3 — Self-description of what a given [DistroLauncher] can do.
 *
 * The UI uses this to decide which "binding" actions to expose (e.g.
 * "Mount /sdcard into this distro" needs [supportsBindMounts]) and to
 * display a one-line "what's running here" badge on the terminal screen.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
data class LauncherCapabilities(
    /**
     * Whether ELF binaries shipped inside the rootfs can actually be
     * executed. `false` for the jailed shell (only `/system/bin/sh`
     * runs, with the rootfs as cwd).
     */
    val canRunElfBinaries: Boolean,

    /**
     * Whether the launched shell is interactive (PTY-friendly). Phase
     * 9.6.3 ships `false` everywhere because Android's ProcessBuilder
     * gives us a pipe, not a PTY; 9.6.3.1 adds PTY via Termux
     * `termux-pty`.
     */
    val exposesPty: Boolean,

    /**
     * Whether bind mounts (/sdcard, /elysium/vault, …) are visible
     * inside the distro. Only true for the proot launcher.
     */
    val supportsBindMounts: Boolean,

    /**
     * True if the launcher requires `adb root` or a custom kernel.
     * Currently nobody on Android; document for future expansion.
     */
    val requiresRoot: Boolean,

    /**
     * ABIs this launcher is compiled for. Empty means "all Android ABIs"
     * (the JVM launcher); a non-empty set lists native ABIs.
     */
    val abiSupport: Set<String>
) {
    companion object {
        /**
         * Conservative baseline used by [JailedDistroLauncher]: works
         * everywhere, exposes nothing fancy.
         */
        val JAILED_BASELINE = LauncherCapabilities(
            canRunElfBinaries = false,
            exposesPty = false,
            supportsBindMounts = false,
            requiresRoot = false,
            abiSupport = emptySet()
        )

        /**
         * Target capability of the proot launcher once it ships.
         */
        val PROOT_TARGET = LauncherCapabilities(
            canRunElfBinaries = true,
            exposesPty = false,
            supportsBindMounts = true,
            requiresRoot = false,
            abiSupport = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        )
    }
}
