package com.elysium.vanguard.core.linux

/**
 * Phase 73 first half — the **Elysium ABI** enum.
 *
 * The ABI is the target instruction set + binary
 * format the package supports. Per the user's
 * vision: "Compatibilidad ABI documentada" —
 * the ABI is the cross-ABI compatibility gate.
 *
 * The ABI is **explicit** (every package declares
 * its ABI; the package manager refuses to install
 * a package with an incompatible ABI). The ABI
 * is **documented** (the canonical name is the
 * Android NDK ABI name).
 *
 * The ABI is **per-package**: a single package
 * may target a specific ABI (e.g. `ARM64`) or
 * `ANY` (the package is ABI-independent — a pure
 * script, a config file, etc.).
 */
enum class ElysiumAbi {
    /** 64-bit ARM (the dominant Android arch). */
    ARM64,

    /** 32-bit ARM (legacy). */
    ARM32,

    /** 64-bit x86 (desktops; some emulators). */
    X86_64,

    /** 32-bit x86 (legacy). */
    X86,

    /**
     * Architecture-agnostic. The package has no
     * native code (a script, a config file, a
     * data file). The package manager installs
     * `ANY` packages on every architecture.
     */
    ANY,
    ;

    companion object {
        /**
         * The canonical name (the Android NDK ABI
         * name). The name is the platform's
         * canonical form (the manifest is keyed by
         * the name; the package manager's
         * compatibility check is by name).
         */
        fun canonicalName(abi: ElysiumAbi): String = when (abi) {
            ARM64 -> "arm64-v8a"
            ARM32 -> "armeabi-v7a"
            X86_64 -> "x86_64"
            X86 -> "x86"
            ANY -> "any"
        }
    }
}
