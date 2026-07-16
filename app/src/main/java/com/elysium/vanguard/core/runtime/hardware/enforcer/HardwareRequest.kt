package com.elysium.vanguard.core.runtime.hardware.enforcer

import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareClass
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision
import com.elysium.vanguard.core.runtime.hardware.broker.HardwarePolicy
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareTargetId

/**
 * Phase 19 — the request the enforcer receives.
 *
 * The enforcer is the seam between the broker's per-request
 * decision (Phase 18) and the actual platform API call
 * (Android `UsbManager.requestPermission`,
 * `BluetoothAdapter.startDiscovery`, etc.). The
 * [HardwareEnforcementService] builds a [HardwareRequest]
 * from a session's request, calls the broker, and then
 * hands the resulting decision + the original request to
 * the enforcer.
 *
 * The enforcer's job is *not* to re-decide. The broker has
 * already said yes / no / require-confirmation. The
 * enforcer's job is to translate that decision into a
 * platform call. If the broker said Allow, the enforcer
 * calls the API. If it said AllowWithConfirmation, the
 * enforcer triggers the consent UI and waits for the
 * user's response. If it said Deny, the enforcer refuses
 * the call (or returns a typed Denied result).
 */
data class HardwareRequest(
    /** Session id of the guest making the request. */
    val sessionId: String,
    /** The policy in effect for the session at request time. */
    val policy: HardwarePolicy,
    val hardwareClass: HardwareClass,
    val action: HardwareAction,
    val targetId: HardwareTargetId,
    /** The broker's typed decision. The enforcer does NOT re-decide. */
    val decision: HardwareDecision
)

/**
 * Opaque handle to an opened hardware resource. The
 * production enforcer hands back a real platform object
 * (e.g. a `UsbDeviceConnection`) wrapped behind this id;
 * the test enforcer hands back a synthetic id. Callers
 * pass the handle back to the enforcer for follow-up
 * operations (read / write / close) — the enforcer
 * resolves the id to the underlying platform object.
 */
@JvmInline
value class HardwareHandle(val id: String) {
    init { require(id.isNotBlank()) { "HardwareHandle id must not be blank" } }
}

/**
 * The enforcer's typed result.
 *
 *   - [Granted] — the platform call succeeded; the caller
 *     may use the [handle] for follow-up operations.
 *     `handle = null` for read-only operations (LIST, READ)
 *     that don't return a long-lived resource.
 *   - [PendingConsent] — the broker said
 *     [HardwareDecision.AllowWithConfirmation]; the
 *     enforcer has dispatched a consent dialog. The
 *     runtime polls or receives a callback with the same
 *     [consentId] when the user decides. This shape lets
 *     the UI render a single in-flight dialog per request
 *     without blocking the calling thread.
 *   - [Denied] — the broker said Deny, OR the platform
 *     call itself returned a "permission denied" error.
 *     The two are indistinguishable from the caller's
 *     perspective; the audit log distinguishes them.
 *   - [Error] — a non-permission error (USB device
 *     disconnected mid-transfer, Bluetooth radio off,
 *     etc.). The caller surfaces the [cause] to the user.
 */
sealed class HardwareEnforcementResult {
    data class Granted(val handle: HardwareHandle?) : HardwareEnforcementResult()
    data class PendingConsent(val consentId: String) : HardwareEnforcementResult()
    object Denied : HardwareEnforcementResult()
    data class Error(val cause: Throwable) : HardwareEnforcementResult()
}
