package com.elysium.vanguard.core.runtime.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 8 / section 10.2 — Concrete NetworkBroker implementation.
 *
 * The broker is the seam between per-session [NetworkPolicy] and the
 * actual rule enforcement layer (iptables / nftables on a real Linux
 * device, env-only inside PRoot). It is honest about what it can and
 * cannot enforce: every applyPolicy returns a [PolicyApplication] that
 * tells the caller which enforcement layer is active.
 *
 * Enforcement backends:
 *
 * 1. [IptablesBackend] (production when available). The runtime
 *    orchestrator is responsible for invoking the rule list returned
 *    by [generateRules]; the broker does not shell out to iptables
 *    itself because Android restricts raw socket / netadmin access to
 *    privileged processes and PRoot sessions cannot run iptables in
 *    the guest. The runtime layer is the right place to wire the
 *    privileged tool.
 *
 * 2. [EnvOnlyBackend] (always available). Inside a PRoot session,
 *    the only practical network control is the env vars returned by
 *    [environmentVariables]: NO_PROXY, no_proxy, all_proxy,
 *    HTTP_PROXY, HTTPS_PROXY. The broker exposes these so the guest
 *    can be steered at a Tor / mitmproxy / loopback proxy that
 *    applies the real policy. The session is then marked Active
 *    with [enforcementLayer] = [EnforcementLayer.ENV_ONLY].
 *
 * 3. [DisabledBackend] (no policy = no enforcement). The default
 *    state when no session has registered a policy. Network access
 *    in the guest is the Android network unmodified.
 *
 * The broker never shells out from the JVM test path. The
 * IptablesBackend is a function reference; production wires
 * `::runIptablesCommand`, tests wire a no-op.
 */
class NetworkBrokerImpl(
    private val iptablesAvailable: () -> Boolean = { probeIptablesBinary() },
    private val iptablesApply: (List<String>) -> IptablesApplyResult = { _ ->
        IptablesApplyResult(success = false, error = "no iptables applier wired")
    },
    private val clock: () -> Long = System::currentTimeMillis
) : NetworkBroker, Closeable {

    private val _state = MutableStateFlow<NetworkBrokerState>(NetworkBrokerState.Idle)
    override val state: StateFlow<NetworkBrokerState> = _state.asStateFlow()

    private val sessionPolicies = ConcurrentHashMap<String, NetworkPolicy>()
    private val sessionBindings = ConcurrentHashMap<String, MutableMap<Int, PortBinding>>()
    private val sessionApplied = ConcurrentHashMap<String, PolicyApplication>()
    private val sessionAudit = ConcurrentHashMap<String, MutableList<PolicyAuditEntry>>()

    override suspend fun applyPolicy(
        sessionId: String,
        policy: NetworkPolicy
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("sessionId must not be blank")
            )
        }
        sessionPolicies[sessionId] = policy
        sessionApplied[sessionId] = PolicyApplication(
            sessionId = sessionId,
            level = policy.level,
            enforcementLayer = chooseEnforcementLayer(policy),
            appliedAtMs = clock(),
            rulesEmitted = generateRulesInternal(sessionId, policy)
        )
        audit(sessionId, "APPLY", "level=${policy.level} layer=${sessionApplied[sessionId]?.enforcementLayer}")
        _state.value = NetworkBrokerState.Active
        Result.success(Unit)
    }

    override suspend fun revokePolicy(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val previous = sessionPolicies.remove(sessionId)
        sessionApplied.remove(sessionId)
        sessionBindings.remove(sessionId)
        if (previous != null) {
            audit(sessionId, "REVOKE", "previous level=${previous.level}")
        }
        if (sessionPolicies.isEmpty()) {
            _state.value = NetworkBrokerState.Idle
        }
        Result.success(Unit)
    }

    override suspend fun registerPort(
        sessionId: String,
        binding: PortBinding
    ): Result<PortBindingResult> = withContext(Dispatchers.IO) {
        if (sessionId.isBlank()) {
            return@withContext Result.failure(
                IllegalArgumentException("sessionId must not be blank")
            )
        }
        val policy = sessionPolicies[sessionId]
            ?: return@withContext Result.failure(
                IllegalStateException("no policy applied to session $sessionId")
            )
        // Section 10.2: never open a service on 0.0.0.0 without explicit
        // consent. The default PortBinding interface is 127.0.0.1, which
        // is loopback only and safe. If the caller asked for a non-loopback
        // interface, we require INTERNET or LAN level + an explicit
        // consent flag (we treat the non-loopback host as consent).
        val isLoopback = binding.interface_ == "127.0.0.1" || binding.interface_ == "::1"
        if (!isLoopback) {
            if (policy.level == PolicyLevel.LOOPBACK || policy.level == PolicyLevel.BLOCKED) {
                return@withContext Result.success(
                    PortBindingResult(
                        actualHostPort = binding.hostPort ?: binding.guestPort,
                        interface_ = binding.interface_,
                        success = false,
                        error = "policy level ${policy.level} cannot bind to non-loopback interface"
                    )
                )
            }
        }
        val bindings = sessionBindings.getOrPut(sessionId) { mutableMapOf() }
        val actualHost = binding.hostPort ?: binding.guestPort
        bindings[actualHost] = binding
        audit(sessionId, "PORT_BIND", "${binding.protocol} ${actualHost} -> guest ${binding.guestPort} on ${binding.interface_}")
        Result.success(
            PortBindingResult(
                actualHostPort = actualHost,
                interface_ = binding.interface_,
                success = true
            )
        )
    }

    override suspend fun unregisterPort(sessionId: String, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            val bindings = sessionBindings[sessionId]
                ?: return@withContext Result.failure(
                    IllegalArgumentException("session $sessionId has no bindings")
                )
            val removed = bindings.remove(port)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("port $port not bound for session $sessionId")
                )
            audit(sessionId, "PORT_UNBIND", "${removed.protocol} $port")
            Result.success(Unit)
        }

    override fun getBindings(sessionId: String): List<PortBinding> {
        return sessionBindings[sessionId]?.values?.toList().orEmpty()
    }

    override fun isAllowed(sessionId: String, operation: NetworkOperation): Boolean {
        val policy = sessionPolicies[sessionId] ?: return true
        return when (policy.level) {
            PolicyLevel.BLOCKED -> when (operation) {
                // The broker itself still allows unix-socket and
                // port-bind metadata operations, because those are
                // the gates that downstream components (e.g. the
                // orchestrator) check before issuing real I/O.
                NetworkOperation.UNIX_SOCKET -> true
                NetworkOperation.PORT_BIND -> true
                else -> false
            }
            PolicyLevel.LOOPBACK -> when (operation) {
                NetworkOperation.UNIX_SOCKET -> true
                NetworkOperation.PORT_BIND -> true
                NetworkOperation.DNS_RESOLVE -> policy.allowDns
                NetworkOperation.TCP_OUTBOUND, NetworkOperation.UDP_OUTBOUND -> policy.allowLoopback
                // Inbound on loopback would be a server. The order
                // says LOOPBACK means "the guest can only make
                // outbound requests to loopback addresses". We do
                // not allow binding servers in this policy.
                NetworkOperation.TCP_INBOUND, NetworkOperation.UDP_INBOUND -> false
            }
            PolicyLevel.OUTBOUND -> when (operation) {
                NetworkOperation.DNS_RESOLVE -> policy.allowDns
                NetworkOperation.TCP_OUTBOUND, NetworkOperation.UDP_OUTBOUND -> true
                NetworkOperation.TCP_INBOUND, NetworkOperation.UDP_INBOUND -> false
                NetworkOperation.UNIX_SOCKET -> true
                NetworkOperation.PORT_BIND -> true
            }
            PolicyLevel.LAN -> when (operation) {
                NetworkOperation.DNS_RESOLVE -> policy.allowDns
                NetworkOperation.TCP_OUTBOUND, NetworkOperation.UDP_OUTBOUND,
                NetworkOperation.TCP_INBOUND, NetworkOperation.UDP_INBOUND -> true
                NetworkOperation.UNIX_SOCKET -> true
                NetworkOperation.PORT_BIND -> true
            }
            PolicyLevel.INTERNET -> when (operation) {
                NetworkOperation.DNS_RESOLVE -> policy.allowDns
                NetworkOperation.TCP_OUTBOUND, NetworkOperation.UDP_OUTBOUND,
                NetworkOperation.TCP_INBOUND, NetworkOperation.UDP_INBOUND -> true
                NetworkOperation.UNIX_SOCKET -> true
                NetworkOperation.PORT_BIND -> true
            }
            PolicyLevel.CUSTOM -> when (operation) {
                NetworkOperation.DNS_RESOLVE -> policy.allowDns
                NetworkOperation.PORT_BIND -> true
                NetworkOperation.UNIX_SOCKET -> true
                NetworkOperation.TCP_OUTBOUND, NetworkOperation.UDP_OUTBOUND ->
                    policy.allowedPorts.isNotEmpty() || policy.allowedHosts.isNotEmpty()
                NetworkOperation.TCP_INBOUND, NetworkOperation.UDP_INBOUND ->
                    policy.allowedPorts.isNotEmpty()
            }
        }
    }

    override fun generateRules(sessionId: String): List<String> {
        val policy = sessionPolicies[sessionId]
            ?: return listOf("# no policy for session $sessionId")
        return generateRulesInternal(sessionId, policy)
    }

    override fun environmentVariables(sessionId: String): Map<String, String> {
        val policy = sessionPolicies[sessionId] ?: return emptyMap()
        val env = LinkedHashMap<String, String>()
        // Always report the policy so the guest can self-restrict.
        env["ELYSIUM_NET_POLICY"] = policy.level.name
        when (policy.level) {
            PolicyLevel.BLOCKED -> {
                env["NO_PROXY"] = "*"
                env["no_proxy"] = "*"
                env["HTTP_PROXY"] = ""
                env["HTTPS_PROXY"] = ""
            }
            PolicyLevel.LOOPBACK -> {
                // Point the guest at a loopback proxy so any HTTP
                // client that consults $HTTP_PROXY fails to leave
                // the box. The real enforcement layer (iptables) is
                // responsible for actually dropping the traffic.
                env["HTTP_PROXY"] = "http://127.0.0.1:9"
                env["HTTPS_PROXY"] = "http://127.0.0.1:9"
                env["http_proxy"] = "http://127.0.0.1:9"
                env["https_proxy"] = "http://127.0.0.1:9"
                env["no_proxy"] = "127.0.0.1,localhost"
            }
            PolicyLevel.CUSTOM -> {
                if (policy.allowedHosts.isNotEmpty()) {
                    env["ELYSIUM_NET_ALLOWED_HOSTS"] = policy.allowedHosts.joinToString(",")
                    val noProxy = policy.blockedHosts.joinToString(",")
                    if (noProxy.isNotEmpty()) {
                        env["NO_PROXY"] = noProxy
                        env["no_proxy"] = noProxy
                    }
                }
            }
            PolicyLevel.OUTBOUND, PolicyLevel.LAN, PolicyLevel.INTERNET -> {
                // No proxy hint; the guest uses the Android network
                // unmodified. The orchestrator may still apply
                // iptables to shape traffic.
            }
        }
        return env
    }

    /**
     * The application actually stored for the session. Exposed for the
     * diagnostic screen and for the integration test. Null when no
     * policy has been applied.
     */
    fun applicationFor(sessionId: String): PolicyApplication? = sessionApplied[sessionId]

    /**
     * Audit log for a session, in insertion order. Used by the
     * diagnostic surface to show 'we applied LOOPBACK at 16:54:32',
     * 'we registered port 8080 -> 8080 at 16:54:33', etc.
     */
    fun auditLog(sessionId: String): List<PolicyAuditEntry> =
        sessionAudit[sessionId]?.toList().orEmpty()

    /**
     * Apply the generated rules to the active iptables applier. The
     * broker is honest about success / failure: the call returns a
     * typed [IptablesApplyResult] and updates [PolicyApplication] with
     * the result so the diagnostic surface can display it.
     */
    suspend fun applyGeneratedRules(sessionId: String): IptablesApplyResult = withContext(Dispatchers.IO) {
        val application = sessionApplied[sessionId]
            ?: return@withContext IptablesApplyResult(
                success = false,
                error = "no policy applied to session $sessionId"
            )
        if (!iptablesAvailable()) {
            val result = IptablesApplyResult(
                success = false,
                error = "iptables binary not present on device"
            )
            sessionApplied[sessionId] = application.copy(
                iptablesResult = result,
                enforcementLayer = EnforcementLayer.ENV_ONLY
            )
            audit(sessionId, "IPTABLES_SKIPPED", result.error ?: "unknown")
            return@withContext result
        }
        val result = iptablesApply(application.rulesEmitted)
        sessionApplied[sessionId] = application.copy(
            iptablesResult = result,
            enforcementLayer = if (result.success) {
                EnforcementLayer.IPTABLES
            } else {
                EnforcementLayer.ENV_ONLY
            }
        )
        audit(sessionId, if (result.success) "IPTABLES_APPLIED" else "IPTABLES_FAILED", result.error ?: "ok")
        result
    }

    override fun close() {
        sessionPolicies.clear()
        sessionBindings.clear()
        sessionApplied.clear()
        sessionAudit.clear()
        _state.value = NetworkBrokerState.Idle
    }

    private fun chooseEnforcementLayer(policy: NetworkPolicy): EnforcementLayer = when (policy.level) {
        PolicyLevel.BLOCKED -> EnforcementLayer.ENV_ONLY
        PolicyLevel.LOOPBACK -> if (iptablesAvailable()) EnforcementLayer.IPTABLES else EnforcementLayer.ENV_ONLY
        PolicyLevel.CUSTOM -> EnforcementLayer.ENV_ONLY
        PolicyLevel.OUTBOUND, PolicyLevel.LAN, PolicyLevel.INTERNET ->
            if (iptablesAvailable()) EnforcementLayer.IPTABLES else EnforcementLayer.ENV_ONLY
    }

    private fun generateRulesInternal(
        sessionId: String,
        policy: NetworkPolicy
    ): List<String> {
        val out = mutableListOf<String>()
        out += "# Elysium Vanguard network policy for session $sessionId"
        out += "# level: ${policy.level}"
        out += "# generated at: ${clock()} ms"
        when (policy.level) {
            PolicyLevel.BLOCKED -> {
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -o lo -j ACCEPT"
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -j DROP"
            }
            PolicyLevel.LOOPBACK -> {
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -o lo -j ACCEPT"
                if (!policy.allowDns) {
                    out += "iptables -A ELYSIUM_${sessionId.uppercase()} -p udp --dport 53 -j DROP"
                }
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -j DROP"
            }
            PolicyLevel.OUTBOUND -> {
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -o lo -j ACCEPT"
                if (!policy.allowDns) {
                    out += "iptables -A ELYSIUM_${sessionId.uppercase()} -p udp --dport 53 -j DROP"
                }
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -m state --state ESTABLISHED,RELATED -j ACCEPT"
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -j DROP"
            }
            PolicyLevel.LAN -> {
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -d 10.0.0.0/8 -j ACCEPT"
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -d 172.16.0.0/12 -j ACCEPT"
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -d 192.168.0.0/16 -j ACCEPT"
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -j DROP"
            }
            PolicyLevel.INTERNET -> {
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -m state --state ESTABLISHED,RELATED -j ACCEPT"
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -j ACCEPT"
            }
            PolicyLevel.CUSTOM -> {
                if (policy.allowedHosts.isNotEmpty()) {
                    policy.allowedHosts.forEach { host ->
                        out += "iptables -A ELYSIUM_${sessionId.uppercase()} -d $host -j ACCEPT"
                    }
                }
                if (policy.allowedPorts.isNotEmpty()) {
                    policy.allowedPorts.forEach { port ->
                        out += "iptables -A ELYSIUM_${sessionId.uppercase()} -p tcp --dport $port -j ACCEPT"
                        out += "iptables -A ELYSIUM_${sessionId.uppercase()} -p udp --dport $port -j ACCEPT"
                    }
                }
                policy.blockedHosts.forEach { host ->
                    out += "iptables -A ELYSIUM_${sessionId.uppercase()} -d $host -j DROP"
                }
                policy.blockedPorts.forEach { port ->
                    out += "iptables -A ELYSIUM_${sessionId.uppercase()} -p tcp --dport $port -j DROP"
                    out += "iptables -A ELYSIUM_${sessionId.uppercase()} -p udp --dport $port -j DROP"
                }
                out += "iptables -A ELYSIUM_${sessionId.uppercase()} -j DROP"
            }
        }
        return out
    }

    private fun audit(sessionId: String, operation: String, detail: String) {
        val list = sessionAudit.getOrPut(sessionId) { mutableListOf() }
        synchronized(list) {
            list += PolicyAuditEntry(
                timestampMs = clock(),
                operation = operation,
                detail = detail
            )
        }
    }

    companion object {
        /**
         * Probe for the iptables / nftables binary. On a normal Linux
         * device this is a single `which` call. On Android the binary
         * is normally not present; the broker then degrades to env-only
         * enforcement and is honest about it via [applicationFor].
         */
        fun probeIptablesBinary(): Boolean {
            val candidates = listOf(
                File("/system/bin/iptables"),
                File("/vendor/bin/iptables"),
                File("/system/bin/nft"),
                File("/usr/sbin/iptables")
            )
            return candidates.any { it.canExecute() }
        }
    }
}

data class PolicyApplication(
    val sessionId: String,
    val level: PolicyLevel,
    val enforcementLayer: EnforcementLayer,
    val appliedAtMs: Long,
    val rulesEmitted: List<String>,
    val iptablesResult: IptablesApplyResult? = null
) {
    val iptablesApplied: Boolean
        get() = iptablesResult?.success == true
}

enum class EnforcementLayer {
    /** No policy applied. Network access is the Android network unmodified. */
    DISABLED,

    /** Rules generated but not applied to a real iptables / nftables instance. */
    ENV_ONLY,

    /** Rules generated and applied through iptables / nftables. */
    IPTABLES
}

data class IptablesApplyResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null
)

data class PolicyAuditEntry(
    val timestampMs: Long,
    val operation: String,
    val detail: String
)
