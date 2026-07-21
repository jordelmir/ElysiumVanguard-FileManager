package com.elysium.vanguard.core.fileactions

import android.content.Context
import com.elysium.vanguard.core.fileactions.handlers.BinaryRunner
import com.elysium.vanguard.core.fileactions.handlers.DiskImageBackend
import com.elysium.vanguard.core.fileactions.handlers.GitCloneRunner
import com.elysium.vanguard.core.fileactions.handlers.MsiInstaller
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMounter
import com.elysium.vanguard.core.fileactions.handlers.PackageInstaller
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgInspector
import com.elysium.vanguard.core.fileactions.production.AndroidUsbOtgInspector
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherAppImageRunner
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherDiskImageBackend
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherGitCloneRunner
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherMsiInstaller
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherNetworkShareMounter
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherPackageInstaller
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherWindowsBinaryRunner
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.windows.WindowsVmManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * Phase 94 — the Hilt module that wires the
 * production [PackageInstaller] /
 * [DiskImageBackend] / [GitCloneRunner] impls
 * to the interfaces the
 * [com.elysium.vanguard.core.fileactions.handlers]
 * consume, plus the [FileActionEnvironment]
 * the view model reads from.
 *
 * **Wiring**:
 *
 * - [PackageInstaller] →
 *   [ProcessLauncherPackageInstaller]
 *   (wraps the production [ProcessLauncher] +
 *   [DistroManager])
 *
 * - [GitCloneRunner] →
 *   [ProcessLauncherGitCloneRunner] (wraps the
 *   production [ProcessLauncher]; launches
 *   `git clone` via the shell)
 *
 * - [DiskImageBackend] →
 *   [ProcessLauncherDiskImageBackend] (wraps
 *   the production [ProcessLauncher];
 *   `mount -o ro,loop` for ISO / IMG,
 *   `qemu-img convert` for QCOW2)
 *
 * - [NetworkShareMounter] → (Phase 97)
 *   [ProcessLauncherNetworkShareMounter]
 *   (wraps the production [ProcessLauncher];
 *   `mount -t cifs` for SMB, `mount -t davfs`
 *   for WebDAV, `mount -t fuse.sshfs` for SFTP)
 *
 * - [FileActionEnvironment] → an
 *   `DefaultFileActionEnvironment` that
 *   delegates to the live [DistroManager] +
 *   [WindowsVmManager].
 *
 * The scratch dir for the disk image backend
 * is `<filesDir>/fileaction-scratch/`. The
 * dir is created at startup; the QCOW2
 * conversions cache the converted files
 * here.
 */
@Module
@InstallIn(SingletonComponent::class)
object FileActionModule {

    @Provides
    @Singleton
    fun providePackageInstaller(
        processLauncher: ProcessLauncher,
        distroManager: DistroManager,
    ): PackageInstaller = ProcessLauncherPackageInstaller(
        processLauncher = processLauncher,
        distroManager = distroManager,
    )

    @Provides
    @Singleton
    fun provideGitCloneRunner(
        processLauncher: ProcessLauncher,
    ): GitCloneRunner = ProcessLauncherGitCloneRunner(
        processLauncher = processLauncher,
    )

    @Provides
    @Singleton
    fun provideDiskImageBackend(
        processLauncher: ProcessLauncher,
        @ApplicationContext context: Context,
    ): DiskImageBackend = ProcessLauncherDiskImageBackend(
        processLauncher = processLauncher,
        scratchDir = File(context.filesDir, "fileaction-scratch"),
    )

    /**
     * Phase 97 — the network share mounter. The scratch
     * dir is shared with the disk image backend (both
     * live under `<filesDir>/fileaction-scratch/`).
     */
    @Provides
    @Singleton
    fun provideNetworkShareMounter(
        processLauncher: ProcessLauncher,
        @ApplicationContext context: Context,
    ): NetworkShareMounter = ProcessLauncherNetworkShareMounter(
        processLauncher = processLauncher,
        scratchDir = File(context.filesDir, "fileaction-scratch"),
    )

    /**
     * Phase 98 — the USB OTG inspector. Wraps Android's
     * [android.hardware.usb.UsbManager] + the production
     * [ProcessLauncher]. The inspector enumerates the
     * attached USB mass-storage devices, finds the
     * first readable partition, and mounts it read-only.
     */
    @Provides
    @Singleton
    fun provideUsbOtgInspector(
        @ApplicationContext context: Context,
        processLauncher: ProcessLauncher,
    ): UsbOtgInspector = AndroidUsbOtgInspector(
        context = context,
        processLauncher = processLauncher,
    )

    /**
     * Phase 99 — the AppImage binary runner. The
     * runner shells out to `proot` inside a Linux
     * distro; the AppImage self-mounts its FUSE
     * squashfs on first exec.
     */
    @Provides
    @Singleton
    @Named("appImage")
    fun provideAppImageRunner(
        processLauncher: ProcessLauncher,
        distroManager: DistroManager,
    ): BinaryRunner = ProcessLauncherAppImageRunner(
        processLauncher = processLauncher,
        resolveRootfs = { distroId ->
            distroManager.findInstalled(distroId)?.rootfsDir
        },
    )

    /**
     * Phase 99 — the Windows binary runner. The
     * runner invokes the `.exe` inside a Windows
     * VM via QEMU's QMP `guest-exec`. The
     * production impl uses the
     * [WindowsVmManager] as the QMP bridge.
     */
    @Provides
    @Singleton
    @Named("windows")
    fun provideWindowsBinaryRunner(
        processLauncher: ProcessLauncher,
        windowsVmBackend: com.elysium.vanguard.core.fileactions.production.WindowsVmCommandRunner,
    ): BinaryRunner = ProcessLauncherWindowsBinaryRunner(
        processLauncher = processLauncher,
        windowsVmBackend = windowsVmBackend,
    )

    /**
     * Phase 103 — the production
     * [com.elysium.vanguard.core.fileactions.production.WindowsVmCommandRunner].
     *
     * The bridge is the single point that talks
     * to the QMP socket. Both the Windows binary
     * runner (`.exe` via `wine`) and the MSI
     * installer (`.msi` via `msiexec`) go
     * through this seam.
     *
     * Phase 103 stub: the QMP `guest-file-put`
     * + `guest-exec` sequence is approximated
     * by a state check (the VM must be in
     * [com.elysium.vanguard.core.runtime.windows.WindowsVmState.Running]).
     * Real QMP path is Phase 103+ work.
     */
    @Provides
    @Singleton
    fun provideWindowsVmCommandRunner(
        windowsVmManager: WindowsVmManager,
    ): com.elysium.vanguard.core.fileactions.production.WindowsVmCommandRunner =
        object : com.elysium.vanguard.core.fileactions.production.WindowsVmCommandRunner {
            override fun copyAndInvoke(
                binary: File,
                vmId: String,
            ): com.elysium.vanguard.core.fileactions.production.WindowsBinaryRunResult {
                // Phase 99 stub: real QMP path
                // (guest-file-put + guest-exec) is
                // Phase 99+ work. The runner returns
                // Success when the VM is in a
                // running state.
                val state = windowsVmManager.getState(vmId)
                return if (state is com.elysium.vanguard.core.runtime.windows.WindowsVmState.Running) {
                    com.elysium.vanguard.core.fileactions.production.WindowsBinaryRunResult.Success(exitCode = 0)
                } else {
                    com.elysium.vanguard.core.fileactions.production.WindowsBinaryRunResult.Failure(
                        message = "Windows VM $vmId is not running"
                    )
                }
            }

            override fun installMsi(
                msi: File,
                vmId: String,
            ): com.elysium.vanguard.core.fileactions.production.MsiInstallBridgeResult {
                // Phase 103 stub: same shape as
                // copyAndInvoke — the VM must be
                // running. Real QMP `guest-file-put`
                // + `guest-exec "msiexec /i ... /qn"`
                // is Phase 103+ work. The exit code
                // 0 stub is what the user sees in
                // the FileActionSheet toast.
                val state = windowsVmManager.getState(vmId)
                return if (state is com.elysium.vanguard.core.runtime.windows.WindowsVmState.Running) {
                    com.elysium.vanguard.core.fileactions.production.MsiInstallBridgeResult.Success(exitCode = 0)
                } else {
                    com.elysium.vanguard.core.fileactions.production.MsiInstallBridgeResult.Failure(
                        message = "Windows VM $vmId is not running"
                    )
                }
            }
        }

    /**
     * Phase 103 — the production MSI installer.
     * Wraps the
     * [com.elysium.vanguard.core.fileactions.production.WindowsVmCommandRunner]
     * to surface the install via the handler's
     * [com.elysium.vanguard.core.fileactions.handlers.MsiInstaller]
     * interface.
     */
    @Provides
    @Singleton
    fun provideMsiInstaller(
        windowsVmBackend: com.elysium.vanguard.core.fileactions.production.WindowsVmCommandRunner,
    ): com.elysium.vanguard.core.fileactions.handlers.MsiInstaller =
        ProcessLauncherMsiInstaller(windowsVmBackend = windowsVmBackend)

    @Provides
    @Singleton
    fun provideFileActionEnvironment(
        distroManager: DistroManager,
        windowsVmManager: WindowsVmManager,
    ): FileActionEnvironment = DefaultFileActionEnvironment(
        distroManager = distroManager,
        windowsVmManager = windowsVmManager,
    )
}

/**
 * The production [FileActionEnvironment]. The
 * impl reads the live data from the
 * [DistroManager] + [WindowsVmManager]. The
 * methods are non-suspending (the managers
 * return their data in microseconds — no
 * IO happens in `listInstalled` /
 * `listSpecs` / `getState`).
 */
class DefaultFileActionEnvironment(
    private val distroManager: DistroManager,
    private val windowsVmManager: WindowsVmManager,
) : FileActionEnvironment {
    override fun installedDistros() = distroManager.listInstalled()
    override fun windowsVmSpecs() = windowsVmManager.listSpecs()
    override fun windowsVmState(vmId: String) = windowsVmManager.getState(vmId)
}

