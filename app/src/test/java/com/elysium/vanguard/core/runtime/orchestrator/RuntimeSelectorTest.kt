package com.elysium.vanguard.core.runtime.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 53 — tests for [RuntimeSelector].
 *
 * The selector is a rule-based mapper from
 * (format, architecture, capabilities) to a
 * [RuntimeKind]. The tests pin every rule
 * path:
 *
 *   - Native ARM64 ELF → ANDROID_NATIVE.
 *   - ARM64 ELF without Android native →
 *     LINUX_PROOT.
 *   - ARM32 ELF → LINUX_PROOT (Android
 *     cannot run 32-bit ARM apps on modern
 *     devices).
 *   - x86-64 ELF → QEMU_VM (or REMOTE if
 *     configured; or Rejected).
 *   - x86 ELF → QEMU_VM (or REMOTE).
 *   - RISC-V 64 ELF → Rejected (no
 *     runtime supports it yet).
 *   - PE x86-64 → WINE_BOX64 (or WINE_FEX,
 *     or QEMU_VM as last resort).
 *   - PE x86 → same as PE x86-64.
 *   - PE ARM64 → WINE_BOX64 (Wine runs on
 *     the host architecture).
 *   - MSI → WINE_BOX64.
 *   - WASM → ANDROID_NATIVE.
 *   - Mach-O → Rejected.
 *   - Script → ANDROID_NATIVE.
 *   - Unknown format → Rejected.
 */
class RuntimeSelectorTest {

    private val selector = RuntimeSelector()
    private val defaultCapabilities = RuntimeCapabilities(
        androidNative = true,
        linuxProot = true,
        wineBox64 = true,
        wineFex = false,
        qemuVm = true,
        remote = false
    )

    private fun meta(
        format: ExecutableFormat,
        architecture: Architecture,
        interpreter: String? = null
    ) = ExecutableMetadata(
        format = format,
        architecture = architecture,
        interpreter = interpreter
    )

    // --- ELF ---

    @Test
    fun `native ARM64 ELF on Android picks ANDROID_NATIVE`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.ARM64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.ANDROID_NATIVE, sel.kind)
    }

    @Test
    fun `ARM64 ELF without Android native picks LINUX_PROOT`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.ARM64),
            defaultCapabilities.copy(androidNative = false)
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.LINUX_PROOT, sel.kind)
    }

    @Test
    fun `ARM32 ELF picks LINUX_PROOT`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.ARM32),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.LINUX_PROOT, sel.kind)
    }

    @Test
    fun `ARM32 ELF without linuxProot is Rejected`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.ARM32),
            defaultCapabilities.copy(linuxProot = false)
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.MissingRuntime, rej.rejection)
    }

    @Test
    fun `x86-64 ELF picks QEMU_VM when qemuVm capability is set`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.X86_64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.QEMU_VM, sel.kind)
    }

    @Test
    fun `x86-64 ELF picks REMOTE when only remote is available`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.X86_64),
            defaultCapabilities.copy(qemuVm = false, remote = true)
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.REMOTE, sel.kind)
    }

    @Test
    fun `x86-64 ELF is Rejected when neither qemuVm nor remote is available`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.X86_64),
            defaultCapabilities.copy(qemuVm = false, remote = false)
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.NoCapableRuntime, rej.rejection)
    }

    @Test
    fun `RISC-V 64 ELF is Rejected (no runtime supports it yet)`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.RISCV64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.UnsupportedArchitecture, rej.rejection)
    }

    @Test
    fun `ELF with UNKNOWN architecture is Rejected`() {
        val choice = selector.select(
            meta(ExecutableFormat.ELF, Architecture.UNKNOWN),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.UnsupportedArchitecture, rej.rejection)
    }

    // --- PE ---

    @Test
    fun `x86-64 PE picks WINE_BOX64 when wineBox64 capability is set`() {
        val choice = selector.select(
            meta(ExecutableFormat.PE, Architecture.X86_64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.WINE_BOX64, sel.kind)
    }

    @Test
    fun `x86-64 PE picks WINE_FEX when only wineFex is available`() {
        val choice = selector.select(
            meta(ExecutableFormat.PE, Architecture.X86_64),
            defaultCapabilities.copy(wineBox64 = false, wineFex = true)
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.WINE_FEX, sel.kind)
    }

    @Test
    fun `x86-64 PE picks QEMU_VM as last resort when no Wine is available`() {
        val choice = selector.select(
            meta(ExecutableFormat.PE, Architecture.X86_64),
            defaultCapabilities.copy(wineBox64 = false, wineFex = false, qemuVm = true)
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.QEMU_VM, sel.kind)
    }

    @Test
    fun `x86 PE picks WINE_BOX64`() {
        val choice = selector.select(
            meta(ExecutableFormat.PE, Architecture.X86),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.WINE_BOX64, sel.kind)
    }

    @Test
    fun `ARM64 PE picks WINE_BOX64 (Wine on the host arch)`() {
        val choice = selector.select(
            meta(ExecutableFormat.PE, Architecture.ARM64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.WINE_BOX64, sel.kind)
    }

    @Test
    fun `PE is Rejected when no Wine or QEMU is available`() {
        val choice = selector.select(
            meta(ExecutableFormat.PE, Architecture.X86_64),
            defaultCapabilities.copy(wineBox64 = false, wineFex = false, qemuVm = false)
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.NoCapableRuntime, rej.rejection)
    }

    // --- MSI ---

    @Test
    fun `MSI picks WINE_BOX64 (Wine's msiexec)`() {
        val choice = selector.select(
            meta(ExecutableFormat.MSI, Architecture.X86_64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.WINE_BOX64, sel.kind)
    }

    @Test
    fun `MSI is Rejected when no Wine is available`() {
        val choice = selector.select(
            meta(ExecutableFormat.MSI, Architecture.X86_64),
            defaultCapabilities.copy(wineBox64 = false, wineFex = false)
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.MissingRuntime, rej.rejection)
    }

    // --- WASM ---

    @Test
    fun `WASM picks ANDROID_NATIVE (host WebAssembly runtime)`() {
        val choice = selector.select(
            meta(ExecutableFormat.WASM, Architecture.UNKNOWN),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.ANDROID_NATIVE, sel.kind)
    }

    // --- Mach-O ---

    @Test
    fun `Mach-O is Rejected (Android is not macOS)`() {
        val choice = selector.select(
            meta(ExecutableFormat.MACHO, Architecture.ARM64),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.UnsupportedArchitecture, rej.rejection)
    }

    // --- Script ---

    @Test
    fun `script picks ANDROID_NATIVE (interpreter on the host)`() {
        val choice = selector.select(
            meta(ExecutableFormat.SCRIPT, Architecture.UNKNOWN, interpreter = "/bin/sh"),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Selected)
        val sel = choice as RuntimeChoice.Selected
        assertEquals(RuntimeKind.ANDROID_NATIVE, sel.kind)
        assertTrue(
            "reason should mention the interpreter: ${sel.reason}",
            sel.reason.contains("interpreter", ignoreCase = true) ||
                sel.reason.contains("/bin/sh")
        )
    }

    // --- Unknown ---

    @Test
    fun `UNKNOWN format is Rejected`() {
        val choice = selector.select(
            meta(ExecutableFormat.UNKNOWN, Architecture.UNKNOWN),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.UnknownFormat, rej.rejection)
    }

    @Test
    fun `metadata that is not runnable is Rejected with UnknownFormat`() {
        val choice = selector.select(
            ExecutableMetadata(format = ExecutableFormat.UNKNOWN, architecture = Architecture.UNKNOWN),
            defaultCapabilities
        )
        assertTrue(choice is RuntimeChoice.Rejected)
        val rej = choice as RuntimeChoice.Rejected
        assertEquals(RejectionReason.UnknownFormat, rej.rejection)
    }
}
