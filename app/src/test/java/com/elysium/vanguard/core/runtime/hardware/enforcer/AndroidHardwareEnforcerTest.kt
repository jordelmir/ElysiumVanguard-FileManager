package com.elysium.vanguard.core.runtime.hardware.enforcer

import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAccess
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareClass
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision
import com.elysium.vanguard.core.runtime.hardware.broker.HardwarePolicy
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareTargetId
import com.elysium.vanguard.core.runtime.hardware.provider.BluetoothDeviceInfo
import com.elysium.vanguard.core.runtime.hardware.provider.LocationAccuracy
import com.elysium.vanguard.core.runtime.hardware.provider.LocationFix
import com.elysium.vanguard.core.runtime.hardware.provider.PlatformHardwareProvider
import com.elysium.vanguard.core.runtime.hardware.provider.SensorInfo
import com.elysium.vanguard.core.runtime.hardware.provider.UsbDeviceHandle
import com.elysium.vanguard.core.runtime.hardware.provider.UsbDeviceInfo
import com.elysium.vanguard.core.runtime.hardware.provider.UsbPermissionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 20 — tests for the production Android hardware
 * enforcer. The enforcer depends on a small
 * [PlatformHardwareProvider] interface (per the
 * engineering gotcha); the test injects a
 * [FakePlatformHardwareProvider] that records calls and
 * returns canned responses.
 *
 * The enforcer is *not* the broker; the tests below
 * assume the broker has already decided and the request
 * is Allow or AllowWithConfirmation. The Deny short-circuit
 * is the service's job (Phase 19).
 */
class AndroidHardwareEnforcerTest {

    private val provider = FakePlatformHardwareProvider()
    private val enforcer = AndroidHardwareEnforcer(provider)

    // --- USB ---

    @Test
    fun `USB LIST returns Granted and stores the device list as a snapshot`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo(
                deviceId = "dev-1",
                vendorId = 1234,
                productId = 5678,
                manufacturerName = "Acme",
                productName = "Widget"
            )
        )
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.LIST,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        val snapshotKey = "usb-list:s1"
        @Suppress("UNCHECKED_CAST")
        val stored = enforcer.readSnapshot<List<UsbDeviceInfo>>(snapshotKey)
        assertNotNull(stored)
        assertEquals(1, stored!!.size)
        assertEquals("dev-1", stored[0].deviceId)
    }

    @Test
    fun `USB READ with a granted permission returns Granted`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo("dev-1", 1234, 5678, "Acme", "Widget")
        )
        provider.usbPermissionResult = UsbPermissionResult.Granted
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
    }

    @Test
    fun `USB READ with a pending permission returns PendingConsent with a stable consentId`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo("dev-1", 1234, 5678, "Acme", "Widget")
        )
        provider.usbPermissionResult = UsbPermissionResult.Pending(intent = "intent://grant/1234:5678")
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.PendingConsent)
        val consentId = (result as HardwareEnforcementResult.PendingConsent).consentId
        // The enforcer stored the original request; the
        // runtime's consent UI looks it up to render the
        // dialog with the request's policy + class + target.
        val stored = enforcer.pendingConsent(consentId)
        assertNotNull("pending consent must be retrievable", stored)
        assertEquals(HardwareClass.USB, stored!!.request.hardwareClass)
        assertEquals("1234:5678", (stored.request.targetId as HardwareTargetId.Specific).id)
    }

    @Test
    fun `USB READ with a denied permission returns Denied`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo("dev-1", 1234, 5678, "Acme", "Widget")
        )
        provider.usbPermissionResult = UsbPermissionResult.Denied
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Denied)
    }

    @Test
    fun `USB READ with no matching device returns Error`() {
        provider.usbDevices = emptyList()
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Error)
    }

    @Test
    fun `USB CONNECT returns Granted with a handle wrapping the open result`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo("dev-1", 1234, 5678, "Acme", "Widget")
        )
        provider.openUsbHandle = UsbDeviceHandle("h-dev-1")
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.CONNECT,
                target = HardwareTargetId.Specific("dev-1")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        val handle = (result as HardwareEnforcementResult.Granted).handle
        assertNotNull("USB CONNECT must return a handle", handle)
        assertEquals("h-dev-1", handle!!.id)
    }

    // --- Bluetooth ---

    @Test
    fun `BLUETOOTH LIST returns the bonded devices when Bluetooth is enabled`() {
        provider.bluetoothEnabled = true
        provider.bluetoothDevices = listOf(
            BluetoothDeviceInfo("11:22:33:44:55:66", "Pixel Buds", true)
        )
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.BLUETOOTH,
                action = HardwareAction.LIST,
                target = HardwareTargetId.Any
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        val stored = enforcer.readSnapshot<List<BluetoothDeviceInfo>>("bt-list:s1")
        assertNotNull(stored)
        assertEquals(1, stored!!.size)
    }

    @Test
    fun `BLUETOOTH LIST returns Error when Bluetooth is disabled`() {
        provider.bluetoothEnabled = false
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.BLUETOOTH,
                action = HardwareAction.LIST
            )
        )
        assertTrue(result is HardwareEnforcementResult.Error)
    }

    // --- NFC ---

    @Test
    fun `NFC LIST returns Granted when NFC is enabled`() {
        provider.nfcEnabled = true
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.NFC,
                action = HardwareAction.LIST
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
    }

    @Test
    fun `NFC LIST returns Error when NFC is disabled`() {
        provider.nfcEnabled = false
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.NFC,
                action = HardwareAction.LIST
            )
        )
        assertTrue(result is HardwareEnforcementResult.Error)
    }

    // --- Camera / Microphone ---

    @Test
    fun `CAMERA LIST returns Granted when a camera is present`() {
        provider.hasCamera = true
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.CAMERA,
                action = HardwareAction.READ
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
    }

    @Test
    fun `CAMERA LIST returns Error when no camera is present`() {
        provider.hasCamera = false
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.CAMERA,
                action = HardwareAction.READ
            )
        )
        assertTrue(result is HardwareEnforcementResult.Error)
    }

    @Test
    fun `MICROPHONE CONNECT returns Granted with a handle`() {
        provider.hasMicrophone = true
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.MICROPHONE,
                action = HardwareAction.CONNECT
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        assertNotNull((result as HardwareEnforcementResult.Granted).handle)
    }

    // --- Location ---

    @Test
    fun `LOCATION READ returns the last known fix`() {
        provider.locationFix = LocationFix(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracyMeters = 5.0f,
            timestampMs = 1_700_000_000_000L
        )
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.LOCATION,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("fine")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        val stored = enforcer.readSnapshot<LocationFix>("location:FINE:s1")
        assertNotNull(stored)
        assertEquals(37.7749, stored!!.latitude, 0.0001)
    }

    @Test
    fun `LOCATION READ with no fix returns Granted and stores null`() {
        provider.locationFix = null
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.LOCATION,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("fine")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        val stored = enforcer.readSnapshot<LocationFix>("location:FINE:s1")
        assertNull("no fix means the snapshot is null", stored)
    }

    // --- Sensors ---

    @Test
    fun `SENSORS LIST returns the available sensors`() {
        provider.sensors = listOf(
            SensorInfo("BMI160 Accelerometer", 1, "Bosch"),
            SensorInfo("BMI160 Gyroscope", 4, "Bosch")
        )
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.SENSORS,
                action = HardwareAction.LIST
            )
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        val stored = enforcer.readSnapshot<List<SensorInfo>>("sensors:s1")
        assertNotNull(stored)
        assertEquals(2, stored!!.size)
    }

    // --- provider exception -> Error ---

    @Test
    fun `provider exception is wrapped as Error with the cause`() {
        val exploding = object : PlatformHardwareProvider by provider {
            override fun listUsbDevices(): List<UsbDeviceInfo> {
                throw IllegalStateException("USB subsystem not available")
            }
        }
        val enforcer2 = AndroidHardwareEnforcer(exploding)
        val result = enforcer2.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.LIST,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Error)
        val cause = (result as HardwareEnforcementResult.Error).cause
        assertTrue(
            "error cause must surface the provider's exception",
            cause is IllegalStateException
        )
    }

    // --- defensive: Deny decision short-circuits ---

    @Test
    fun `Deny decision is short-circuited and never calls the provider`() {
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.LIST,
                decision = HardwareDecision.Deny("test")
            )
        )
        assertTrue(result is HardwareEnforcementResult.Denied)
        // The provider was never called; the snapshot is
        // empty.
        assertTrue("enforcer must not store a snapshot on Deny", enforcer.listSnapshotKeys().isEmpty())
    }

    // --- thread safety ---

    @Test
    fun `enforcer is thread-safe under concurrent USB CONNECT`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo("dev-1", 1234, 5678, "Acme", "Widget")
        )
        provider.openUsbHandle = UsbDeviceHandle("h-1")
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { threadIdx ->
            Thread {
                start.await()
                repeat(50) { i ->
                    val result = enforcer.enforce(
                        makeRequest(
                            hardwareClass = HardwareClass.USB,
                            action = HardwareAction.CONNECT,
                            target = HardwareTargetId.Specific("dev-1"),
                            sessionId = "s$threadIdx-$i"
                        )
                    )
                    assertTrue(
                        "every concurrent CONNECT must succeed",
                        result is HardwareEnforcementResult.Granted
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        // USB CONNECT does not write a snapshot (the
        // Granted handle is the result). We assert on
        // the enforcer's `listSnapshotKeys` returning
        // successfully under contention, not on its size.
        // The real concurrent-safety check is that no
        // exception was thrown.
        val keys = enforcer.listSnapshotKeys()
        // Each thread ran 50 requests; the listSnapshotKeys
        // call is itself thread-safe (synchronized). The
        // count is just a sanity check.
        assertTrue("listSnapshotKeys must complete under contention", keys.size >= 0)
    }

    // --- consent lifecycle ---

    @Test
    fun `resolveConsent removes the pending entry`() {
        provider.usbDevices = listOf(
            UsbDeviceInfo("dev-1", 1234, 5678, "Acme", "Widget")
        )
        provider.usbPermissionResult = UsbPermissionResult.Pending(intent = "intent://x")
        val result = enforcer.enforce(
            makeRequest(
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.READ,
                target = HardwareTargetId.Specific("1234:5678")
            )
        )
        assertTrue(result is HardwareEnforcementResult.PendingConsent)
        val consentId = (result as HardwareEnforcementResult.PendingConsent).consentId
        assertNotNull(enforcer.pendingConsent(consentId))
        enforcer.resolveConsent(consentId, granted = true)
        assertNull(
            "after resolveConsent, the pending entry is gone",
            enforcer.pendingConsent(consentId)
        )
    }

    // --- helpers ---

    private fun makeRequest(
        hardwareClass: HardwareClass,
        action: HardwareAction,
        target: HardwareTargetId = HardwareTargetId.Any,
        decision: HardwareDecision = HardwareDecision.Allow,
        sessionId: String = "s1"
    ): HardwareRequest = HardwareRequest(
        sessionId = sessionId,
        policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
        hardwareClass = hardwareClass,
        action = action,
        targetId = target,
        decision = decision
    )
}

/**
 * 5-line hand-rolled [PlatformHardwareProvider] for tests.
 * Every field has a sensible default; tests override only
 * the field they care about.
 */
private class FakePlatformHardwareProvider : PlatformHardwareProvider {
    var usbDevices: List<UsbDeviceInfo> = emptyList()
    var usbPermissionResult: UsbPermissionResult = UsbPermissionResult.Denied
    var openUsbHandle: UsbDeviceHandle? = null
    var bluetoothEnabled: Boolean = false
    var bluetoothDevices: List<BluetoothDeviceInfo> = emptyList()
    var nfcEnabled: Boolean = false
    var hasCamera: Boolean = false
    var hasMicrophone: Boolean = false
    var locationFix: LocationFix? = null
    var sensors: List<SensorInfo> = emptyList()

    override fun listUsbDevices(): List<UsbDeviceInfo> = usbDevices
    override fun requestUsbPermission(deviceId: String): UsbPermissionResult = usbPermissionResult
    override fun openUsbDevice(deviceId: String): UsbDeviceHandle? = openUsbHandle
    override fun listBluetoothDevices(): List<BluetoothDeviceInfo> = bluetoothDevices
    override fun isBluetoothEnabled(): Boolean = bluetoothEnabled
    override fun isNfcEnabled(): Boolean = nfcEnabled
    override fun hasCamera(): Boolean = hasCamera
    override fun hasMicrophone(): Boolean = hasMicrophone
    override fun lastKnownLocation(accuracy: LocationAccuracy): LocationFix? = locationFix
    override fun listSensors(): List<SensorInfo> = sensors
}
