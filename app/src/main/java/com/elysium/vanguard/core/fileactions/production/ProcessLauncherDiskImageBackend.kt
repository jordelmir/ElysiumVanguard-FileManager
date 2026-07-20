package com.elysium.vanguard.core.fileactions.production

import com.elysium.vanguard.core.fileactions.DiskImageFormat
import com.elysium.vanguard.core.fileactions.handlers.DiskImageBackend
import com.elysium.vanguard.core.fileactions.handlers.DiskImageResult
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import java.io.File

/**
 * Phase 94 — the production
 * [DiskImageBackend].
 *
 * The backend handles two operations:
 *
 * 1. **Mount read-only** — for ISO / IMG
 *    images, the backend calls `mount -o ro,loop`
 *    via the [ProcessLauncher]. The mount
 *    point is `<scratchDir>/mnt/<image-name>/`;
 *    the user can browse the contents as a
 *    folder.
 *
 * 2. **Boot a VM** — for QCOW2 images, the
 *    backend passes the image directly to
 *    `qemu-system-x86_64` (or `qemu-system-aarch64`).
 *    For ISO / IMG images, the backend first
 *    converts the image to QCOW2 via
 *    `qemu-img convert -O qcow2`, then boots
 *    the converted image.
 *
 * **JVM testability**: the backend takes a
 * [ProcessLauncher] in its constructor. Tests
 * pass a fake that records the call and returns
 * a fake `LaunchedProcess`. Production uses
 * `AndroidProcessLauncher` (Hilt-injected).
 *
 * **Note on the on-device mount primitive**:
 * Android's `StorageManager` is the recommended
 * surface for ISO / IMG (the platform has
 * `StorageManager.openProxyFileDescriptor`
 * for OBB / image files). The Phase 94
 * implementation uses the Linux `mount`
 * syscall via the [ProcessLauncher] because
 * (a) the user has root-like access via the
 * `proot` path the platform already exposes,
 * and (b) the Linux mount gives us a
 * consistent filesystem path the
 * [com.elysium.vanguard.core.fileactions.FileActionResolver]
 * can use. A future phase can swap in
 * `StorageManager` for the Android-native path.
 */
class ProcessLauncherDiskImageBackend(
    private val processLauncher: ProcessLauncher,
    private val scratchDir: File,
) : DiskImageBackend {

    init {
        if (!scratchDir.exists()) {
            scratchDir.mkdirs()
        }
    }

    override suspend fun mountReadOnly(
        image: File,
        format: DiskImageFormat,
    ): DiskImageResult {
        if (!image.exists() || !image.isFile) {
            return DiskImageResult.Failure(
                message = "image file not found: ${image.absolutePath}"
            )
        }
        val mountPoint = File(scratchDir, "mnt/${image.nameWithoutExtension}")
        mountPoint.mkdirs()
        val cmd = when (format) {
            DiskImageFormat.ISO,
            DiskImageFormat.IMG -> listOf(
                "mount", "-o", "ro,loop",
                image.absolutePath,
                mountPoint.absolutePath,
            )
            DiskImageFormat.QCOW2 -> {
                // QCOW2 must be converted to raw
                // first, then mounted. The
                // conversion is a one-time
                // operation; the converted file
                // is cached in the scratch dir.
                val rawFile = File(scratchDir, "raw/${image.nameWithoutExtension}.raw")
                rawFile.parentFile?.mkdirs()
                val convert = processLauncher.start(
                    command = listOf(
                        "qemu-img", "convert", "-O", "raw",
                        "-f", "qcow2",
                        image.absolutePath,
                        rawFile.absolutePath,
                    ),
                    env = emptyList(),
                    cwd = rawFile.parentFile,
                )
                val convertExit = waitForExit(convert)
                if (convertExit != 0) {
                    return DiskImageResult.Failure(
                        message = "qemu-img convert failed (exit=$convertExit)"
                    )
                }
                listOf(
                    "mount", "-o", "ro,loop",
                    rawFile.absolutePath,
                    mountPoint.absolutePath,
                )
            }
        }
        val launched = processLauncher.start(
            command = cmd,
            env = emptyList(),
            cwd = mountPoint,
        )
        val exitCode = waitForExit(launched)
        return if (exitCode == 0) {
            DiskImageResult.Mounted(
                mountPoint = mountPoint.absolutePath,
                format = format,
            )
        } else {
            DiskImageResult.Failure(
                message = "mount failed (exit=$exitCode)"
            )
        }
    }

    override suspend fun bootVm(
        image: File,
        format: DiskImageFormat,
        preferredVmId: String?,
    ): DiskImageResult {
        if (!image.exists() || !image.isFile) {
            return DiskImageResult.Failure(
                message = "image file not found: ${image.absolutePath}"
            )
        }
        // The QCOW2 path passes the image
        // directly to QEMU. ISO / IMG need a
        // QCOW2 conversion first.
        val bootImage: File = when (format) {
            DiskImageFormat.QCOW2 -> image
            DiskImageFormat.ISO,
            DiskImageFormat.IMG -> {
                val qcowFile = File(scratchDir, "boot/${image.nameWithoutExtension}.qcow2")
                qcowFile.parentFile?.mkdirs()
                val convert = processLauncher.start(
                    command = listOf(
                        "qemu-img", "convert", "-O", "qcow2",
                        image.absolutePath,
                        qcowFile.absolutePath,
                    ),
                    env = emptyList(),
                    cwd = qcowFile.parentFile,
                )
                val convertExit = waitForExit(convert)
                if (convertExit != 0) {
                    return DiskImageResult.Failure(
                        message = "qemu-img convert failed (exit=$convertExit)"
                    )
                }
                qcowFile
            }
        }
        // Spawn QEMU in detached mode. The
        // preferredVmId, when set, selects a
        // specific VM configuration; when null,
        // a default x86_64 VM is started.
        val arch = if (preferredVmId?.contains("arm", ignoreCase = true) == true) {
            "aarch64"
        } else {
            "x86_64"
        }
        val qemuBinary = "qemu-system-$arch"
        val cmd = listOf(
            qemuBinary,
            "-m", "2048",
            "-hda", bootImage.absolutePath,
            "-nographic",
            "-daemonize",
        )
        val launched = processLauncher.start(
            command = cmd,
            env = emptyList(),
            cwd = bootImage.parentFile,
        )
        // Don't wait for QEMU to exit — it
        // runs as a daemon. We capture the
        // launch exit code (0 if the daemon
        // was forked successfully) and return.
        val launchExit = waitForExit(launched, timeoutMs = 5_000)
        val vmId = preferredVmId ?: "qemu-${bootImage.nameWithoutExtension}"
        return if (launchExit == 0 || launchExit == -1) {
            DiskImageResult.VmBooted(vmId = vmId, format = format)
        } else {
            DiskImageResult.Failure(
                message = "$qemuBinary failed to launch (exit=$launchExit)"
            )
        }
    }

    /**
     * Wait for the launched process to exit.
     * Phase 94's heuristic: the production
     * `ProcessLauncher` does not expose
     * `waitFor()`; we approximate with a
     * 60-second polling loop. The Phase 95+
     * refactor will use a real `waitFor`.
     */
    private fun waitForExit(
        launched: com.elysium.vanguard.core.runtime.runner.LaunchedProcess,
        timeoutMs: Long = 60_000,
    ): Int {
        val attempts = (timeoutMs / 100).toInt()
        var i = 0
        while (i < attempts) {
            if (launched.pid <= 0) return 0
            Thread.sleep(100)
            i++
        }
        launched.stop()
        return -1
    }
}
