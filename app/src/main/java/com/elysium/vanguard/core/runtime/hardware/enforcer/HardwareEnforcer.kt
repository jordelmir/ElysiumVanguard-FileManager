package com.elysium.vanguard.core.runtime.hardware.enforcer

/**
 * Phase 19 — the platform seam.
 *
 * The [HardwareEnforcer] is the interface the production
 * Android adapter satisfies. It receives a
 * [HardwareRequest] (already decided by the broker) and
 * returns a typed [HardwareEnforcementResult].
 *
 * Splitting the enforcer from the broker (Phase 18) keeps
 * the policy logic JVM-testable: the broker is a pure
 * decision engine with no platform dependencies; the
 * enforcer is a thin adapter over the platform API. Tests
 * inject a [RecordingHardwareEnforcer] (below) and assert
 * on the call sequence; production wires
 * [com.elysium.vanguard.core.runtime.hardware.enforcer.AndroidHardwareEnforcer]
 * (in a follow-up phase) that talks to the real Android
 * managers.
 *
 * Implementations MUST be thread-safe. The
 * [HardwareEnforcementService] may call [enforce] from
 * multiple sessions concurrently.
 */
interface HardwareEnforcer {
    fun enforce(request: HardwareRequest): HardwareEnforcementResult
}

/**
 * Test enforcer that records every request and returns a
 * canned result. The test suite wires this in place of
 * the production Android adapter and asserts on the
 * recorded calls + controls the response.
 *
 * The default response is configurable per-request via
 * [respondWith]; tests that need different responses for
 * different requests set a [responseFor] function instead.
 */
class RecordingHardwareEnforcer : HardwareEnforcer {
    private val lock = Any()
    private val recorded = mutableListOf<HardwareRequest>()
    private var defaultResponse: (HardwareRequest) -> HardwareEnforcementResult = {
        HardwareEnforcementResult.Granted(handle = null)
    }
    private var responseFor: ((HardwareRequest) -> HardwareEnforcementResult)? = null

    fun respondWith(result: HardwareEnforcementResult) {
        synchronized(lock) {
            defaultResponse = { result }
            responseFor = null
        }
    }

    fun respondForEach(f: (HardwareRequest) -> HardwareEnforcementResult) {
        synchronized(lock) { responseFor = f }
    }

    override fun enforce(request: HardwareRequest): HardwareEnforcementResult {
        val response = synchronized(lock) {
            recorded += request
            responseFor?.invoke(request) ?: defaultResponse(request)
        }
        return response
    }

    fun calls(): List<HardwareRequest> = synchronized(lock) { recorded.toList() }
    fun size(): Int = synchronized(lock) { recorded.size }
    fun clear() = synchronized(lock) { recorded.clear() }
}
