package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.handlers.InstallPackageResult
import com.elysium.vanguard.core.fileactions.handlers.PackageInstaller
import com.elysium.vanguard.core.runtime.distros.DistroInstallation
import com.elysium.vanguard.core.runtime.distros.DistroManager
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import java.io.File

/**
 * Phase 94 — the production
 * [PackageInstaller].
 *
 * The installer copies the package file from
 * the Android-side path into the target
 * distro's rootfs (the distro cannot reach
 * the Android-side path directly), then shells
 * into the distro to invoke the package
 * manager.
 *
 * **Why this is non-trivial**: the distro's
 * rootfs is a directory on the Android file
 * system (`<filesDir>/workspaces/<distroId>/`).
 * The package file lives outside the rootfs
 * (e.g. in `/sdcard/Download/`). PRoot cannot
 * mount a host file from outside the
 * `rootfs`; the package must be physically
 * inside the rootfs before `apt` / `dnf` /
 * `pacman` can see it.
 *
 * **Algorithm** (per package manager):
 *
 * 1. Resolve the target distro via
 *    [DistroManager]. If not found, return
 *    [InstallPackageResult.MissingDistro].
 * 2. Copy the package file into
 *    `<rootfs>/tmp/elysium-pkg-<uuid>.<ext>`.
 * 3. Spawn a process via [ProcessLauncher] that
 *    runs `proot ... <package-manager-command>`
 *    in the distro's rootfs.
 * 4. Wait for the process to exit + parse the
 *    exit code.
 * 5. Return [InstallPackageResult.Success] or
 *    [InstallPackageResult.Failure].
 *
 * **JVM testability**: the installer takes a
 * [ProcessLauncher] + a [DistroManager] in its
 * constructor. Tests pass a fake launcher
 * (records the call) + a fake distro manager
 * (returns a fixed installation).
 */
class ProcessLauncherPackageInstaller(
    private val processLauncher: ProcessLauncher,
    private val distroManager: DistroManager,
    private val prootBinary: String = DEFAULT_PROOT_BINARY,
    private val prootLink: String = DEFAULT_PROOT_LINK,
) : PackageInstaller {

    override suspend fun installApt(
        distroId: String,
        packageFile: File,
    ): InstallPackageResult = installDebImpl(distroId, packageFile)

    override suspend fun installDnf(
        distroId: String,
        packageFile: File,
    ): InstallPackageResult = installRpmImpl(distroId, packageFile)

    override suspend fun installPacman(
        distroId: String,
        packageFile: File,
    ): InstallPackageResult = installPacmanImpl(distroId, packageFile)

    /**
     * Install a `.deb` package via `apt-get install
     * <local-file>`. The package is first
     * copied into the distro's `/tmp`; then
     * `apt-get` is invoked with the local
     * path.
     */
    private fun installDebImpl(distroId: String, packageFile: File): InstallPackageResult {
        val installation = distroManager.findInstalled(distroId)
            ?: return InstallPackageResult.MissingDistro(distroId)
        return runPackageManager(
            installation = installation,
            packageFile = packageFile,
            inDistroCommand = listOf(
                "apt-get", "install", "-y",
                "/tmp/${packageFile.name}",
            ),
            failureMessage = "apt-get install failed for ${packageFile.name}",
        )
    }

    /**
     * Install a `.rpm` package via `dnf install
     * <local-file>`. The package is first
     * copied into the distro's `/tmp`; then
     * `dnf` is invoked with the local path.
     */
    private fun installRpmImpl(distroId: String, packageFile: File): InstallPackageResult {
        val installation = distroManager.findInstalled(distroId)
            ?: return InstallPackageResult.MissingDistro(distroId)
        return runPackageManager(
            installation = installation,
            packageFile = packageFile,
            inDistroCommand = listOf(
                "dnf", "install", "-y",
                "/tmp/${packageFile.name}",
            ),
            failureMessage = "dnf install failed for ${packageFile.name}",
        )
    }

    /**
     * Install a pacman package via `pacman -U
     * <local-file>`. The package is first
     * copied into the distro's `/tmp`; then
     * `pacman` is invoked with the local path.
     */
    private fun installPacmanImpl(distroId: String, packageFile: File): InstallPackageResult {
        val installation = distroManager.findInstalled(distroId)
            ?: return InstallPackageResult.MissingDistro(distroId)
        return runPackageManager(
            installation = installation,
            packageFile = packageFile,
            inDistroCommand = listOf(
                "pacman", "-U", "--noconfirm",
                "/tmp/${packageFile.name}",
            ),
            failureMessage = "pacman -U failed for ${packageFile.name}",
        )
    }

    /**
     * Copy the package file into the distro's
     * `/tmp` + run the in-distro command via
     * PRoot. Returns the appropriate
     * [InstallPackageResult] based on the
     * process's exit code.
     */
    private fun runPackageManager(
        installation: DistroInstallation,
        packageFile: File,
        inDistroCommand: List<String>,
        failureMessage: String,
    ): InstallPackageResult {
        // Step 1: copy the package into the
        // distro's /tmp. The PRoot process
        // cannot see files outside the
        // rootfs, so we physically copy the
        // bytes into the rootfs first.
        val targetPath = File(installation.rootfsDir, "tmp/${packageFile.name}")
        try {
            targetPath.parentFile?.mkdirs()
            packageFile.copyTo(targetPath, overwrite = true)
        } catch (e: Exception) {
            return InstallPackageResult.Failure(
                message = "could not copy package into rootfs: ${e.message}"
            )
        }
        // Step 2: spawn the package manager via
        // PRoot. The full command line is:
        //   <proot> --link2symlink -r <rootfs>
        //     -b /dev -b /proc -b /sys
        //     <in-distro-command>
        val rootfs = installation.rootfsDir
        val cmd = listOf(
            prootBinary,
            "--link2symlink",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            prootLink,
        ) + inDistroCommand
        val launched = processLauncher.start(
            command = cmd,
            env = listOf("DEBIAN_FRONTEND" to "noninteractive"),
            cwd = rootfs,
        )
        val exitCode = waitForExit(launched)
        if (exitCode == 0) {
            return InstallPackageResult.Success(
                distroId = installation.distro.id,
                packageName = packageFile.name,
                exitCode = 0,
            )
        }
        return InstallPackageResult.Failure(message = "$failureMessage (exit=$exitCode)")
    }

    /**
     * Wait for the launched process to exit.
     *
     * PHASE 117 — this used to be a 60-second polling loop
     * that checked `launched.pid <= 0` (always false because
     * PIDs are assigned at fork time). The loop never
     * detected exit, so `proot dpkg -i` and `proot rpm -i`
     * were always timed out and the install path was
     * effectively broken. Replaced with a direct delegate
     * to `LaunchedProcess.waitFor` (the production launcher
     * wires `Process.waitFor()` into that callback).
     */
    private fun waitForExit(launched: com.elysium.vanguard.core.runtime.runner.LaunchedProcess): Int =
        launched.waitFor()

    companion object {
        const val DEFAULT_PROOT_BINARY = "proot"
        const val DEFAULT_PROOT_LINK = "/usr/bin/env"
    }
}
