package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 9.6.3 — Tag that describes which sandbox flavor a launcher uses.
 *
 * Different launchers produce very different process trees, so consumers
 * (the UI, log scrubbers, future exporters) tag log lines with this enum
 * instead of pattern-matching the launcher class name.
 *
 * Phase 9.6.3 — first build; intentionally minimal.
 */
enum class LauncherKind {
    /**
     * Runs the distro as a sub-process of `/system/bin/sh` with the
     * rootfs as cwd. No syscall translation, no ELF execution from
     * inside the distro. Cheap and works everywhere; weak.
     */
    JAILED_SHELL,

    /**
     * Uses Termux's `proot` binary vendored in `jniLibs/<abi>/libproot.so`
     * (or a future equivalent). True chroot-ish behavior, no root needed.
     */
    NATIVE_PROOT,

    /**
     * Future: a `prctl(PR_SET_NO_NEW_PRIVS, 1, …)` based launcher that
     * goes through `unshare` for namespace isolation.
     */
    NAMESPACE_UNSHARE
}
