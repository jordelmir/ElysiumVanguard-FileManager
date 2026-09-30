package com.elysium.vanguard.core.runtime.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 56 — tests for [ToolchainKind] +
 * [ToolchainRegistry] + [ToolchainInstall].
 *
 * The registry probes standard locations
 * for each toolchain binary. The tests
 * pin:
 *
 *   - [ToolchainKind] enum has the 9
 *     expected kinds (8 toolchains + the
 *     LINUX_ARM64 catch-all).
 *   - [ToolchainRegistry.detectAt] returns
 *     a populated registry when a base
 *     directory contains a known binary.
 *   - The detector returns a registry with
 *     NO installs when the base directory
 *     has no matching binaries.
 *   - The detector picks the first match
 *     per toolchain (when multiple
 *     candidates exist, the first one
 *     wins).
 *   - [ToolchainRegistry.isInstalled] +
 *     [ToolchainRegistry.installFor] work
 *     correctly.
 *   - [withOverride] replaces the install
 *     for a toolchain.
 *   - [ToolchainInstall] init-block
 *     invariants (non-blank binaryPath).
 */
class ToolchainRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var toolchainDir: File

    @Before
    fun setUp() {
        toolchainDir = tempFolder.newFolder("toolchains")
    }

    @Test
    fun `ToolchainKind has the expected kinds`() {
        // The enum has 9 values: 8
        // toolchains + the LINUX_ARM64
        // catch-all.
        assertEquals(9, ToolchainKind.values().size)
        assertTrue(
            "expected RUST in values()",
            ToolchainKind.values().any { it == ToolchainKind.RUST }
        )
        assertTrue(
            "expected LINUX_ARM64 in values()",
            ToolchainKind.values().any { it == ToolchainKind.LINUX_ARM64 }
        )
    }

    @Test
    fun `ToolchainKind displayName is human-readable`() {
        assertEquals("Rust (cargo / rustc)", ToolchainKind.RUST.displayName)
        assertEquals("C / C++ (gcc / clang)", ToolchainKind.C_CPP.displayName)
        assertEquals("Node.js (node / npm / npx)", ToolchainKind.NODE.displayName)
    }

    @Test
    fun `ToolchainInstall rejects a blank binaryPath`() {
        try {
            ToolchainInstall(binaryPath = File(""))
            assert(false) { "expected IllegalArgumentException" }
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `detectAt returns an empty registry when no binaries are present`() {
        val registry = ToolchainRegistry.detectAt(listOf(toolchainDir))
        assertTrue(registry.installedKinds().isEmpty())
    }

    @Test
    fun `detectAt finds a Rust toolchain when cargo is present`() {
        createExecutable("cargo")
        val registry = ToolchainRegistry.detectAt(listOf(toolchainDir))
        assertTrue(registry.isInstalled(ToolchainKind.RUST))
        val install = registry.installFor(ToolchainKind.RUST)
        assertNotNull(install)
        assertEquals(
            File(toolchainDir, "cargo").absolutePath,
            install!!.binaryPath.absolutePath
        )
    }

    @Test
    fun `detectAt finds multiple toolchains when their binaries are present`() {
        createExecutable("cargo")
        createExecutable("go")
        createExecutable("python3")
        createExecutable("node")
        val registry = ToolchainRegistry.detectAt(listOf(toolchainDir))
        assertTrue(registry.isInstalled(ToolchainKind.RUST))
        assertTrue(registry.isInstalled(ToolchainKind.GO))
        assertTrue(registry.isInstalled(ToolchainKind.PYTHON))
        assertTrue(registry.isInstalled(ToolchainKind.NODE))
        assertFalse(registry.isInstalled(ToolchainKind.C_CPP))
    }

    @Test
    fun `detectAt picks the first match per toolchain when multiple candidates exist`() {
        // Create rustc and cargo; the
        // detector should pick whichever
        // comes first in the candidates
        // list.
        createExecutable("cargo")
        createExecutable("rustc")
        val registry = ToolchainRegistry.detectAt(listOf(toolchainDir))
        val install = registry.installFor(ToolchainKind.RUST)
        assertNotNull(install)
        // The candidates list for RUST
        // starts with "cargo" — the
        // detector should pick cargo.
        assertEquals("cargo", install!!.binaryPath.name)
    }

    @Test
    fun `isInstalled returns false for an un-installed toolchain`() {
        val registry = ToolchainRegistry()
        assertFalse(registry.isInstalled(ToolchainKind.RUST))
    }

    @Test
    fun `installFor returns null for an un-installed toolchain`() {
        val registry = ToolchainRegistry()
        assertNull(registry.installFor(ToolchainKind.RUST))
    }

    @Test
    fun `withOverride replaces the install for a toolchain`() {
        val registry = ToolchainRegistry()
        val customInstall = ToolchainInstall(
            binaryPath = File("/custom/cargo")
        )
        val newRegistry = registry.withOverride(ToolchainKind.RUST, customInstall)
        assertTrue(newRegistry.isInstalled(ToolchainKind.RUST))
        assertEquals("/custom/cargo", newRegistry.installFor(ToolchainKind.RUST)!!.binaryPath.path)
        // The original registry is
        // unchanged (data class
        // immutability).
        assertFalse(registry.isInstalled(ToolchainKind.RUST))
    }

    @Test
    fun `withOverride preserves other toolchains`() {
        val rustInstall = ToolchainInstall(binaryPath = File("/custom/cargo"))
        val goInstall = ToolchainInstall(binaryPath = File("/usr/bin/go"))
        val original = ToolchainRegistry(
            installed = mapOf(
                ToolchainKind.RUST to rustInstall,
                ToolchainKind.GO to goInstall
            )
        )
        val newInstall = ToolchainInstall(binaryPath = File("/new/cargo"))
        val newRegistry = original.withOverride(ToolchainKind.RUST, newInstall)
        // RUST is replaced.
        assertEquals("/new/cargo", newRegistry.installFor(ToolchainKind.RUST)!!.binaryPath.path)
        // GO is preserved.
        assertEquals("/usr/bin/go", newRegistry.installFor(ToolchainKind.GO)!!.binaryPath.path)
    }

    // --- helpers ---

    /**
     * Create an executable file with the
     * given name in the toolchain dir.
     * The file is empty; the detector
     * only checks `canExecute()`.
     */
    private fun createExecutable(name: String) {
        val file = File(toolchainDir, name)
        file.writeText("#!/bin/sh\n")
        file.setExecutable(true)
    }
}
