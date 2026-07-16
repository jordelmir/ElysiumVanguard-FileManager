package com.elysium.vanguard.core.runtime.hardware.broker

import java.util.UUID

/**
 * Phase 18 — pure decision engine for guest hardware access.
 *
 * The broker says yes / no / require-confirmation given a
 * [HardwarePolicy] and a request. It does not enforce
 * anything itself — that is the
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcer]'s
 * job (Android `UsbManager.requestPermission`,
 * `BluetoothAdapter.startDiscovery`, etc.). Splitting
 * decisions from enforcement keeps the broker JVM-testable
 * end-to-end without an Android device.
 *
 * The rules the broker implements:
 *
 *   - [HardwareAccess.BLOCKED] denies everything, no
 *     exceptions, no confirmation path.
 *   - [HardwareAccess.SILENT] returns Allow but the enforcer
 *     will return empty data (this is "the guest can call
 *     the API but the call resolves to nothing"). Useful for
 *     sandboxed sessions where a `READ` on the camera should
 *     succeed (no exception) but return a black frame.
 *   - [HardwareAccess.READ_ONLY] allows [HardwareAction.LIST]
 *     and [HardwareAction.READ]; denies [HardwareAction.WRITE]
 *     and [HardwareAction.CONNECT] without a confirmation
 *     path.
 *   - [HardwareAccess.READ_WRITE] allows everything without
 *     UI.
 *   - [HardwareAccess.CONFIRM] always returns
 *     [HardwareDecision.AllowWithConfirmation] regardless
 *     of the action.
 *
 * 0.0.0.0-equivalent rules (per master order §18, "never
 * allow access to *every* device without explicit consent"):
 *
 *   - USB LIST with a wildcard `vid=*` or `pid=*` requires
 *     confirmation, even when the policy is READ_WRITE.
 *     The broker flags the request as "any device" and the
 *     UI shows the consent prompt.
 *   - BLUETOOTH LIST with no `address` filter (i.e. the
 *     guest wants every paired / discovered device) requires
 *     confirmation under READ_WRITE. Pairing a specific
 *     device is allowed; the bulk-list is not.
 *   - LOCATION READ with no `accuracy` hint (i.e. the guest
 *     wants both fine and coarse) requires confirmation
 *     under READ_WRITE. A targeted single-accuracy request
 *     is allowed.
 *
 * The broker is *additive* to the platform enforcer. A
 * green light from the broker means "the policy is willing";
 * the platform enforcer then asks the OS for the actual
 * permission (e.g. `Manifest.permission.ACCESS_FINE_LOCATION`).
 * The OS may still refuse — that refusal is a separate
 * decision the broker does not gate.
 */
class HardwareBroker {

    /**
     * Decide whether a process under [policy] is allowed to
     * perform [action] on the [hardwareClass], optionally
     * targeting a specific device identified by [targetId]
     * (USB vid/pid pair, Bluetooth MAC, NFC tag id, etc.).
     *
     * The broker does not look at the actual id; it looks
     * at the *shape* of the request (wildcard vs specific,
     * accuracy hint, etc.). The id is included in the
     * decision's reason so the UI can show "pairing device
     * 11:22:33:44:55:66" rather than just "pairing a
     * device".
     */
    fun decide(
        policy: HardwarePolicy,
        hardwareClass: HardwareClass,
        action: HardwareAction,
        targetId: HardwareTargetId = HardwareTargetId.Any,
        audit: HardwareAuditLog? = null
    ): HardwareDecision {
        val access = policy.accessFor(hardwareClass)
        val reasonLog = "$action on $hardwareClass (target=$targetId)"

        // BLOCKED — hard no for every action.
        if (access == HardwareAccess.BLOCKED) {
            return deny("$access policy denies $reasonLog", audit)
        }
        // SILENT — allow but the enforcer returns nothing.
        if (access == HardwareAccess.SILENT) {
            audit?.record(
                AuditEvent(policy, hardwareClass, action, targetId, HardwareDecision.Allow)
            )
            return HardwareDecision.Allow
        }
        // CONFIRM — always require a UI round-trip.
        if (access == HardwareAccess.CONFIRM) {
            val decision = HardwareDecision.AllowWithConfirmation(
                "CONFIRM policy requires consent for $reasonLog"
            )
            audit?.record(AuditEvent(policy, hardwareClass, action, targetId, decision))
            return decision
        }
        // READ_ONLY — deny writes/connects; allow reads/lists.
        if (access == HardwareAccess.READ_ONLY) {
            return when (action) {
                HardwareAction.LIST, HardwareAction.READ -> {
                    val allow = HardwareDecision.Allow
                    audit?.record(
                        AuditEvent(policy, hardwareClass, action, targetId, allow)
                    )
                    allow
                }
                HardwareAction.WRITE, HardwareAction.CONNECT -> deny(
                    "READ_ONLY policy denies $reasonLog",
                    audit
                )
            }
        }
        // READ_WRITE — allow everything subject to the
        // wildcard rules below.
        check(access == HardwareAccess.READ_WRITE) {
            "unreachable: every access mode handled above ($access)"
        }
        val wildcard = wildcardDecision(hardwareClass, action, targetId, reasonLog)
        if (wildcard != null) {
            audit?.record(AuditEvent(policy, hardwareClass, action, targetId, wildcard))
            return wildcard
        }
        val allow = HardwareDecision.Allow
        audit?.record(AuditEvent(policy, hardwareClass, action, targetId, allow))
        return allow
    }

    private fun wildcardDecision(
        hardwareClass: HardwareClass,
        action: HardwareAction,
        targetId: HardwareTargetId,
        reasonLog: String
    ): HardwareDecision? {
        return when (hardwareClass) {
            HardwareClass.USB -> when (action) {
                HardwareAction.LIST ->
                    if (targetId is HardwareTargetId.WildcardUsb) {
                        HardwareDecision.AllowWithConfirmation(
                            "USB LIST with no specific VID/PID requires consent ($reasonLog)"
                        )
                    } else null
                HardwareAction.CONNECT ->
                    if (targetId is HardwareTargetId.WildcardUsb) {
                        HardwareDecision.AllowWithConfirmation(
                            "USB CONNECT to 'any device' requires consent ($reasonLog)"
                        )
                    } else null
                else -> null
            }
            HardwareClass.BLUETOOTH -> when (action) {
                HardwareAction.LIST ->
                    if (targetId is HardwareTargetId.Any) {
                        HardwareDecision.AllowWithConfirmation(
                            "BLUETOOTH LIST with no address filter requires consent ($reasonLog)"
                        )
                    } else null
                else -> null
            }
            HardwareClass.LOCATION -> when (action) {
                HardwareAction.READ ->
                    if (targetId is HardwareTargetId.Any) {
                        HardwareDecision.AllowWithConfirmation(
                            "LOCATION READ with no accuracy hint requires consent ($reasonLog)"
                        )
                    } else null
                else -> null
            }
            else -> null
        }
    }

    private fun deny(reason: String, audit: HardwareAuditLog?): HardwareDecision {
        // We do not have a target at the deny level for
        // the audit path; the caller passes one when they
        // call decide*. So we skip audit on the deny path;
        // the caller is expected to audit themselves on
        // the deny path with full context. We mirror the
        // NetworkBroker's pattern.
        val decision = HardwareDecision.Deny(reason)
        return decision
    }
}

/**
 * Identifies the target of a hardware request.
 *
 * The broker looks at the *shape* (Any vs Specific vs
 * Wildcard) but never reads the actual id. The id is
 * surfaced in the decision's reason so the UI can show
 * "pairing 11:22:33:44:55:66" rather than "pairing a
 * device".
 */
sealed class HardwareTargetId {
    /** No target filter — the guest wants every device / every accuracy. */
    object Any : HardwareTargetId()

    /** A specific target with a stable id (USB vid/pid, MAC, tag id, ...). */
    data class Specific(val id: String) : HardwareTargetId() {
        init { require(id.isNotBlank()) { "specific target id must not be blank" } }
    }

    /** USB-specific wildcard (vid=* or pid=*). */
    object WildcardUsb : HardwareTargetId()
}

/**
 * One row of the hardware audit log. The log is in-memory
 * (a [HardwareAuditLog] is a synchronized list);
 * persistence is the caller's job.
 */
data class AuditEvent(
    val policy: HardwarePolicy,
    val hardwareClass: HardwareClass,
    val action: HardwareAction,
    val target: HardwareTargetId,
    val decision: HardwareDecision
) {
    val atMs: Long = System.currentTimeMillis()
}

/**
 * In-memory append-only log of [AuditEvent]s. Synchronized
 * for thread-safety; a real-world load would replace this
 * with a ring buffer that flushes to disk.
 */
class HardwareAuditLog {
    private val events = mutableListOf<AuditEvent>()
    private val lock = Any()

    fun record(event: AuditEvent) {
        synchronized(lock) { events.add(event) }
    }

    fun snapshot(): List<AuditEvent> = synchronized(lock) { events.toList() }

    fun size(): Int = synchronized(lock) { events.size }

    fun clear() = synchronized(lock) { events.clear() }
}
