package com.elysium.vanguard.core.fileactions

import android.content.Context
import com.elysium.vanguard.core.fileactions.handlers.DiskImageBackend
import com.elysium.vanguard.core.fileactions.handlers.GitCloneRunner
import com.elysium.vanguard.core.fileactions.handlers.NetworkShareMounter
import com.elysium.vanguard.core.fileactions.handlers.PackageInstaller
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherDiskImageBackend
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherGitCloneRunner
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherNetworkShareMounter
import com.elysium.vanguard.core.fileactions.production.ProcessLauncherPackageInstaller
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import com.elysium.vanguard.core.runtime.windows.WindowsVmManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

