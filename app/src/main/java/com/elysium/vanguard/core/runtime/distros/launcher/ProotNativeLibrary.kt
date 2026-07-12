package com.elysium.vanguard.core.runtime.distros.launcher

import java.io.File
import java.util.Locale

/**
 * PHASE 9.6.4 — Detective for whether the native `libproot.so` is
 * available on this device, and if so where to load it from.
 *
 * Three realistic locations the binary can appear in, in priority
 * order:
 *
 *   1. **Bundled in the APK** under `lib/<abi>/libproot.so` — done by
 *      gradle's `mergeNativeLibs` task. We can't add this without
 *      cross-compiling proot, but Phase 9.6.4 lays the wire-up.
 *   2. **User-installed** under `<filesDir>/proot/libproot.so` (and
 *      per-ABI sub-dirs). The user can copy the .so here from a
 *      Termux package archive or a custom build.
 *   3. **Termux's well-known prefix**: `$PREFIX/libexec/proot` or
 *      `/data/data/com.termux/files/usr/libexec/proot`. We treat the
 *      file as present when the path exists.
 *
 * The launcher only invokes proot when [location] reports one of these
 * exist. Until then, the jailed shell path (Phase 9.6.3) is correct.
 *
 * Phase 9.6.4 — first build; intentionally minimal.
 */
class ProotNativeLibrary(
    private val bundledAbis: Set<String>,
    private val nativeLibraryDir: File?,
    private val userProotDir: File?,
    private val termuxProotCandidates: List<File>
) {

    /**
     * Where the launcher can find the .so on this device. Null when
     * nothing is wired up yet — caller falls back to JAILED_SHELL.
     */
    val location: ProotLocation? = detect()

    /**
     * Sums up what we found across bundled abis and external candidates.
     * Useful for the UI to tell the user "found a .so at <path>" without
     * blocking.
     */
    fun describeForUi(): String {
        return if (location == null) {
            "no libproot.so located (using jailed shell fallback)"
        } else {
            "libproot.so available at ${location.displayPath}"
        }
    }

    /**
     * The set of ABIs we're configured to look for in the bundled APK.
     * Empty means "no bundled candidate; rely on user-provided or
     * Termux".
     */
    fun bundledAbis(): Set<String> = bundledAbis

    private fun detect(): ProotLocation? {
        // Android extracts APK native libraries into one ABI-specific
        // applicationInfo.nativeLibraryDir. Unlike the old placeholder,
        // this is the real executable path ProcessBuilder can invoke.
        val bundled = nativeLibraryDir?.let { File(it, "libproot.so") }
        if (bundled?.isFile == true) {
            return ProotLocation(
                source = ProotLocation.Source.BUNDLED,
                path = bundled,
                abi = bundledAbis.firstOrNull() ?: "unknown"
            )
        }
        // Second pass: user-installed under filesDir/proot/<abi>/.
        val userDir = userProotDir
        if (userDir != null && userDir.isDirectory) {
            for (abi in bundledAbis) {
                val candidate = File(userDir, "$abi/libproot.so")
                if (candidate.isFile) {
                    return ProotLocation(
                        source = ProotLocation.Source.USER_INSTALLED,
                        path = candidate,
                        abi = abi
                    )
                }
            }
            val flat = File(userDir, "libproot.so")
            if (flat.isFile) {
                return ProotLocation(
                    source = ProotLocation.Source.USER_INSTALLED,
                    path = flat,
                    abi = bundledAbis.firstOrNull() ?: "unknown"
                )
            }
        }
        // Third pass: Termux's prefixes.
        for (candidate in termuxProotCandidates) {
            if (candidate.isFile) {
                return ProotLocation(
                    source = ProotLocation.Source.TERMUX,
                    path = candidate,
                    abi = bundledAbis.firstOrNull() ?: "unknown"
                )
            }
        }
        return null
    }

    companion object {
        /**
         * The default [ProotNativeLibrary] wired against a typical
         * Android device. Pass the [androidx.work] -style `application`
         * so the helper can locate user dirs.
         */
        fun default(
            abis: Set<String>,
            userProotDir: File?,
            nativeLibraryDir: File? = null,
            termuxProotCandidates: List<File> = DEFAULT_TERMUX_PROBES
        ): ProotNativeLibrary = ProotNativeLibrary(
            bundledAbis = abis,
            nativeLibraryDir = nativeLibraryDir,
            userProotDir = userProotDir,
            termuxProotCandidates = termuxProotCandidates
        )

        /**
         * Common Termux installation locations. We check them in
         * order; the first that exists wins.
         */
        val DEFAULT_TERMUX_PROBES: List<File> = listOf(
            File("/data/data/com.termux/files/usr/bin/proot"),
            File("/data/user/0/com.termux/files/usr/bin/proot"),
            File("/system/bin/proot")
        )
    }
}

/**
 * Description of where libproot.so was found. Used by both the
 * launcher (to decide whether to engage) and the UI (to display).
 *
 * Phase 9.6.4 — first build; intentionally minimal.
 */
data class ProotLocation(
    val source: Source,
    val path: File,
    val abi: String
) {
    enum class Source { BUNDLED, USER_INSTALLED, TERMUX }

    val displayPath: String
        get() = "${source.name.lowercase(Locale.US)} → ${path.absolutePath} (abi=$abi)"

    val loaderPath: File
        get() = when (source) {
            Source.BUNDLED, Source.USER_INSTALLED -> File(path.parentFile, "libproot_loader.so")
            Source.TERMUX -> {
                val usrDir = path.parentFile?.parentFile
                if (usrDir != null) File(usrDir, "libexec/proot/loader")
                else File(path.parentFile, "loader")
            }
        }
}
