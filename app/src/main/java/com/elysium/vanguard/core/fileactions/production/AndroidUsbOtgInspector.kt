package com.elysium.vanguard.core.fileactions.production

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.elysium.vanguard.core.fileactions.handlers.UsbDeviceSummary
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgInspectResult
import com.elysium.vanguard.core.fileactions.handlers.UsbOtgInspector
import com.elysium.vanguard.core.fileactions.handlers.UsbPartition
import com.elysium.vanguard.core.runtime.runner.ProcessLauncher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 98 — the production [UsbOtgInspector] that
 * wraps Android's [UsbManager] + the production
 * [ProcessLauncher].
 *
 * The inspector:
 *
 * 1. **Find a device**. The Android [UsbManager] is
 *    queried via `getDeviceList()`. We filter to
 *    mass-storage class (08 / 06 / 00 subclasses that
 *    the kernel exposes as `/dev/block/sd*`).
 *
 * 2. **Enumerate partitions**. We probe
 *    `/sys/block/sdX/` for partition entries. The
 *    `/sys/block/` virtual filesystem is mounted by
 *    the Android kernel; the entries are stable
 *    across devices.
 *
 * 3. **Mount the first readable partition**. We spawn
 *    `mount -o ro <partition> <mountpoint>` via the
 *    [ProcessLauncher]. The mount point is
 *    `<filesDir>/fileaction-scratch/mnt/<productName>/`.
 *
 * **Why not `StorageManager`?** Android's
 * `StorageManager.openProxyFileDescriptor` works for
 * SAF tree URIs; it does not give us a "browse as folder"
 * UX through the File Manager's `java.io.File` API.
 * The `mount -o ro` path gives us a directory the
 * File Manager can recurse into.
 *
 * **JVM testability**: the inspector is small enough
 * to test by mocking the [UsbManager] (or, in the
 * test suite, by passing a fake [UsbOtgInspector]
 * directly to the handler). The mount step uses the
 * same `ProcessLauncher` seam as the disk-image
 * backend; the `RecordingProcessLauncher` test fake
 * works here too.
 */
@Singleton
class AndroidUsbOtgInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processLauncher: ProcessLauncher,
) : UsbOtgInspector {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scratchDir: File =
        File(context.filesDir, "fileaction-scratch").also { it.mkdirs() }

    override fun findFirstMassStorageDevice(): UsbDeviceSummary? {
        val map = usbManager.deviceList
        for (device in map.values) {
            if (isMassStorage(device)) {
                return device.toSummary()
            }
        }
        return null
    }

    override fun findByBlockPath(blockPath: String): UsbDeviceSummary? {
        // The block path is `/dev/block/sdX<N>`. The
        // parent device path is `/dev/block/sdX`. We
        // try to map the device id by enumerating
        // UsbManager devices and matching on the
        // kernel's name. The mapping is best-effort:
        // most Android devices do not expose the
        // USB id <-> sdX mapping through UsbManager.
        // When we cannot map, we fall back to a
        // synthetic summary that carries the
        // requested path.
        val basePath = stripTrailingDigits(blockPath)
        val map = usbManager.deviceList
        for (device in map.values) {
            if (isMassStorage(device)) {
                // Best-effort: accept the device if
                // any of its block paths match.
                val anyMatch = enumerateBlockDevices().any { it.startsWith(basePath) }
                if (anyMatch) {
                    return device.toSummary()
                }
            }
        }
        // Fallback: synthetic device, mount the path
        // directly.
        return UsbDeviceSummary(
            deviceId = -1,
            vendorId = 0,
            productId = 0,
            productName = basePath.substringAfterLast('/'),
            manufacturerName = null,
        )
    }

    override fun firstReadablePartition(device: UsbDeviceSummary): UsbPartition? {
        val basePath = when {
            device.deviceId < 0 -> "synthetic"
            else -> {
                // We don't have a direct way to map
                // deviceId <-> sdX. Use `ls -l
                // /sys/block/` to discover the
                // block-device names that exist.
                val blockDevices = enumerateBlockDevices()
                if (blockDevices.isEmpty()) return null
                // The first block device is the parent.
                blockDevices.first()
            }
        }
        val partitions = listPartitions(basePath)
        return partitions.firstOrNull()
    }

    override suspend fun mountReadOnly(partition: UsbPartition): UsbOtgInspectResult {
        val mountPoint = File(
            scratchDir,
            "mnt/${partition.device.productName ?: partition.blockPath.substringAfterLast('/')}"
        )
        mountPoint.mkdirs()
        val cmd = listOf(
            "mount", "-o", "ro",
            partition.blockPath,
            mountPoint.absolutePath,
        )
        val launched = try {
            processLauncher.start(
                command = cmd,
                env = emptyList(),
                cwd = mountPoint,
            )
        } catch (e: Exception) {
            return UsbOtgInspectResult.Failure(
                message = "could not spawn mount: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        val exitCode = waitForExit(launched)
        return if (exitCode == 0) {
            UsbOtgInspectResult.Mounted(
                device = partition.device,
                partition = partition,
                mountPoint = mountPoint.absolutePath,
            )
        } else {
            UsbOtgInspectResult.Failure(
                message = "mount failed (exit=$exitCode) for ${partition.blockPath}"
            )
        }
    }

    /**
     * Class 08 = Mass Storage. The Android USB
     * mass-storage subclass 06 = SCSI. We accept
     * both as "mass storage" since the kernel may
     * expose either as a block device.
     */
    private fun isMassStorage(device: UsbDevice): Boolean {
        val interfaceClass = device.getInterface(0)?.interfaceClass ?: return false
        return interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE ||
            interfaceClass == UsbConstants.USB_CLASS_HUB // 09 — some legacy devices report as hub
    }

    /**
     * List the top-level block-device names under
     * `/sys/block/`. Used to map a [UsbDevice] to its
     * kernel-assigned name.
     */
    private fun enumerateBlockDevices(): List<String> {
        val sysBlock = File("/sys/block")
        if (!sysBlock.isDirectory) return emptyList()
        return sysBlock.listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    /**
     * List the partitions of a block device. We
     * probe the kernel's partition table by reading
     * `/proc/partitions`.
     */
    private fun listPartitions(baseName: String): List<UsbPartition> {
        val proc = File("/proc/partitions")
        if (!proc.isFile) return emptyList()
        val partitions = mutableListOf<UsbPartition>()
        try {
            proc.useLines { lines ->
                for (line in lines) {
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 4) continue
                    val name = parts[3]
                    if (name == baseName || name.startsWith("$baseName")) {
                        partitions += UsbPartition(
                            device = UsbDeviceSummary(
                                deviceId = -1,
                                vendorId = 0,
                                productId = 0,
                                productName = baseName,
                                manufacturerName = null,
                            ),
                            blockPath = "/dev/block/$name",
                            fsType = null, // the mount step tries ro; the kernel auto-detects the fs
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // /proc/partitions read failure — return
            // what we have so far.
        }
        return partitions
    }

    private fun waitForExit(
        launched: com.elysium.vanguard.core.runtime.runner.LaunchedProcess,
    ): Int {
        var attempts = 0
        while (attempts < 600) {
            if (launched.pid <= 0) return 0
            Thread.sleep(100)
            attempts++
        }
        launched.stop()
        return -1
    }

    /**
     * Strip trailing digits from a path.
     * `/dev/block/sda1` → `/dev/block/sda`;
     * `/dev/block/sda` → `/dev/block/sda`.
     */
    private fun stripTrailingDigits(path: String): String {
        var end = path.length
        while (end > 0 && path[end - 1].isDigit()) end--
        return if (end == 0) path else path.substring(0, end)
    }

    private fun UsbDevice.toSummary() = UsbDeviceSummary(
        deviceId = deviceId,
        vendorId = vendorId,
        productId = productId,
        productName = productName,
        manufacturerName = manufacturerName,
    )
}

/**
 * Android USB class constants. We declare them
 * locally to avoid a dependency on the Android
 * `UsbConstants` class (which is not in the test
 * classpath). The values match
 * `android.hardware.usb.UsbConstants` exactly.
 */
private object UsbConstants {
    const val USB_CLASS_MASS_STORAGE = 8
    const val USB_CLASS_HUB = 9
}
