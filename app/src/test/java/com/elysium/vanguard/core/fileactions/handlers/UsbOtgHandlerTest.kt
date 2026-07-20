package com.elysium.vanguard.core.fileactions.handlers

import com.elysium.vanguard.core.fileactions.FileAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 98 — the test suite for the
 * [UsbOtgHandler]. The handler reads the
 * block-device path from the descriptor file
 * body (or treats the action's path as the
 * literal), and delegates the partition
 * discovery + mount to the [UsbOtgInspector].
 * The inspector is a fake in tests; production
 * uses the [com.elysium.vanguard.core.fileactions.production.AndroidUsbOtgInspector]
 * wrapping Android's
 * [android.hardware.usb.UsbManager].
 */
class UsbOtgHandlerTest {

    @get:Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `inspect with a literal block path mounts the first partition`() = runTest {
        val device = UsbDeviceSummary(
            deviceId = 42,
            vendorId = 0x0781,
            productId = 0x5571,
            productName = "SanDisk Cruzer",
            manufacturerName = "SanDisk",
        )
        val partition = UsbPartition(
            device = device,
            blockPath = "/dev/block/sda1",
            fsType = "vfat",
        )
        val inspector = RecordingUsbOtgInspector(
            findByBlockResult = device,
            firstPartitionResult = partition,
            mountResult = UsbOtgInspectResult.Mounted(
                device = device,
                partition = partition,
                mountPoint = "/mnt/usb",
            ),
        )
        val handler = UsbOtgHandler(inspector)
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = "/dev/block/sda1",
        )
        val result = handler.inspect(action)
        assertTrue("expected Mounted, got $result", result is UsbOtgInspectResult.Mounted)
        val mounted = result as UsbOtgInspectResult.Mounted
        assertEquals("/dev/block/sda1", mounted.partition.blockPath)
        assertEquals(1, inspector.mountCalls.size)
        assertEquals("/dev/block/sda1", inspector.mountCalls[0].blockPath)
    }

    @Test
    fun `inspect with auto discovers the first attached mass storage device`() = runTest {
        val device = UsbDeviceSummary(
            deviceId = 7,
            vendorId = 0x0951,
            productId = 0x1666,
            productName = "Kingston",
            manufacturerName = "Kingston",
        )
        val partition = UsbPartition(
            device = device,
            blockPath = "/dev/block/sdb1",
            fsType = "ext4",
        )
        val inspector = RecordingUsbOtgInspector(
            firstMassStorageResult = device,
            firstPartitionResult = partition,
            mountResult = UsbOtgInspectResult.Mounted(
                device = device,
                partition = partition,
                mountPoint = "/mnt/kingston",
            ),
        )
        val handler = UsbOtgHandler(inspector)
        val descriptor = tmp.newFile("auto.usbotg")
        descriptor.writeText("# my USB stick\nauto\n")
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = descriptor.absolutePath,
        )
        val result = handler.inspect(action)
        assertTrue("expected Mounted, got $result", result is UsbOtgInspectResult.Mounted)
        assertEquals(1, inspector.findFirstMassStorageCalls)
    }

    @Test
    fun `inspect with descriptor file reads the block path from the body`() = runTest {
        val device = UsbDeviceSummary(
            deviceId = 1,
            vendorId = 0x1234,
            productId = 0x5678,
            productName = "Test",
            manufacturerName = "Test",
        )
        val partition = UsbPartition(
            device = device,
            blockPath = "/dev/block/sdc1",
            fsType = "ntfs",
        )
        val inspector = RecordingUsbOtgInspector(
            findByBlockResult = device,
            firstPartitionResult = partition,
            mountResult = UsbOtgInspectResult.Mounted(
                device = device,
                partition = partition,
                mountPoint = "/mnt/test",
            ),
        )
        val handler = UsbOtgHandler(inspector)
        val descriptor = tmp.newFile("myusb.usbotg")
        descriptor.writeText(
            "# my USB stick\n" +
                "\n" +
                "/dev/block/sdc1\n"
        )
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = descriptor.absolutePath,
        )
        val result = handler.inspect(action)
        assertTrue("expected Mounted, got $result", result is UsbOtgInspectResult.Mounted)
        assertEquals("/dev/block/sdc1", inspector.findByBlockCalls[0])
    }

    @Test
    fun `inspect with no attached device returns Failure`() = runTest {
        val inspector = RecordingUsbOtgInspector(
            firstMassStorageResult = null,
        )
        val handler = UsbOtgHandler(inspector)
        val descriptor = tmp.newFile("auto.usbotg")
        descriptor.writeText("auto\n")
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = descriptor.absolutePath,
        )
        val result = handler.inspect(action)
        assertTrue("expected Failure, got $result", result is UsbOtgInspectResult.Failure)
    }

    @Test
    fun `inspect with a literal block path that the kernel does not have returns Failure`() = runTest {
        val inspector = RecordingUsbOtgInspector(
            findByBlockResult = null,
        )
        val handler = UsbOtgHandler(inspector)
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = "/dev/block/sdz99",
        )
        val result = handler.inspect(action)
        assertTrue(result is UsbOtgInspectResult.Failure)
    }

    @Test
    fun `inspect with empty descriptor body returns Failure`() = runTest {
        val inspector = RecordingUsbOtgInspector()
        val handler = UsbOtgHandler(inspector)
        val descriptor = tmp.newFile("empty.usbotg")
        descriptor.writeText("# only a comment, no path\n")
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = descriptor.absolutePath,
        )
        val result = handler.inspect(action)
        assertTrue("expected Failure, got $result", result is UsbOtgInspectResult.Failure)
    }

    @Test
    fun `inspect with a device that has no readable partitions returns Failure`() = runTest {
        val device = UsbDeviceSummary(
            deviceId = 1,
            vendorId = 0x1234,
            productId = 0x5678,
            productName = "NoPartitions",
            manufacturerName = "Test",
        )
        val inspector = RecordingUsbOtgInspector(
            findByBlockResult = device,
            firstPartitionResult = null,
        )
        val handler = UsbOtgHandler(inspector)
        val action = FileAction.InspectUsbOtgDevice(
            id = "test",
            blockDevice = "/dev/block/sda1",
        )
        val result = handler.inspect(action)
        assertTrue("expected Failure, got $result", result is UsbOtgInspectResult.Failure)
    }
}

private class RecordingUsbOtgInspector(
    private val firstMassStorageResult: UsbDeviceSummary? = null,
    private val findByBlockResult: UsbDeviceSummary? = null,
    private val firstPartitionResult: UsbPartition? = null,
    private val mountResult: UsbOtgInspectResult = UsbOtgInspectResult.Failure(
        message = "no test result configured"
    ),
) : UsbOtgInspector {
    var findFirstMassStorageCalls: Int = 0
    val findByBlockCalls: MutableList<String> = mutableListOf()
    val mountCalls: MutableList<UsbPartition> = mutableListOf()

    override fun findFirstMassStorageDevice(): UsbDeviceSummary? {
        findFirstMassStorageCalls++
        return firstMassStorageResult
    }

    override fun findByBlockPath(blockPath: String): UsbDeviceSummary? {
        findByBlockCalls.add(blockPath)
        return findByBlockResult
    }

    override fun firstReadablePartition(device: UsbDeviceSummary): UsbPartition? =
        firstPartitionResult

    override suspend fun mountReadOnly(partition: UsbPartition): UsbOtgInspectResult {
        mountCalls.add(partition)
        return mountResult
    }
}
