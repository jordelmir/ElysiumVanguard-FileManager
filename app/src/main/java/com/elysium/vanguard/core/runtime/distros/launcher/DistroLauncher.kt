package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 9.6.3 — Contract for "what kind of execution environment does this
 * distro get?".
 *
 * A launcher knows how to build the OS-level command line that walks the
 * user into a distro. It is the seam between DistroManager (which knows
 * which distro is installed) and TerminalSession (which only sees a list
 * of strings).
 *
 * Why an interface: Phase 9.6.3 ships a JAILED launcher (no proot binary
 * required; runs the Android /system/bin/sh with the rootfs as cwd and
 * exposes a minimal scoped filesystem view) plus a NATIVE_PROOT launcher
 * that is wired but stubbed (binary comes in 9.6.3.1). Future phases can
 * drop in a NATIVE_PRCTL or CHROOT_INTRINSIC launcher by implementing the
 * same interface.
 *
 * Backward compat: Phase 9.6.1 and 9.6.2 paths continue to work because
 * the default `TerminalSession.Config` is still `/system/bin/sh`.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
interface DistroLauncher {

    /** Tag used in logs and UI strings. */
    val kind: LauncherKind

    /**
     * What this launcher can and cannot do, used by the UI when it wants
     * to show "Capabilities: X / Y / Z" badges or auto-detect workarounds
     * (e.g. fall back to bind-mount emulation when real proot isn't
     * available).
     */
    val capabilities: LauncherCapabilities

    /**
     * Build a shell command line for this distro. The returned list is
     * passed straight into [ProcessBuilder]; entries must be absolute
     * paths or resolved by the launcher.
     *
     * @param rootfsDir the on-disk distro directory (`<baseDir>/<id>/rootfs/`)
     * @param script the script or command to run inside the distro. May be
     *   an empty string to mean "drop the user into an interactive shell"
     *   (only meaningful when the launcher supports [LauncherCapabilities.canRunInteractive]).
     */
    fun buildShellCommand(rootfsDir: java.io.File, script: String): List<String>

    /**
     * Variant of [buildShellCommand] for non-interactive, one-shot probes
     * like "what's the kernel?" or "list /etc/os-release". The launcher
     * may pick a different exec strategy here (faster, no tty, etc.).
     */
    fun buildProbeCommand(rootfsDir: java.io.File, args: List<String>): List<String>

    /**
     * Environment required by the host-side launcher process. This is
     * deliberately separate from variables passed inside the guest:
     * PRoot needs its loader/library paths before the guest exists,
     * while direct-exec needs rootfs paths for Android's linker.
     */
    fun environmentVariables(rootfsDir: java.io.File): List<Pair<String, String>> = emptyList()

    /**
     * Cheap check whether this launcher can produce a working process for
     * the given rootfs (e.g. JailedDistroLauncher needs
     * `rootfsDir/etc/os-release`; proot needs a populated ELF tree).
     */
    fun isAvailable(rootfsDir: java.io.File): Boolean
}
