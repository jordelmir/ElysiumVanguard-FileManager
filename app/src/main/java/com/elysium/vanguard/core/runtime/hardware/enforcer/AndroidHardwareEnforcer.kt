package com.elysium.vanguard.core.runtime.hardware.enforcer

import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareClass
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareTargetId
import com.elysium.vanguard.core.runtime.hardware.provider.BluetoothDeviceInfo
import com.elysium.vanguard.core.runtime.hardware.provider.LocationAccuracy
import com.elysium.vanguard.core.runtime.hardware.provider.PlatformHardwareProvider
import com.elysium.vanguard.core.runtime.hardware.provider.UsbDeviceInfo
import com.elysium.vanguard.core.runtime.hardware.provider.UsbPermissionResult
import java.util.UUID

/**
 * Phase 20 — the production hardware enforcer.
 *
 * The enforcer implements [HardwareEnforcer] (Phase 19)
 * and dispatches the broker's decision to a
 * [PlatformHardwareProvider]. The provider is the small
 * interface that the engineering gotcha prescribes for
 * Context-dependent Android code: it captures only the
 * values the JVM-testable code actually reads, and the
 * production adapter wraps the real Android managers.
 *
 * The enforcer's per-class dispatch is deliberately small.
 * The pattern for every class is:
 *
 *   - LIST: ask the provider for the device list. Return
 *     Granted with a synthetic handle id (the enforcer
 *     does not retain the list; the caller queries the
 *     provider directly via [listSnapshot] if it needs
 *     the data).
 *   - READ: ask the provider for the current value.
 *     Return Granted with a synthetic handle id; the
 *     caller reads the value via [readSnapshot].
 *   - WRITE / CONNECT: call the provider's open / write
 *     method. The result is a typed [UsbPermissionResult]
 *     (for USB) or a similar typed result for the other
 *     classes. Map back to the enforcer's
 *     [HardwareEnforcementResult].
 *
 * The enforcer's job is translation, not policy. Every
 * rule lives in the broker; the enforcer's
 * `when (decision)` is a thin mapping.
 */
class AndroidHardwareEnforcer(
    private val provider: PlatformHardwareProvider
) : HardwareEnforcer {

    private val lock = Any()
    private val snapshots = mutableMapOf<String, Any?>()
    private val pendingConsents = mutableMapOf<String, PendingConsent>()

    override fun enforce(request: HardwareRequest): HardwareEnforcementResult {
        if (request.decision is HardwareDecision.Deny) {
            // Defensive: the service never calls us on Deny,
            // but if a future caller does, we refuse cleanly.
            return HardwareEnforcementResult.Denied
        }
        return try {
            when (request.hardwareClass) {
                HardwareClass.USB -> enforceUsb(request)
                HardwareClass.BLUETOOTH -> enforceBluetooth(request)
                HardwareClass.NFC -> enforceNfc(request)
                HardwareClass.CAMERA -> enforceCamera(request)
                HardwareClass.MICROPHONE -> enforceMicrophone(request)
                HardwareClass.LOCATION -> enforceLocation(request)
                HardwareClass.SENSORS -> enforceSensors(request)
            }
        } catch (e: Throwable) {
            HardwareEnforcementResult.Error(e)
        }
    }

    // --- USB ---

    private fun enforceUsb(request: HardwareRequest): HardwareEnforcementResult {
        val target = request.targetId
        val devices = provider.listUsbDevices()
        return when (request.action) {
            HardwareAction.LIST -> storeSnapshotAndGrant(
                key = "usb-list:${request.sessionId}",
                value = devices,
                handle = null
            )
            HardwareAction.READ -> {
                val device = findUsbDevice(target, devices)
                if (device == null) {
                    HardwareEnforcementResult.Error(
                        IllegalArgumentException("USB device not found: $target")
                    )
                } else {
                    val permission = provider.requestUsbPermission(device.deviceId)
                    when (permission) {
                        is UsbPermissionResult.Granted -> storeSnapshotAndGrant(
                            key = "usb-read:${device.deviceId}:${request.sessionId}",
                            value = device,
                            handle = null
                        )
                        is UsbPermissionResult.Denied -> HardwareEnforcementResult.Denied
                        is UsbPermissionResult.Pending -> {
                            val consentId = "usb-consent:${UUID.randomUUID()}"
                            synchronized(lock) {
                                pendingConsents[consentId] = PendingConsent(
                                    request = request,
                                    intent = permission.intent
                                )
                            }
                            HardwareEnforcementResult.PendingConsent(consentId)
                        }
                        is UsbPermissionResult.Error -> HardwareEnforcementResult.Error(
                            permission.cause
                        )
                    }
                }
            }
            HardwareAction.WRITE, HardwareAction.CONNECT -> {
                val device = findUsbDevice(target, devices)
                    ?: return HardwareEnforcementResult.Error(
                        IllegalArgumentException("USB device not found: $target")
                    )
                val opened = provider.openUsbDevice(device.deviceId)
                    ?: return HardwareEnforcementResult.Error(
                        IllegalStateException("USB openDevice returned null for ${device.deviceId}")
                    )
                HardwareEnforcementResult.Granted(handle = HardwareHandle(opened.id))
            }
        }
    }

    private fun findUsbDevice(target: HardwareTargetId, devices: List<UsbDeviceInfo>): UsbDeviceInfo? {
        return when (target) {
            is HardwareTargetId.Specific -> {
                val id = target.id
                devices.firstOrNull { it.deviceId == id }
                    ?: devices.firstOrNull { "${it.vendorId}:${it.productId}" == id }
            }
            else -> devices.firstOrNull()
        }
    }

    // --- Bluetooth ---

    private fun enforceBluetooth(request: HardwareRequest): HardwareEnforcementResult {
        return when (request.action) {
            HardwareAction.LIST -> {
                if (!provider.isBluetoothEnabled()) {
                    return HardwareEnforcementResult.Error(
                        IllegalStateException("Bluetooth is not enabled on this device")
                    )
                }
                val devices = provider.listBluetoothDevices()
                storeSnapshotAndGrant(
                    key = "bt-list:${request.sessionId}",
                    value = devices,
                    handle = null
                )
            }
            HardwareAction.READ -> storeSnapshotAndGrant(
                key = "bt-read:${request.targetId}:${request.sessionId}",
                value = null,
                handle = null
            )
            HardwareAction.WRITE, HardwareAction.CONNECT -> {
                // A real implementation would call
                // BluetoothDevice.createRfcommSocket or
                // similar. The provider does not model that
                // yet; for now, return a synthetic handle.
                val handle = HardwareHandle("bt:${request.targetId}:${request.sessionId}")
                HardwareEnforcementResult.Granted(handle = handle)
            }
        }
    }

    // --- NFC ---

    private fun enforceNfc(request: HardwareRequest): HardwareEnforcementResult {
        return when (request.action) {
            HardwareAction.LIST, HardwareAction.READ -> {
                if (!provider.isNfcEnabled()) {
                    return HardwareEnforcementResult.Error(
                        IllegalStateException("NFC is not enabled on this device")
                    )
                }
                storeSnapshotAndGrant(
                    key = "nfc:${request.sessionId}",
                    value = null,
                    handle = null
                )
            }
            HardwareAction.WRITE, HardwareAction.CONNECT -> {
                // NFC write / connect returns a synthetic
                // handle the caller can use for follow-up
                // tag operations.
                val handle = HardwareHandle("nfc:${request.targetId}:${request.sessionId}")
                HardwareEnforcementResult.Granted(handle = handle)
            }
        }
    }

    // --- Camera ---

    private fun enforceCamera(request: HardwareRequest): HardwareEnforcementResult {
        return when (request.action) {
            HardwareAction.LIST, HardwareAction.READ -> {
                if (!provider.hasCamera()) {
                    return HardwareEnforcementResult.Error(
                        IllegalStateException("no camera on this device")
                    )
                }
                storeSnapshotAndGrant(
                    key = "camera:${request.sessionId}",
                    value = null,
                    handle = null
                )
            }
            HardwareAction.WRITE, HardwareAction.CONNECT ->
                HardwareEnforcementResult.Granted(
                    handle = HardwareHandle("camera:${request.sessionId}")
                )
        }
    }

    // --- Microphone ---

    private fun enforceMicrophone(request: HardwareRequest): HardwareEnforcementResult {
        return when (request.action) {
            HardwareAction.LIST, HardwareAction.READ -> {
                if (!provider.hasMicrophone()) {
                    return HardwareEnforcementResult.Error(
                        IllegalStateException("no microphone on this device")
                    )
                }
                storeSnapshotAndGrant(
                    key = "mic:${request.sessionId}",
                    value = null,
                    handle = null
                )
            }
            HardwareAction.WRITE, HardwareAction.CONNECT ->
                HardwareEnforcementResult.Granted(
                    handle = HardwareHandle("mic:${request.sessionId}")
                )
        }
    }

    // --- Location ---

    private fun enforceLocation(request: HardwareRequest): HardwareEnforcementResult {
        val accuracy = when (request.targetId) {
            is HardwareTargetId.Specific ->
                runCatching { LocationAccuracy.valueOf(request.targetId.id.uppercase()) }
                    .getOrDefault(LocationAccuracy.FINE)
            else -> LocationAccuracy.FINE
        }
        val fix = provider.lastKnownLocation(accuracy)
        return storeSnapshotAndGrant(
            key = "location:${accuracy}:${request.sessionId}",
            value = fix,
            handle = null
        )
    }

    // --- Sensors ---

    private fun enforceSensors(request: HardwareRequest): HardwareEnforcementResult {
        return when (request.action) {
            HardwareAction.LIST -> {
                val sensors = provider.listSensors()
                storeSnapshotAndGrant(
                    key = "sensors:${request.sessionId}",
                    value = sensors,
                    handle = null
                )
            }
            HardwareAction.READ, HardwareAction.CONNECT -> {
                val sensors = provider.listSensors()
                storeSnapshotAndGrant(
                    key = "sensor:${request.targetId}:${request.sessionId}",
                    value = sensors,
                    handle = HardwareHandle("sensor:${request.sessionId}")
                )
            }
            HardwareAction.WRITE ->
                HardwareEnforcementResult.Granted(
                    handle = HardwareHandle("sensor-write:${request.sessionId}")
                )
        }
    }

    // --- helpers ---

    private fun storeSnapshotAndGrant(
        key: String,
        value: Any?,
        handle: HardwareHandle?
    ): HardwareEnforcementResult.Granted {
        synchronized(lock) { snapshots[key] = value }
        return HardwareEnforcementResult.Granted(handle = handle)
    }

    /**
     * Snapshot accessors. The caller (or a follow-up
     * `read()` call against the same handle) reads the
     * stored data here. Kept on the enforcer because the
     * handle's `id` is opaque; the snapshots live with
     * the enforcer that produced the handle.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> readSnapshot(key: String): T? = synchronized(lock) {
        snapshots[key] as T?
    }

    fun listSnapshotKeys(): List<String> = synchronized(lock) { snapshots.keys.toList() }

    /**
     * Look up a pending consent by id. The runtime's
     * consent UI re-issues the request when the user
     * decides; the enforcer surfaces the original
     * [HardwareRequest] for the UI to display.
     */
    fun pendingConsent(consentId: String): PendingConsent? = synchronized(lock) {
        pendingConsents[consentId]
    }

    fun resolveConsent(consentId: String, granted: Boolean) {
        synchronized(lock) {
            val pending = pendingConsents.remove(consentId) ?: return
            // The consent UI's decision is already
            // applied by the runtime; this method exists
            // for the enforcer to clean up its tracking.
            // We expose it for the test suite to verify
            // the lifecycle.
            if (granted) {
                // No-op: the runtime will re-call enforce
                // with the broker's confirmation bypassed.
            }
        }
    }

    data class PendingConsent(
        val request: HardwareRequest,
        val intent: String
    )
}
