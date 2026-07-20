package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.DiskImageFormat
import com.elysium.vanguard.core.fileactions.FileAction
import java.io.File

/**
 * Phase 93 — the **disk image handler** for
 * `.iso`, `.img`, and `.qcow2` files.
 *
 * The handler is a thin shell over the existing
 * `QemuWindowsVmBackend` (for QCOW2) + a
 * read-only mount primitive (for ISO / IMG).
 * It (1) verifies the image file exists, (2)
 * branches on the format, (3) returns a
 * [DiskImageResult] describing what was done
 * (mounted, booted, or rejected).
 *
 * **Format-specific behavior**:
 *
 * - **ISO** (`.iso`): mounted read-only via
 *   the Android `LoopManager` + a read-only
 *   `mount` syscall. The user can browse the
 *   contents as a folder.
 * - **IMG** (`.img`): same as ISO, but the
 *   filesystem may not be ISO-9660 (could be
 *   ext4, FAT, etc.). The mount primitive
 *   auto-detects.
 * - **QCOW2** (`.qcow2`): converted to raw
 *   first (via `qemu-img convert -O raw`), then
 *   mounted; or, if the user picked "Boot VM",
 *   passed directly to QEMU.
 *
 * **JVM testability**: the handler takes a
 * [DiskImageBackend] interface in its
 * constructor; production uses the real
 * backend; tests use a fake.
 */
class DiskImageHandler(
    private val backend: DiskImageBackend,
) {

    /**
     * Mount the image read-only. The result
     * carries the mount-point path on success
     * (the user can browse the contents as a
     * folder).
     */
    suspend fun mount(action: FileAction.MountDiskImage): DiskImageResult {
        val imageFile = File(action.imagePath)
        if (!imageFile.exists() || !imageFile.isFile) {
            return DiskImageResult.Failure(
                message = "image file not found: ${action.imagePath}"
            )
        }
        return backend.mountReadOnly(imageFile, action.imageFormat)
    }

    /**
     * Boot a VM from the image. The
     * [preferredVmId] (when set) selects the
     * VM to boot; the backend creates a new
     * ephemeral VM if no preference is set.
     */
    suspend fun boot(action: FileAction.BootVmFromImage): DiskImageResult {
        val imageFile = File(action.imagePath)
        if (!imageFile.exists() || !imageFile.isFile) {
            return DiskImageResult.Failure(
                message = "image file not found: ${action.imagePath}"
            )
        }
        return backend.bootVm(imageFile, action.imageFormat, action.preferredVmId)
    }
}

/**
 * The [DiskImageBackend] decouples the
 * [DiskImageHandler] from the underlying mount
 * + VM primitives. Production wraps the
 * `LoopManager` + `QemuWindowsVmBackend`; tests
 * use a fake.
 */
interface DiskImageBackend {
    suspend fun mountReadOnly(
        image: File,
        format: DiskImageFormat,
    ): DiskImageResult

    suspend fun bootVm(
        image: File,
        format: DiskImageFormat,
        preferredVmId: String?,
    ): DiskImageResult
}

/**
 * The result of a disk-image action. A
 * sealed class so the caller pattern-matches
 * on the outcome.
 */
sealed class DiskImageResult {
    data class Mounted(
        val mountPoint: String,
        val format: DiskImageFormat,
    ) : DiskImageResult()

    data class VmBooted(
        val vmId: String,
        val format: DiskImageFormat,
    ) : DiskImageResult()

    data class Failure(
        val message: String,
    ) : DiskImageResult()
}
