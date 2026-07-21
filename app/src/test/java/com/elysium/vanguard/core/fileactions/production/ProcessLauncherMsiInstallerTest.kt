package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.MsiInstallResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 103 — JVM tests for the production
 * [ProcessLauncherMsiInstaller]. The installer
 * is a thin wrapper over the
 * [WindowsVmCommandRunner] bridge; tests
 * pass a fake bridge and assert the
 * translation between bridge result and
 * handler result. The install() method is
 * `suspend` so each test wraps the call in
 * `runTest { }`.
 */
class ProcessLauncherMsiInstallerTest {

    @Test
    fun `Completed bridge result is translated to Completed handler result`() = runTest {
        val bridge = FakeBridge(
            bridgeResult = MsiInstallBridgeResult.Success(exitCode = 0)
        )
        val installer = ProcessLauncherMsiInstaller(bridge)
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            val result = installer.install(msi, "win10-1")
            assertTrue("expected Completed, got $result",
                result is MsiInstallResult.Completed)
            val completed = result as MsiInstallResult.Completed
            assertEquals(0, completed.exitCode)
            assertEquals("win10-1", completed.vmId)
            assertEquals(msi.absolutePath, completed.msiPath)
        } finally {
            msi.delete()
        }
    }

    @Test
    fun `Failure bridge result is translated to Failure handler result with verbatim message`() = runTest {
        val bridge = FakeBridge(
            bridgeResult = MsiInstallBridgeResult.Failure(message = "VM is not running")
        )
        val installer = ProcessLauncherMsiInstaller(bridge)
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            val result = installer.install(msi, "win10-1")
            assertTrue("expected Failure, got $result",
                result is MsiInstallResult.Failure)
            val failure = result as MsiInstallResult.Failure
            assertEquals("VM is not running", failure.message)
        } finally {
            msi.delete()
        }
    }

    @Test
    fun `non-zero exit code is surfaced in the Completed result`() = runTest {
        val bridge = FakeBridge(
            bridgeResult = MsiInstallBridgeResult.Success(exitCode = 1603) // ERROR_INSTALL_FAILURE
        )
        val installer = ProcessLauncherMsiInstaller(bridge)
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            val result = installer.install(msi, "win10-1") as MsiInstallResult.Completed
            assertEquals(1603, result.exitCode)
        } finally {
            msi.delete()
        }
    }

    @Test
    fun `bridge throwing is caught and translated to a Failure result`() = runTest {
        val bridge = FakeBridge(shouldThrow = RuntimeException("QMP socket closed"))
        val installer = ProcessLauncherMsiInstaller(bridge)
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            val result = installer.install(msi, "win10-1")
            assertTrue("expected Failure, got $result",
                result is MsiInstallResult.Failure)
            val failure = result as MsiInstallResult.Failure
            assertTrue(
                "failure message must mention the bridge error: ${failure.message}",
                failure.message.contains("QMP socket closed")
            )
        } finally {
            msi.delete()
        }
    }

    @Test
    fun `non-file input is refused without calling the bridge`() = runTest {
        val bridge = FakeBridge(bridgeResult = MsiInstallBridgeResult.Success(0))
        val installer = ProcessLauncherMsiInstaller(bridge)
        val notAFile = File("/this/path/does/not/exist/Setup.msi")
        val result = installer.install(notAFile, "win10-1")
        assertTrue(result is MsiInstallResult.Failure)
        assertEquals(0, bridge.callCount)
    }
}

/**
 * In-memory [WindowsVmCommandRunner] for tests.
 * Returns the configured [bridgeResult] on every
 * call (or throws [shouldThrow] if set). Records
 * the call count for tests that need to assert on
 * "did the bridge get called?".
 */
class FakeBridge(
    private val bridgeResult: MsiInstallBridgeResult = MsiInstallBridgeResult.Success(0),
    private val shouldThrow: Throwable? = null,
) : WindowsVmCommandRunner {
    var callCount: Int = 0
        private set

    override fun copyAndInvoke(
        binary: File,
        vmId: String,
    ): WindowsBinaryRunResult = WindowsBinaryRunResult.Success(0)

    override fun installMsi(msi: File, vmId: String): MsiInstallBridgeResult {
        callCount++
        if (shouldThrow != null) throw shouldThrow
        return bridgeResult
    }
}
