package com.elysium.vanguard.core.runtime.network.firewall

/**
 * Phase 15 — firewall rule primitives.
 *
 * The runtime's enforcement layer does not talk to iptables or
 * cgroup directly; it compiles a [NetworkPolicy] into a list of
 * [FirewallRule]s and hands them to a [FirewallRuleBackend].
 * Production backends translate the rules into iptables
 * `iptables-restore` text, cgroup eBPF maps, or whatever the
 * platform supports; the in-memory test backend stores the
 * rules verbatim and lets tests assert on them.
 *
 * The model is deliberately narrow: every field is a primitive
 * or a small enum. We do not model conntrack helpers, NAT, or
 * packet marking — those are platform concerns and live in the
 * backend. The job of this layer is to express "what the policy
 * means" in a form every backend can read.
 */
enum class Direction { INBOUND, OUTBOUND }
enum class AddressFamily { IPV4, IPV6, ANY }
enum class FirewallProtocol { TCP, UDP, ICMP, ANY }
enum class FirewallAction { ACCEPT, DROP, REJECT }
enum class ConnectionState { NEW, ESTABLISHED, RELATED, ANY }

/**
 * An inclusive CIDR range. We represent it as a string so the
 * backend can pass it directly to iptables (`-s 10.0.0.0/8`) or
 * to its platform equivalent. Special forms:
 *
 *   - `"any"` — matches every address.
 *   - `"127.0.0.0/8"` — IPv4 CIDR.
 *   - `"::1/128"` — IPv6 CIDR.
 *   - `"169.254.169.254/32"` — exact host (link-local cloud
 *     metadata, useful for explicit deny rules).
 *
 * Validation is in the [FirewallRule] init block; we keep this
 * type as a value type without a parsing helper to avoid a
 * dependency on Guava or a custom CIDR library.
 */
@JvmInline
value class IpRange(val cidr: String) {
    init {
        require(cidr.isNotBlank()) { "IpRange cidr must not be blank" }
        if (cidr != "any") {
            // Cheap syntactic check: must contain a "/", a
            // numeric prefix length, and a non-empty address
            // half. We do not validate the address half here —
            // a real deployment should re-validate at the
            // backend, but a typo must not compile a rule.
            val slash = cidr.indexOf('/')
            require(slash > 0) { "IpRange must be CIDR (e.g. 10.0.0.0/8): $cidr" }
            val prefix = cidr.substring(slash + 1)
            require(prefix.isNotEmpty() && prefix.all { it.isDigit() }) {
                "IpRange prefix length must be a non-negative integer: $cidr"
            }
            val prefixLen = prefix.toIntOrNull()
            require(prefixLen != null && prefixLen in 0..128) {
                "IpRange prefix length out of range: $cidr"
            }
            val addressPart = cidr.substring(0, slash)
            require(addressPart.isNotBlank()) { "IpRange address part is blank: $cidr" }
        }
    }
    override fun toString(): String = cidr

    companion object {
        val ANY = IpRange("any")
        val LOOPBACK_V4 = IpRange("127.0.0.0/8")
        val LOOPBACK_V6 = IpRange("::1/128")
        val LAN_10 = IpRange("10.0.0.0/8")
        val LAN_172 = IpRange("172.16.0.0/12")
        val LAN_192 = IpRange("192.168.0.0/16")
        val LINK_LOCAL_V4 = IpRange("169.254.0.0/16")
        val CGN = IpRange("100.64.0.0/10")
        val CLOUD_METADATA = IpRange("169.254.169.254/32")
    }
}

/**
 * One row in a session's firewall chain. The rule is uniquely
 * identified by [id] so a [FirewallRuleBackend] can diff a
 * desired state against a current state without re-emitting
 * identical rules.
 *
 * Rule fields use enums + value classes so the backend can
 * render them with a `when` over the enums. Adding a new field
 * is a breaking change to the test contract — that's on
 * purpose, the firewall is a security boundary.
 */
data class FirewallRule(
    /**
     * Stable id for diffing. The translator produces
     * deterministic ids (`mode-direction-priority-meaning`).
     * A backend MUST NOT mutate the id of a rule it received.
     */
    val id: String,
    val direction: Direction,
    val family: AddressFamily,
    /**
     * Network interface name. `"lo"` for loopback, `"any"` for
     * any interface, an actual name like `"wlan0"` for a
     * specific one.
     */
    val interfaceName: String,
    val protocol: FirewallProtocol,
    /**
     * Destination (for INBOUND) or source (for OUTBOUND) port.
     * `null` means any port.
     */
    val port: Int?,
    val source: IpRange,
    val destination: IpRange,
    val state: ConnectionState,
    val action: FirewallAction,
    /**
     * Lower numbers are emitted first. The translator sets the
     * priority so the rule order is deterministic across runs;
     * the backend is free to ignore the field if its format
     * doesn't have a notion of priority.
     */
    val priority: Int,
    val comment: String
) {
    init {
        require(id.isNotBlank()) { "FirewallRule id must not be blank" }
        require(interfaceName.isNotBlank()) { "FirewallRule interface must not be blank" }
        require(port == null || port in 1..65535) {
            "FirewallRule port must be 1..65535 or null: $port"
        }
    }
}
