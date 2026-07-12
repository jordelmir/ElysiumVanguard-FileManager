package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File

/**
 * PHASE 9.6.4 — Native-proot launcher (wired but binary-pending).
 *
 * Two pieces matter:
 *
 *   1. **Native library detector** ([ProotNativeLibrary]) tells us
 *      whether `libproot.so` is on the device (bundled in the APK,
 *      user-installed in `filesDir/proot/`, or Termux's well-known
 *      prefix). The launcher queries it inside [isAvailable].
 *
 *   2. **Bind mounts** come from [com.elysium.vanguard.core.runtime.bridge.FilesystemBridge]
 *      and are translated 1:1 to proot's `-b <host>:<guest>` flags.
 *      When the bridge has no entries for a session we still produce
 *      a valid command line, just without bind mounts.
 *
 * What this launcher does today: detects the library, builds the
 * proot command line, includes every bind mount the bridge yields,
 * and reports availability. When the user actually launches a distro
 * we hand this command list to `ProcessBuilder` exactly as we would
 * for the jailed shell — proot's binary then takes over and
 * translates syscalls.
 *
 * Phase 9.6.4 — wired but inert until libproot.so ships (see
 * `proot/INSTALL.md`).
 */
open class NativeProotLauncher(
    /**
     * Set of ABIs this binary was bundled for. Empty means "no binary,
     * inert" — the resolution code will skip us entirely.
     */
    private val bundledAbis: Set<String> = emptySet(),

    /**
     * Where to look for a binary outside the APK. Phase 9.6.3 leaves
     * this empty; the resolver also looks in Termux's well-known paths.
     */
    /**
     * PHASE 9.6.4 — Optional native library detector. Production code
     * wires a real one; tests can supply a fake.
     */
    private val nativeLibrary: ProotNativeLibrary? = null,

    /** Writable location for PRoot glue files and temporary state. */
    private val runtimeTmpDir: File? = null,

    /** Host paths explicitly exposed inside the guest namespace. */
    private val additionalMounts: List<com.elysium.vanguard.core.runtime.bridge.MountEntry> = emptyList()
) : DistroLauncher {

    override val kind: LauncherKind = LauncherKind.NATIVE_PROOT

    override val capabilities: LauncherCapabilities = LauncherCapabilities(
        canRunElfBinaries = bundledAbis.isNotEmpty(),
        exposesPty = true,
        supportsBindMounts = bundledAbis.isNotEmpty(),
        requiresRoot = false,
        abiSupport = bundledAbis
    )

    override fun buildShellCommand(rootfsDir: File, script: String): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        if (!isAvailable(rootfsDir)) {
            // Honest: build the same string shape we *would* have built,
            // but the UI / callers are expected to detect via
            // [isAvailable] and use [JailedDistroLauncher] instead.
            return listOf("proot-missing")
        }
        val location = nativeLibrary?.location ?: return listOf("proot-missing")
        // Real PRoot command. The executable is a PIE shipped in
        // nativeLibraryDir, not a symbolic command resolved through PATH.
        val args = ArrayList<String>()
        args += location.path.absolutePath
        args += "--kill-on-exit"
        args += "--link2symlink"
        args += "-0"
        args += "-r"
        args += rootfsDir.absolutePath
        listOf("/dev", "/proc", "/sys").forEach { hostPath ->
            if (File(hostPath).exists()) {
                args += "-b"
                args += hostPath
            }
        }
        for (mount in additionalMounts) {
            if (!File(mount.hostPath).exists()) continue
            // PRoot's basic bind option does not provide a trustworthy
            // read-only guarantee. Fail closed until the filesystem broker
            // can enforce that policy instead of silently widening access.
            if (mount.readOnly) continue
            args += "-b"
            args += "${mount.hostPath}:${mount.guestPath}"
        }
        args += "-w"
        args += "/root"
        args += "/usr/bin/env"
        args += "-i"
        args += "HOME=/root"
        args += "USER=root"
        args += "LOGNAME=root"
        args += "SHELL=/bin/sh"
        args += "TERM=xterm-256color"
        args += "LANG=C.UTF-8"
        args += "TMPDIR=/tmp"
        args += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        args += "/bin/sh"
        if (script.isBlank()) {
            args += "-l"
        } else {
            args += "-lc"
            args += script
        }
        return args
    }

    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        return buildShellCommand(
            rootfsDir = rootfsDir,
            script = args.joinToString(" ") { shellQuote(it) }
        )
    }

    override fun environmentVariables(rootfsDir: File): List<Pair<String, String>> {
        val location = nativeLibrary?.location ?: return emptyList()
        val libraryDir = location.path.parentFile?.absolutePath ?: return emptyList()
        val tmp = runtimeTmpDir ?: File(rootfsDir.parentFile, "proot-tmp")
        if (!tmp.isDirectory && !tmp.mkdirs()) return emptyList()
        return listOf(
            "LD_LIBRARY_PATH" to libraryDir,
            "PROOT_LOADER" to location.loaderPath.absolutePath,
            "PROOT_TMP_DIR" to tmp.absolutePath,
            // Android vendor kernels differ substantially. Disabling
            // seccomp acceleration trades a little speed for predictable
            // behavior across Android 8-16 and foldable OEM kernels.
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_DONT_POLLUTE_ROOTFS" to "1"
        )
    }

    /**
     * PHASE 9.6.4 — Real availability probe. We say yes only when:
     *
     *   - a real proot library is detectable (bundled, user-installed,
     *     or Termux), AND
     *   - the supplied rootfs directory exists.
     *
     * The jailed-shell launcher (Phase 9.6.3) is the fallback when this
     * returns false. We deliberately do NOT return true on
     * "bundledAbis non-empty" alone: that's a structural declaration,
     * not a guarantee that `libproot.so` was successfully loaded.
     */
    override fun isAvailable(rootfsDir: File): Boolean {
        if (!rootfsDir.isDirectory) return false
        val location = nativeLibrary?.location ?: return false
        return location.path.isFile &&
            location.path.length() > 0L &&
            location.loaderPath.isFile &&
            location.loaderPath.length() > 0L
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
