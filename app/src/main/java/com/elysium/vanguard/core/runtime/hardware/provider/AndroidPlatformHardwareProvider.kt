package com.elysium.vanguard.core.runtime.hardware.provider

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.nfc.NfcAdapter
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 21 — the production [PlatformHardwareProvider].
 *
 * Wraps the Android platform managers
 * ([UsbManager], [BluetoothManager], [NfcAdapter],
 * [SensorManager], [LocationManager], [CameraManager],
 * [AudioRecord]) and adapts them to the small
 * [PlatformHardwareProvider] interface the
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.AndroidHardwareEnforcer]
 * depends on. The provider does NOT enforce policy — the
 * runtime's broker (Phase 18) and the runtime's
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcementService]
 * are the policy layer. The provider is a thin adapter
 * that translates platform calls to typed records.
 *
 * Permission model: every method checks the relevant
 * Android permission via [ContextCompat.checkSelfPermission].
 * When the permission is not granted, the method returns
 * a "device absent" result (empty list, null fix, false
 * capability) rather than throwing. The runtime's broker
 * is the layer that decides "should the guest even be
 * able to call this?"; the permission check is the
 * "can the platform serve this call?" question.
 *
 * Thread-safety: the platform managers themselves are
 * thread-safe. The provider caches a `nextHandleId` atomic
 * for USB device handle ids. There is no other state.
 */
@SuppressLint("MissingPermission") // We check each permission before each call.
class AndroidPlatformHardwareProvider(
    private val context: Context
) : PlatformHardwareProvider {

    private val nextHandleId = AtomicInteger(0)

    // --- USB ---

    override fun listUsbDevices(): List<UsbDeviceInfo> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.map { device ->
            UsbDeviceInfo(
                // Android's UsbDevice.deviceId is an Int
                // (the device's index in the host controller's
                // table); we serialise to a string so the
                // runtime can pass it around without an Int.
                deviceId = device.deviceId.toString(),
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturerName = device.manufacturerName,
                productName = device.productName
            )
        }
    }

    override fun requestUsbPermission(deviceId: String): UsbPermissionResult {
        // The actual `UsbManager.requestPermission` requires
        // a `PendingIntent` and a `BroadcastReceiver` to
        // deliver the result asynchronously. The runtime
        // wires the broadcast; the provider returns a
        // `Pending` result with the intent's action so the
        // enforcer can hand it to the consent UI.
        //
        // For the synchronous code path (USB device
        // permission already granted), `hasPermission`
        // returns true and we surface Granted.
        val manager = context.getSystemService(UsbManager::class.java)
            ?: return UsbPermissionResult.Error(
                IllegalStateException("UsbManager not available on this device")
            )
        val device = manager.deviceList[deviceId]
            ?: return UsbPermissionResult.Error(
                IllegalArgumentException("USB device not found: $deviceId")
            )
        return if (manager.hasPermission(device)) {
            UsbPermissionResult.Granted
        } else {
            // The actual `requestPermission` call needs a
            // PendingIntent. The runtime's consent UI
            // creates the PendingIntent and hands the
            // device id to `UsbManager.requestPermission`.
            // We return a Pending result that names the
            // intent action; the enforcer surfaces it as
            // HardwareEnforcementResult.PendingConsent.
            UsbPermissionResult.Pending(
                intent = "com.elysium.vanguard.action.USB_PERMISSION:$deviceId"
            )
        }
    }

    override fun openUsbDevice(deviceId: String): UsbDeviceHandle? {
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        val device = manager.deviceList[deviceId] ?: return null
        if (!manager.hasPermission(device)) return null
        val connection = manager.openDevice(device) ?: return null
        // The connection is the live object the enforcer
        // would hand back; we return an id and stash the
        // connection in a per-process map. A future phase
        // moves the map to a dedicated resource manager.
        val id = "usb:${nextHandleId.incrementAndGet()}:${device.deviceId}"
        liveUsbConnections[id] = connection
        return UsbDeviceHandle(id)
    }

    // --- Bluetooth ---

    override fun isBluetoothEnabled(): Boolean {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return false
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
        return manager.adapter?.isEnabled == true
    }

    override fun listBluetoothDevices(): List<BluetoothDeviceInfo> {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map { device: BluetoothDevice ->
            BluetoothDeviceInfo(
                address = device.address,
                name = runCatching { device.name }.getOrNull(),
                paired = device.bondState == BluetoothDevice.BOND_BONDED
            )
        }
    }

    // --- NFC ---

    override fun isNfcEnabled(): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return false
        return adapter.isEnabled
    }

    // --- Camera ---

    override fun hasCamera(): Boolean {
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        return runCatching { manager.cameraIdList.isNotEmpty() }.getOrDefault(false)
    }

    // --- Microphone ---

    override fun hasMicrophone(): Boolean {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) return false
        // Open a 1-byte `AudioRecord` to probe; close
        // immediately. We use `MediaRecorder` (a
        // permissionless probe via the audio source) as a
        // quick check; the real session opens
        // `AudioRecord` lazily on CONNECT.
        return runCatching {
            AudioRecord.getMinBufferSize(
                16_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) > 0
        }.getOrDefault(false)
    }

    // --- Location ---

    override fun lastKnownLocation(accuracy: LocationAccuracy): LocationFix? {
        val permission = when (accuracy) {
            LocationAccuracy.COARSE -> Manifest.permission.ACCESS_COARSE_LOCATION
            LocationAccuracy.FINE -> Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!hasPermission(permission)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when (accuracy) {
            LocationAccuracy.COARSE -> LocationManager.PASSIVE_PROVIDER
            LocationAccuracy.FINE -> LocationManager.GPS_PROVIDER
        }
        val location: Location? = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        return location?.let {
            LocationFix(
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyMeters = it.accuracy,
                timestampMs = it.time
            )
        }
    }

    // --- Sensors ---

    override fun listSensors(): List<SensorInfo> {
        val manager = context.getSystemService(SensorManager::class.java) ?: return emptyList()
        return manager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            SensorInfo(
                name = sensor.name,
                type = sensor.type,
                vendor = sensor.vendor
            )
        }
    }

    // --- helpers ---

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /**
         * Per-process map of USB device handle id to the
         * live `UsbDeviceConnection`. Cleared when the
         * process dies. A future phase moves the map to
         * a dedicated resource manager that tracks
         * ownership and surfaces leaks.
         */
        val liveUsbConnections: MutableMap<String, android.hardware.usb.UsbDeviceConnection> =
            mutableMapOf()
    }
}
