package com.elysium.vanguard.core.runtime.build

import java.io.File

/**
 * Phase 56 — the supported build toolchains.
 *
 * The [ToolchainKind] enum is the input to
 * the [LocalBuildRunner]; the
 * [ToolchainRegistry] probes for which
 * toolchains are installed on the device.
 *
 * Phase 56 ships the eight toolchains the
 * master vision names:
 * - [RUST] (cargo / rustc)
 * - [C_CPP] (gcc / clang / make / cmake)
 * - [JAVA_KOTLIN] (javac / kotlinc)
 * - [GRADLE] (gradle / gradlew)
 * - [NODE] (node / npm / npx)
 * - [PYTHON] (python3 / pip)
 * - [GO] (go)
 * - [WEBASSEMBLY] (wat2wasm / asc / emcc)
 *
 * [LINUX_ARM64] is the catch-all for "any
 * Linux ARM64 binary" — used when a
 * project produces a binary that the
 * device can run natively (Phase 36's
 * [com.elysium.vanguard.core.runtime.runner.AndroidProcessLauncher]).
 */
enum class ToolchainKind {
    RUST,
    C_CPP,
    JAVA_KOTLIN,
    GRADLE,
    NODE,
    PYTHON,
    GO,
    WEBASSEMBLY,
    LINUX_ARM64;

    /**
     * The human-readable name the user
     * sees in the UI.
     */
    val displayName: String
        get() = when (this) {
            RUST -> "Rust (cargo / rustc)"
            C_CPP -> "C / C++ (gcc / clang)"
            JAVA_KOTLIN -> "Java / Kotlin (javac / kotlinc)"
            GRADLE -> "Gradle (gradle / gradlew)"
            NODE -> "Node.js (node / npm / npx)"
            PYTHON -> "Python (python3 / pip)"
            GO -> "Go (go)"
            WEBASSEMBLY -> "WebAssembly (wat2wasm / asc / emcc)"
            LINUX_ARM64 -> "Linux ARM64 (cross-compile)"
        }
}

/**
 * Phase 56 — the locally installed toolchain
 * registry.
 *
 * The registry is a `Map<ToolchainKind,
 * ToolchainInstall>` where the install is
 * the binary path (e.g. `/usr/bin/cargo`)
 * plus an optional version. The
 * [detect] factory probes standard
 * locations for each toolchain and
 * returns a populated registry. The
 * registry is JVM-testable — a test
 * creates a temp dir with a fake
 * `cargo` binary, calls
 * `ToolchainRegistry.detectAt(baseDir)`,
 * and asserts the registry reports Rust
 * as installed.
 *
 * The registry is immutable. A user with
 * a "Rust was uninstalled" complaint can
 * re-detect; the registry is rebuilt.
 */
data class ToolchainRegistry(
    val installed: Map<ToolchainKind, ToolchainInstall> = emptyMap()
) {
    /**
     * True iff [kind] is installed. The
     * runner refuses to start a build with
     * an un-installed toolchain unless
     * `BuildRequest.forceRemote` is true.
     */
    fun isInstalled(kind: ToolchainKind): Boolean = installed.containsKey(kind)

    /**
     * The install for [kind], or `null` if
     * not installed.
     */
    fun installFor(kind: ToolchainKind): ToolchainInstall? = installed[kind]

    /**
     * The set of installed toolchain kinds.
     * The UI uses this to render a "what's
     * available" list.
     */
    fun installedKinds(): Set<ToolchainKind> = installed.keys

    companion object {
        /**
         * Detect installed toolchains at
         * standard locations. Returns a
         * populated [ToolchainRegistry] with
         * only the toolchains that were
         * found; un-installed toolchains are
         * absent from the map.
         *
         * The detector probes:
         * - `which <binary>` style: it walks
         *   a hard-coded list of common
         *   locations.
         * - The list is conservative — false
         *   negatives (a toolchain is
         *   installed but not detected) are
         *   possible. A user with a custom
         *   toolchain path can call
         *   [withOverride] to add it.
         */
        fun detect(): ToolchainRegistry = detectAt(DEFAULT_BASE_DIRS)

        /**
         * The base directories to probe for
         * toolchain binaries. Phase 56
         * defaults to the standard Linux
         * locations; the proot-distro path
         * is added by callers that have a
         * distro installed.
         */
        val DEFAULT_BASE_DIRS: List<File> = listOf(
            File("/usr/bin"),
            File("/usr/local/bin"),
            File("/data/data/com.termux/files/usr/bin"),
            File("/system/bin")
        )

        /**
         * The standard binary names for each
         * toolchain. The detector walks
         * `DEFAULT_BASE_DIRS` and looks for
         * the first match.
         */
        private val TOOLCHAIN_BINARIES: Map<ToolchainKind, List<String>> = mapOf(
            ToolchainKind.RUST to listOf("cargo", "rustc"),
            ToolchainKind.C_CPP to listOf("gcc", "clang", "cc", "c++", "make", "cmake"),
            ToolchainKind.JAVA_KOTLIN to listOf("javac", "kotlinc", "java"),
            ToolchainKind.GRADLE to listOf("gradle", "gradlew"),
            ToolchainKind.NODE to listOf("node", "npm", "npx", "yarn"),
            ToolchainKind.PYTHON to listOf("python3", "python", "pip", "pip3"),
            ToolchainKind.GO to listOf("go"),
            ToolchainKind.WEBASSEMBLY to listOf("wat2wasm", "asc", "emcc")
        )

        /**
         * Probe [baseDirs] for each toolchain
         * binary. Returns a registry with
         * the first match per toolchain.
         */
        fun detectAt(baseDirs: List<File>): ToolchainRegistry {
            val installed = HashMap<ToolchainKind, ToolchainInstall>()
            for ((kind, candidates) in TOOLCHAIN_BINARIES) {
                for (baseDir in baseDirs) {
                    if (!baseDir.isDirectory) continue
                    for (binary in candidates) {
                        val file = File(baseDir, binary)
                        if (file.canExecute()) {
                            installed[kind] = ToolchainInstall(
                                binaryPath = file,
                                version = null
                            )
                            break // first match wins
                        }
                    }
                    if (kind in installed) break
                }
            }
            return ToolchainRegistry(installed = installed)
        }
    }

    /**
     * Return a new [ToolchainRegistry] with
     * [override] registered as the install
     * for [kind]. Existing entries for
     * [kind] are replaced; other entries
     * are preserved.
     */
    fun withOverride(kind: ToolchainKind, override: ToolchainInstall): ToolchainRegistry =
        copy(installed = installed + (kind to override))
}

/**
 * Phase 56 — a single toolchain install.
 *
 * The install is the path to the toolchain
 * binary plus an optional version. The
 * version is `null` when the runtime has
 * not yet queried the toolchain (Phase 56
 * does not query versions; a future phase
 * can run `<toolchain> --version` and
 * parse the output).
 */
data class ToolchainInstall(
    val binaryPath: File,
    val version: String? = null
) {
    init {
        require(binaryPath.path.isNotBlank()) { "binaryPath must not be blank" }
    }
}
