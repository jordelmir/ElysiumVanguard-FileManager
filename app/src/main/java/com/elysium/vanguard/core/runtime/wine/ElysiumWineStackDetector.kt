package com.elysium.vanguard.core.runtime.wine

import java.io.File
import java.util.Locale

/**
 * PHASE 144 — Elysium-aware Wine + Box64 detector.
 *
 * Mirrors the [com.elysium.vanguard.core.runtime.distros.launcher.ProotNativeLibrary]
 * pattern (Phase 9.6.4) but for the Wine + Box64 stack. The
 * existing [WineStack.detect] (Phase 54) probes only broad
 * system paths (`/system/bin/`, `/vendor/bin/`, `/usr/bin/`,
 * `/data/local/tmp/`) — those paths are not where the Elysium
 * APK ships its native payload.
 *
 * The Elysium layout is the source of truth:
 *   1. **Bundled in the APK** under
 *      `nativeLibraryDir/libwine.so` + `libbox64.so` (analogous
 *      to how `libproot.so` is bundled). Android extracts the
 *      .so into the device's `applicationInfo.nativeLibraryDir`
 *      at install time.
 *   2. **User-installed** under `<filesDir>/wine/<abi>/` —
 *      the user can copy a Wine+Box64 build from a Termux
 *      package archive, a custom NDK build, or a downloaded
 *      tarball.
 *   3. **Termux's well-known prefix** for each binary:
 *      `$PREFIX/bin/wine` and `$PREFIX/bin/box64` (Termux
 *      ships Wine+Box64 in its community repo on Adreno
 *      devices). We probe the same paths the Phase 54 detector
 *      probes PLUS the user-data variant
 *      `/data/user/0/com.termux/...` (some devices symlink the
 *      data dir).
 *
 * The detector returns a populated [WineStack] iff `wine` is
 * located; Box64 + Box86 are optional. The detector is
 * **read-only** (no side effects, no I/O beyond `File.isFile` +
 * `File.canExecute`); JVM unit tests exercise the logic
 * without touching the real filesystem beyond a temp dir.
 *
 * The detector is the bridge between Phase 54's typed Wine
 * types and the Elysium APK's actual layout. A future phase
 * cross-compiles `libwine.so` + `libbox64.so` for ARM64; until
 * then, the detector is the place that surfaces "Wine not yet
 * bundled — install via Termux" so the UI can show actionable
 * diagnostics.
 */
class ElysiumWineStackDetector(
    private val nativeLibraryDir: File?,
    private val userWineDir: File?,
    private val termuxWineCandidates: List<File> = DEFAULT_TERMUX_WINE_PROBES,
    private val termuxBox64Candidates: List<File> = DEFAULT_TERMUX_BOX64_PROBES,
) {

    /**
     * The detected stack. Null when `wine` is not located at
     * any probe site. The caller falls back to "Wine not
     * installed" UX in that case.
     */
    val stack: WineStack? = detect()

    /**
     * Where the Wine binary was found. Independent of [stack]:
     * a caller can ask "is wine installed at all?" without
     * resolving the full stack. Null when not located.
     */
    val wineLocation: ElysiumWineLocation? = locateWine()

    /**
     * Where the Box64 binary was found. Null when not located
     * (the stack is still returned with `box64Path = null` if
     * Box64 is missing — Wine still works for 32-bit Windows
     * apps under Box86 alone, or for ARM64 Windows apps with
     * no translator).
     */
    val box64Location: ElysiumBox64Location? = locateBox64()

    /**
     * Human-readable summary for the UI / a diagnostic screen.
     * Format: `"wine=… box64=… source=…"` so the user can see
     * which probe site resolved the stack.
     */
    fun describeForUi(): String {
        val wine = wineLocation ?: return "no wine located; install via Termux (pkg i wine) or drop libwine.so in <filesDir>/wine/"
        val box64 = box64Location?.path?.absolutePath ?: "missing (x86-64 Windows apps will not run)"
        return "wine=${wine.path.absolutePath} box64=$box64 source=${wine.source.name.lowercase(Locale.US)}"
    }

    private fun detect(): WineStack? {
        val wine = locateWine() ?: return null
        val box64 = locateBox64()?.path
        return WineStack(
            winePath = wine.path,
            box64Path = box64,
            box86Path = null, // Phase 144 ships box64 only; box86 is a future increment
        )
    }

    private fun locateWine(): ElysiumWineLocation? {
        // First pass: bundled in the APK's nativeLibraryDir.
        // Android extracts APK native libraries into
        // applicationInfo.nativeLibraryDir at install time
        // (a real, on-device file path ProcessBuilder can
        // invoke via the dlopen stub).
        nativeLibraryDir?.let { dir ->
            val bundled = File(dir, "libwine.so")
            if (bundled.isFile && bundled.length() > 0L) {
                return ElysiumWineLocation(
                    source = ElysiumWineLocation.Source.BUNDLED,
                    path = bundled,
                    abi = "arm64-v8a",
                )
            }
        }
        // Second pass: user-installed under filesDir/wine/<abi>/.
        // The user can drop the binary anywhere under the
        // wine root, either flat (`<filesDir>/wine/libwine.so`)
        // or per-ABI (`<filesDir>/wine/arm64-v8a/libwine.so`).
        val userDir = userWineDir
        if (userDir != null && userDir.isDirectory) {
            val flat = File(userDir, "libwine.so")
            if (flat.isFile && flat.length() > 0L) {
                return ElysiumWineLocation(
                    source = ElysiumWineLocation.Source.USER_INSTALLED,
                    path = flat,
                    abi = "arm64-v8a",
                )
            }
            // Per-ABI sub-directory. We probe arm64-v8a only
            // for now; multi-ABI is a future increment.
            for (abi in SUPPORTED_ABIS) {
                val candidate = File(File(userDir, abi), "libwine.so")
                if (candidate.isFile && candidate.length() > 0L) {
                    return ElysiumWineLocation(
                        source = ElysiumWineLocation.Source.USER_INSTALLED,
                        path = candidate,
                        abi = abi,
                    )
                }
            }
        }
        // Third pass: Termux + system prefixes. The same set
        // of paths the Phase 54 detector probes, plus the
        // /data/user/0/ variant.
        for (candidate in termuxWineCandidates) {
            if (candidate.isFile && candidate.canExecute()) {
                return ElysiumWineLocation(
                    source = ElysiumWineLocation.Source.TERMUX,
                    path = candidate,
                    abi = "arm64-v8a",
                )
            }
        }
        return null
    }

    private fun locateBox64(): ElysiumBox64Location? {
        // First pass: bundled in the APK's nativeLibraryDir.
        nativeLibraryDir?.let { dir ->
            val bundled = File(dir, "libbox64.so")
            if (bundled.isFile && bundled.length() > 0L) {
                return ElysiumBox64Location(
                    source = ElysiumBox64Location.Source.BUNDLED,
                    path = bundled,
                    abi = "arm64-v8a",
                )
            }
        }
        // Second pass: user-installed.
        val userDir = userWineDir
        if (userDir != null && userDir.isDirectory) {
            val flat = File(userDir, "libbox64.so")
            if (flat.isFile && flat.length() > 0L) {
                return ElysiumBox64Location(
                    source = ElysiumBox64Location.Source.USER_INSTALLED,
                    path = flat,
                    abi = "arm64-v8a",
                )
            }
            for (abi in SUPPORTED_ABIS) {
                val candidate = File(File(userDir, abi), "libbox64.so")
                if (candidate.isFile && candidate.length() > 0L) {
                    return ElysiumBox64Location(
                        source = ElysiumBox64Location.Source.USER_INSTALLED,
                        path = candidate,
                        abi = abi,
                    )
                }
            }
        }
        // Third pass: Termux + system prefixes.
        for (candidate in termuxBox64Candidates) {
            if (candidate.isFile && candidate.canExecute()) {
                return ElysiumBox64Location(
                    source = ElysiumBox64Location.Source.TERMUX,
                    path = candidate,
                    abi = "arm64-v8a",
                )
            }
        }
        return null
    }

    companion object {
        /**
         * The ABIs the Elysium APK ships native libraries for.
         * Today: only arm64-v8a. A future phase may add
         * armv7 (32-bit ARM) for legacy devices.
         */
        val SUPPORTED_ABIS: List<String> = listOf("arm64-v8a")

        /**
         * Common Termux installation locations for `wine`.
         * We check them in order; the first that exists +
         * is executable wins.
         */
        val DEFAULT_TERMUX_WINE_PROBES: List<File> = listOf(
            File("/data/data/com.termux/files/usr/bin/wine"),
            File("/data/user/0/com.termux/files/usr/bin/wine"),
            File("/system/bin/wine"),
            File("/vendor/bin/wine"),
            File("/usr/bin/wine"),
            File("/data/local/tmp/wine"),
        )

        /**
         * Common Termux installation locations for `box64`.
         */
        val DEFAULT_TERMUX_BOX64_PROBES: List<File> = listOf(
            File("/data/data/com.termux/files/usr/bin/box64"),
            File("/data/user/0/com.termux/files/usr/bin/box64"),
            File("/system/bin/box64"),
            File("/vendor/bin/box64"),
            File("/usr/bin/box64"),
            File("/data/local/tmp/box64"),
        )
    }
}

/**
 * Where the `wine` binary (or `libwine.so`) was found.
 * Mirrors the [com.elysium.vanguard.core.runtime.distros.launcher.ProotLocation]
 * shape (Phase 9.6.4) so the UI can show "from bundled .so at
 * X" or "from Termux at Y" in the same format.
 */
data class ElysiumWineLocation(
    val source: Source,
    val path: File,
    val abi: String,
) {
    enum class Source { BUNDLED, USER_INSTALLED, TERMUX }

    val displayPath: String
        get() = "${source.name.lowercase(Locale.US)} → ${path.absolutePath} (abi=$abi)"
}

/**
 * Where the `box64` binary (or `libbox64.so`) was found.
 * Same shape as [ElysiumWineLocation]; the two are kept
 * separate so each binary's source is independently tracked
 * (Wine can be bundled while Box64 comes from Termux, and
 * vice versa).
 */
data class ElysiumBox64Location(
    val source: Source,
    val path: File,
    val abi: String,
) {
    enum class Source { BUNDLED, USER_INSTALLED, TERMUX }

    val displayPath: String
        get() = "${source.name.lowercase(Locale.US)} → ${path.absolutePath} (abi=$abi)"
}
