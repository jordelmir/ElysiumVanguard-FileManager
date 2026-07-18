package com.elysium.vanguard.core.runtime.wine

import java.io.File

/**
 * Phase 54 — the installed Wine + Box64
 * binaries.
 *
 * The stack is the value type that holds
 * the paths to the `wine`, `box64`, and
 * (optionally) `box86` binaries. The stack
 * also has a [detect] factory that probes
 * standard locations and returns either a
 * populated stack or a "Wine not installed"
 * rejection.
 *
 * ## Detection
 *
 * The detector probes:
 * - `/system/bin/wine`, `/vendor/bin/wine`,
 *   `/usr/bin/wine` (the `wine` binary).
 * - `/system/bin/box64`, `/vendor/bin/box64`,
 *   `/usr/bin/box64` (the Box64 translator).
 * - `/system/bin/box86`, `/vendor/bin/box86`,
 *   `/usr/bin/box86` (the Box86 translator
 *   for 32-bit x86; optional — most
 *   devices do not have it).
 *
 * On Android, Wine and Box64 are not in
 * the standard locations by default; the
 * user installs them via the Elysium
 * Vanguard AppStore or sideloads them. The
 * stack's paths are the user's choices.
 *
 * ## Why a value type
 *
 * The stack is a pure data carrier. The
 * [WineSessionRunner] consumes the stack
 * to build the command line. The detector
 * is a factory method, not a state machine.
 * Multiple stacks can coexist (one per
 * device; one per user install).
 */
data class WineStack(
    val winePath: File,
    val box64Path: File?,
    val box86Path: File? = null
) {
    init {
        require(winePath.path.isNotBlank()) { "winePath must not be blank" }
    }

    /**
     * True iff the stack is ready to run
     * x86-64 Windows apps. Box64 is the
     * x86-64 translator; its absence means
     * x86-64 apps cannot run.
     */
    val supportsX86_64: Boolean
        get() = box64Path != null

    /**
     * True iff the stack is ready to run
     * x86 (32-bit) Windows apps. Box86 is
     * the x86 translator; its absence means
     * x86 apps cannot run.
     */
    val supportsX86: Boolean
        get() = box86Path != null

    companion object {

        /**
         * Standard locations to probe for
         * the Wine binary. The first match
         * wins.
         */
        private val WINE_CANDIDATES = listOf(
            "/system/bin/wine",
            "/vendor/bin/wine",
            "/usr/bin/wine",
            "/data/local/tmp/wine"
        )

        /**
         * Standard locations to probe for
         * the Box64 binary.
         */
        private val BOX64_CANDIDATES = listOf(
            "/system/bin/box64",
            "/vendor/bin/box64",
            "/usr/bin/box64",
            "/data/local/tmp/box64"
        )

        /**
         * Standard locations to probe for
         * the Box86 binary.
         */
        private val BOX86_CANDIDATES = listOf(
            "/system/bin/box86",
            "/vendor/bin/box86",
            "/usr/bin/box86"
        )

        /**
         * Probe the standard locations and
         * return a populated [WineStack]
         * iff `wine` was found. Box64 /
         * Box86 are optional. Returns `null`
         * when Wine is not installed.
         */
        fun detect(): WineStack? {
            val wine = WINE_CANDIDATES
                .map(::File)
                .firstOrNull { it.canExecute() } ?: return null
            val box64 = BOX64_CANDIDATES
                .map(::File)
                .firstOrNull { it.canExecute() }
            val box86 = BOX86_CANDIDATES
                .map(::File)
                .firstOrNull { it.canExecute() }
            return WineStack(
                winePath = wine,
                box64Path = box64,
                box86Path = box86
            )
        }
    }
}

/**
 * Phase 54 — a per-app Wine prefix.
 *
 * A prefix is a directory that contains a
 * fake `C:\` drive with `windows`,
 * `system32`, `Program Files`, and a per-
 * user `users/<name>/`. Each Wine app gets
 * its own prefix; the user-visible "app" is
 * the prefix + the installed app inside it.
 *
 * The runtime creates one prefix per
 * [com.elysium.vanguard.core.runtime.orchestrator.ExecutionManifest]
 * (one per app) on first run; subsequent
 * runs of the same app reuse the prefix.
 * The prefix lives at
 * `<filesDir>/wine-prefixes/<app-id>/`.
 *
 * A user with a "this app's Wine config is
 * broken" complaint can delete the prefix
 * for that one app without affecting other
 * Wine apps.
 */
data class WinePrefix(
    /** Absolute path to the prefix directory.
     *  The runtime creates the directory if
     *  it does not exist. */
    val path: File,
    /** Windows version the prefix emulates
     *  (e.g. "Windows 10", "Windows 7"). */
    val windowsVersion: String = DEFAULT_WINDOWS_VERSION,
    /** Architecture override: "win64" for
     *  64-bit, "win32" for 32-bit. Defaults
     *  to win64. */
    val architecture: String = DEFAULT_ARCHITECTURE
) {
    init {
        require(path.path.isNotBlank()) { "prefix path must not be blank" }
        require(windowsVersion.isNotBlank()) { "windowsVersion must not be blank" }
        require(architecture in ALLOWED_ARCHITECTURES) {
            "architecture must be one of $ALLOWED_ARCHITECTURES, was '$architecture'"
        }
    }

    /**
     * The `C:\` drive path inside the prefix.
     * Wine treats `<prefix>/drive_c` as the
     * Windows `C:\` drive.
     */
    val driveC: File
        get() = File(path, "drive_c")

    /**
     * The `system32` directory inside the
     * prefix. Wine puts 64-bit Windows DLLs
     * here (and 32-bit in `syswow64`).
     */
    val system32: File
        get() = File(driveC, "windows/system32")

    /**
     * Initialise the prefix directory tree.
     * Creates `<prefix>/drive_c/windows/system32`
     * if missing. A real Wine install runs
     * `wineboot --init` after this; Phase 54
     * ships the directory creation, the
     * `wineboot` step is a Phase 55+ concern.
     */
    fun initialise() {
        if (!path.isDirectory && !path.mkdirs()) {
            throw java.io.IOException("Could not create prefix directory: ${path.absolutePath}")
        }
        if (!driveC.isDirectory && !driveC.mkdirs()) {
            throw java.io.IOException("Could not create drive_c: ${driveC.absolutePath}")
        }
        val windowsDir = File(driveC, "windows")
        if (!windowsDir.isDirectory && !windowsDir.mkdirs()) {
            throw java.io.IOException("Could not create windows dir: ${windowsDir.absolutePath}")
        }
        if (!system32.isDirectory && !system32.mkdirs()) {
            throw java.io.IOException("Could not create system32: ${system32.absolutePath}")
        }
    }

    companion object {
        const val DEFAULT_WINDOWS_VERSION: String = "Windows 10"
        const val DEFAULT_ARCHITECTURE: String = "win64"
        val ALLOWED_ARCHITECTURES: Set<String> = setOf("win64", "win32")
    }
}

/**
 * Phase 54 — the Box64 translator's
 * configuration.
 *
 * Box64 translates x86-64 instructions to
 * ARM64 at runtime. The config controls:
 *
 * - [translationMode]: `DEFAULT` (let
 *   Box64 decide) or `DYNAREC` (force
 *   dynamic recompilation). The dynarec
 *   mode is faster but uses more memory.
 * - [libraryOverrides]: a list of host
 *   libraries Box64 should prefer over
 *   the Windows versions. Useful when a
 *   Windows app calls a Linux-native
 *   function (e.g. SQLite) that the user
 *   has installed.
 * - [environmentVariables]: extra env vars
 *   Box64 / Wine should see.
 *
 * The config is consumed by the
 * [WineSessionRunner] when building the
 * command line. The runner does not parse
 * the config; the config is just a record
 * the runner reads.
 */
data class Box64Config(
    val translationMode: TranslationMode = TranslationMode.DEFAULT,
    val libraryOverrides: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap()
) {
    /**
     * Convert the config to a list of
     * `BOX64_*` environment variables the
     * Box64 binary reads. The list is
     * appended to the parent process's
     * environment when the runner starts
     * the Wine session.
     */
    fun toEnvironment(): Map<String, String> {
        val env = HashMap<String, String>(environmentVariables)
        when (translationMode) {
            TranslationMode.DEFAULT -> Unit
            TranslationMode.DYNAREC -> env["BOX64_DYNAREC"] = "1"
        }
        if (libraryOverrides.isNotEmpty()) {
            env["BOX64_LD_LIBRARY_PATH"] = libraryOverrides.joinToString(":")
        }
        return env
    }

    enum class TranslationMode { DEFAULT, DYNAREC }
}
