package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.BinaryRunResult
import com.elysium.vanguard.core.fileactions.handlers.BinaryRunner
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import java.io.File

/**
 * Phase 99 — the production [BinaryRunner] impls
 * for AppImage and Windows binaries.
 *
 * The two impls share a common pattern:
 *
 * 1. **AppImage** — spawn `proot` inside a Linux
 *    distro + run the AppImage binary. The AppImage
 *    self-mounts its FUSE squashfs on first exec;
 *    the distro's FUSE driver (`/dev/fuse`) is
 *    bind-mounted into the proot rootfs. The
 *    AppImage also runs faster when
 *    `APPIMAGE_EXTRACT_AND_RUN=1` is set: the
 *    FUSE mount is bypassed; the binary is
 *    extracted to a tmpdir + exec'd directly.
 *
 * 2. **Windows** — copy the `.exe` into the Windows
 *    VM's `C:\elysium\` directory via QEMU's
 *    QMP guest-file-put, then invoke
 *    `wine <path>` via QEMU's QMP guest-exec.
 *    (For Phase 99, the copy is approximated by
 *    a QEMU drive-attach path; Phase 99+ will
 *    swap to proper QMP guest-file operations.)
 *
 * **JVM testability**: each runner takes a
 * [ProcessLauncher] in its constructor. Tests pass
 * a fake that records the call.
 */
class ProcessLauncherAppImageRunner(
    private val processLauncher: ProcessLauncher,
    private val resolveRootfs: (String) -> File?,
    private val prootBinary: String = DEFAULT_PROOT_BINARY,
) : BinaryRunner {

    override suspend fun run(
        binary: File,
        targetId: String,
        runtimeLabel: String,
    ): BinaryRunResult {
        val rootfs = resolveRootfs(targetId)
            ?: return BinaryRunResult.Failure(
                message = "distro $targetId is not installed"
            )
        if (!rootfs.isDirectory) {
            return BinaryRunResult.Failure(
                message = "distro rootfs is not a directory: $rootfs"
            )
        }
        // The proot command bind-mounts /dev/fuse
        // + /dev/null so the AppImage binary can
        // access the FUSE device node. When
        // APPIMAGE_EXTRACT_AND_RUN=1, the FUSE
        // mount is bypassed; the binary is
        // extracted to a tmpdir + exec'd directly.
        val cmd = listOf(
            prootBinary,
            "--link2symlink",
            "-r", rootfs.absolutePath,
            "-b", "/dev/fuse",
            "-b", "/dev/null",
            binary.absolutePath,
        )
        val launched = try {
            processLauncher.start(
                command = cmd,
                env = listOf("APPIMAGE_EXTRACT_AND_RUN" to "1"),
                cwd = binary.parentFile ?: File("."),
            )
        } catch (e: Exception) {
            return BinaryRunResult.Failure(
                message = "could not spawn AppImage: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        val exitCode = waitForExit(launched)
        return if (exitCode == 0) {
            BinaryRunResult.Launched(
                runtimeLabel = runtimeLabel,
                targetId = targetId,
                binaryPath = binary.absolutePath,
            )
        } else {
            BinaryRunResult.Failure(
                message = "AppImage exited with code $exitCode"
            )
        }
    }

    private fun waitForExit(launched: com.elysium.vanguard.core.runtime.runner.LaunchedProcess): Int {
        var attempts = 0
        while (attempts < 600) {
            if (launched.pid <= 0) return 0
            Thread.sleep(100)
            attempts++
        }
        launched.stop()
        return -1
    }

    companion object {
        const val DEFAULT_PROOT_BINARY = "proot"
    }
}

/**
 * The Windows binary runner. The Phase 99 impl
 * uses QEMU's QMP `guest-exec` to invoke `wine`
 * inside the VM. The QMP path is a TODO for
 * Phase 99+; for now the runner spawns the
 * binary via Wine on a Linux distro (the Linux
 * is the "Windows VM" for the prototype). A real
 * production impl will use the QEMU + Wine
 * stack from Phase 78.
 */
class ProcessLauncherWindowsBinaryRunner(
    private val processLauncher: ProcessLauncher,
    private val windowsVmBackend: WindowsVmCommandRunner,
) : BinaryRunner {

    override suspend fun run(
        binary: File,
        targetId: String,
        runtimeLabel: String,
    ): BinaryRunResult {
        val qmpResult = windowsVmBackend.copyAndInvoke(binary, targetId)
        if (qmpResult is WindowsBinaryRunResult.Failure) {
            return BinaryRunResult.Failure(message = qmpResult.message)
        }
        return BinaryRunResult.Launched(
            runtimeLabel = runtimeLabel,
            targetId = targetId,
            binaryPath = binary.absolutePath,
        )
    }
}

/**
 * The narrow interface the Windows binary runner
 * needs from the Windows VM backend. Production
 * wires the [com.elysium.vanguard.core.runtime.windows.WindowsVmManager];
 * tests pass a fake. The interface is narrow
 * so the test surface is small.
 */
interface WindowsVmCommandRunner {
    fun copyAndInvoke(binary: File, vmId: String): WindowsBinaryRunResult

    /**
     * Phase 103 — install a Windows Installer
     * package (`.msi`) inside the VM via
     * `msiexec /i <msi> /qn`. The bridge
     * copies the file into the guest's
     * `C:\elysium\` directory + invokes
     * `msiexec` via QMP `guest-exec`.
     */
    fun installMsi(msi: File, vmId: String): MsiInstallBridgeResult
}

sealed class WindowsBinaryRunResult {
    data class Success(val exitCode: Int) : WindowsBinaryRunResult()
    data class Failure(val message: String) : WindowsBinaryRunResult()
}

/**
 * Phase 103 — the bridge result for MSI
 * installs. Mirrors [MsiInstallResult] but
 * lives in the production package (Hilt
 * needs an Android-side type to bind).
 */
sealed class MsiInstallBridgeResult {
    data class Success(val exitCode: Int) : MsiInstallBridgeResult()
    data class Failure(val message: String) : MsiInstallBridgeResult()
}
