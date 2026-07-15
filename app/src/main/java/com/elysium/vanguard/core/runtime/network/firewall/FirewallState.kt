package com.elysium.vanguard.core.runtime.network.firewall

/**
 * Phase 15 — the compiled firewall for one session.
 *
 * A [FirewallState] is the desired state of a single session's
 * iptables chain (or cgroup eBPF map, or whatever the backend
 * uses). It pairs a [chainName] (the table key in the backend)
 * with a [rules] list. The list is **sorted by
 * [FirewallRule.priority] ascending** so the backend can emit
 * rules in order without re-sorting.
 */
data class FirewallState(
    val chainName: String,
    val rules: List<FirewallRule>
) {
    init {
        require(chainName.isNotBlank()) { "chainName must not be blank" }
        require(rules.none { it.id.isBlank() }) { "rules must have non-blank ids" }
        // Sanity: rule ids must be unique within a chain. Two
        // rules with the same id would silently shadow each
        // other in the diff path.
        val ids = rules.map { it.id }
        require(ids.size == ids.toSet().size) {
            "FirewallState has duplicate rule ids: " +
                ids.groupBy { it }.filterValues { it.size > 1 }.keys
        }
    }
}

/**
 * The change set between a [desired] [FirewallState] and the
 * [current] state in the backend. The translator produces
 * `desired`; the backend reports `current`; the enforcer
 * applies the diff.
 *
 * - [added] — rules in `desired` not in `current`. The
 *   backend should append these.
 * - [removed] — rules in `current` not in `desired`. The
 *   backend should delete these.
 * - [kept] — rules in both. The backend can leave these
 *   alone (they're idempotent in any backend that compares
 *   by id).
 */
data class FirewallDiff(
    val added: List<FirewallRule>,
    val removed: List<FirewallRule>,
    val kept: List<FirewallRule>
) {
    val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty()

    val totalChanges: Int
        get() = added.size + removed.size
}
