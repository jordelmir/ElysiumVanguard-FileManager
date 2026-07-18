package com.elysium.vanguard.core.runtime.orchestrator

/**
 * Phase 53 — the executable's format.
 *
 * The format is the file's *executable* type:
 * ELF (Linux), PE (Windows), Mach-O (macOS),
 * WASM, script (text with a shebang line),
 * MSI (Windows installer), etc. The format
 * is the input to the runtime selector: an
 * ELF ARM64 binary picks `ANDROID_NATIVE` or
 * `LINUX_PROOT`; a PE x86-64 binary picks
 * `WINE_BOX64`; a script picks the script's
 * interpreter runtime.
 *
 * The `UNKNOWN` value is the fallback for
 * files the inspector cannot classify. A
 * file with `UNKNOWN` format is rejected by
 * the runtime selector — the orchestrator
 * refuses to plan a run for an unknown
 * binary.
 */
enum class ExecutableFormat {
    UNKNOWN,
    /** ELF — Linux / Unix native binary
     *  (the dominant format on Android
     *  Linux distros). */
    ELF,
    /** PE — Windows native binary
     *  (the dominant format on Windows).
     *  Identified by the "MZ" DOS header
     *  followed by a PE signature. */
    PE,
    /** MSI — Windows installer package.
     *  Identified by the OLE compound
     *  document signature (D0 CF 11 E0). */
    MSI,
    /** Mach-O — macOS / iOS native binary. */
    MACHO,
    /** WebAssembly — portable bytecode
     *  format. Identified by the "asm" magic
     *  after a version number. */
    WASM,
    /** Script — a text file with a shebang
     *  line (`#!interpreter [args]`). The
     *  interpreter is in [ExecutableMetadata.interpreter]. */
    SCRIPT,
    /** Java class — `.class` file. Identified
     *  by the `0xCAFEBABE` magic. */
    JAVA_CLASS
}

/**
 * Phase 53 — the executable's target
 * instruction set architecture.
 *
 * The architecture is extracted from the
 * binary's header (ELF `e_machine` for ELF,
 * PE `Machine` for PE). The `UNKNOWN` value
 * is the fallback when the inspector cannot
 * determine the architecture (e.g. a script
 * with a `#!/usr/bin/env` shebang that
 * defers interpreter selection to PATH).
 *
 * The architecture is the second input to
 * the runtime selector: an ELF X86_64 binary
 * on an ARM64 device picks `QEMU_VM`; an
 * ELF ARM64 binary on the same device picks
 * `ANDROID_NATIVE` or `LINUX_PROOT`.
 */
enum class Architecture {
    UNKNOWN,
    /** 32-bit ARM (legacy Android, RPi). */
    ARM32,
    /** 64-bit ARM (modern Android, M1+,
     *  Snapdragon 8xx). */
    ARM64,
    /** 32-bit x86 (legacy Windows / Linux). */
    X86,
    /** 64-bit x86 (modern Windows / Linux
     *  / Steam Deck). */
    X86_64,
    /** 64-bit RISC-V (emerging; not yet
     *  supported by the runtime). */
    RISCV64
}

/**
 * Phase 53 — the inspector's output.
 *
 * The metadata is a stable, serialisable
 * description of a file. A user can
 * pre-compute the metadata (the inspector
 * runs once when the app is installed) and
 * cache it on the [com.elysium.vanguard.core.runtime.capsule.ApplicationCapsule]
 * (Phase 14).
 *
 * The metadata is intentionally minimal —
 * format, architecture, interpreter (for
 * scripts), and a list of detected
 * dependencies (a future phase will
 * populate this from the binary's
 * dynamic section). The runtime selector
 * consumes the format + architecture; the
 * interpreter is consumed by the runner.
 */
data class ExecutableMetadata(
    val format: ExecutableFormat,
    val architecture: Architecture,
    val interpreter: String? = null,
    val detectedSizeBytes: Long = 0L,
    val notes: List<String> = emptyList()
) {
    init {
        require(detectedSizeBytes >= 0L) { "detectedSizeBytes must be non-negative" }
    }

    /**
     * True iff the metadata is usable by the
     * runtime selector. A metadata with
     * `UNKNOWN` format is not.
     */
    val isRunnable: Boolean
        get() = format != ExecutableFormat.UNKNOWN

    override fun toString(): String =
        "ExecutableMetadata(format=$format, architecture=$architecture, " +
            "interpreter=${interpreter ?: "none"}, " +
            "detectedSizeBytes=$detectedSizeBytes, notes=${notes.size})"
}
