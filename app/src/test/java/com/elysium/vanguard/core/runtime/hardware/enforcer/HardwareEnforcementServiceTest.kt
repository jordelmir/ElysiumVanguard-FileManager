package com.elysium.vanguard.core.runtime.hardware.enforcer

import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAccess
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAuditLog
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareBroker
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareClass
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision
import com.elysium.vanguard.core.runtime.hardware.broker.HardwarePolicy
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareTargetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 19 — tests for the [HardwareEnforcementService].
 *
 * The service is the runtime's user-facing hardware access
 * seam. The tests pin:
 *
 *   - The broker decides, the enforcer enforces; the
 *     service does not re-decide.
 *   - Deny decisions never reach the enforcer.
 *   - Allow + AllowWithConfirmation both reach the
 *     enforcer with the broker's decision baked into the
 *     request.
 *   - The audit log captures every decision (allow,
 *     confirmation, deny).
 *   - The service is thread-safe under concurrent
 *     requests from multiple sessions.
 */
class HardwareEnforcementServiceTest {

    // --- happy path: Allow reaches the enforcer ---

    @Test
    fun `Allow decision reaches the enforcer and the result is Granted`() {
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.Granted(handle = null))
        }
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        assertTrue("Allow must produce Granted, got $result", result is HardwareEnforcementResult.Granted)
        // The enforcer saw exactly one request, with the
        // correct session id + class + action + target.
        val calls = enforcer.calls()
        assertEquals(1, calls.size)
        val req = calls.single()
        assertEquals("s1", req.sessionId)
        assertEquals(HardwareClass.USB, req.hardwareClass)
        assertEquals(HardwareAction.LIST, req.action)
        assertEquals(HardwareTargetId.Specific("1234:5678"), req.targetId)
        assertTrue("decision baked into the request must be Allow", req.decision is HardwareDecision.Allow)
    }

    // --- AllowWithConfirmation reaches the enforcer with a consent id ---

    @Test
    fun `AllowWithConfirmation reaches the enforcer and the result is PendingConsent`() {
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.PendingConsent(consentId = "consent-1"))
        }
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.CONFIRM),
            hardwareClass = HardwareClass.BLUETOOTH,
            action = HardwareAction.LIST
        )
        assertTrue(
            "AllowWithConfirmation must produce PendingConsent, got $result",
            result is HardwareEnforcementResult.PendingConsent
        )
        assertEquals("consent-1", (result as HardwareEnforcementResult.PendingConsent).consentId)
        val req = enforcer.calls().single()
        assertTrue(
            "decision baked into the request must be AllowWithConfirmation",
            req.decision is HardwareDecision.AllowWithConfirmation
        )
    }

    // --- Deny never reaches the enforcer ---

    @Test
    fun `Deny decision never reaches the enforcer and the result is Denied`() {
        // The enforcer is wired but the service must not
        // call it on a Deny decision. We assert on the
        // recording's size rather than installing a `fail`
        // in the response — that would throw on the
        // *construction* of the recording, not the
        // dispatch.
        val enforcer = RecordingHardwareEnforcer()
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.BLOCKED),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.LIST
        )
        assertTrue(
            "BLOCKED must produce Denied, got $result",
            result is HardwareEnforcementResult.Denied
        )
        assertEquals("enforcer must not see the request", 0, enforcer.size())
    }

    // --- Error from the enforcer propagates ---

    @Test
    fun `enforcer Error propagates as Error with the cause`() {
        val cause = RuntimeException("USB device disconnected")
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.Error(cause))
        }
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.READ,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        assertTrue(
            "enforcer Error must surface as Error, got $result",
            result is HardwareEnforcementResult.Error
        )
        assertEquals(cause, (result as HardwareEnforcementResult.Error).cause)
    }

    // --- the enforcer can return Denied even for an Allow (e.g. permission denied) ---

    @Test
    fun `enforcer Denied is the result when the platform refuses the call`() {
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.Denied)
        }
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        assertTrue(result is HardwareEnforcementResult.Denied)
    }

    // --- audit log captures every decision ---

    @Test
    fun `audit log records allow, confirmation, AND deny decisions`() {
        val audit = HardwareAuditLog()
        val enforcer = RecordingHardwareEnforcer()
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = audit
        )
        // Allow
        service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.READ,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        // Confirmation
        service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.CONFIRM),
            hardwareClass = HardwareClass.BLUETOOTH,
            action = HardwareAction.LIST
        )
        // Deny
        service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.BLOCKED),
            hardwareClass = HardwareClass.NFC,
            action = HardwareAction.LIST
        )
        val events = audit.snapshot()
        // The service records all 3 (broker records the
        // first 2, service records the deny).
        assertEquals("audit must record all 3 decisions", 3, events.size)
        assertTrue(events[0].decision is HardwareDecision.Allow)
        assertTrue(events[1].decision is HardwareDecision.AllowWithConfirmation)
        assertTrue(events[2].decision is HardwareDecision.Deny)
    }

    // --- thread safety ---

    @Test
    fun `service is thread-safe under concurrent requests from multiple sessions`() {
        val audit = HardwareAuditLog()
        val enforcer = RecordingHardwareEnforcer()
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = audit
        )
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { threadIdx ->
            Thread {
                start.await()
                repeat(50) {
                    service.request(
                        sessionId = "s$threadIdx",
                        policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
                        hardwareClass = HardwareClass.USB,
                        action = HardwareAction.READ,
                        targetId = HardwareTargetId.Specific("dev-$threadIdx.$it")
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals("enforcer must see all 400 requests", 8 * 50, enforcer.size())
        assertEquals("audit must record all 400 events", 8 * 50, audit.size())
    }

    // --- value type invariants ---

    @Test
    fun `HardwareHandle rejects a blank id`() {
        try {
            HardwareHandle("")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `RecordingHardwareEnforcer default response is Granted with no handle`() {
        val enforcer = RecordingHardwareEnforcer()
        val request = HardwareRequest(
            sessionId = "s1",
            policy = HardwarePolicy(),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.LIST,
            targetId = HardwareTargetId.Any,
            decision = HardwareDecision.Allow
        )
        val result = enforcer.enforce(request)
        assertTrue(result is HardwareEnforcementResult.Granted)
        assertNotNull(result)
        assertNull("default Granted has no handle", (result as HardwareEnforcementResult.Granted).handle)
    }

    @Test
    fun `RecordingHardwareEnforcer per-request response overrides the default`() {
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.Denied)
            respondForEach { req ->
                when (req.hardwareClass) {
                    HardwareClass.USB -> HardwareEnforcementResult.Granted(handle = HardwareHandle("h-usb"))
                    else -> HardwareEnforcementResult.Denied
                }
            }
        }
        val usb = enforcer.enforce(makeRequest(HardwareClass.USB))
        val bt = enforcer.enforce(makeRequest(HardwareClass.BLUETOOTH))
        assertTrue(usb is HardwareEnforcementResult.Granted)
        assertEquals(
            "h-usb",
            (usb as HardwareEnforcementResult.Granted).handle!!.id
        )
        assertTrue(bt is HardwareEnforcementResult.Denied)
    }

    @Test
    fun `defaultService wires broker + enforcer + audit into a service`() {
        val enforcer = RecordingHardwareEnforcer()
        val service = defaultService(enforcer)
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        assertEquals(1, enforcer.size())
    }

    @Test
    fun `enforcer calls() returns a defensive copy`() {
        val enforcer = RecordingHardwareEnforcer()
        enforcer.enforce(makeRequest(HardwareClass.USB))
        val first = enforcer.calls()
        enforcer.clear()
        val second = enforcer.calls()
        assertEquals(1, first.size)
        assertEquals(0, second.size)
    }

    @Test
    fun `AllowWithConfirmation for BLUETOOTH LIST with no address still requires consent`() {
        // End-to-end sanity check: broker wildcard rule +
        // service glue + enforcer recording.
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.PendingConsent("c-bt-1"))
        }
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.BLUETOOTH,
            action = HardwareAction.LIST,
            targetId = HardwareTargetId.Any
        )
        assertTrue(result is HardwareEnforcementResult.PendingConsent)
        val req = enforcer.calls().single()
        assertTrue(req.decision is HardwareDecision.AllowWithConfirmation)
    }

    @Test
    fun `enforcer Denied is treated as a non-error result, not an Error`() {
        val enforcer = RecordingHardwareEnforcer().apply {
            respondWith(HardwareEnforcementResult.Denied)
        }
        val service = HardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
            hardwareClass = HardwareClass.USB,
            action = HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        assertFalse("Denied must not be wrapped as Error", result is HardwareEnforcementResult.Error)
        assertTrue(result is HardwareEnforcementResult.Denied)
    }

    // --- helpers ---

    private fun makeRequest(cls: HardwareClass): HardwareRequest = HardwareRequest(
        sessionId = "s1",
        policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE),
        hardwareClass = cls,
        action = HardwareAction.LIST,
        targetId = HardwareTargetId.Any,
        decision = HardwareDecision.Allow
    )

    private fun assertNull(message: String, value: Any?) {
        if (value != null) fail("$message: expected null but was $value")
    }
}
