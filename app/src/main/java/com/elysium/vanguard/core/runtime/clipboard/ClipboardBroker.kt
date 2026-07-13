package com.elysium.vanguard.core.runtime.clipboard

import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 8 — Clipboard broker interface.
 *
 * Manages bidirectional clipboard transfer between Android and Linux
 * guest sessions with configurable security policies.
 */
interface ClipboardBroker {
    val state: StateFlow<ClipboardBrokerState>
    val policy: StateFlow<ClipboardPolicy>
    val lastAccess: StateFlow<ClipboardAccessEvent?>

    /**
     * Set the clipboard policy for a session.
     */
    suspend fun setPolicy(sessionId: String, policy: ClipboardPolicy): Result<Unit>

    /**
     * Copy text from Android to the guest clipboard.
     */
    suspend fun pushText(sessionId: String, text: String): Result<Unit>

    /**
     * Copy text from the guest to the Android clipboard.
     */
    suspend fun pullText(sessionId: String): Result<String?>

    /**
     * Copy an image from Android to the guest.
     */
    suspend fun pushImage(sessionId: String, data: ByteArray, mimeType: String): Result<Unit>

    /**
     * Copy an image from the guest to Android.
     */
    suspend fun pullImage(sessionId: String): Result<ClipboardImage?>

    /**
     * Register a guest-side clipboard file (OSC 52 / xclip).
     */
    fun registerGuestClipboard(sessionId: String, path: String)

    /**
     * Clear the clipboard for a session.
     */
    suspend fun clear(sessionId: String): Result<Unit>
}

data class ClipboardImage(
    val data: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardImage) return false
        return data.contentEquals(other.data) && mimeType == other.mimeType
    }
    override fun hashCode(): Int = 31 * data.contentHashCode() + mimeType.hashCode()
}

enum class ClipboardPolicy {
    DISABLED,
    TEXT_ONLY,
    TEXT_AND_IMAGE,
    FULL,
    ASK_EVERY_TIME
}

data class ClipboardAccessEvent(
    val sessionId: String,
    val direction: Direction,
    val type: ClipboardType,
    val timestamp: Long = System.currentTimeMillis(),
    val sizeBytes: Int = 0
)

enum class Direction { PUSH, PULL }
enum class ClipboardType { TEXT, IMAGE, FILE }

sealed class ClipboardBrokerState {
    data object Idle : ClipboardBrokerState()
    data object Active : ClipboardBrokerState()
    data class Failed(val error: String) : ClipboardBrokerState()
}
