package com.elysium.vanguard.core.runtime.hardware.provider

/**
 * Phase 20 — the small interface the [AndroidHardwareEnforcer]
 * depends on.
 *
 * The full Android platform API (`UsbManager`,
 * `BluetoothManager`, `NfcManager`, `SensorManager`,
 * `LocationManager`, `CameraManager`, `AudioRecord`...) is
 * too large to mock. The engineering gotcha for
 * Context-dependent Android classes is to define a small
 * interface that captures *only* the values the
 * JVM-testable code actually reads. The production
 * adapter wraps the real Android managers; the test
 * adapter is a 5-line hand-rolled impl.
 *
 * The interface is intentionally narrow. Each method
 * returns either a typed result, a list of typed records,
 * or `null` (device not present). It does NOT expose
 * the platform managers themselves — a caller that needs
 * a `UsbDeviceConnection` asks the provider for an opaque
 * [UsbDeviceHandle] and the enforcer hands that back as a
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareHandle].
 */
interface PlatformHardwareProvider {

    // --- USB ---

    fun listUsbDevices(): List<UsbDeviceInfo>
    fun requestUsbPermission(deviceId: String): UsbPermissionResult
    fun openUsbDevice(deviceId: String): UsbDeviceHandle?

    // --- Bluetooth ---

    fun listBluetoothDevices(): List<BluetoothDeviceInfo>
    fun isBluetoothEnabled(): Boolean

    // --- NFC ---

    fun isNfcEnabled(): Boolean

    // --- Camera ---

    fun hasCamera(): Boolean

    // --- Microphone ---

    fun hasMicrophone(): Boolean

    // --- Location ---

    fun lastKnownLocation(accuracy: LocationAccuracy): LocationFix?

    // --- Sensors ---

    fun listSensors(): List<SensorInfo>
}

/**
 * A typed USB device record. The provider's production
 * impl wraps `android.hardware.usb.UsbDevice`; the test
 * impl returns whatever it wants.
 */
data class UsbDeviceInfo(
    val deviceId: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?
)

/**
 * The result of a USB permission request. The platform
 * enforcer surfaces this as a typed
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcementResult]
 * (Granted / PendingConsent / Denied / Error).
 */
sealed class UsbPermissionResult {
    object Granted : UsbPermissionResult()
    object Denied : UsbPermissionResult()
    data class Pending(val intent: String) : UsbPermissionResult()
    data class Error(val cause: Throwable) : UsbPermissionResult()
}

/**
 * Opaque handle to an opened USB device. The test impl
 * returns a synthetic id; the production impl wraps the
 * real `UsbDeviceConnection` and stores it in a private
 * map keyed by the id.
 */
data class UsbDeviceHandle(val id: String)

/**
 * A typed Bluetooth device record.
 */
data class BluetoothDeviceInfo(
    val address: String,
    val name: String?,
    val paired: Boolean
)

/**
 * Coarse location accuracy. Mirrors the broker's wildcard
 * rule: `ANY` requests confirmation; a specific accuracy
 * is allowed.
 */
enum class LocationAccuracy { COARSE, FINE }

/**
 * A typed location fix. `null` means "no fix available".
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMs: Long
)

/**
 * A typed sensor record.
 */
data class SensorInfo(
    val name: String,
    val type: Int,
    val vendor: String?
)
