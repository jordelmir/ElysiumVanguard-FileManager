package com.elysium.vanguard.core.runtime.hardware

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class HardwareBrokerImpl(
    private val appContext: Context,
    private val bridgeBaseDir: String = DEFAULT_BRIDGE_DIR
) : HardwareBroker, Closeable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<HardwareBrokerState>(HardwareBrokerState.Idle)
    override val state: StateFlow<HardwareBrokerState> = _state.asStateFlow()

    private val activeTokens = ConcurrentHashMap<String, CapabilityToken>()
    private val sessionResources = ConcurrentHashMap<String, MutableSet<String>>()
    private val auditEntries = CopyOnWriteArrayList<AuditEntry>()
    private val resourceCache = lazy { probeResources() }

    private val tokenCounter = AtomicLong(0)

    override suspend fun requestAccess(
        sessionId: String,
        resource: HardwareResource,
        scope: AccessScope
    ): Result<CapabilityToken> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("sessionId must not be blank")
            )
        }
        _state.value = HardwareBrokerState.Active
        val available = availableResources()
        if (available.none { it.type == resource.type && it.id == resource.id }) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Resource ${resource.type}/${resource.id} is not available on this device"
                )
            )
        }
        val token = CapabilityToken(
            token = generateToken(sessionId, resource),
            sessionId = sessionId,
            resource = resource,
            scope = scope,
            issuedAtMs = System.currentTimeMillis(),
            expiresAtMs = System.currentTimeMillis() + scope.timeoutMs
        )
        activeTokens[token.token] = token
        sessionResources.getOrPut(sessionId) { mutableSetOf() }.add(token.token)
        audit(AuditEntry(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            resource = "${resource.type}/${resource.id}",
            operation = "ACCESS_REQUESTED",
            success = true
        ))
        _state.value = HardwareBrokerState.Active
        Result.success(token)
    }

    override suspend fun revokeAccess(tokenId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = activeTokens.remove(tokenId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Token not found: $tokenId")
                )
            val sessionSet = sessionResources[token.sessionId]
            sessionSet?.remove(tokenId)
            if (sessionSet?.isEmpty() == true) {
                sessionResources.remove(token.sessionId)
            }
            audit(AuditEntry(
                timestamp = System.currentTimeMillis(),
                sessionId = token.sessionId,
                resource = "${token.resource.type}/${token.resource.id}",
                operation = "ACCESS_REVOKED",
                success = true
            ))
            Result.success(Unit)
        }

    override fun hasAccess(sessionId: String, resource: HardwareResource): Boolean {
        val tokenIds = sessionResources[sessionId] ?: return false
        return tokenIds.any { tid ->
            val token = activeTokens[tid]
            token != null &&
                token.resource.type == resource.type &&
                token.resource.id == resource.id &&
                !token.isExpired
        }
    }

    override fun devicePath(resource: HardwareResource): String {
        return when (resource.type) {
            ResourceType.SERIAL -> "$bridgeBaseDir/serial${resource.id}"
            ResourceType.OBD -> "$bridgeBaseDir/obd0"
            ResourceType.CAN -> "$bridgeBaseDir/can0"
            ResourceType.USB -> "$bridgeBaseDir/usb/${resource.id}"
            ResourceType.BLUETOOTH -> "$bridgeBaseDir/bt/${resource.id}"
            ResourceType.BLE -> "$bridgeBaseDir/ble/${resource.id}"
            ResourceType.CAMERA -> "$bridgeBaseDir/camera/${resource.id}"
            ResourceType.MICROPHONE -> "$bridgeBaseDir/mic0"
            ResourceType.GPS -> "$bridgeBaseDir/gps0"
            ResourceType.SENSOR -> "$bridgeBaseDir/sensors/${resource.id}"
            ResourceType.MIDI -> "$bridgeBaseDir/midi/${resource.id}"
            ResourceType.GAMEPAD -> "$bridgeBaseDir/gamepad/${resource.id}"
            ResourceType.NFC -> "$bridgeBaseDir/nfc0"
            ResourceType.STORAGE -> "$bridgeBaseDir/storage/${resource.id}"
            ResourceType.PRINTER -> "$bridgeBaseDir/printer/${resource.id}"
        }
    }

    override fun availableResources(): List<HardwareResource> = resourceCache.value

    override fun auditLog(sessionId: String): List<AuditEntry> {
        return auditEntries.filter { it.sessionId == sessionId }
    }

    override suspend fun shutdown(): Result<Unit> = withContext(Dispatchers.IO) {
        activeTokens.clear()
        sessionResources.clear()
        _state.value = HardwareBrokerState.Idle
        Result.success(Unit)
    }

    override fun close() {
        activeTokens.clear()
        sessionResources.clear()
        auditEntries.clear()
        scope.cancel()
        _state.value = HardwareBrokerState.Idle
    }

    private fun probeResources(): List<HardwareResource> {
        val resources = mutableListOf<HardwareResource>()

        resources += probeUsb()
        resources += probeBluetooth()
        resources += probeCamera()
        resources += probeMicrophone()
        resources += probeGps()
        resources += probeSensors()

        return resources
    }

    private fun probeUsb(): List<HardwareResource> {
        val mgr = appContext.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        return mgr.deviceList.map { (id, device) ->
            HardwareResource(
                type = ResourceType.USB,
                id = id,
                name = device.productName ?: device.deviceName ?: "USB device $id",
                capabilities = setOf(ResourceCapability.READ, ResourceCapability.WRITE)
            )
        }
    }

    private fun probeBluetooth(): List<HardwareResource> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return emptyList()
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        val bonded = adapter.bondedDevices ?: return emptyList()
        return bonded.map { device ->
            HardwareResource(
                type = ResourceType.BLUETOOTH,
                id = device.address,
                name = device.name ?: device.address,
                capabilities = setOf(ResourceCapability.READ, ResourceCapability.WRITE, ResourceCapability.CONNECT)
            )
        }
    }

    private fun probeCamera(): List<HardwareResource> {
        val mgr = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return try {
            mgr.cameraIdList.map { id ->
                val chars = mgr.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                val label = when (facing) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Rear Camera"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> "External Camera"
                    else -> "Camera"
                }
                HardwareResource(
                    type = ResourceType.CAMERA,
                    id = id,
                    name = label,
                    capabilities = setOf(ResourceCapability.READ, ResourceCapability.STREAM)
                )
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun probeMicrophone(): List<HardwareResource> {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                44100,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (bufferSize > 0) {
                listOf(
                    HardwareResource(
                        type = ResourceType.MICROPHONE,
                        id = "builtin",
                        name = "Built-in Microphone",
                        capabilities = setOf(ResourceCapability.READ, ResourceCapability.STREAM)
                    )
                )
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun probeGps(): List<HardwareResource> {
        val mgr = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return emptyList()
        val hasGps = try {
            mgr.getAllProviders().contains(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            false
        }
        if (hasGps) {
            return listOf(
                HardwareResource(
                    type = ResourceType.GPS,
                    id = "builtin",
                    name = "GPS",
                    capabilities = setOf(ResourceCapability.READ)
                )
            )
        }
        return emptyList()
    }

    private fun probeSensors(): List<HardwareResource> {
        val mgr = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return emptyList()
        return mgr.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            HardwareResource(
                type = ResourceType.SENSOR,
                id = sensor.stringType,
                name = sensor.name,
                capabilities = setOf(ResourceCapability.READ)
            )
        }
    }

    private fun audit(entry: AuditEntry) {
        auditEntries.add(entry)
    }

    private fun generateToken(sessionId: String, resource: HardwareResource): String {
        val counter = tokenCounter.incrementAndGet()
        val ts = System.currentTimeMillis()
        return "hwtok_${sessionId.take(8)}_${resource.type.name.lowercase()}_${ts}_$counter"
    }

    private companion object {
        private const val DEFAULT_BRIDGE_DIR = "/run/elysium"
    }
}
