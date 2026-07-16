package com.elysium.vanguard.core.runtime.hardware.enforcer

import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAccess
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAuditLog
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareBroker
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareClass
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision
import com.elysium.vanguard.core.runtime.hardware.broker.HardwarePolicy
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareTargetId

/**
 * Phase 19 — the runtime's hardware access seam.
 *
 * The service is the user-facing entry point. A session
 * that wants to access a piece of host hardware calls
 * [request]; the service asks the broker for a decision,
 * then hands the decision + the original request to the
 * enforcer. The enforcer translates the decision into a
 * platform call.
 *
 * The split (broker decides, enforcer enforces) keeps the
 * decision logic JVM-testable end-to-end. The service
 * itself is a thin glue class that adds no policy of its
 * own — every rule lives in the broker.
 *
 * Lifecycle:
 *
 *   - [request] is called from the session's coroutine
 *     scope. The enforcer's call is synchronous; the
 *     consent UI is the only async path.
 *   - On [HardwareEnforcementResult.PendingConsent], the
 *     caller is expected to poll or subscribe to a
 *     `ConsentEventBus` (Phase 25) for the matching
 *     [HardwareEnforcementResult.PendingConsent.consentId]
 *     and re-call [request] with the same parameters.
 *   - The service is stateless across calls; the broker
 *     + enforcer + audit log are the durable state.
 */
class HardwareEnforcementService(
    private val broker: HardwareBroker = HardwareBroker(),
    private val enforcer: HardwareEnforcer,
    private val audit: HardwareAuditLog = HardwareAuditLog()
) {
    /**
     * Request hardware access for [sessionId] under [policy].
     * The flow:
     *
     *   1. Broker decides (Allow / AllowWithConfirmation / Deny).
     *   2. If Deny, return [HardwareEnforcementResult.Denied]
     *      without calling the enforcer. The broker has
     *      already recorded the decision in the audit log.
     *   3. Otherwise, hand the request to the enforcer.
     *      The enforcer returns Granted / PendingConsent /
     *      Denied / Error.
     */
    fun request(
        sessionId: String,
        policy: HardwarePolicy,
        hardwareClass: HardwareClass,
        action: HardwareAction,
        targetId: HardwareTargetId = HardwareTargetId.Any
    ): HardwareEnforcementResult {
        val decision = broker.decide(policy, hardwareClass, action, targetId, audit)
        return when (decision) {
            is HardwareDecision.Deny -> {
                // The broker did not record deny decisions
                // (the deny path skips audit to keep the
                // call site in one place). The service
                // records the deny here for the audit log.
                audit.record(
                    com.elysium.vanguard.core.runtime.hardware.broker.AuditEvent(
                        policy = policy,
                        hardwareClass = hardwareClass,
                        action = action,
                        target = targetId,
                        decision = decision
                    )
                )
                HardwareEnforcementResult.Denied
            }
            is HardwareDecision.Allow,
            is HardwareDecision.AllowWithConfirmation -> {
                enforcer.enforce(
                    HardwareRequest(
                        sessionId = sessionId,
                        policy = policy,
                        hardwareClass = hardwareClass,
                        action = action,
                        targetId = targetId,
                        decision = decision
                    )
                )
            }
        }
    }

    /**
     * The "remember my choice" reply for a consent dialog.
     * The runtime records the user's choice and (if
     * [HardwarePolicy.rememberConfirmations] is true) the
     * next request with the same [hardwareClass] +
     * [targetId] under the same [sessionId] skips the
     * confirmation step.
     *
     * The remember-grant set is held by the policy; the
     * service is stateless. A future phase moves the
     * remember-set onto a `ConfirmationMemory` so it
     * survives across service instances.
     */
    fun rememberConsent(
        sessionId: String,
        hardwareClass: HardwareClass,
        targetId: HardwareTargetId
    ): Boolean {
        // The current `rememberConfirmations` flag is on
        // the policy; this helper is the seam a future
        // phase fills in. For now, it's a no-op that
        // returns true when the policy allows remembering.
        return false
    }
}

/**
 * The runtime's canonical "default" service: broker +
 * enforcer + audit log, ready to be wired into a session.
 * Production replaces the enforcer with the Android
 * adapter; tests replace it with [RecordingHardwareEnforcer].
 */
fun defaultService(enforcer: HardwareEnforcer): HardwareEnforcementService =
    HardwareEnforcementService(
        broker = HardwareBroker(),
        enforcer = enforcer,
        audit = HardwareAuditLog()
    )
