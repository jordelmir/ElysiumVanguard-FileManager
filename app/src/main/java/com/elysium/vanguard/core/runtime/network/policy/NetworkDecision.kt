package com.elysium.vanguard.core.runtime.network.policy

/**
 * Result of a [NetworkBroker] decision.
 *
 * Three states:
 *
 *   - [Allow] — the operation is permitted.
 *   - [AllowWithConfirmation] — the operation is permitted only
 *     after a user-visible confirmation. Used for 0.0.0.0 binds
 *     and any other operation the master order §10.2 calls out
 *     as "never without explicit consent".
 *   - [Deny] — the operation is refused. The caller MUST NOT
 *     proceed; the broker will not negotiate further.
 */
sealed class NetworkDecision {
    object Allow : NetworkDecision()
    data class AllowWithConfirmation(val reason: String) : NetworkDecision()
    data class Deny(val reason: String) : NetworkDecision()

    /** Convenience: true if the decision permits the operation
     *  (with or without confirmation). */
    val permits: Boolean
        get() = this is Allow || this is AllowWithConfirmation
}
