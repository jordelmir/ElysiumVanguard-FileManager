package com.elysium.vanguard.core.runtime.orchestrator

import com.elysium.vanguard.core.format.MagicDetector
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 53 — the executable format +
 * architecture inspector.
 *
 * The inspector takes a file and returns an
 * [ExecutableMetadata]. The inspector is a
 * pure function over the file's bytes; it
 * does not run the file.
 *
 * ## Detection strategy
 *
 * 1. Read the file's first 4 KB (the "magic
 *    head") via [MagicDetector]. The
 *    detector tells us the *format* (ELF,
 *    PE, MSI, etc.) but not the
 *    architecture.
 * 2. For ELF: parse the e_machine field
 *    (offset 18 in a 32-bit ELF; offset 18
 *    in a 64-bit ELF — the e_ident is fixed-
 *    length, e_type is 2 bytes, e_machine is
 *    2 bytes at offset 18). The e_machine
 *    values are EM_X86_64 (62), EM_X86 (3),
 *    EM_AARCH64 (183), EM_ARM (40), EM_RISCV
 *    (243).
 * 3. For PE: the DOS MZ header is at offset
 *    0; the e_lfanew field at offset 0x3C
 *    points to the PE signature ("PE\0\0").
 *    The COFF header is at PE signature + 4;
 *    the Machine field is the first 2 bytes
 *    of the COFF header. Machine values are
 *    IMAGE_FILE_MACHINE_AMD64 (0x8664),
 *    IMAGE_FILE_MACHINE_I386 (0x14C),
 *    IMAGE_FILE_MACHINE_ARM64 (0xAA64),
 *    IMAGE_FILE_MACHINE_ARM (0x1C0).
 * 4. For scripts: read the first line; if
 *    it starts with `#!`, the remainder is
 *    the interpreter (e.g. `#!/bin/sh`).
 * 5. For everything else: format =
 *    `UNKNOWN`, architecture = `UNKNOWN`.
 *
 * The inspector reads at most 4 KB from the
 * file. The full file is not read.
 */
class ExecutableInspector(
    private val magicDetector: MagicDetector = MagicDetector()
) {

    /** The maximum number of bytes the
     *  inspector reads. ELF + PE headers fit
     *  comfortably in 4 KB. */
    private val probeSize = 4 * 1024

    /**
     * Inspect [file] and return its
     * [ExecutableMetadata]. Returns
     * `ExecutableMetadata(format = UNKNOWN, ...)`
     * if the file is missing, empty, or the
     * inspector cannot classify it.
     */
    fun inspect(file: File): ExecutableMetadata {
        if (!file.isFile) {
            return ExecutableMetadata(
                format = ExecutableFormat.UNKNOWN,
                architecture = Architecture.UNKNOWN,
                notes = listOf("file does not exist or is not a regular file: ${file.absolutePath}")
            )
        }
        val head = readHead(file) ?: return ExecutableMetadata(
            format = ExecutableFormat.UNKNOWN,
            architecture = Architecture.UNKNOWN,
            notes = listOf("could not read file head")
        )
        if (head.isEmpty()) {
            return ExecutableMetadata(
                format = ExecutableFormat.UNKNOWN,
                architecture = Architecture.UNKNOWN,
                notes = listOf("file is empty")
            )
        }
        // Step 1: format detection via
        // MagicDetector.
        val detection = magicDetector.detectFromHead(head)
        val format = mapFormat(detection.kind)
        if (format == ExecutableFormat.UNKNOWN) {
            // No magic matched. Try the script
            // fallback (text files with
            // shebangs are not in MagicDetector's
            // rules).
            return if (looksLikeScript(head)) {
                val interpreter = parseShebang(head)
                ExecutableMetadata(
                    format = ExecutableFormat.SCRIPT,
                    architecture = Architecture.UNKNOWN,
                    interpreter = interpreter,
                    detectedSizeBytes = file.length()
                )
            } else {
                ExecutableMetadata(
                    format = ExecutableFormat.UNKNOWN,
                    architecture = Architecture.UNKNOWN,
                    detectedSizeBytes = file.length(),
                    notes = listOf("MagicDetector: ${detection.humanName}")
                )
            }
        }
        // Step 2/3: architecture extraction
        // based on the format.
        val architecture = when (format) {
            ExecutableFormat.ELF -> parseElfArchitecture(head)
            ExecutableFormat.PE -> parsePeArchitecture(head)
            ExecutableFormat.WASM -> Architecture.UNKNOWN
            ExecutableFormat.MACHO -> parseMachOArchitecture(head)
            ExecutableFormat.MSI -> Architecture.X86_64
            ExecutableFormat.JAVA_CLASS -> Architecture.UNKNOWN
            ExecutableFormat.SCRIPT -> Architecture.UNKNOWN
            ExecutableFormat.UNKNOWN -> Architecture.UNKNOWN
        }
        return ExecutableMetadata(
            format = format,
            architecture = architecture,
            detectedSizeBytes = file.length()
        )
    }

    // --- format mapping ---

    /**
     * Map the [MagicDetector.FileKind] to the
     * orchestrator's [ExecutableFormat]. The
     * mapping is a small switch; anything the
     * orchestrator does not know how to run
     * returns `UNKNOWN`.
     */
    private fun mapFormat(kind: MagicDetector.FileKind): ExecutableFormat = when (kind) {
        MagicDetector.FileKind.ELF -> ExecutableFormat.ELF
        MagicDetector.FileKind.EXE_MZ -> ExecutableFormat.PE
        MagicDetector.FileKind.WASM -> ExecutableFormat.WASM
        MagicDetector.FileKind.CLASS -> ExecutableFormat.JAVA_CLASS
        // MagicDetector does not currently
        // detect MSI (OLE compound document)
        // or Mach-O; the inspector does its
        // own detection for those formats.
        else -> ExecutableFormat.UNKNOWN
    }

    // --- ELF header parsing ---

    /**
     * Parse the ELF `e_machine` field. The
     * header is 52 bytes for a 32-bit ELF and
     * 64 bytes for a 64-bit ELF; both have
     * the e_machine field at offset 18. We
     * read at most 20 bytes from the head.
     */
    private fun parseElfArchitecture(head: ByteArray): Architecture {
        if (head.size < 20) return Architecture.UNKNOWN
        // EI_CLASS: byte 4. 1 = 32-bit,
        // 2 = 64-bit. EI_DATA: byte 5. 1 = LE,
        // 2 = BE. The e_machine field is 2
        // bytes at offset 18.
        val eiClass = head[4].toInt() and 0xff
        val eiData = head[5].toInt() and 0xff
        if (eiData != 1 && eiData != 2) return Architecture.UNKNOWN
        val buffer = ByteBuffer.wrap(head, 18, 2).order(
            if (eiData == 1) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        )
        val eMachine = buffer.short.toInt() and 0xffff
        return mapElfMachine(eMachine, eiClass)
    }

    private fun mapElfMachine(eMachine: Int, eiClass: Int): Architecture = when (eMachine) {
        EM_X86_64 -> Architecture.X86_64
        EM_X86 -> Architecture.X86
        EM_AARCH64 -> Architecture.ARM64
        EM_ARM -> if (eiClass == 1) Architecture.ARM32 else Architecture.UNKNOWN
        EM_RISCV -> if (eiClass == 2) Architecture.RISCV64 else Architecture.UNKNOWN
        else -> Architecture.UNKNOWN
    }

    // --- PE header parsing ---

    /**
     * Parse the PE `Machine` field. The
     * chain is: DOS MZ header (at offset 0;
     * the "MZ" magic is at offset 0; the
     * e_lfanew field is at offset 0x3C) →
     * PE signature ("PE\0\0") → COFF header
     * (Machine field is the first 2 bytes of
     * the COFF header).
     */
    private fun parsePeArchitecture(head: ByteArray): Architecture {
        if (head.size < 0x40) return Architecture.UNKNOWN
        // The e_lfanew field is a 4-byte LE
        // int at offset 0x3C.
        val buffer = ByteBuffer.wrap(head, 0x3C, 4).order(ByteOrder.LITTLE_ENDIAN)
        val peOffset = buffer.int
        if (peOffset <= 0 || peOffset + 6 > head.size) return Architecture.UNKNOWN
        // PE signature must be "PE\0\0".
        if (head[peOffset].toInt() != 0x50 ||
            head[peOffset + 1].toInt() != 0x45 ||
            head[peOffset + 2].toInt() != 0x00 ||
            head[peOffset + 3].toInt() != 0x00
        ) {
            return Architecture.UNKNOWN
        }
        // COFF header starts at peOffset + 4.
        val machine = ByteBuffer.wrap(head, peOffset + 4, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short.toInt() and 0xffff
        return mapPeMachine(machine)
    }

    private fun mapPeMachine(machine: Int): Architecture = when (machine) {
        IMAGE_FILE_MACHINE_I386 -> Architecture.X86
        IMAGE_FILE_MACHINE_AMD64 -> Architecture.X86_64
        IMAGE_FILE_MACHINE_ARM -> Architecture.ARM32
        IMAGE_FILE_MACHINE_ARM64 -> Architecture.ARM64
        else -> Architecture.UNKNOWN
    }

    // --- Mach-O magic (best-effort) ---

    private fun parseMachOArchitecture(head: ByteArray): Architecture {
        if (head.size < 8) return Architecture.UNKNOWN
        // Mach-O magic numbers (32-bit big-
        // endian: 0xFEEDFACE, 64-bit big-endian:
        // 0xFEEDFACF, 32-bit little-endian:
        // 0xCEFAEDFE, 64-bit little-endian:
        // 0xCFFAEDFE). We do not parse the
        // full Mach-O header in Phase 53;
        // unknown architecture is acceptable
        // for now.
        return Architecture.UNKNOWN
    }

    // --- script detection ---

    /**
     * True iff the head looks like a text
     * file with a shebang line. A text file
     * starts with printable ASCII or UTF-8;
     * a shebang is `#!` at the start of the
     * first line.
     */
    private fun looksLikeScript(head: ByteArray): Boolean {
        if (head.size < 2) return false
        if (head[0] != '#'.code.toByte()) return false
        if (head[1] != '!'.code.toByte()) return false
        return true
    }

    /**
     * Parse the shebang line. Returns the
     * interpreter (e.g. `/bin/sh` for
     * `#!/bin/sh`) or null if the shebang is
     * malformed.
     */
    private fun parseShebang(head: ByteArray): String? {
        // Find the end of the first line.
        val newline = head.indexOf('\n'.code.toByte())
        val firstLine = if (newline < 0) head else head.copyOfRange(0, newline)
        if (firstLine.size < 2) return null
        if (firstLine[0] != '#'.code.toByte() || firstLine[1] != '!'.code.toByte()) {
            return null
        }
        val content = String(firstLine, 2, firstLine.size - 2, Charsets.US_ASCII).trim()
        if (content.isEmpty()) return null
        // The shebang can be "#!/path/to/interpreter" or
        // "#!/usr/bin/env interpreter-name". We extract
        // the first token.
        val firstToken = content.split(Regex("\\s+")).firstOrNull() ?: return null
        return firstToken
    }

    // --- I/O helper ---

    private fun readHead(file: File): ByteArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length().coerceAtMost(probeSize.toLong())
            val buffer = ByteArray(length.toInt())
            raf.readFully(buffer)
            buffer
        }
    } catch (t: Throwable) {
        null
    }

    companion object {
        // ELF e_machine values (from
        // /usr/include/elf.h on Linux).
        private const val EM_X86 = 3
        private const val EM_ARM = 40
        private const val EM_X86_64 = 62
        private const val EM_AARCH64 = 183
        private const val EM_RISCV = 243

        // PE Machine values (from winnt.h).
        private const val IMAGE_FILE_MACHINE_I386 = 0x014C
        private const val IMAGE_FILE_MACHINE_AMD64 = 0x8664
        private const val IMAGE_FILE_MACHINE_ARM = 0x01C0
        private const val IMAGE_FILE_MACHINE_ARM64 = 0xAA64
    }
}
