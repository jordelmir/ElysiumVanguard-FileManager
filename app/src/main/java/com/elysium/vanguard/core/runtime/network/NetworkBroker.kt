package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 8 — Network broker interface.
 *
 * Manages network policies for Linux guest sessions. Each session
 * can have different network access levels:
 *   - LOOPBACK: only localhost
 *   - OUTBOUND: allow outgoing connections
 *   - LAN: allow local network access
 *   - INTERNET: full internet access
 *   - BLOCKED: no network at all
 *   - CUSTOM: per-port/per-host rules
 */
interface NetworkBroker {
    val state: StateFlow<NetworkBrokerState>

    /**
     * Apply a network policy to a session.
     */
    suspend fun applyPolicy(sessionId: String, policy: NetworkPolicy): Result<Unit>

    /**
     * Remove network policy for a session (revoke all access).
     */
    suspend fun revokePolicy(sessionId: String): Result<Unit>

    /**
     * Register a port binding (guest port -> host mapping).
     */
    suspend fun registerPort(sessionId: String, binding: PortBinding): Result<PortBindingResult>

    /**
     * Unregister a port binding.
     */
    suspend fun unregisterPort(sessionId: String, port: Int): Result<Unit>

    /**
     * Get active port bindings for a session.
     */
    fun getBindings(sessionId: String): List<PortBinding>

    /**
     * Check if a specific operation is allowed under the current policy.
     */
    fun isAllowed(sessionId: String, operation: NetworkOperation): Boolean

    /**
     * Generate iptables/nftables rules for the session.
     */
    fun generateRules(sessionId: String): List<String>

    /**
     * Get environment variables to inject into the guest.
     */
    fun environmentVariables(sessionId: String): Map<String, String>
}

data class NetworkPolicy(
    val level: PolicyLevel,
    val allowedHosts: Set<String> = emptySet(),
    val allowedPorts: Set<Int> = emptySet(),
    val blockedHosts: Set<String> = emptySet(),
    val blockedPorts: Set<Int> = emptySet(),
    val allowDns: Boolean = true,
    val allowLoopback: Boolean = true,
    val maxConnections: Int = 64,
    val maxBytesPerSecond: Long = Long.MAX_VALUE
) {
    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(maxBytesPerSecond > 0) { "maxBytesPerSecond must be positive" }
    }
}

enum class PolicyLevel {
    LOOPBACK,
    OUTBOUND,
    LAN,
    INTERNET,
    BLOCKED,
    CUSTOM
}

enum class NetworkOperation {
    DNS_RESOLVE,
    TCP_OUTBOUND,
    TCP_INBOUND,
    UDP_OUTBOUND,
    UDP_INBOUND,
    UNIX_SOCKET,
    PORT_BIND
}

data class PortBinding(
    val guestPort: Int,
    val hostPort: Int? = null,
    val interface_: String = "127.0.0.1",
    val protocol: Protocol = Protocol.TCP,
    val description: String = ""
) {
    init {
        require(guestPort in 1..65535) { "invalid guest port" }
        hostPort?.let { require(it in 1..65535) { "invalid host port" } }
    }
}

enum class Protocol { TCP, UDP }

data class PortBindingResult(
    val actualHostPort: Int,
    val interface_: String,
    val success: Boolean,
    val error: String? = null
)

sealed class NetworkBrokerState {
    data object Idle : NetworkBrokerState()
    data object Active : NetworkBrokerState()
    data class Failed(val error: String) : NetworkBrokerState()
}
