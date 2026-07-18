package com.elysium.vanguard.core.runtime.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phase 53 — tests for [ExecutableInspector].
 *
 * The inspector is the runtime's format +
 * architecture detector. The tests pin:
 *
 *   - ELF detection (ARM64, ARM32, x86-64,
 *     x86, RISC-V 64).
 *   - PE detection (x86-64, x86, ARM64,
 *     ARM32) with the chained MZ → PE
 *     signature → COFF header parsing.
 *   - Script detection via shebang.
 *   - Java class detection.
 *   - WASM detection.
 *   - Edge cases: missing file, empty file,
 *     truncated ELF, malformed PE.
 */
class ExecutableInspectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var inspector: ExecutableInspector

    @Before
    fun setUp() {
        inspector = ExecutableInspector()
    }

    // --- ELF ---

    @Test
    fun `inspect detects an ARM64 ELF as ELF + ARM64`() {
        val file = tempFolder.newFile("hello-arm64")
        file.writeBytes(elfBytes(architectureElfMachine = 0xB7, eiClass = 2))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.ARM64, meta.architecture)
    }

    @Test
    fun `inspect detects an ARM32 ELF as ELF + ARM32`() {
        val file = tempFolder.newFile("hello-arm32")
        file.writeBytes(elfBytes(architectureElfMachine = 0x28, eiClass = 1))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.ARM32, meta.architecture)
    }

    @Test
    fun `inspect detects an x86-64 ELF as ELF + X86_64`() {
        val file = tempFolder.newFile("hello-x86-64")
        file.writeBytes(elfBytes(architectureElfMachine = 0x3E, eiClass = 2))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.X86_64, meta.architecture)
    }

    @Test
    fun `inspect detects an x86 ELF as ELF + X86`() {
        val file = tempFolder.newFile("hello-x86")
        file.writeBytes(elfBytes(architectureElfMachine = 0x03, eiClass = 1))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.X86, meta.architecture)
    }

    @Test
    fun `inspect detects a RISC-V 64 ELF as RISCV64`() {
        val file = tempFolder.newFile("hello-riscv64")
        file.writeBytes(elfBytes(architectureElfMachine = 0xF3, eiClass = 2))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.RISCV64, meta.architecture)
    }

    @Test
    fun `inspect returns UNKNOWN architecture for an ELF with an unknown e_machine`() {
        val file = tempFolder.newFile("hello-unknown-arch")
        file.writeBytes(elfBytes(architectureElfMachine = 0xFF, eiClass = 2))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.UNKNOWN, meta.architecture)
    }

    // --- PE ---

    @Test
    fun `inspect detects an x86-64 PE as PE + X86_64`() {
        val file = tempFolder.newFile("hello-x86-64.exe")
        file.writeBytes(peBytes(peMachine = 0x8664))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.PE, meta.format)
        assertEquals(Architecture.X86_64, meta.architecture)
    }

    @Test
    fun `inspect detects an x86 PE as PE + X86`() {
        val file = tempFolder.newFile("hello-x86.exe")
        file.writeBytes(peBytes(peMachine = 0x014C))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.PE, meta.format)
        assertEquals(Architecture.X86, meta.architecture)
    }

    @Test
    fun `inspect detects an ARM64 PE as PE + ARM64`() {
        val file = tempFolder.newFile("hello-arm64.exe")
        file.writeBytes(peBytes(peMachine = 0xAA64))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.PE, meta.format)
        assertEquals(Architecture.ARM64, meta.architecture)
    }

    @Test
    fun `inspect returns UNKNOWN for a PE with a missing PE signature`() {
        // The DOS MZ header is present but
        // the e_lfanew points to a non-PE
        // location.
        val bytes = ByteArray(0x120) // 0x100 + 0x20 = 288 bytes
        // MZ
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        // e_lfanew at 0x3C: 0x100 (a valid
        // offset)
        bytes[0x3C] = 0x00
        bytes[0x3D] = 0x01
        bytes[0x3E] = 0x00
        bytes[0x3F] = 0x00
        // At 0x100: "BAD!" (not "PE\0\0")
        bytes[0x100] = 'B'.code.toByte()
        bytes[0x101] = 'A'.code.toByte()
        bytes[0x102] = 'D'.code.toByte()
        bytes[0x103] = '!'.code.toByte()
        val file = tempFolder.newFile("broken.exe")
        file.writeBytes(bytes)
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.PE, meta.format)
        assertEquals(Architecture.UNKNOWN, meta.architecture)
    }

    // --- script ---

    @Test
    fun `inspect detects a shebang script`() {
        val file = tempFolder.newFile("hello.sh")
        file.writeText("#!/bin/sh\necho hello\n")
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.SCRIPT, meta.format)
        assertEquals("/bin/sh", meta.interpreter)
    }

    @Test
    fun `inspect detects a script with env shebang`() {
        val file = tempFolder.newFile("hello.py")
        file.writeText("#!/usr/bin/env python3\nprint('hi')\n")
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.SCRIPT, meta.format)
        assertEquals("/usr/bin/env", meta.interpreter)
    }

    // --- WASM ---

    @Test
    fun `inspect detects a WASM binary`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, // "\0asm"
            0x01, 0x00, 0x00, 0x00  // version 1
        ) + ByteArray(248)
        val file = tempFolder.newFile("module.wasm")
        file.writeBytes(bytes)
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.WASM, meta.format)
    }

    // --- Java class ---

    @Test
    fun `inspect detects a Java class file`() {
        val bytes = byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte() // 0xCAFEBABE
        ) + ByteArray(252)
        val file = tempFolder.newFile("Hello.class")
        file.writeBytes(bytes)
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.JAVA_CLASS, meta.format)
    }

    // --- edge cases ---

    @Test
    fun `inspect returns UNKNOWN for a missing file`() {
        val meta = inspector.inspect(File("/nonexistent/path/here"))
        assertEquals(ExecutableFormat.UNKNOWN, meta.format)
        assertTrue(
            "notes should mention missing file: ${meta.notes}",
            meta.notes.any { it.contains("does not exist", ignoreCase = true) }
        )
    }

    @Test
    fun `inspect returns UNKNOWN for an empty file`() {
        val file = tempFolder.newFile("empty")
        file.writeBytes(ByteArray(0))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.UNKNOWN, meta.format)
        assertTrue(
            "notes should mention empty file: ${meta.notes}",
            meta.notes.any { it.contains("empty", ignoreCase = true) }
        )
    }

    @Test
    fun `inspect returns UNKNOWN architecture for a truncated ELF header`() {
        val file = tempFolder.newFile("truncated")
        // ELF magic is present (4 bytes) but
        // the file is too short for the ELF
        // header. MagicDetector classifies
        // the format as ELF; the architecture
        // parser returns UNKNOWN because the
        // head is too short for the e_machine
        // field at offset 18.
        file.writeBytes(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02))
        val meta = inspector.inspect(file)
        assertEquals(ExecutableFormat.ELF, meta.format)
        assertEquals(Architecture.UNKNOWN, meta.architecture)
    }

    // --- helpers ---

    /**
     * Build a minimal ELF header with the
     * given e_machine value and EI_CLASS
     * (1=32-bit, 2=64-bit). The header is
     * little-endian.
     */
    private fun elfBytes(architectureElfMachine: Int, eiClass: Int): ByteArray {
        val bytes = ByteArray(64)
        // Magic
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        // EI_CLASS
        bytes[4] = eiClass.toByte()
        // EI_DATA = little-endian
        bytes[5] = 1
        // EI_VERSION
        bytes[6] = 1
        // EI_OSABI
        bytes[7] = 0
        // EI_ABIVERSION + padding (zero-
        // fill bytes 8..15)
        for (i in 8..15) bytes[i] = 0
        // e_type = ET_EXEC (2)
        bytes[16] = 0x02
        bytes[17] = 0x00
        // e_machine (LE)
        bytes[18] = (architectureElfMachine and 0xff).toByte()
        bytes[19] = ((architectureElfMachine shr 8) and 0xff).toByte()
        return bytes
    }

    /**
     * Build a minimal PE (DOS MZ + PE\0\0 +
     * COFF header) with the given Machine
     * value (little-endian). The e_lfanew
     * points to a fixed offset where the PE
     * signature starts.
     */
    private fun peBytes(peMachine: Int): ByteArray {
        val peOffset = 0x80
        val bytes = ByteArray(peOffset + 24)
        // DOS MZ magic
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        // e_lfanew at 0x3C: peOffset (LE)
        bytes[0x3C] = (peOffset and 0xff).toByte()
        bytes[0x3D] = ((peOffset shr 8) and 0xff).toByte()
        bytes[0x3E] = ((peOffset shr 16) and 0xff).toByte()
        bytes[0x3F] = ((peOffset shr 24) and 0xff).toByte()
        // PE signature
        bytes[peOffset] = 'P'.code.toByte()
        bytes[peOffset + 1] = 'E'.code.toByte()
        bytes[peOffset + 2] = 0x00
        bytes[peOffset + 3] = 0x00
        // COFF header: Machine (LE)
        bytes[peOffset + 4] = (peMachine and 0xff).toByte()
        bytes[peOffset + 5] = ((peMachine shr 8) and 0xff).toByte()
        return bytes
    }
}
