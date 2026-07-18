package com.elysium.vanguard.core.runtime.orchestrator

/**
 * Phase 53 — the runtime backends the
 * orchestrator can pick.
 *
 * The orchestrator's rule table (in
 * [RuntimeSelector]) maps a
 * (format, architecture, capabilities) tuple
 * to one of these runtimes. The actual
 * integration with each backend is a
 * follow-up phase:
 *
 * - [ANDROID_NATIVE] — `ProcessBuilder`
 *   on the host Android runtime. (Phase 36
 *   already wires
 *   [com.elysium.vanguard.core.runtime.runner.AndroidProcessLauncher]
 *   for this.)
 * - [LINUX_PROOT] — Phase 30's
 *   [com.elysium.vanguard.core.runtime.runner.LinuxProotSessionRunner]
 *   for Linux ARM64 / ARM32 ELFs in a proot
 *   distro.
 * - [WINE_BOX64] — Phase 54+ will wire this
 *   for x86-64 PE on ARM64 devices via
 *   Wine + Box64 translation.
 * - [WINE_FEX] — Phase 54+ alternative to
 *   Box64 (FEX-emu).
 * - [QEMU_VM] — Phase 23's
 *   [com.elysium.vanguard.core.runtime.windows.qemu.QemuWindowsVmBackend]
 *   for x86 / x86-64 ELFs that need full
 *   emulation.
 * - [REMOTE] — Phase 55+ for off-device
 *   execution.
 * - [REJECTED] — no runtime can run this
 *   binary; the orchestrator surfaces a
 *   typed reason.
 */
enum class RuntimeKind {
    ANDROID_NATIVE,
    LINUX_PROOT,
    WINE_BOX64,
    WINE_FEX,
    QEMU_VM,
    REMOTE,
    REJECTED
}

/**
 * Phase 53 — the device's runtime
 * capabilities.
 *
 * The selector consults the capabilities to
 * decide which [RuntimeKind] is actually
 * available. A Snapdragon Android device in
 * 2026 has `linuxProot` and `androidNative`
 * (always true on Android) but typically
 * lacks `qemuVm` for full x86 emulation
 * without a separate VM. A device that has
 * Wine + Box64 installed reports
 * `wineBox64 = true`. A device without any
 * binary translation reports `wineBox64 =
 * false, wineFex = false, qemuVm = false`,
 * and any x86 / x86-64 binary is rejected.
 */
data class RuntimeCapabilities(
    /** True iff the device can run a native
     *  Android process. Always true on
     *  Android. */
    val androidNative: Boolean = true,
    /** True iff the device has a proot-capable
     *  Linux distro installed. Set by
     *  [com.elysium.vanguard.core.runtime.distros.DistroManager]
     *  when a distro is registered. */
    val linuxProot: Boolean = false,
    /** True iff the device has Wine + Box64
     *  installed. Set when the Wine stack is
     *  detected on disk. */
    val wineBox64: Boolean = false,
    /** True iff the device has Wine + FEX
     *  installed. Set when the FEX stack is
     *  detected. */
    val wineFex: Boolean = false,
    /** True iff the device has QEMU with x86
     *  system-mode available. Set when the
     *  QEMU binary is detected. */
    val qemuVm: Boolean = false,
    /** True iff a remote execution backend
     *  is reachable. Set by the cloud sync
     *  subsystem when a remote host is
     *  authenticated. */
    val remote: Boolean = false
) {
    init {
        // androidNative is the only "always
        // on" capability; the rest are
        // device-specific and default to
        // false so a freshly constructed
        // capabilities object is
        // conservative.
    }
}

/**
 * Phase 53 — the selector's output.
 *
 * A [RuntimeChoice] is a (kind, reason) pair
 * the orchestrator turns into an
 * [ExecutionPlan]. The reason is a
 * human-readable string the user sees when
 * the orchestrator picks a runtime
 * ("selected WINE_BOX64 because the binary
 * is x86-64 PE and the device has Wine +
 * Box64").
 *
 * A [RuntimeChoice.Rejected] is a special
 * case where the orchestrator refuses to
 * plan a run; the reason is a typed
 * [RejectionReason] the user / runner can
 * act on.
 */
sealed class RuntimeChoice {
    abstract val kind: RuntimeKind
    abstract val reason: String

    data class Selected(
        override val kind: RuntimeKind,
        override val reason: String
    ) : RuntimeChoice()

    data class Rejected(
        val rejection: RejectionReason,
        override val reason: String
    ) : RuntimeChoice() {
        override val kind: RuntimeKind = RuntimeKind.REJECTED
    }
}

/**
 * Phase 53 — the typed reason a runtime
 * was rejected. The runner / UI surfaces
 * this to the user.
 */
sealed class RejectionReason {
    /** The inspector could not classify the
     *  binary. */
    object UnknownFormat : RejectionReason()
    /** The binary's architecture is not
     *  supported by any available runtime. */
    object UnsupportedArchitecture : RejectionReason()
    /** The binary requires a runtime the
     *  device does not have installed. */
    object MissingRuntime : RejectionReason()
    /** The binary's format is classified
     *  (e.g. PE) but no runtime on the device
     *  can run that format. */
    object NoCapableRuntime : RejectionReason()
}

/**
 * Phase 53 — the runtime selector.
 *
 * The selector is a stateless function over
 * an [ExecutableMetadata] + a
 * [RuntimeCapabilities]. The selection is
 * rule-based: each rule is a small `when`
 * over (format, architecture, capabilities).
 * A future phase can layer a learned model
 * on top of the rule table; Phase 53 ships
 * the table.
 *
 * ## Selection rules (in order)
 *
 * 1. **Native ARM64 ELF** on Android →
 *    [RuntimeKind.ANDROID_NATIVE]. The
 *    device can run it directly.
 * 2. **Native ARM64 ELF** without Android
 *    native (e.g. a server with no Android
 *    layer) → [RuntimeKind.LINUX_PROOT] if
 *    `linuxProot` capability is set.
 * 3. **Linux ELF on a non-native
 *    architecture** (e.g. x86 / x86-64 ELF
 *    on an ARM64 device) → [RuntimeKind.QEMU_VM]
 *    if `qemuVm` capability is set.
 * 4. **PE x86-64** on ARM64 →
 *    [RuntimeKind.WINE_BOX64] if
 *    `wineBox64` capability is set, else
 *    [RuntimeKind.WINE_FEX] if `wineFex` is
 *    set, else [RuntimeKind.QEMU_VM] as
 *    last-resort (full Windows VM).
 * 5. **PE x86** on ARM64 → same as PE
 *    x86-64, except Wine may need Box86
 *    instead of Box64. (Phase 54+ adds
 *    Box86.)
 * 6. **PE ARM64** on ARM64 → Wine native
 *    (Wine can run PE on the host
 *    architecture). [RuntimeKind.WINE_BOX64]
 *    if `wineBox64` is set.
 * 7. **WASM** → [RuntimeKind.ANDROID_NATIVE]
 *    (the host's WebAssembly runtime).
 * 8. **Mach-O** → rejected (the device is
 *    not macOS / iOS).
 * 9. **Script** → [RuntimeKind.ANDROID_NATIVE]
 *    with the interpreter from
 *    [ExecutableMetadata.interpreter]. The
 *    runner shells out to the interpreter
 *    on the host.
 * 10. **MSI** → [RuntimeKind.WINE_BOX64] if
 *     `wineBox64` is set (Wine's
 *     `msiexec`); else rejected.
 * 11. **Anything else** →
 *     [RuntimeChoice.Rejected].
 */
class RuntimeSelector {

    /**
     * Select the best [RuntimeKind] for
     * [metadata] given [capabilities].
     * Returns a [RuntimeChoice.Selected] or
     * [RuntimeChoice.Rejected].
     */
    fun select(
        metadata: ExecutableMetadata,
        capabilities: RuntimeCapabilities
    ): RuntimeChoice {
        if (!metadata.isRunnable) {
            return RuntimeChoice.Rejected(
                rejection = RejectionReason.UnknownFormat,
                reason = "binary format is UNKNOWN; cannot plan a run"
            )
        }
        return when (metadata.format) {
            ExecutableFormat.ELF -> selectElf(metadata, capabilities)
            ExecutableFormat.PE -> selectPe(metadata, capabilities)
            ExecutableFormat.MSI -> selectMsi(capabilities)
            ExecutableFormat.WASM -> selectWasm(capabilities)
            ExecutableFormat.MACHO -> RuntimeChoice.Rejected(
                rejection = RejectionReason.UnsupportedArchitecture,
                reason = "Mach-O binaries are not supported on Android"
            )
            ExecutableFormat.JAVA_CLASS -> RuntimeChoice.Selected(
                kind = RuntimeKind.ANDROID_NATIVE,
                reason = "Java class file (.class) is run via the host JVM"
            )
            ExecutableFormat.SCRIPT -> RuntimeChoice.Selected(
                kind = RuntimeKind.ANDROID_NATIVE,
                reason = "script: ${metadata.interpreter ?: "unknown interpreter"}"
            )
            ExecutableFormat.UNKNOWN -> RuntimeChoice.Rejected(
                rejection = RejectionReason.UnknownFormat,
                reason = "binary format is UNKNOWN; cannot plan a run"
            )
        }
    }

    private fun selectElf(
        metadata: ExecutableMetadata,
        capabilities: RuntimeCapabilities
    ): RuntimeChoice = when (metadata.architecture) {
        Architecture.ARM64 -> {
            if (capabilities.androidNative) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.ANDROID_NATIVE,
                    reason = "ARM64 ELF runs natively on the host Android"
                )
            } else if (capabilities.linuxProot) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.LINUX_PROOT,
                    reason = "ARM64 ELF runs in a proot-distro Linux"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.MissingRuntime,
                    reason = "ARM64 ELF needs Android native or a proot-distro; none available"
                )
            }
        }
        Architecture.ARM32 -> {
            if (capabilities.linuxProot) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.LINUX_PROOT,
                    reason = "ARM32 ELF runs in a 32-bit proot-distro"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.MissingRuntime,
                    reason = "ARM32 ELF needs a 32-bit proot-distro; none installed"
                )
            }
        }
        Architecture.X86_64 -> {
            if (capabilities.qemuVm) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.QEMU_VM,
                    reason = "x86-64 ELF needs full emulation via QEMU"
                )
            } else if (capabilities.remote) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.REMOTE,
                    reason = "x86-64 ELF sent to a remote execution backend"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.NoCapableRuntime,
                    reason = "x86-64 ELF needs QEMU or remote execution; neither available"
                )
            }
        }
        Architecture.X86 -> {
            if (capabilities.qemuVm) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.QEMU_VM,
                    reason = "x86 ELF needs full emulation via QEMU"
                )
            } else if (capabilities.remote) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.REMOTE,
                    reason = "x86 ELF sent to a remote execution backend"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.NoCapableRuntime,
                    reason = "x86 ELF needs QEMU or remote execution; neither available"
                )
            }
        }
        Architecture.RISCV64 -> RuntimeChoice.Rejected(
            rejection = RejectionReason.UnsupportedArchitecture,
            reason = "RISC-V is not yet supported by any runtime"
        )
        Architecture.UNKNOWN -> RuntimeChoice.Rejected(
            rejection = RejectionReason.UnsupportedArchitecture,
            reason = "ELF architecture is UNKNOWN; cannot select a runtime"
        )
    }

    private fun selectPe(
        metadata: ExecutableMetadata,
        capabilities: RuntimeCapabilities
    ): RuntimeChoice = when (metadata.architecture) {
        Architecture.X86_64 -> {
            if (capabilities.wineBox64) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.WINE_BOX64,
                    reason = "x86-64 PE on ARM64 via Wine + Box64 translation"
                )
            } else if (capabilities.wineFex) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.WINE_FEX,
                    reason = "x86-64 PE on ARM64 via Wine + FEX translation"
                )
            } else if (capabilities.qemuVm) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.QEMU_VM,
                    reason = "x86-64 PE on ARM64 via full Windows VM (QEMU)"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.NoCapableRuntime,
                    reason = "x86-64 PE needs Wine (Box64 / FEX) or QEMU; none available"
                )
            }
        }
        Architecture.X86 -> {
            if (capabilities.wineBox64) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.WINE_BOX64,
                    reason = "x86 PE on ARM64 via Wine + Box64 (Box86 path)"
                )
            } else if (capabilities.wineFex) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.WINE_FEX,
                    reason = "x86 PE on ARM64 via Wine + FEX"
                )
            } else if (capabilities.qemuVm) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.QEMU_VM,
                    reason = "x86 PE on ARM64 via full Windows VM (QEMU)"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.NoCapableRuntime,
                    reason = "x86 PE needs Wine or QEMU; none available"
                )
            }
        }
        Architecture.ARM64 -> {
            if (capabilities.wineBox64) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.WINE_BOX64,
                    reason = "ARM64 PE runs via Wine on the host ARM64 (no translation needed)"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.MissingRuntime,
                    reason = "ARM64 PE needs Wine; not installed"
                )
            }
        }
        Architecture.ARM32 -> {
            if (capabilities.wineBox64) {
                RuntimeChoice.Selected(
                    kind = RuntimeKind.WINE_BOX64,
                    reason = "ARM32 PE runs via Wine on ARM64 (with translation)"
                )
            } else {
                RuntimeChoice.Rejected(
                    rejection = RejectionReason.MissingRuntime,
                    reason = "ARM32 PE needs Wine; not installed"
                )
            }
        }
        Architecture.UNKNOWN -> RuntimeChoice.Rejected(
            rejection = RejectionReason.UnsupportedArchitecture,
            reason = "PE architecture is UNKNOWN; cannot select a runtime"
        )
        Architecture.RISCV64 -> RuntimeChoice.Rejected(
            rejection = RejectionReason.UnsupportedArchitecture,
            reason = "RISC-V PE is not yet supported by any runtime"
        )
    }

    private fun selectMsi(capabilities: RuntimeCapabilities): RuntimeChoice {
        if (capabilities.wineBox64) {
            return RuntimeChoice.Selected(
                kind = RuntimeKind.WINE_BOX64,
                reason = "MSI installer runs via Wine's msiexec"
            )
        }
        return RuntimeChoice.Rejected(
            rejection = RejectionReason.MissingRuntime,
            reason = "MSI needs Wine; not installed"
        )
    }

    private fun selectWasm(capabilities: RuntimeCapabilities): RuntimeChoice {
        // WASM runs on the host via the
        // Android WebAssembly runtime (the
        // system WebView, or a future
        // dedicated runtime). No translation
        // is needed.
        return RuntimeChoice.Selected(
            kind = RuntimeKind.ANDROID_NATIVE,
            reason = "WebAssembly runs on the host WebAssembly runtime"
        )
    }
}
