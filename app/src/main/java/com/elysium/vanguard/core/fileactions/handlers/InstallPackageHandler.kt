package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import com.elysium.vanguard.core.fileactions.LinuxPackageManager
import java.io.File

/**
 * Phase 93 — the **install-package handler** for
 * `.deb`, `.rpm`, and `.pkg.tar.zst` files.
 *
 * The handler is a thin shell over the existing
 * distro manager + workspace launcher: it
 * (1) verifies the package file exists, (2)
 * verifies the target distro is installed,
 * (3) copies the package into the distro's
 * filesystem (the distro cannot reach the
 * Android-side path), (4) launches the
 * appropriate package manager command.
 *
 * The handler is **async** (suspend function).
 * The caller (the [com.elysium.vanguard.features.fileactions.FileActionViewModel])
 * invokes it from a coroutine scope. The
 * result is a sealed [InstallPackageResult]
 * that the caller renders.
 *
 * **Why not call the distro manager directly?**
 * The distro manager is the surface the file
 * action sheet uses to discover distros. The
 * install handler is a *separate* concern:
 * it takes a `.deb` file + a target distro and
 * installs the package. Splitting the two
 * concerns keeps the distro manager small and
 * the install handler testable.
 *
 * **JVM testability**: the handler takes a
 * `PackageInstaller` interface in its
 * constructor; production uses the real
 * one (which wraps the existing `ProcessLauncher`
 * + the distro's rootfs path); tests pass a
 * fake.
 */
class InstallPackageHandler(
    private val installer: PackageInstaller,
) {

    /**
     * Install the package described by [action]
     * in its target distro. The [action] carries
     * the package path, the target distro's id,
     * and the target distro's display name.
     */
    suspend fun install(action: FileAction): InstallPackageResult = when (action) {
        is FileAction.InstallDebPackage ->
            installAptPackage(action)
        is FileAction.InstallRpmPackage ->
            installDnfPackage(action)
        is FileAction.InstallPacmanPackage ->
            installPacmanPackage(action)
        else -> InstallPackageResult.Failure(
            message = "action is not an install-package action: ${action::class.simpleName}"
        )
    }

    private suspend fun installAptPackage(action: FileAction.InstallDebPackage): InstallPackageResult {
        val packageFile = File(action.packagePath)
        if (!packageFile.exists() || !packageFile.isFile) {
            return InstallPackageResult.Failure(
                message = "package file not found: ${action.packagePath}"
            )
        }
        return installer.installApt(action.targetDistroId, packageFile)
    }

    private suspend fun installDnfPackage(action: FileAction.InstallRpmPackage): InstallPackageResult {
        val packageFile = File(action.packagePath)
        if (!packageFile.exists() || !packageFile.isFile) {
            return InstallPackageResult.Failure(
                message = "package file not found: ${action.packagePath}"
            )
        }
        return installer.installDnf(action.targetDistroId, packageFile)
    }

    private suspend fun installPacmanPackage(action: FileAction.InstallPacmanPackage): InstallPackageResult {
        val packageFile = File(action.packagePath)
        if (!packageFile.exists() || !packageFile.isFile) {
            return InstallPackageResult.Failure(
                message = "package file not found: ${action.packagePath}"
            )
        }
        return installer.installPacman(action.targetDistroId, packageFile)
    }
}

/**
 * The [PackageInstaller] interface decouples
 * the [InstallPackageHandler] from the
 * distro manager. Production uses the
 * [ProcessLauncher]-backed impl; tests use a
 * fake.
 */
interface PackageInstaller {
    suspend fun installApt(distroId: String, packageFile: File): InstallPackageResult
    suspend fun installDnf(distroId: String, packageFile: File): InstallPackageResult
    suspend fun installPacman(distroId: String, packageFile: File): InstallPackageResult
}

/**
 * The result of an install-package action. A
 * sealed class so the caller pattern-matches
 * on the outcome (Success, Failure, MissingDistro).
 */
sealed class InstallPackageResult {
    data class Success(
        val distroId: String,
        val packageName: String,
        val exitCode: Int,
    ) : InstallPackageResult()

    data class Failure(
        val message: String,
    ) : InstallPackageResult()

    data class MissingDistro(
        val distroId: String,
    ) : InstallPackageResult()
}
