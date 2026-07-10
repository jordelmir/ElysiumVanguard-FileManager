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
    private val externalBinarySearchPaths: List<File> = emptyList(),

    /**
     * PHASE 9.6.4 — Optional filesystem bridge provider. When the
     * launcher builds a command line it queries this for the bind
     * mounts to encode as `-b` flags. The injected default pulls
     * from the [com.elysium.vanguard.core.runtime.bridge.ElysiumNamespaces]
     * source.
     */
    private val bridge: com.elysium.vanguard.core.runtime.bridge.FilesystemBridge = com.elysium.vanguard.core.runtime.bridge.FilesystemBridge,

    /**
     * PHASE 9.6.4 — Optional native library detector. Production code
     * wires a real one; tests can supply a fake.
     */
    private val nativeLibrary: ProotNativeLibrary? = null
) : DistroLauncher {

    override val kind: LauncherKind = LauncherKind.NATIVE_PROOT

    override val capabilities: LauncherCapabilities = LauncherCapabilities(
        canRunElfBinaries = bundledAbis.isNotEmpty(),
        exposesPty = false,
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
        // PHASE 9.6.4 — Real proot command with bind mounts.
        val args = ArrayList<String>()
        args += "proot"
        args += "-0"
        args += "-r"
        args += rootfsDir.absolutePath
        // Bind mounts: query the bridge for the active namespaces.
        for (mount in bridge.standardMounts(sdcardPath = null, vaultPath = null)) {
            args += "-b"
            args += "${mount.hostPath}:${mount.guestPath}"
        }
        args += "/bin/sh"
        args += "-c"
        args += if (script.isBlank()) {
            "echo '[proot] ready'; exec /bin/sh"
        } else {
            script
        }
        return args
    }

    override fun buildProbeCommand(rootfsDir: File, args: List<String>): List<String> {
        require(rootfsDir.isDirectory) { "rootfsDir is not a directory: $rootfsDir" }
        val out = ArrayList<String>()
        out += "proot"
        out += "-0"
        out += "-r"
        out += rootfsDir.absolutePath
        out += "/bin/sh"
        out += "-c"
        out += args.joinToString(" ")
        return out
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
        if (nativeLibrary == null) return false
        return nativeLibrary.location != null
    }
}
