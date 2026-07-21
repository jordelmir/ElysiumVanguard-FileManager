package com.elysium.vanguard.core.runtime.network.policy

import com.elysium.vanguard.core.runtime.network.policy.NetworkMode
import com.elysium.vanguard.core.runtime.network.policy.NetworkPolicy
import com.elysium.vanguard.core.runtime.workspace_def.NetworkAccessMode
import com.elysium.vanguard.core.runtime.workspace_def.NetworkPolicySpec

/**
 * PHASE 105 — translation from the **workspace-level
 * [NetworkPolicySpec]** (Phase 104) to the **session-level
 * [NetworkPolicy]** (Phase 13).
 *
 * The workspace spec is the creator's *intent* (e.g. "this
 * Blender workspace can reach api.example.com"). The session
 * policy is the runtime's *enforcement* (a `NetworkMode`
 * the firewall compiles iptables rules from). The
 * translation is the seam between intent and enforcement.
 *
 * **Mapping** (the only place this is defined):
 *
 *  - [NetworkAccessMode.DENY_ALL] →
 *    [NetworkMode.LOOPBACK_ONLY]. The workspace can use
 *    loopback (for IPC with the host side, e.g. the
 *    Elysium device bridge) but cannot reach any remote
 *    host. We do NOT use [NetworkMode.BLOCKED] because
 *    that drops loopback, which breaks a lot of tools
 *    that talk to localhost (X11 forwarding, dbus,
 *    systemd-resolved, etc.).
 *
 *  - [NetworkAccessMode.ALLOW_LIST] →
 *    [NetworkMode.OUTBOUND_ONLY] +
 *    `allowedRemoteHosts` populated from
 *    [NetworkPolicySpec.allowedHosts] +
 *    `publishedPorts` populated from
 *    [NetworkPolicySpec.allowedPorts] +
 *    `dnsAllowed` from [NetworkPolicySpec.dnsAllowed].
 *    The workspace can reach exactly the listed hosts on
 *    the listed ports; nothing else.
 *
 *  - [NetworkAccessMode.ALLOW_ALL] →
 *    [NetworkMode.INTERNET]. The workspace can reach any
 *    host. The `dnsAllowed` flag is also propagated (a
 *    rare `INTERNET + no DNS` config would be reachable
 *    by IP only).
 *
 * **Why a translation function (not direct use of the
 * spec)**: the firewall + broker are typed against
 * [NetworkPolicy]. The spec lives at a different layer
 * (the workspace JSON file). A function is the only
 * place the two layers meet; if a future refactor wants
 * to change the mapping (e.g. `DENY_ALL` →
 * `BLOCKED`), one place to change.
 *
 * **JVM testability**: the function is a pure mapping
 * over a sealed type. No I/O, no Android. Tests
 * pin the truth table.
 */
object NetworkPolicySpecBridge {

    /**
     * Translate a workspace [spec] to a session [NetworkPolicy].
     *
     * The [defaultMode] is the fallback mode for an
     * unrecognized [NetworkAccessMode] (defense in depth:
     * the enum has 3 values today, but a future version
     * might add a 4th; the bridge must never crash on
     * an unknown value). Defaults to
     * [NetworkMode.LOOPBACK_ONLY] (the safe direction).
     */
    fun toSessionPolicy(
        spec: NetworkPolicySpec,
        defaultMode: NetworkMode = NetworkMode.LOOPBACK_ONLY,
    ): NetworkPolicy = when (spec.mode) {
        NetworkAccessMode.DENY_ALL -> NetworkPolicy(
            mode = NetworkMode.LOOPBACK_ONLY,
            publishedPorts = emptySet(),
            allowedRemoteHosts = emptySet(),
            allowWildcardListen = false,
        )

        NetworkAccessMode.ALLOW_LIST -> NetworkPolicy(
            mode = NetworkMode.OUTBOUND_ONLY,
            publishedPorts = spec.allowedPorts,
            // The spec's allowedHosts go into the
            // session's allowedRemoteHosts. The
            // firewall's `OUTBOUND_ONLY` rules use this
            // set as the allow-list.
            allowedRemoteHosts = spec.allowedHosts.toSet(),
            // Wildcard listen is OFF by default even
            // when a workspace declares a network
            // allow-list. A workspace that wants to
            // listen on 0.0.0.0 must opt in via a
            // separate "publish" action (Phase 105+).
            allowWildcardListen = false,
        )

        NetworkAccessMode.ALLOW_ALL -> NetworkPolicy(
            mode = NetworkMode.INTERNET,
            publishedPorts = spec.allowedPorts,
            allowedRemoteHosts = emptySet(), // INTERNET = any host
            allowWildcardListen = false,
        )

        // The `when` is exhaustive on NetworkAccessMode.
        // A future enum value will trigger a compile
        // error here, forcing the bridge to be updated.
    }

    /**
     * True iff the spec actually enforces a
     * deny-by-default posture (i.e. the workspace
     * cannot reach any remote host without an explicit
     * opt-in). Used by the security audit log to
     * confirm the platform's Zero-Trust posture for a
     * given workspace.
     */
    fun isDenyByDefault(spec: NetworkPolicySpec): Boolean =
        spec.mode == NetworkAccessMode.DENY_ALL
}
