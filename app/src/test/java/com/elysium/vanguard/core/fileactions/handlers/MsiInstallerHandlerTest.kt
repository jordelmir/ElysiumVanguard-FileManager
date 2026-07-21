package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 103 — JVM tests for [MsiInstallerHandler].
 * The handler is a thin shell over [MsiInstaller];
 * tests pass a fake installer and assert the
 * delegation. The install() method is `suspend`
 * so each test wraps the call in `runTest { }`.
 */
class MsiInstallerHandlerTest {

    @Test
    fun `install fails when the msi file does not exist`() = runTest {
        val installer = RecordingMsiInstaller()
        val handler = MsiInstallerHandler(installer)
        val action = FileAction.InstallWindowsMsi(
            id = "test-1",
            msiPath = "/this/path/does/not/exist/Setup.msi",
            targetVmId = "win10-1",
            targetVmName = "Windows 10",
        )
        val result = handler.install(action)
        assertTrue("expected Failure, got $result", result is MsiInstallResult.Failure)
        val failure = result as MsiInstallResult.Failure
        assertTrue(
            "failure message must mention the missing path: ${failure.message}",
            failure.message.contains("not found")
        )
        // The fake installer must NOT have been called
        // when the file is missing.
        assertEquals(0, installer.callCount)
    }

    @Test
    fun `install delegates to the installer when the msi file exists`() = runTest {
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            msi.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte()))
            val installer = RecordingMsiInstaller()
            val handler = MsiInstallerHandler(installer)
            val action = FileAction.InstallWindowsMsi(
                id = "test-2",
                msiPath = msi.absolutePath,
                targetVmId = "win10-1",
                targetVmName = "Windows 10",
            )
            val result = handler.install(action)
            // The fake returns Completed; the handler
            // must surface the same exit code.
            assertTrue("expected Completed, got $result", result is MsiInstallResult.Completed)
            val completed = result as MsiInstallResult.Completed
            assertEquals(0, completed.exitCode)
            assertEquals(msi.absolutePath, completed.msiPath)
            assertEquals("win10-1", completed.vmId)
            assertEquals(1, installer.callCount)
            // The recorded call should target the same VM
            assertEquals("win10-1", installer.lastVmId)
        } finally {
            msi.delete()
        }
    }

    @Test
    fun `install surfaces a non-zero exit code from the installer`() = runTest {
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            val installer = RecordingMsiInstaller(
                scripted = { _, _ -> MsiInstallResult.Completed(
                    vmId = "win10-1",
                    msiPath = msi.absolutePath,
                    exitCode = 1603, // ERROR_INSTALL_FAILURE
                ) }
            )
            val handler = MsiInstallerHandler(installer)
            val action = FileAction.InstallWindowsMsi(
                id = "test-3",
                msiPath = msi.absolutePath,
                targetVmId = "win10-1",
                targetVmName = "Windows 10",
            )
            val result = handler.install(action) as MsiInstallResult.Completed
            assertEquals(1603, result.exitCode)
        } finally {
            msi.delete()
        }
    }

    @Test
    fun `install surfaces a Failure from the installer verbatim`() = runTest {
        val msi = Files.createTempFile("elysium-msi-test", ".msi").toFile()
        try {
            val installer = RecordingMsiInstaller(
                scripted = { _, _ -> MsiInstallResult.Failure(message = "msiexec returned 1602") }
            )
            val handler = MsiInstallerHandler(installer)
            val action = FileAction.InstallWindowsMsi(
                id = "test-4",
                msiPath = msi.absolutePath,
                targetVmId = "win10-1",
                targetVmName = "Windows 10",
            )
            val result = handler.install(action) as MsiInstallResult.Failure
            assertEquals("msiexec returned 1602", result.message)
        } finally {
            msi.delete()
        }
    }
}

/**
 * In-memory [MsiInstaller] for tests. The optional
 * [scripted] lambda overrides the default
 * `Completed(exitCode=0)` response. Records the
 * last call's args for assertion.
 */
class RecordingMsiInstaller(
    private val scripted: ((File, String) -> MsiInstallResult)? = null,
) : MsiInstaller {
    var callCount: Int = 0
        private set
    var lastMsi: File? = null
        private set
    var lastVmId: String? = null
        private set

    override suspend fun install(msi: File, vmId: String): MsiInstallResult {
        callCount++
        lastMsi = msi
        lastVmId = vmId
        return scripted?.invoke(msi, vmId) ?: MsiInstallResult.Completed(
            vmId = vmId,
            msiPath = msi.absolutePath,
            exitCode = 0,
        )
    }
}
