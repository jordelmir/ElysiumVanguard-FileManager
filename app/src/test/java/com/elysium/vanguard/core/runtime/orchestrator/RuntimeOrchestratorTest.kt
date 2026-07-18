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
 * Phase 53 — tests for [RuntimeOrchestrator].
 *
 * The orchestrator is the user-facing entry
 * point that wires the inspector + the
 * selector into a single API. The tests
 * pin:
 *
 *   - planExecution on a real file returns
 *     an ExecutionPlan.Ready with the
 *     correct manifest.
 *   - planExecution on a rejected binary
 *     returns ExecutionPlan.Rejected with
 *     the typed reason.
 *   - planFromManifest returns Ready with
 *     the user's manifest unchanged.
 *   - inspect returns the raw metadata.
 *   - The plan's selectionReason is
 *     human-readable.
 *   - workspaceId + commandLineArgs +
 *     environmentVariables are threaded
 *     through to the manifest.
 */
class RuntimeOrchestratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var orchestrator: RuntimeOrchestrator
    private val capabilities = RuntimeCapabilities(
        androidNative = true,
        linuxProot = true,
        wineBox64 = true,
        wineFex = false,
        qemuVm = true,
        remote = false
    )

    @Before
    fun setUp() {
        orchestrator = RuntimeOrchestrator()
    }

    @Test
    fun `planExecution on a native ARM64 ELF returns Ready with ANDROID_NATIVE`() {
        val file = tempFolder.newFile("hello")
        file.writeBytes(elfBytes(architectureElfMachine = 0xB7, eiClass = 2))
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities,
            workspaceId = "ws-1"
        )
        assertTrue(plan is ExecutionPlan.Ready)
        val manifest = (plan as ExecutionPlan.Ready).manifest
        assertEquals(RuntimeKind.ANDROID_NATIVE, manifest.runtime)
        assertEquals("ws-1", manifest.workspaceId)
        assertTrue(
            "selectionReason should be human-readable: ${manifest.selectionReason}",
            manifest.selectionReason.isNotBlank()
        )
    }

    @Test
    fun `planExecution on an x86-64 PE returns Ready with WINE_BOX64`() {
        val file = tempFolder.newFile("setup.exe")
        file.writeBytes(peBytes(peMachine = 0x8664))
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities
        )
        assertTrue(plan is ExecutionPlan.Ready)
        val manifest = (plan as ExecutionPlan.Ready).manifest
        assertEquals(RuntimeKind.WINE_BOX64, manifest.runtime)
    }

    @Test
    fun `planExecution on a shebang script returns Ready with ANDROID_NATIVE`() {
        val file = tempFolder.newFile("hello.sh")
        file.writeText("#!/bin/sh\necho hi\n")
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities
        )
        assertTrue(plan is ExecutionPlan.Ready)
        val manifest = (plan as ExecutionPlan.Ready).manifest
        assertEquals(RuntimeKind.ANDROID_NATIVE, manifest.runtime)
        assertEquals("/bin/sh", manifest.interpreter)
    }

    @Test
    fun `planExecution on an unclassifiable file returns Rejected`() {
        val file = tempFolder.newFile("mystery")
        file.writeBytes(byteArrayOf(0x12, 0x34, 0x56, 0x78))
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities
        )
        assertTrue(plan is ExecutionPlan.Rejected)
        val rej = plan as ExecutionPlan.Rejected
        assertEquals(RejectionReason.UnknownFormat, rej.rejection)
        assertTrue(rej.reason.isNotBlank())
    }

    @Test
    fun `planExecution on a missing file returns Rejected`() {
        val plan = orchestrator.planExecution(
            binaryPath = "/nonexistent/path/here",
            capabilities = capabilities
        )
        assertTrue(plan is ExecutionPlan.Rejected)
        val rej = plan as ExecutionPlan.Rejected
        assertEquals(RejectionReason.UnknownFormat, rej.rejection)
    }

    @Test
    fun `planExecution threads commandLineArgs and environmentVariables through to the manifest`() {
        val file = tempFolder.newFile("hello")
        file.writeBytes(elfBytes(architectureElfMachine = 0xB7, eiClass = 2))
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities,
            commandLineArgs = listOf("--foo", "bar"),
            environmentVariables = mapOf("LANG" to "C.UTF-8")
        )
        assertTrue(plan is ExecutionPlan.Ready)
        val manifest = (plan as ExecutionPlan.Ready).manifest
        assertEquals(listOf("--foo", "bar"), manifest.commandLineArgs)
        assertEquals(mapOf("LANG" to "C.UTF-8"), manifest.environmentVariables)
    }

    @Test
    fun `planExecution on x86-64 ELF picks QEMU_VM`() {
        val file = tempFolder.newFile("legacy-binary")
        file.writeBytes(elfBytes(architectureElfMachine = 0x3E, eiClass = 2))
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities
        )
        assertTrue(plan is ExecutionPlan.Ready)
        val manifest = (plan as ExecutionPlan.Ready).manifest
        assertEquals(RuntimeKind.QEMU_VM, manifest.runtime)
    }

    @Test
    fun `planExecution on x86-64 ELF with no QEMU and no remote is Rejected`() {
        val file = tempFolder.newFile("legacy-binary")
        file.writeBytes(elfBytes(architectureElfMachine = 0x3E, eiClass = 2))
        val plan = orchestrator.planExecution(
            binaryPath = file.absolutePath,
            capabilities = capabilities.copy(qemuVm = false, remote = false)
        )
        assertTrue(plan is ExecutionPlan.Rejected)
        val rej = plan as ExecutionPlan.Rejected
        assertEquals(RejectionReason.NoCapableRuntime, rej.rejection)
    }

    @Test
    fun `planFromManifest returns Ready with the user's manifest unchanged`() {
        val userManifest = ExecutionManifest(
            binaryPath = "/some/path",
            runtime = RuntimeKind.LINUX_PROOT,
            workspaceId = "ws-2",
            commandLineArgs = listOf("--verbose"),
            environmentVariables = mapOf("DEBUG" to "1"),
            selectionReason = "user chose proot"
        )
        val plan = orchestrator.planFromManifest(userManifest)
        assertTrue(plan is ExecutionPlan.Ready)
        assertEquals(userManifest, (plan as ExecutionPlan.Ready).manifest)
    }

    @Test
    fun `inspect returns the raw metadata`() {
        val file = tempFolder.newFile("hello")
        file.writeBytes(elfBytes(architectureElfMachine = 0xB7, eiClass = 2))
        val metadata = orchestrator.inspect(file.absolutePath)
        assertEquals(ExecutableFormat.ELF, metadata.format)
        assertEquals(Architecture.ARM64, metadata.architecture)
    }

    @Test
    fun `inspect on a missing file returns UNKNOWN metadata`() {
        val metadata = orchestrator.inspect("/nonexistent/path")
        assertEquals(ExecutableFormat.UNKNOWN, metadata.format)
    }

    // --- helpers ---

    private fun elfBytes(architectureElfMachine: Int, eiClass: Int): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = eiClass.toByte()
        bytes[5] = 1 // LE
        bytes[6] = 1
        bytes[16] = 0x02
        bytes[17] = 0x00
        bytes[18] = (architectureElfMachine and 0xff).toByte()
        bytes[19] = ((architectureElfMachine shr 8) and 0xff).toByte()
        return bytes
    }

    private fun peBytes(peMachine: Int): ByteArray {
        val peOffset = 0x80
        val bytes = ByteArray(peOffset + 24)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3C] = (peOffset and 0xff).toByte()
        bytes[0x3D] = ((peOffset shr 8) and 0xff).toByte()
        bytes[0x3E] = ((peOffset shr 16) and 0xff).toByte()
        bytes[0x3F] = ((peOffset shr 24) and 0xff).toByte()
        bytes[peOffset] = 'P'.code.toByte()
        bytes[peOffset + 1] = 'E'.code.toByte()
        bytes[peOffset + 2] = 0x00
        bytes[peOffset + 3] = 0x00
        bytes[peOffset + 4] = (peMachine and 0xff).toByte()
        bytes[peOffset + 5] = ((peMachine shr 8) and 0xff).toByte()
        return bytes
    }
}
