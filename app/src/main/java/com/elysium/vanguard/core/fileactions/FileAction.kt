package com.elysium.vanguard.core.fileactions

import com.elysium.vanguard.core.runtime.workspace_def.RuntimeKind

/**
 * Phase 93 — the typed **file action** that the
 * [FileActionResolver] returns for a given file +
 * context.
 *
 * A file action is a passive value: it carries
 * the inputs needed to perform an action on a
 * file (install / execute / mount / clone) but
 * does not perform the action itself. The
 * [com.elysium.vanguard.features.fileactions.FileActionSheet]
 * renders the action; the executor (Hilt-injected
 * ViewModel) performs it.
 *
 * The vision (Section 1) lists the "contextual
 * actions" the File Manager should offer for any
 * file:
 *
 * - `.apk` → install or inspect (Android-native)
 * - `.deb`, `.rpm`, `.pkg.tar.zst` → install
 *   inside a Linux distro
 * - `.AppImage` → run inside Linux
 * - `.exe`, `.msi` → run inside a Windows VM
 * - `.sh`, `.py`, `.js`, `.jar`, ELF binaries
 *   → run with the appropriate runtime
 * - `.iso`, `.img`, `.qcow2` → mount, inspect or
 *   boot a VM
 * - Git repos → clone, build, run, deploy
 *
 * The sealed class below covers every action in
 * that list.
 */
sealed class FileAction {

    abstract val id: String
    abstract val label: String
    abstract val description: String

    /**
     * The runtime the action targets. `null`
     * means the action is runtime-agnostic
     * (e.g. mounting an SMB share, cloning a
     * Git repo).
     */
    abstract val targetRuntime: RuntimeKind?

    /**
     * Install a Debian package (`.deb`) inside
     * a Linux distro using `apt` / `dpkg`.
     */
    data class InstallDebPackage(
        override val id: String,
        val packagePath: String,
        val targetDistroId: String,
        val targetDistroName: String,
    ) : FileAction() {
        override val label: String = "Install in $targetDistroName"
        override val description: String = "Install .deb via apt/dpkg in $targetDistroName"
        override val targetRuntime: RuntimeKind = RuntimeKind.LINUX_PROOT
    }

    /**
     * Install an RPM (`.rpm`) inside a Fedora /
     * openSUSE distro using `dnf` / `rpm`.
     */
    data class InstallRpmPackage(
        override val id: String,
        val packagePath: String,
        val targetDistroId: String,
        val targetDistroName: String,
    ) : FileAction() {
        override val label: String = "Install in $targetDistroName"
        override val description: String = "Install .rpm via dnf/rpm in $targetDistroName"
        override val targetRuntime: RuntimeKind = RuntimeKind.LINUX_PROOT
    }

    /**
     * Install a pacman package (`.pkg.tar.zst`)
     * inside an Arch Linux distro.
     */
    data class InstallPacmanPackage(
        override val id: String,
        val packagePath: String,
        val targetDistroId: String,
        val targetDistroName: String,
    ) : FileAction() {
        override val label: String = "Install in $targetDistroName"
        override val description: String = "Install .pkg.tar.zst via pacman in $targetDistroName"
        override val targetRuntime: RuntimeKind = RuntimeKind.LINUX_PROOT
    }

    /**
     * Run an AppImage inside a Linux distro.
     * AppImages are self-mounting FUSE images
     * (`/usr/bin/blender` etc.); the launcher
     * extracts + execs the inner binary.
     */
    data class RunAppImage(
        override val id: String,
        val appImagePath: String,
        val targetDistroId: String,
        val targetDistroName: String,
    ) : FileAction() {
        override val label: String = "Run in $targetDistroName"
        override val description: String = "Run AppImage inside $targetDistroName (FUSE)"
        override val targetRuntime: RuntimeKind = RuntimeKind.LINUX_PROOT
    }

    /**
     * Run a Windows executable (`.exe` or
     * `.msi`) inside a Windows VM via Wine +
     * QEMU.
     */
    data class RunWindowsBinary(
        override val id: String,
        val binaryPath: String,
        val targetVmId: String,
        val targetVmName: String,
    ) : FileAction() {
        override val label: String = "Run in $targetVmName"
        override val description: String = "Run via Wine inside $targetVmName"
        override val targetRuntime: RuntimeKind = RuntimeKind.WINDOWS_VM
    }

    /**
     * Mount a disk image (`.iso`, `.img`,
     * `.qcow2`) for read access. ISO / IMG
     * are mounted as filesystem images;
     * QCOW2 is converted to raw first.
     */
    data class MountDiskImage(
        override val id: String,
        val imagePath: String,
        val imageFormat: DiskImageFormat,
    ) : FileAction() {
        override val label: String = "Mount as $imageFormat"
        override val description: String = "Mount ${imageFormat.name} image read-only"
        override val targetRuntime: RuntimeKind? = null
    }

    /**
     * Boot a Windows / Linux VM from a disk
     * image. QCOW2 images are passed directly
     * to QEMU; ISO / IMG are converted to
     * QCOW2 first.
     */
    data class BootVmFromImage(
        override val id: String,
        val imagePath: String,
        val imageFormat: DiskImageFormat,
        val preferredVmId: String?,
    ) : FileAction() {
        override val label: String = "Boot VM from $imageFormat"
        override val description: String = "Boot a virtual machine from this image"
        override val targetRuntime: RuntimeKind? = null
    }

    /**
     * Clone a Git repository from a URL.
     * The URL is extracted from the file
     * (e.g. a `.git` file or a file containing
     * the URL as plain text). The clone
     * destination is the parent directory of
     * the file.
     */
    data class GitClone(
        override val id: String,
        val repoUrl: String,
        val destinationDir: String,
    ) : FileAction() {
        override val label: String = "Clone repo"
        override val description: String = "git clone $repoUrl → $destinationDir"
        override val targetRuntime: RuntimeKind? = null
    }

    /**
     * Mount a network share (SMB / WebDAV) as a
     * local folder. The URL is extracted from
     * the file (which acts as a "share
     * descriptor" — typically a `.smb` or
     * `.webdav` file with the URL on the first
     * line).
     */
    data class MountNetworkShare(
        override val id: String,
        val url: String,
        val protocol: NetworkProtocol,
    ) : FileAction() {
        override val label: String = "Mount as ${protocol.name}"
        override val description: String = "Mount $url as a local folder"
        override val targetRuntime: RuntimeKind? = null
    }

    /**
     * Inspect a USB OTG device: list the
     * partitions + mount the first readable
     * one. The file in the File Manager is a
     * `.usbotg` descriptor with the device's
     * block path.
     */
    data class InspectUsbOtgDevice(
        override val id: String,
        val blockDevice: String,
    ) : FileAction() {
        override val label: String = "Inspect USB OTG"
        override val description: String = "List partitions + mount first readable"
        override val targetRuntime: RuntimeKind? = null
    }
}

/**
 * The disk image formats the platform supports.
 * ISO is the optical-disc format; IMG is the
 * raw disk format; QCOW2 is the QEMU copy-on-write
 * format.
 */
enum class DiskImageFormat(val extensions: List<String>) {
    ISO(listOf("iso")),
    IMG(listOf("img")),
    QCOW2(listOf("qcow2", "qcow2c"));

    companion object {
        /**
         * Resolve a file extension to a
         * [DiskImageFormat], or `null` if the
         * extension is not a known image format.
         */
        fun fromExtension(ext: String): DiskImageFormat? = values()
            .firstOrNull { it.extensions.contains(ext.lowercase()) }
    }
}

/**
 * The network protocols the platform supports
 * for remote-mount file actions. The protocol
 * is determined by the URL scheme in the
 * descriptor file.
 */
enum class NetworkProtocol(val schemes: List<String>) {
    SMB(listOf("smb://", "cifs://")),
    WEBDAV(listOf("webdav://", "dav://", "davs://")),
    SFTP(listOf("sftp://"));

    companion object {
        fun fromUrl(url: String): NetworkProtocol? = values()
            .firstOrNull { p -> p.schemes.any { url.startsWith(it) } }
    }
}
