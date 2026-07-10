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
        // First pass: bundled candidates inside the APK.
        for (abi in bundledAbis) {
            val candidate = File(bundledAbisDir(), "$abi/libproot.so")
            if (candidate.isFile || looksLikeBundled(candidate)) {
                return ProotLocation(
                    source = ProotLocation.Source.BUNDLED,
                    path = candidate,
                    abi = abi
                )
            }
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

    /**
     * APK-merged native libs extract to `<apkExtractDir>/lib/<abi>/...`
     * at install; on a live device they're at `<dataDir>/lib/<abi>/...`.
     * We can't actually see that filesystem location without
     * `ApplicationInfo.nativeLibraryDir`, but Phase 9.6.4 only needs
     * this for tests. The real detection path is in
     * [com.elysium.vanguard.core.runtime.distros.launcher.NativeProotLauncher].
     */
    private fun bundledAbisDir(): File =
        File("/data/app/~~enoYnooWNOPQvY6LmvD3w==/lib") // placeholder; resolved via context in prod

    private fun looksLikeBundled(f: File): Boolean {
        // Tests can opt into "bundled present" by writing a stub here.
        return f.isFile
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
            termuxProotCandidates: List<File> = DEFAULT_TERMUX_PROBES
        ): ProotNativeLibrary = ProotNativeLibrary(
            bundledAbis = abis,
            userProotDir = userProotDir,
            termuxProotCandidates = termuxProotCandidates
        )

        /**
         * Common Termux installation locations. We check them in
         * order; the first that exists wins.
         */
        val DEFAULT_TERMUX_PROBES: List<File> = listOf(
            File("/data/data/com.termux/files/usr/libexec/proot"),
            File("/data/user/0/com.termux/files/usr/libexec/proot"),
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
}
