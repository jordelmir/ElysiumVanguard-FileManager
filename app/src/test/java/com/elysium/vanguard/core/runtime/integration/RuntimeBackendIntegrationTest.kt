package com.elysium.vanguard.core.runtime.integration

import com.elysium.vanguard.core.runtime.domain.BackendKind
import com.elysium.vanguard.core.runtime.domain.CapabilityProfile
import com.elysium.vanguard.core.runtime.domain.ExitReport
import com.elysium.vanguard.core.runtime.domain.RuntimeBackend
import com.elysium.vanguard.core.runtime.domain.RuntimeCapability
import com.elysium.vanguard.core.runtime.domain.RuntimeId
import com.elysium.vanguard.core.runtime.domain.RuntimeSpec
import com.elysium.vanguard.core.runtime.domain.RuntimeSession
import com.elysium.vanguard.core.runtime.domain.SessionId
import com.elysium.vanguard.core.runtime.domain.SessionSpec
import com.elysium.vanguard.core.runtime.domain.SessionState
import com.elysium.vanguard.core.runtime.domain.TerminalSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Integration tests for the PRoot + PTY + DNS pipeline.
 *
 * These tests verify the contract between RuntimeBackend, RuntimeSession,
 * and the domain model without requiring a real device or rootfs.
 */
class RuntimeBackendIntegrationTest {

    @Test
    fun `ProotRuntimeBackend reports correct runtime spec`() {
        val backend = createMockBackend()
        assertEquals("proot-linux", backend.runtime.id.value)
        assertEquals(BackendKind.PROOT_LINUX, backend.runtime.backend)
        assertTrue(backend.runtime.displayName.contains("PRoot"))
    }

    @Test
    fun `ProotRuntimeBackend capability profile includes PTY`() {
        val backend = createMockBackend()
        val caps = backend.capabilities()
        assertTrue(caps.supports(RuntimeCapability.PTY))
        assertTrue(caps.supports(RuntimeCapability.RESIZE))
        assertTrue(caps.supports(RuntimeCapability.LINUX_ARM64))
    }

    @Test
    fun `ProotRuntimeBackend capability profile without rootfs excludes FILESYSTEM_BRIDGE`() {
        val backend = createMockBackend(rootfsDir = File("/nonexistent"))
        val caps = backend.capabilities()
        assertFalse(caps.supports(RuntimeCapability.FILESYSTEM_BRIDGE))
        assertTrue(caps.unavailableReasons.containsKey(RuntimeCapability.FILESYSTEM_BRIDGE))
    }

    @Test
    fun `SessionSpec validates correctly`() {
        val spec = SessionSpec(
            runtimeId = RuntimeId("proot-linux"),
            argv = listOf("/bin/sh"),
            terminalSize = TerminalSize(80, 24)
        )
        assertEquals(80, spec.terminalSize.columns)
        assertEquals(24, spec.terminalSize.rows)
        assertEquals(1, spec.argv.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SessionSpec rejects empty argv`() {
        SessionSpec(
            runtimeId = RuntimeId("proot-linux"),
            argv = emptyList()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SessionSpec rejects blank argv first element`() {
        SessionSpec(
            runtimeId = RuntimeId("proot-linux"),
            argv = listOf("")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SessionSpec rejects NUL in environment values`() {
        SessionSpec(
            runtimeId = RuntimeId("proot-linux"),
            argv = listOf("/bin/sh"),
            environment = mapOf("KEY" to "value\u0000extra")
        )
    }

    @Test
    fun `SessionState transitions are typed`() {
        val created = SessionState.Created
        val running = SessionState.Running(pid = 1234L, startedAtMs = System.currentTimeMillis())
        val stopped = SessionState.Stopped(
            ExitReport(
                exitCode = 0,
                signal = null,
                startedAtMs = running.startedAtMs,
                finishedAtMs = System.currentTimeMillis(),
                forced = false,
                processGroupClean = true,
                closedFileDescriptors = 0
            )
        )
        val failed = SessionState.Failed(
            com.elysium.vanguard.core.runtime.domain.RuntimeError(
                code = com.elysium.vanguard.core.runtime.domain.RuntimeErrorCode.SPAWN_FAILED,
                message = "test failure",
                recoverable = true
            )
        )

        assertTrue(created is SessionState.Created)
        assertTrue(running is SessionState.Running)
        assertTrue(stopped is SessionState.Stopped)
        assertTrue(failed is SessionState.Failed)
        assertEquals(0, stopped.report.exitCode)
        assertEquals(1234L, running.pid)
    }

    @Test
    fun `RuntimeCapability enum covers all required capabilities`() {
        val required = setOf(
            "PTY", "PROCESS_GROUP_SIGNALS", "RESIZE", "LINUX_ARM64",
            "FILESYSTEM_BRIDGE", "NETWORK_BRIDGE", "DISPLAY", "AUDIO",
            "CLIPBOARD", "SUSPEND", "SNAPSHOT"
        )
        val actual = RuntimeCapability.entries.map { it.name }.toSet()
        assertTrue("Missing capabilities: ${required - actual}", required.all { it in actual })
    }

    @Test
    fun `RuntimeErrorCode enum covers all required error codes`() {
        val required = setOf(
            "INVALID_SPEC", "ROOTFS_MISSING", "ARCHITECTURE_UNSUPPORTED",
            "PTY_UNAVAILABLE", "SPAWN_FAILED", "IO_FAILED", "RESIZE_FAILED",
            "SIGNAL_FAILED", "DNS_UNREACHABLE", "DISPLAY_UNAVAILABLE",
            "CAPABILITY_UNAVAILABLE", "PERMISSION_DENIED", "STORAGE_EXHAUSTED",
            "PAGE_SIZE_UNSUPPORTED", "TIMEOUT", "UNEXPECTED_EXIT", "INTERNAL"
        )
        val actual = com.elysium.vanguard.core.runtime.domain.RuntimeErrorCode.entries
            .map { it.name }.toSet()
        assertTrue("Missing error codes: ${required - actual}", required.all { it in actual })
    }

    @Test
    fun `TerminalSize validates bounds`() {
        val min = TerminalSize(TerminalSize.MIN_COLUMNS, TerminalSize.MIN_ROWS)
        val max = TerminalSize(TerminalSize.MAX_COLUMNS, TerminalSize.MAX_ROWS)
        assertEquals(2, min.columns)
        assertEquals(1, min.rows)
        assertEquals(1000, max.columns)
        assertEquals(1000, max.rows)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TerminalSize rejects columns below minimum`() {
        TerminalSize(1, 24)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TerminalSize rejects rows above maximum`() {
        TerminalSize(80, 1001)
    }

    @Test
    fun `CapabilityProfile correctly reports supported and unsupported`() {
        val profile = CapabilityProfile(
            available = setOf(RuntimeCapability.PTY, RuntimeCapability.RESIZE),
            unavailableReasons = mapOf(RuntimeCapability.DISPLAY to "no X11 server")
        )
        assertTrue(profile.supports(RuntimeCapability.PTY))
        assertTrue(profile.supports(RuntimeCapability.RESIZE))
        assertFalse(profile.supports(RuntimeCapability.DISPLAY))
        assertEquals("no X11 server", profile.unavailableReasons[RuntimeCapability.DISPLAY])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `CapabilityProfile rejects capability in both available and unavailable`() {
        CapabilityProfile(
            available = setOf(RuntimeCapability.PTY),
            unavailableReasons = mapOf(RuntimeCapability.PTY to "reason")
        )
    }

    @Test
    fun `RuntimeId rejects blank values`() {
        try {
            RuntimeId("")
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("blank") == true)
        }
    }

    @Test
    fun `SessionId generates unique IDs`() {
        val a = SessionId.create()
        val b = SessionId.create()
        assertFalse(a.value.isEmpty())
        assertFalse(a.value == b.value)
    }

    private fun createMockBackend(
        rootfsDir: File = File("/tmp/test-rootfs")
    ): RuntimeBackend = object : RuntimeBackend {
        override val runtime: RuntimeSpec = RuntimeSpec(
            id = RuntimeId("proot-linux"),
            displayName = "PRoot Linux (ARM64)",
            backend = BackendKind.PROOT_LINUX
        )

        override fun capabilities(): CapabilityProfile {
            val available = mutableSetOf(RuntimeCapability.PTY, RuntimeCapability.RESIZE, RuntimeCapability.LINUX_ARM64)
            val reasons = mutableMapOf<RuntimeCapability, String>()
            if (!rootfsDir.isDirectory) {
                reasons[RuntimeCapability.FILESYSTEM_BRIDGE] = "Rootfs not installed"
            } else {
                available += RuntimeCapability.FILESYSTEM_BRIDGE
            }
            return CapabilityProfile(available = available, unavailableReasons = reasons)
        }

        override suspend fun open(spec: SessionSpec): RuntimeSession {
            throw IllegalStateException("mock backend does not spawn real processes")
        }
    }
}
