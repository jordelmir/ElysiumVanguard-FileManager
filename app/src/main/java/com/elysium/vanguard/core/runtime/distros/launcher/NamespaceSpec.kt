package com.elysium.vanguard.core.runtime.distros.launcher

/**
 * PHASE 102 — typed wrapper for the **Linux namespace flags** that
 * the [NamespacedDistroLauncher] hands to `unshare(1)`.
 *
 * The platform supports seven namespace types on a typical rooted
 * Android device:
 *
 *  - `CLONE_NEWNS`   (mount)   → `--mount`
 *  - `CLONE_NEWPID`  (pid)     → `--pid`
 *  - `CLONE_NEWNET`  (network) → `--network`
 *  - `CLONE_NEWIPC`  (ipc)     → `--ipc`
 *  - `CLONE_NEWUTS`  (uts)     → `--uts`
 *  - `CLONE_NEWCGROUP` (cgroup) → `--cgroup`
 *  - `CLONE_NEWUSER` (user)    → `--user`
 *
 * The first six are always set by the unshare builder (we want a
 * full container-like sandbox). The user namespace is opt-in
 * because on most Android kernels `unprivileged_userns_clone` is
 * disabled, and forcing `--user` would fail on un-patched devices
 * even with `su` available. Users who want nested user-namespace
 * isolation toggle it explicitly via [RootedModeSettingsScreen].
 *
 * **Why a typed wrapper (not raw booleans)**: Kotlin can't bind
 * `Triple<Boolean, Boolean, ...>` into Hilt, and a god-class
 * `RootedModeConfig(allTheBooleans)` is hard to evolve. A small
 * data class is the cleanest seam between "what the UI offers"
 * and "what the builder consumes".
 *
 * **JVM testability**: the spec is a pure data class. The
 * builder is a pure function. The probe is a side-effecting
 * Android call wrapped in a narrow interface. Three layers,
 * each independently testable.
 */
data class NamespaceSpec(
    /**
     * Mount namespace (`CLONE_NEWNS`). Always `true` in the
     * rooted launcher; exposed for completeness so a future
     * "shared mount" mode can opt out.
     */
    val mount: Boolean = true,

    /**
     * PID namespace (`CLONE_NEWPID`). Always `true`; the
     * process sees itself as PID 1 inside the new namespace,
     * which is the canonical container behavior.
     */
    val pid: Boolean = true,

    /**
     * Network namespace (`CLONE_NEWNET`). Always `true`;
     * the distro gets a fresh network stack. The
     * [com.elysium.vanguard.core.runtime.network.policy.NetworkBroker]
     * plumbs a veth pair when it wants connectivity.
     */
    val network: Boolean = true,

    /**
     * IPC namespace (`CLONE_NEWIPC`). Always `true`; the
     * distro cannot see SysV IPC / POSIX MQ from the host.
     */
    val ipc: Boolean = true,

    /**
     * UTS namespace (`CLONE_NEWUTS`). Always `true`; the
     * distro can set its own hostname without polluting
     * the Android device.
     */
    val uts: Boolean = true,

    /**
     * Cgroup namespace (`CLONE_NEWCGROUP`). Always `true`;
     * the distro sees its own cgroup view (cgroup v2).
     */
    val cgroup: Boolean = true,

    /**
     * User namespace (`CLONE_NEWUSER`). Opt-in. Requires
     * `kernel.unprivileged_userns_clone = 1` (or a
     * Magisk-patched kernel that allows it under su).
     * Most stock Android kernels have this off, so the
     * default is `false` to keep the default launcher
     * viable on every rooted device.
     */
    val user: Boolean = false,

    /**
     * Make mount propagation `private` inside the new
     * namespace. Always `true`; the alternative (`shared`
     * or `slave`) leaks mount events from the host into
     * the guest, which is the opposite of what we want.
     */
    val privatePropagation: Boolean = true,
) {
    init {
        // The "always true" set is invariant. Allowing the UI to
        // pass `false` would silently weaken isolation; we
        // surface the bug at construction time.
        require(mount) { "mount namespace cannot be disabled in rooted mode" }
        require(pid) { "pid namespace cannot be disabled in rooted mode" }
        require(network) { "network namespace cannot be disabled in rooted mode" }
        require(ipc) { "ipc namespace cannot be disabled in rooted mode" }
        require(uts) { "uts namespace cannot be disabled in rooted mode" }
        require(cgroup) { "cgroup namespace cannot be disabled in rooted mode" }
        require(privatePropagation) { "private propagation cannot be disabled in rooted mode" }
    }

    /**
     * The canonical "full sandbox" spec used when the user
     * toggles Rooted Mode ON. Every namespace is on except
     * `user` (which depends on kernel support).
     */
    companion object {
        val FULL_SANDBOX = NamespaceSpec(user = false)
    }
}
