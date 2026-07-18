package com.elysium.vanguard.core.runtime.policy

import com.elysium.vanguard.core.runtime.bridge.MountEntry

/**
 * Phase 50 — the runtime-side mount policy
 * enforcer.
 *
 * The enforcer takes a [MountPolicy] and a
 * proposed list of [MountEntry] instances and
 * returns either a [MountEnforcementResult.Allowed]
 * (with the filtered + read-only-annotated
 * mount list) or a [MountEnforcementResult.Denied]
 * (with the violations).
 *
 * The enforcer is the place the runner
 * ([com.elysium.vanguard.core.runtime.runner.LinuxProotSessionRunner])
 * consults before producing its `proot -b` flag
 * list. The runner does not need to know about
 * the policy: it gets a clean [MountEntry] list
 * and feeds it to the proot launcher.
 *
 * ## Behaviour
 *
 * The enforcer's [enforce] method:
 *
 * 1. If the policy is in [MountPolicyMode.OPEN]
 *    mode, every proposed mount is allowed with
 *    the proposed read-only flag (or `false` if
 *    the policy's [MountPolicy.defaultReadOnly]
 *    is `false` and the mount does not specify
 *    one).
 * 2. If the policy is in [MountPolicyMode.ALLOWLIST]
 *    mode, a mount is allowed iff its `hostPath`
 *    starts with one of the policy entries'
 *    `hostPathPrefix`. The mount's read-only
 *    flag is the AND of the proposed flag and
 *    the policy entry's `readOnly` flag (a
 *    policy entry can only make a mount MORE
 *    restrictive, not less).
 * 3. If the policy is in [MountPolicyMode.BLOCKLIST]
 *    mode, a mount is allowed iff its `hostPath`
 *    does NOT start with any of the policy
 *    entries' `hostPathPrefix`. The mount's
 *    read-only flag is unchanged.
 *
 * Denials are recorded in the
 * [MountEnforcementResult.Denied.violations]
 * list. The runner (or a future
 * [com.elysium.vanguard.core.runtime.policy.MountAuditLog])
 * consumes the violations list to record the
 * decision.
 *
 * ## Thread safety
 *
 * The enforcer is a stateless function over
 * its inputs. Multiple threads can call
 * [enforce] concurrently with no synchronisation.
 */
class MountPolicyEnforcer {

    /**
     * Apply [policy] to [proposed]. Returns
     * either [MountEnforcementResult.Allowed]
     * (with the filtered list) or
     * [MountEnforcementResult.Denied] (with the
     * violations).
     */
    fun enforce(
        policy: MountPolicy,
        proposed: List<MountEntry>
    ): MountEnforcementResult {
        if (policy.mode == MountPolicyMode.OPEN) {
            // OPEN mode: no policy. Every mount
            // is allowed; the proposed read-only
            // flag is honoured as-is.
            return MountEnforcementResult.Allowed(
                filteredMounts = proposed.toList()
            )
        }

        val violations = ArrayList<MountPolicyViolation>()
        val allowed = ArrayList<MountEntry>()

        for (entry in proposed) {
            val matchingEntry = findMatchingPolicyEntry(policy, entry.hostPath)
            val permitted = when (policy.mode) {
                MountPolicyMode.ALLOWLIST -> matchingEntry != null
                MountPolicyMode.BLOCKLIST -> matchingEntry == null
                MountPolicyMode.OPEN -> true // handled above
            }
            if (!permitted) {
                violations += MountPolicyViolation(
                    hostPath = entry.hostPath,
                    guestPath = entry.guestPath,
                    reason = when (policy.mode) {
                        MountPolicyMode.ALLOWLIST ->
                            "hostPath '${entry.hostPath}' is not in the workspace's mount allowlist"
                        MountPolicyMode.BLOCKLIST ->
                            "hostPath '${entry.hostPath}' is explicitly blocked by the workspace's mount policy"
                        MountPolicyMode.OPEN -> "" // unreachable
                    }
                )
                continue
            }
            // Read-only tightening: in ALLOWLIST
            // mode, the policy entry's read-only
            // flag can only make a mount MORE
            // restrictive, not less. In BLOCKLIST
            // mode, the policy does not affect
            // read-only-ness (the entry is a
            // denial, not a tightening).
            val tightenedReadOnly = when (policy.mode) {
                MountPolicyMode.ALLOWLIST ->
                    entry.readOnly || (matchingEntry?.readOnly == true)
                MountPolicyMode.BLOCKLIST -> entry.readOnly
                MountPolicyMode.OPEN -> entry.readOnly
            }
            allowed += entry.copy(readOnly = tightenedReadOnly)
        }

        return if (violations.isEmpty()) {
            MountEnforcementResult.Allowed(filteredMounts = allowed)
        } else {
            MountEnforcementResult.Denied(
                allowedMounts = allowed,
                violations = violations
            )
        }
    }

    /**
     * Find the policy entry whose
     * [MountPolicyEntry.normalisedPrefix] is a
     * prefix of [hostPath]. Returns `null` if no
     * entry matches.
     *
     * The match is case-sensitive. On Android
     * (ext4 / f2fs) file paths are
     * case-sensitive; on Linux the same.
     */
    private fun findMatchingPolicyEntry(
        policy: MountPolicy,
        hostPath: String
    ): MountPolicyEntry? {
        for (entry in policy.entries) {
            val prefix = entry.normalisedPrefix
            if (hostPath == prefix || hostPath.startsWith("$prefix/")) {
                return entry
            }
        }
        return null
    }
}

/**
 * The enforcer's result.
 *
 * - [Allowed] — every proposed mount is in the
 *   policy. [filteredMounts] is the proposed
 *   list with read-only flags tightened to
 *   satisfy the policy.
 * - [Denied] — one or more proposed mounts are
 *   outside the policy. [allowedMounts] is the
 *   subset that passed; [violations] is the
 *   list of denied mounts + the reason.
 *
 * Even on a denial, [allowedMounts] is
 * non-empty if at least one mount passed. The
 * runner can choose to "run with the allowed
 * subset" (a graceful degradation) or "refuse
 * to start" (a hard stop). Phase 50 returns
 * the full result; the runner's policy is a
 * Phase 51+ concern.
 */
sealed class MountEnforcementResult {
    data class Allowed(
        val filteredMounts: List<MountEntry>
    ) : MountEnforcementResult()

    data class Denied(
        val allowedMounts: List<MountEntry>,
        val violations: List<MountPolicyViolation>
    ) : MountEnforcementResult()
}

/**
 * A single mount that was denied by the policy.
 *
 * The runtime surfaces [hostPath] / [guestPath]
 * in the [com.elysium.vanguard.core.runtime.observability.RuntimeEvent]
 * for the user to see what the session tried
 * to mount, plus a human-readable [reason].
 */
data class MountPolicyViolation(
    val hostPath: String,
    val guestPath: String,
    val reason: String
) {
    init {
        require(hostPath.isNotBlank()) { "hostPath must not be blank" }
        require(guestPath.isNotBlank()) { "guestPath must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}
