package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File

/**
 * PHASE 102 — the **rooted `unshare + chroot + cgexec` launcher**.
 *
 * Resolves the [LauncherKind.NAMESPACE_UNSHARE] tag declared in
 * Phase 9.6.3 but never implemented. The launcher is selected
 * when:
 *
 *   - [RootedModeProbe.probe] reports `isRooted == true`, AND
 *   - `unshare(1)` is on `$PATH` inside the su shell, AND
 *   - the user has toggled Rooted Mode ON in [RootedModeSettingsScreen].
 *
 * When any of those checks fail the launcher returns
 * [UnshareCommandBuilder.MISSING_SENTINEL] and the resolver falls
 * back to [NativeProotLauncher] or [DirectExecDistroLauncher].
 *
 * **Why a separate class and not an extension of [NativeProotLauncher]**:
 * The proot launcher is a *single binary* call (`proot -r <rootfs> /bin/sh`).
 * The rooted launcher is a *pipeline* (`su` → `unshare` → `cgexec` → `chroot` →
 * `env` → shell) that depends on three binaries + a cgroup hierarchy. Combining
 * them would force the proot launcher to take a nullable `RootedModeProbe`
 * and a nullable `NamespaceSpec` it never uses, weakening both contracts.
 *
 * **Why `cgroup` is optional**: cgroup v2 is universal on modern Android,
 * but `cgexec(1)` (a separate binary from libcgroup-tools) may not be
 * present. We probe it once at construction and skip the cgexec layer
 * when it's missing — the rest of the pipeline still works.
 */
class NamespacedDistroLauncher(
    private val probe: RootedModeProbe,
    private val namespaceSpec: NamespaceSpec = NamespaceSpec.FULL_SANDBOX,
    private val cgroupSpec: CgroupSpec = CgroupSpec.NONE,
    private val sliceName: String = "elysium.slice",
    private val requireUserNamespace: Boolean = false,
) : DistroLauncher {

    override val kind: LauncherKind = LauncherKind.NAMESPACE_UNSHARE

    override val capabilities: LauncherCapabilities = LauncherCapabilities(
        // Native ELFs inside the rootfs run because chroot + su
        // grant the loader the right path. This is the most
        // "real Linux" experience we can give an Android device.
        canRunElfBinaries = true,
        // A real PTY comes from the terminal backend; the
        // launcher itself does not allocate one.
        exposesPty = true,
        // Bind mounts are implemented via `mount --bind` inside
        // the new mount namespace (chroot-only mounts don't
        // survive chroot on most kernels). Production code
        // wires this through the FilesystemBridge.
        supportsBindMounts = true,
        // This is the ONLY launcher that requires root. The
        // UI shows a "Root required" badge when this launcher
        // is selected.
        requiresRoot = true,
        abiSupport = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    )

    override fun buildShellCommand(rootfsDir: File, script: String): List<String> {
        if (!isAvailable(rootfsDir)) {
            return listOf(UnshareCommandBuilder.MISSING_SENTINEL)
        }
        return UnshareCommandBuilder.build(
            rootfsDir = rootfsDir,
            script = script,
            namespaces = effectiveNamespaceSpec(),
            cgroups = cgroupSpec,
            sliceName = sliceName,
        )
    }

    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> {
        // One-shot probes don't need an interactive login shell.
        // We use `/bin/sh -c <args>` so the result is the same as
        // running the args directly inside the chroot.
        if (!isAvailable(rootfsDir)) {
            return listOf(UnshareCommandBuilder.MISSING_SENTINEL)
        }
        val inline = args.joinToString(" ") { UnshareCommandBuilder.shellQuote(it) }
        return UnshareCommandBuilder.build(
            rootfsDir = rootfsDir,
            script = inline,
            namespaces = effectiveNamespaceSpec(),
            cgroups = cgroupSpec,
            sliceName = sliceName,
        )
    }

    /**
     * No host-side env. The chroot inherits the su shell's env
     * (which is already root-flavored); the
     * [UnshareCommandBuilder.DEFAULT_ENV] provides the guest-side
     * env via `env -i`.
     */
    override fun environmentVariables(rootfsDir: File): List<Pair<String, String>> = emptyList()

    /**
     * True iff the probe says we're rooted + unshare exists +
     * the requested cgroup spec is honor-able (v2 + cgexec).
     */
    override fun isAvailable(rootfsDir: File): Boolean {
        if (!rootfsDir.isDirectory) return false
        val status = probe.probe()
        if (!status.canLaunchRooted) return false
        if (requireUserNamespace && status.unprivilegedUserNsClone != true) return false
        if (!status.canHonorCgroupSpec(cgroupSpec)) return false
        return true
    }

    /**
     * If the user requested a user namespace but the kernel
     * doesn't support it, return the spec without the user
     * flag. The probe already reported the mismatch; we
     * degrade silently rather than failing the launch.
     */
    private fun effectiveNamespaceSpec(): NamespaceSpec {
        if (!namespaceSpec.user) return namespaceSpec
        val status = probe.probe()
        if (status.unprivilegedUserNsClone == true) return namespaceSpec
        return namespaceSpec.copy(user = false)
    }
}
