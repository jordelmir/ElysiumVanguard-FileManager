package com.elysium.vanguard.core.fileactions.handlers

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.elysium.vanguard.core.fileactions.FileAction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Phase 98 — the **USB OTG handler** for `.usbotg`
 * descriptor files.
 *
 * The handler reads the block-device path from the
 * descriptor file body, verifies the device is
 * attached via [UsbManager], enumerates the partitions,
 * and mounts the first readable one read-only.
 *
 * **Descriptor format** — the file body is a single
 * non-blank, non-comment line containing the block
 * device path (e.g. `/dev/block/sda1` or `auto` to
 * auto-detect the first attached mass-storage device):
 *
 * ```
 * # my USB stick
 * /dev/block/sda1
 * ```
 *
 * or simply `auto` to let the handler find the first
 * attached mass-storage device.
 *
 * **Why a descriptor at all?** The vision calls for
 * "USB OTG — no dedicado (probablemente via SAF,
 * pero no como acción)". The descriptor pattern makes
 * the action consistent with the other 4 handlers
 * (.deb / .iso / .git / .smb): drop a file → long-press
 * → contextual action sheet → tap → action runs.
 *
 * **JVM testability**: the handler takes a
 * [UsbOtgInspector] interface in its constructor;
 * production wraps the Android [UsbManager];
 * tests use a fake.
 */
class UsbOtgHandler @Inject constructor(
    private val inspector: UsbOtgInspector,
) {

    /**
     * Inspect the USB device described by [action] and
     * mount the first readable partition read-only.
     * The [action] carries the descriptor file path;
     * the handler reads the body to discover the
     * block device.
     */
    suspend fun inspect(action: FileAction.InspectUsbOtgDevice): UsbOtgInspectResult {
        val descriptor = File(action.blockDevice)
        val blockPath = if (descriptor.exists() && descriptor.isFile) {
            try {
                descriptor.readLines().firstOrNull {
                    it.isNotBlank() && !it.trim().startsWith("#")
                }?.trim()
            } catch (e: Exception) {
                null
            }
        } else {
            // The action's blockDevice field is a path
            // even when no descriptor file exists (the
            // resolver passes the absolute path as a
            // placeholder). Treat it as the literal
            // path.
            action.blockDevice.trim()
        }
        if (blockPath.isNullOrBlank()) {
            return UsbOtgInspectResult.Failure(
                message = "USB descriptor has no block-device path: ${action.blockDevice}"
            )
        }
        if (blockPath == "auto") {
            val firstDevice = inspector.findFirstMassStorageDevice()
                ?: return UsbOtgInspectResult.Failure(
                    message = "no attached USB mass-storage device"
                )
            return mountFirstPartition(firstDevice)
        }
        // The path is a literal block device; mount
        // it directly.
        val device = inspector.findByBlockPath(blockPath)
        if (device == null) {
            return UsbOtgInspectResult.Failure(
                message = "USB device not attached: $blockPath"
            )
        }
        return mountFirstPartition(device)
    }

    private suspend fun mountFirstPartition(
        device: UsbDeviceSummary,
    ): UsbOtgInspectResult {
        val partition = inspector.firstReadablePartition(device)
            ?: return UsbOtgInspectResult.Failure(
                message = "no readable partition on ${device.productName ?: device.deviceId}"
            )
        return inspector.mountReadOnly(partition)
    }
}

/**
 * A [UsbOtgInspector] decouples the [UsbOtgHandler] from
 * the Android [UsbManager] + filesystem mount primitive.
 * Production wraps the [UsbManager] + the
 * [com.elysium.vanguard.core.runtime.runner.ProcessLauncher];
 * tests use a fake.
 */
interface UsbOtgInspector {
    fun findFirstMassStorageDevice(): UsbDeviceSummary?
    fun findByBlockPath(blockPath: String): UsbDeviceSummary?
    fun firstReadablePartition(device: UsbDeviceSummary): UsbPartition?
    suspend fun mountReadOnly(partition: UsbPartition): UsbOtgInspectResult
}

/**
 * A summary of an attached USB device. The
 * [com.elysium.vanguard.core.runtime.windows.WindowsVmManager]
 * also has a `UsbDevice` model; the summary here is
 * intentionally narrower (we only need id + name).
 */
data class UsbDeviceSummary(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
)

/**
 * A single partition on a USB mass-storage device.
 * The handler mounts the first one the inspector
 * reports as readable.
 */
data class UsbPartition(
    val device: UsbDeviceSummary,
    val blockPath: String,
    val fsType: String?,
)

/**
 * The result of a USB OTG inspection + mount.
 */
sealed class UsbOtgInspectResult {
    data class Mounted(
        val device: UsbDeviceSummary,
        val partition: UsbPartition,
        val mountPoint: String,
    ) : UsbOtgInspectResult()

    data class Unmounted(
        val device: UsbDeviceSummary,
        val partitions: List<UsbPartition>,
    ) : UsbOtgInspectResult()

    data class Failure(
        val message: String,
    ) : UsbOtgInspectResult()
}
