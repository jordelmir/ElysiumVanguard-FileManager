package com.elysium.vanguard.core.runtime.hardware

import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 8 — Hardware broker interface.
 *
 * Provides controlled access to Android hardware from Linux guest sessions.
 * Each hardware resource is exposed via a virtual endpoint or Unix-domain
 * socket under /run/elysium/.
 */
interface HardwareBroker {
    val state: StateFlow<HardwareBrokerState>

    /**
     * Request access to a hardware resource.
     * Returns a capability token if authorized.
     */
    suspend fun requestAccess(
        sessionId: String,
        resource: HardwareResource,
        scope: AccessScope
    ): Result<CapabilityToken>

    /**
     * Revoke a capability token.
     */
    suspend fun revokeAccess(token: String): Result<Unit>

    /**
     * Check if a session has access to a resource.
     */
    fun hasAccess(sessionId: String, resource: HardwareResource): Boolean

    /**
     * Get the virtual device path for a resource.
     */
    fun devicePath(resource: HardwareResource): String

    /**
     * List all available hardware resources on this device.
     */
    fun availableResources(): List<HardwareResource>

    /**
     * Get audit log for a session.
     */
    fun auditLog(sessionId: String): List<AuditEntry>

    /**
     * Shut down the broker and release all resources.
     */
    suspend fun shutdown(): Result<Unit>
}

data class HardwareResource(
    val type: ResourceType,
    val id: String,
    val name: String,
    val capabilities: Set<ResourceCapability>
)

enum class ResourceType {
    USB,
    BLUETOOTH,
    BLE,
    CAMERA,
    MICROPHONE,
    GPS,
    SENSOR,
    MIDI,
    GAMEPAD,
    NFC,
    SERIAL,
    OBD,
    CAN,
    STORAGE,
    PRINTER
}

enum class ResourceCapability {
    READ,
    WRITE,
    SCAN,
    CONNECT,
    STREAM,
    CONTROL
}

data class AccessScope(
    val allowedOperations: Set<ResourceCapability>,
    val timeoutMs: Long = 300_000,
    val maxBytes: Long = Long.MAX_VALUE
)

data class CapabilityToken(
    val token: String,
    val sessionId: String,
    val resource: HardwareResource,
    val scope: AccessScope,
    val issuedAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = System.currentTimeMillis() + 300_000
) {
    val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMs
}

data class AuditEntry(
    val timestamp: Long,
    val sessionId: String,
    val resource: String,
    val operation: String,
    val success: Boolean,
    val detail: String = ""
)

sealed class HardwareBrokerState {
    data object Idle : HardwareBrokerState()
    data object Active : HardwareBrokerState()
    data class Failed(val error: String) : HardwareBrokerState()
}
