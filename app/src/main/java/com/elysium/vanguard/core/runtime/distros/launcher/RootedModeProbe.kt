package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 102 — narrow interface that detects whether the current
 * Android device is **rooted in a way that's usable for the
 * [NamespacedDistroLauncher]**.
 *
 * "Rooted" is a fuzzy term on Android. The launcher needs three
 * concrete capabilities:
 *
 *   1. **`su` binary callable** — we shell out to `su -c '<rest>'`
 *      to drop into root. Without `su` we cannot chroot / unshare.
 *   2. **Working `chroot(1)`** — the su shell must allow chroot
 *      (Magisk and KernelSU both allow it by default; some
 *      Magisk modules don't).
 *   3. **Working `unshare(1)`** — Android's toybox `unshare`
 *      supports `mount/pid/network/ipc/uts/cgroup`; user-ns
 *      depends on the kernel.
 *
 * The probe returns a [RootStatus] that captures all three so
 * the [RootedModeSettingsScreen] can show a precise "what's
 * missing" message instead of a binary "rooted: yes/no".
 *
 * **JVM testability**: production uses [AndroidRootedModeProbe]
 * (which actually calls `Runtime.exec("su -c id")`). Tests
 * supply a 5-line in-memory impl. The interface is the seam.
 */
interface RootedModeProbe {

    /**
     * Inspect the device's root status. The result is cached
     * for [cacheTtlMs] (the production impl caches for 5s) so
     * a screen that re-renders doesn't re-spawn `su`.
     */
    fun probe(): RootStatus
}

/**
 * The full root status snapshot.
 */
data class RootStatus(
    /**
     * True iff the device has a callable `su` binary that
     * returns 0 on `su -c id`. This is the gate condition
     * for the [NamespacedDistroLauncher].
     */
    val isRooted: Boolean,

    /**
     * The root provider that granted the su session.
     * "none" when [isRooted] is false.
     */
    val provider: RootProvider,

    /**
     * True iff `unshare(1)` is on `$PATH` inside the su
     * shell. False means we can use chroot but not
     * namespace isolation.
     */
    val unshareAvailable: Boolean,

    /**
     * True iff `cgexec(1)` is on `$PATH`. False means
     * the [CgroupSpec] will be silently dropped (the
     * builder checks this via [CgroupSpec.isEmpty] +
     * the probe's `cgexecAvailable`).
     */
    val cgexecAvailable: Boolean,

    /**
     * The kernel's `kernel.unprivileged_userns_clone`
     * sysctl value (0 or 1). Only meaningful on kernels
     * that expose the sysctl; null on kernels that
     * don't. Drives the [NamespaceSpec.user] default.
     */
    val unprivilegedUserNsClone: Boolean?,

    /**
     * The cgroup hierarchy version detected on the device
     * (1, 2, or null if undetectable). 2 = the [CgroupSpec]
     * v2 controllers (cpu/memory/io/pids) will be honored.
     * 1 = the v2 controllers are NOT honored; the launcher
     * refuses to launch when a cgroup spec is non-empty.
     * null = the launcher attempts the cgroup layer anyway
     * (the user is opting in to "best effort").
     */
    val cgroupVersion: Int?,

    /**
     * Free-form diagnostics string. Surfaced verbatim in
     * [RootedModeSettingsScreen]'s "details" expandable.
     */
    val diagnostics: String,
) {
    /**
     * True iff the launcher's base requirements are met:
     * rooted + `unshare(1)` on PATH. The cgroup hierarchy
     * version is a SEPARATE concern checked by the
     * launcher only when a [CgroupSpec] is requested.
     */
    val canLaunchRooted: Boolean
        get() = isRooted && unshareAvailable

    /**
     * True iff the user has requested a [CgroupSpec] AND
     * the device can honor it (cgroup v2 + cgexec binary).
     * Used by the launcher to fail closed when the user
     * asks for limits the device cannot deliver.
     */
    fun canHonorCgroupSpec(spec: CgroupSpec): Boolean {
        if (spec.isEmpty) return true
        return cgroupVersion == 2 && cgexecAvailable
    }
}

/**
 * The root provider detected by [AndroidRootedModeProbe].
 * Ordered from most-preferred (Magisk) to least-preferred
 * (generic `su` binary).
 */
enum class RootProvider {
    /** No root detected. */
    NONE,

    /** Topjohnwu Magisk (`com.topjohnwu.magisk` package). */
    MAGISK,

    /** weishu KernelSU (`me.weishu.kernelsu` package). */
    KERNEL_SU,

    /** APatch (`me.bmax.apatch` package). */
    APATCH,

    /** A stock `su` binary without a known manager. */
    GENERIC_SU,

    /** Root reported by `id` but provider not identified. */
    UNKNOWN;
}
