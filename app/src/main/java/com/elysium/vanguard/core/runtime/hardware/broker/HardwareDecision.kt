package com.elysium.vanguard.core.runtime.hardware.broker

/**
 * Phase 18 — the broker's per-request decision.
 *
 * Three states, parallel to
 * [com.elysium.vanguard.core.runtime.network.policy.NetworkDecision]:
 *
 *   - [Allow] — the operation is permitted; the enforcer
 *     proceeds without a UI round-trip.
 *   - [AllowWithConfirmation] — the operation is permitted
 *     after a user-visible confirmation. The runtime
 *     surfaces the [reason] in the consent prompt so the
 *     user can see *why* a request needs approval.
 *   - [Deny] — the operation is refused. The enforcer MUST
 *     NOT proceed; the broker will not negotiate further.
 */
sealed class HardwareDecision {
    object Allow : HardwareDecision()
    data class AllowWithConfirmation(val reason: String) : HardwareDecision()
    data class Deny(val reason: String) : HardwareDecision()

    /** Convenience: true if the decision permits the operation
     *  (with or without confirmation). */
    val permits: Boolean
        get() = this is Allow || this is AllowWithConfirmation
}
