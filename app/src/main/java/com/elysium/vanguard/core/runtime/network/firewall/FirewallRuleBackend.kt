package com.elysium.vanguard.core.runtime.network.firewall

/**
 * Phase 15 — abstract backend for firewall state.
 *
 * A [FirewallRuleBackend] is the platform-specific sink for
 * [FirewallState]s. The production backend (Phase 15 production
 * wiring) will translate each state into iptables / cgroup
 * eBPF; this interface is the seam the test backend satisfies.
 *
 * The contract is:
 *
 *   - [apply] replaces the chain for [state.chainName] with
 *     [state]. The backend is free to compute its own diff and
 *     apply the minimal change set; callers should not assume
 *     any in-place mutation.
 *   - [remove] tears down the chain for [chainName]. After
 *     `remove`, [snapshot] returns null for that chain.
 *   - [snapshot] returns the chain's current state, or null
 *     when the chain does not exist.
 *   - [listChains] enumerates the chains the backend currently
 *     holds. The runtime uses this at startup to reconcile
 *     stale state from a previous boot.
 *
 * All methods are thread-safe. The production backend uses
 * iptables' atomic restore (`iptables-restore`); the test
 * backend uses a `synchronized` map.
 */
interface FirewallRuleBackend {
    fun apply(state: FirewallState)
    fun remove(chainName: String)
    fun snapshot(chainName: String): FirewallState?
    fun listChains(): List<String>
}

/**
 * Pure-JVM backend that stores the chains verbatim in a
 * `synchronized` map. The unit tests assert against this
 * backend's snapshot.
 *
 * The backend does not enforce uniqueness of `chainName`; the
 * caller is expected to use the [NetworkPolicyFirewall]'s
 * [chainNameFor] helper. The map keys are the chain names.
 */
class InMemoryFirewallBackend : FirewallRuleBackend {
    private val states = mutableMapOf<String, FirewallState>()
    private val lock = Any()

    override fun apply(state: FirewallState) {
        synchronized(lock) { states[state.chainName] = state }
    }

    override fun remove(chainName: String) {
        synchronized(lock) { states.remove(chainName) }
    }

    override fun snapshot(chainName: String): FirewallState? = synchronized(lock) {
        states[chainName]
    }

    override fun listChains(): List<String> = synchronized(lock) {
        states.keys.sorted()
    }
}
