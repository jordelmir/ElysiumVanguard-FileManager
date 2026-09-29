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
     * [file] in [context]. Descriptor files
     * (`.git` / `.smb` / `.usbotg` / `.elysv`)
     * return their dedicated action; every other
     * file gets the four universal actions
     * (encrypt / chmod / chown / symlink) after
     * any extension-matched actions, and
     * [FileAction.ScanForMalware] is appended to
     * every non-empty list.
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
        val isElysV = ext == "elysv"

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
                ),
                scanForMalwareAction(file),
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
                ),
                scanForMalwareAction(file),
            )
        }

        // USB OTG descriptor: a `.usbotg` file
        // with the block path on the first line.
        if (ext == "usbotg") {
            return listOf(
                FileAction.InspectUsbOtgDevice(
                    id = "usbotg-inspect-${name}",
                    blockDevice = file.absolutePath, // placeholder; handler reads body
                ),
                scanForMalwareAction(file),
            )
        }

        // .elysv encrypted vault: offer decrypt action
        if (isElysV) {
            return listOf(
                FileAction.DecryptFile(
                    id = "decrypt-elysv-${name}",
                    vaultPath = file.absolutePath,
                    password = "", // will be prompted by UI
                    outputDir = file.parentFile?.absolutePath,
                ),
                scanForMalwareAction(file),
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
            "exe" -> context.windowsVms.firstOrNull {
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
            // Phase 103 — `.msi` is a Windows
            // Installer database, not a
            // Wine-runnable PE. The right tool is
            // `msiexec /i <file.msi> /qn` (silent
            // install), not `wine <file.msi>`. We
            // offer a separate `InstallWindowsMsi`
            // action so the FileActionSheet can
            // label it accurately.
            "msi" -> context.windowsVms.firstOrNull {
                it.id == context.preferredWindowsVmId
            }?.let { vm ->
                actions.add(
                    FileAction.InstallWindowsMsi(
                        id = "install-msi-${vm.id}-${name}",
                        msiPath = file.absolutePath,
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

        // Universal "Encrypt with password" action
        // (available for any file). Creates a .elysv
        // encrypted vault using AES-256-GCM + PBKDF2.
        actions.add(
            FileAction.EncryptFile(
                id = "encrypt-${name}",
                sourcePath = file.absolutePath,
                password = "", // will be prompted by UI
                outputPath = "${file.absolutePath}.elysv",
            )
        )

        // Universal "Change permissions" (chmod) action
        // (available for any file). Requires root for system files.
        actions.add(
            FileAction.ChangePermissions(
                id = "chmod-${name}",
                path = file.absolutePath,
                mode = 420, // default; UI will prompt (0o644 = 420)
            )
        )

        // Universal "Change ownership" (chown) action
        // (available for any file). Requires root.
        actions.add(
            FileAction.ChangeOwnership(
                id = "chown-${name}",
                path = file.absolutePath,
                owner = null, // will be prompted by UI
                group = null,
            )
        )

        // Universal "Create symlink" action
        // (available for any file/directory).
        actions.add(
            FileAction.CreateSymlink(
                id = "symlink-${name}",
                targetPath = file.absolutePath,
                linkPath = "${file.parentFile?.absolutePath}/${file.name}.link",
            )
        )

        // Universal "Create EncFS volume" action
        // (available for any directory). Creates a new EncFS volume.
        if (file.isDirectory) {
            actions.add(
                FileAction.CreateEncFsVolume(
                    id = "encfs-create-${name}",
                    volumePath = "${file.absolutePath}.encfs",
                    password = "", // will be prompted by UI
                )
            )
        }

        // Universal "Mount EncFS volume" action
        // (available for .encfs directories or EncFS volume files).
        val isEncFs = ext == "encfs" || (file.isDirectory && File("${file.absolutePath}/.encfs6.xml").exists())
        if (isEncFs) {
            actions.add(
                FileAction.MountEncFsVolume(
                    id = "encfs-mount-${name}",
                    volumePath = file.absolutePath,
                    password = "", // will be prompted by UI
                    mountPoint = "${file.parentFile?.absolutePath}/${file.nameWithoutExtension}_mount",
                )
            )
        }

        // Universal "Unmount EncFS volume" action
        // (available for mounted EncFS volumes).
        // In a real implementation, we'd check if the path is a mounted EncFS volume.
        // For now, offer unmount for directories that look like mount points.
        if (file.isDirectory && file.name.endsWith("_mount")) {
            actions.add(
                FileAction.UnmountEncFsVolume(
                    id = "encfs-unmount-${name}",
                    mountPoint = file.absolutePath,
                )
            )
        }

        // PHASE 110 — append a malware scan
        // action to every list of extension-
        // matched actions. The scan is a
        // universal action: any file (a `.deb`,
        // an `.iso`, an `.exe`) can be scanned
        // for malware patterns. The user can
        // long-press a file → "Scan for
        // malware" without committing to the
        // primary action (install / run /
        // mount).
        if (actions.isNotEmpty()) {
            actions.add(scanForMalwareAction(file))
        }

        return actions
    }

    /**
     * PHASE 110 — the universal
     * [FileAction.ScanForMalware] factory. The
     * action targets the file's absolute path;
     * the [com.elysium.vanguard.core.fileactions.handlers.MalwareScanHandler]
     * reads the bytes + dispatches to the
     * [com.elysium.vanguard.core.security.malware.MalwareAnalyzer].
     *
     * The id is stable per file name so the UI
     * can identify the action across re-
     * resolutions.
     */
    private fun scanForMalwareAction(file: File): FileAction.ScanForMalware =
        FileAction.ScanForMalware(
            id = "scan-malware-${file.name}",
            targetPath = file.absolutePath,
            displayName = file.name,
        )
}
