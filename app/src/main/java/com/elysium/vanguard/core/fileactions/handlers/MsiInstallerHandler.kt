package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import java.io.File

/**
 * Phase 103 — the **MSI installer handler** for
 * `.msi` (Windows Installer) files.
 *
 * The handler validates the MSI file exists,
 * looks up the target Windows VM, and delegates
 * the actual install to the [MsiInstaller]
 * interface. The install runs `msiexec /i <msi>
 * /qn` inside the VM via QEMU's QMP `guest-exec`
 * (silent install, no UI prompts).
 *
 * **Why a separate handler from
 * [BinaryRunnerHandler]**:
 *
 *  - `.exe` is run via `wine` (the Wine runtime
 *    inside the VM); `.msi` is run via
 *    `msiexec` (Windows Installer service).
 *    These are different command paths.
 *  - `.msi` install is a *transaction* — it can
 *    fail midway, leave the system in an
 *    inconsistent state, and the result type
 *    should distinguish "started" vs
 *    "completed with exit code 1624" (ERROR_INSTALL_USEREXIT)
 *    vs "completed with exit code 1603" (ERROR_INSTALL_FAILURE).
 *  - The `MsiInstaller` interface is narrow
 *    (just `install(msi, vmId)`); the production
 *    impl is a QMP bridge.
 *
 * **JVM testability**: the handler takes the
 * [MsiInstaller] interface in its constructor;
 * production wires the QMP-backed impl; tests
 * pass a fake.
 */
class MsiInstallerHandler @javax.inject.Inject constructor(
    private val installer: MsiInstaller,
) {

    /**
     * Install the MSI described by [action] in
     * the target Windows VM. Returns a typed
     * [MsiInstallResult].
     */
    suspend fun install(action: FileAction.InstallWindowsMsi): MsiInstallResult {
        val msi = File(action.msiPath)
        if (!msi.exists() || !msi.isFile) {
            return MsiInstallResult.Failure(
                message = "msi file not found: ${action.msiPath}"
            )
        }
        return installer.install(msi, action.targetVmId)
    }
}

/**
 * The [MsiInstaller] decouples [MsiInstallerHandler]
 * from the QEMU QMP plumbing. Production wires
 * the QMP-backed impl; tests pass a fake.
 *
 * The interface intentionally has a single
 * method — the surface is small and the test
 * fakes are 5 lines.
 */
interface MsiInstaller {
    suspend fun install(msi: File, vmId: String): MsiInstallResult
}

/**
 * The result of an MSI install. A sealed class
 * so the caller pattern-matches on the outcome.
 *
 * The error codes mirror Windows Installer
 * standard exit codes — callers can branch on
 * the exit code (e.g. 3010 = "reboot required"
 * is not really a failure, just a warning).
 */
sealed class MsiInstallResult {
    /**
     * The MSI install completed. The [exitCode]
     * is `0` on success or a Windows Installer
     * exit code (e.g. 3010 = success + reboot
     * required, 1603 = fatal install error).
     */
    data class Completed(
        val vmId: String,
        val msiPath: String,
        val exitCode: Int,
    ) : MsiInstallResult()

    /**
     * The MSI install could not be started
     * (VM not running, QMP refused, etc.).
     */
    data class Failure(
        val message: String,
    ) : MsiInstallResult()
}
