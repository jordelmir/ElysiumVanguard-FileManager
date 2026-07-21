package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.MsiInstallResult
import com.elysium.vanguard.core.fileactions.handlers.MsiInstaller
import java.io.File

/**
 * Phase 103 — the production [MsiInstaller].
 *
 * The installer delegates to the
 * [WindowsVmCommandRunner] which is the QMP
 * bridge to the running Windows VM. The QMP
 * path is the canonical way to copy a file
 * into a QEMU guest + execute a command +
 * receive the exit code.
 *
 * **Flow**:
 *
 *   1. [MsiInstallerHandler.install] calls
 *      [MsiInstaller.install] here.
 *   2. We delegate to
 *      [WindowsVmCommandRunner.installMsi] which
 *      performs the QMP `guest-file-put` +
 *      `guest-exec` sequence.
 *   3. The result is translated from the
 *      bridge's [MsiInstallBridgeResult] to
 *      the handler's [MsiInstallResult].
 *
 * **Why a thin wrapper** (and not inline in
 * the handler): the production wiring is the
 * only thing in here. The QMP logic lives in
 * the bridge (testable in isolation). Adding
 * logging / audit / metrics later means we
 * change this file, not the handler.
 */
class ProcessLauncherMsiInstaller(
    private val windowsVmBackend: WindowsVmCommandRunner,
) : MsiInstaller {

    override suspend fun install(msi: File, vmId: String): MsiInstallResult {
        if (!msi.isFile) {
            return MsiInstallResult.Failure(
                message = "msi is not a file: ${msi.absolutePath}"
            )
        }
        val bridgeResult = try {
            windowsVmBackend.installMsi(msi, vmId)
        } catch (e: Exception) {
            return MsiInstallResult.Failure(
                message = "msi install bridge threw: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        return when (bridgeResult) {
            is MsiInstallBridgeResult.Success -> MsiInstallResult.Completed(
                vmId = vmId,
                msiPath = msi.absolutePath,
                exitCode = bridgeResult.exitCode,
            )
            is MsiInstallBridgeResult.Failure -> MsiInstallResult.Failure(
                message = bridgeResult.message,
            )
        }
    }
}
