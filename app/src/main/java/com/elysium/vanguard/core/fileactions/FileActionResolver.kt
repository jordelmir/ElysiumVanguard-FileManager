package com.elysium.vanguard.core.fileactions

import java.io.File

/**
 * Phase 93 — the **resolver** that maps a file +
 * [FileActionContext] to a list of
 * [FileAction]s.
 *
 * The resolver is the single source of truth for
 * "what can I do with this file?". Every UI
 * surface that shows a contextual action sheet
 * (the File Manager's file list, the dashboard's
 * "open file" card, the AI Operator's plan
 * preview) goes through this resolver.
 *
 * **Algorithm** (single-file resolution):
 *
 * 1. Read the file's extension. Lowercase
 *    for case-insensitive matching.
 * 2. Look up the extension in a small table
 *    that maps extension → action factory.
 * 3. For each matching factory, build the
 *    concrete [FileAction] (passing the
 *    relevant fields from the context: the
 *    target distro, the target VM, etc.).
 * 4. Sort the actions by priority (most
 *    recommended first).
 * 5. Return the list.
 *
 * The resolver does **not** read the file
 * content. A `.git`-URL detection from a file
 * would require reading bytes; that lives in a
 * separate `GitCloneHandler` (Phase 93 also
 * ships the handler).
 *
 * **Testability**: the resolver is a pure
 * function. Tests pass a fixed [File] + a
 * fixed [FileActionContext] and assert the
 * returned list. No Android dependencies.
 */
object FileActionResolver {

    /**
     * The list of [FileAction]s available for
     * [file] in [context]. The list is empty if
     * the file's extension is not recognized.
     *
     * The order of the returned list is the
     * order in which the actions should be
     * displayed (most recommended first).
     */
    fun resolve(file: File, context: FileActionContext): List<FileAction> {
        val name = file.name
        val ext = file.extension.lowercase()
        // Compound extensions (e.g. `.pkg.tar.zst`)
        // need a more permissive match than
        // `file.extension` (which returns only
        // the part after the LAST dot). The
        // vision calls out `.pkg.tar.zst`
        // specifically; we match the suffix.
        val isPkgTarZst = name.lowercase().endsWith(".pkg.tar.zst")
        val isAppImage = ext == "appimage"

        // Git clone: the file is a `.git` file
        // (or has a URL inside; that lives in
        // the handler, not here).
        if (ext == "git" || name.endsWith(".git")) {
            // The resolver can't read the file
            // body; the handler does. We return
            // a single action that the user
            // can opt into (the handler will
            // resolve the URL on click).
            val parent = file.parentFile?.absolutePath ?: "/"
            return listOf(
                FileAction.GitClone(
                    id = "git-clone-${name}",
                    repoUrl = file.absolutePath, // handler reads the URL from the body
                    destinationDir = parent,
                )
            )
        }

        // SMB / WebDAV share descriptors: the
        // file is a `.smb` or `.webdav` file
        // (or `.dav`/`.davs`); the handler
        // reads the URL from the first line.
        if (ext in setOf("smb", "webdav", "dav", "davs", "cifs")) {
            val proto = when {
                ext in setOf("smb", "cifs") -> NetworkProtocol.SMB
                else -> NetworkProtocol.WEBDAV
            }
            return listOf(
                FileAction.MountNetworkShare(
                    id = "mount-${proto.name.lowercase()}-${name}",
                    url = file.absolutePath, // placeholder; handler reads body
                    protocol = proto,
                )
            )
        }

        // USB OTG descriptor: a `.usbotg` file
        // with the block path on the first line.
        if (ext == "usbotg") {
            return listOf(
                FileAction.InspectUsbOtgDevice(
                    id = "usbotg-inspect-${name}",
                    blockDevice = file.absolutePath, // placeholder; handler reads body
                )
            )
        }

        // The extension table: extension →
        // action factories. Each factory takes
        // the file + context and returns the
        // concrete [FileAction] (or null if no
        // context matches).
        val actions = mutableListOf<FileAction>()
        when (ext) {
            "deb" -> context.linuxDistrosByPackageManager[LinuxPackageManager.APT]
                ?.map { distro ->
                    FileAction.InstallDebPackage(
                        id = "install-deb-${distro.id}-${name}",
                        packagePath = file.absolutePath,
                        targetDistroId = distro.id,
                        targetDistroName = distro.name,
                    )
                }
                ?.let { actions.addAll(it) }
            "rpm" -> context.linuxDistrosByPackageManager[LinuxPackageManager.DNF]
                ?.map { distro ->
                    FileAction.InstallRpmPackage(
                        id = "install-rpm-${distro.id}-${name}",
                        packagePath = file.absolutePath,
                        targetDistroId = distro.id,
                        targetDistroName = distro.name,
                    )
                }
                ?.let { actions.addAll(it) }
            "zst" -> if (isPkgTarZst) {
                context.linuxDistrosByPackageManager[LinuxPackageManager.PACMAN]
                    ?.map { distro ->
                        FileAction.InstallPacmanPackage(
                            id = "install-pkg-${distro.id}-${name}",
                            packagePath = file.absolutePath,
                            targetDistroId = distro.id,
                            targetDistroName = distro.name,
                        )
                    }
                    ?.let { actions.addAll(it) }
            }
            "appimage" -> context.linuxDistros.firstOrNull {
                it.id == context.preferredLinuxDistroId
            }?.let { distro ->
                actions.add(
                    FileAction.RunAppImage(
                        id = "run-appimage-${distro.id}-${name}",
                        appImagePath = file.absolutePath,
                        targetDistroId = distro.id,
                        targetDistroName = distro.name,
                    )
                )
            }
            "exe", "msi" -> context.windowsVms.firstOrNull {
                it.id == context.preferredWindowsVmId
            }?.let { vm ->
                actions.add(
                    FileAction.RunWindowsBinary(
                        id = "run-windows-${vm.id}-${name}",
                        binaryPath = file.absolutePath,
                        targetVmId = vm.id,
                        targetVmName = vm.name,
                    )
                )
            }
            "iso", "img", "qcow2", "qcow2c" -> {
                val format = DiskImageFormat.fromExtension(ext) ?: return emptyList()
                actions.add(
                    FileAction.MountDiskImage(
                        id = "mount-image-${name}",
                        imagePath = file.absolutePath,
                        imageFormat = format,
                    )
                )
                actions.add(
                    FileAction.BootVmFromImage(
                        id = "boot-vm-${name}",
                        imagePath = file.absolutePath,
                        imageFormat = format,
                        preferredVmId = context.preferredWindowsVmId,
                    )
                )
            }
        }

        return actions
    }
}
