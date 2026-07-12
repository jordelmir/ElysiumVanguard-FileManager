package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File
import java.io.IOException
import com.elysium.vanguard.core.runtime.network.GuestDnsConfig
import com.elysium.vanguard.core.runtime.network.GuestDnsConfigProvider

/**
 * Native PRoot launcher.
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
 * The APK bundles the executable PRoot payload for arm64; resolution still
 * performs a real on-device file check before selecting it.
 */
open class NativeProotLauncher(
    /**
     * Set of ABIs this binary was bundled for. Empty means no native payload,
     * so resolution skips this launcher.
     */
    private val bundledAbis: Set<String> = emptySet(),

    /**
     * Optional native library detector. Production code supplies the APK's
     * extracted native directory; tests can supply an isolated fixture.
     */
    private val nativeLibrary: ProotNativeLibrary? = null,

    /** Writable location for PRoot glue files and temporary state. */
    private val runtimeTmpDir: File? = null,

    /** Host paths explicitly exposed inside the guest namespace. */
    private val additionalMounts: List<com.elysium.vanguard.core.runtime.bridge.MountEntry> = emptyList(),

    /** Android-network DNS rendered into a transient PRoot bind mount. */
    private val guestDnsConfigProvider: GuestDnsConfigProvider = GuestDnsConfigProvider.NONE
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
            // Callers must resolve availability before launch; this sentinel
            // is retained only as a defensive non-executable result.
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
        prepareGuestDnsFile(rootfsDir)?.let { resolvConf ->
            // Ubuntu often makes /etc/resolv.conf a link into /run, while
            // other distros use the path directly. Bind all standard targets
            // to one app-private file so we do not mutate the user's rootfs.
            GUEST_RESOLV_PATHS.forEach { guestPath ->
                args += "-b"
                args += "${resolvConf.absolutePath}:$guestPath"
            }
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

    private fun prepareGuestDnsFile(rootfsDir: File): File? {
        val config = guestDnsConfigProvider.current()
        if (config.nameservers.isEmpty()) return null
        val runtimeDir = runtimeTmpDir ?: return null
        return try {
            val dnsDir = File(runtimeDir, "dns")
            if (!dnsDir.isDirectory && !dnsDir.mkdirs()) return null
            cleanupStaleDnsFiles(dnsDir)
            val key = rootfsDir.parentFile?.name.orEmpty()
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "guest" }
            val target = File(dnsDir, "$key.resolv.conf")
            val staging = File(dnsDir, "$key.resolv.conf.part")
            staging.writeText(config.renderResolvConf())
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            target
        } catch (_: IOException) {
            null
        }
    }

    private fun cleanupStaleDnsFiles(dnsDir: File) {
        val cutoff = System.currentTimeMillis() - DNS_FILE_MAX_AGE_MS
        dnsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".resolv.conf") && file.lastModified() < cutoff) file.delete()
            if (file.name.endsWith(".resolv.conf.part")) file.delete()
        }
    }

    private companion object {
        val GUEST_RESOLV_PATHS = listOf(
            "/etc/resolv.conf",
            "/run/systemd/resolve/stub-resolv.conf",
            "/run/systemd/resolve/resolv.conf",
            "/run/NetworkManager/no-stub-resolv.conf"
        )
        const val DNS_FILE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}
