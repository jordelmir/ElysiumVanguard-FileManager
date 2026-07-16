package com.elysium.vanguard.core.runtime.hardware.broker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase 18 — tests for the [HardwareBroker] decision engine
 * and its supporting types.
 *
 * The broker is the seam between the [HardwarePolicy] (a
 * value type the catalog ships) and the platform enforcer
 * (Android `UsbManager`, `BluetoothManager`, `NfcManager`).
 * The tests pin every access mode, the wildcard
 * confirmation rules, and the audit log.
 */
class HardwareBrokerTest {

    private val broker = HardwareBroker()

    // --- per-mode behaviour ---

    @Test
    fun `BLOCKED denies every action on every class`() {
        val policy = HardwarePolicy(
            defaultMode = HardwareAccess.BLOCKED,
            classAccess = mapOf(
                HardwareClass.USB to HardwareAccess.BLOCKED,
                HardwareClass.BLUETOOTH to HardwareAccess.BLOCKED
            )
        )
        for (cls in HardwareClass.values()) {
            for (action in HardwareAction.values()) {
                val decision = broker.decide(policy, cls, action)
                assertFalse(
                    "BLOCKED must deny $action on $cls",
                    decision.permits
                )
                assertTrue(
                    "BLOCKED denial must be a Deny, not a confirmation",
                    decision is HardwareDecision.Deny
                )
            }
        }
    }

    @Test
    fun `SILENT allows the call (the enforcer returns nothing)`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.SILENT)
        for (action in HardwareAction.values()) {
            val decision = broker.decide(policy, HardwareClass.CAMERA, action)
            assertTrue("SILENT must allow $action", decision.permits)
            assertTrue(
                "SILENT must be a plain Allow, not a confirmation",
                decision is HardwareDecision.Allow
            )
        }
    }

    @Test
    fun `READ_ONLY allows LIST and READ but denies WRITE and CONNECT`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_ONLY)
        for (cls in listOf(HardwareClass.USB, HardwareClass.BLUETOOTH, HardwareClass.NFC)) {
            assertTrue(
                "READ_ONLY must allow LIST on $cls",
                broker.decide(policy, cls, HardwareAction.LIST).permits
            )
            assertTrue(
                "READ_ONLY must allow READ on $cls",
                broker.decide(policy, cls, HardwareAction.READ).permits
            )
            assertFalse(
                "READ_ONLY must deny WRITE on $cls",
                broker.decide(policy, cls, HardwareAction.WRITE).permits
            )
            assertFalse(
                "READ_ONLY must deny CONNECT on $cls",
                broker.decide(policy, cls, HardwareAction.CONNECT).permits
            )
        }
    }

    @Test
    fun `READ_WRITE allows every action subject to wildcard rules`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        // Specific-target requests pass.
        for (cls in HardwareClass.values()) {
            for (action in HardwareAction.values()) {
                val decision = broker.decide(
                    policy,
                    cls,
                    action,
                    targetId = HardwareTargetId.Specific("dev-1")
                )
                assertTrue(
                    "READ_WRITE with specific target must allow $action on $cls",
                    decision.permits
                )
            }
        }
    }

    @Test
    fun `CONFIRM requires consent for every action`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.CONFIRM)
        for (cls in HardwareClass.values()) {
            for (action in HardwareAction.values()) {
                val decision = broker.decide(policy, cls, action)
                assertTrue(
                    "CONFIRM must permit $action on $cls (with a prompt)",
                    decision.permits
                )
                assertTrue(
                    "CONFIRM decision must be AllowWithConfirmation, got $decision",
                    decision is HardwareDecision.AllowWithConfirmation
                )
            }
        }
    }

    // --- per-class override beats default ---

    @Test
    fun `per-class access overrides the default`() {
        val policy = HardwarePolicy(
            defaultMode = HardwareAccess.READ_WRITE,
            classAccess = mapOf(HardwareClass.CAMERA to HardwareAccess.BLOCKED)
        )
        // Camera is BLOCKED.
        assertFalse(
            "CAMERA override must win over the default",
            broker.decide(policy, HardwareClass.CAMERA, HardwareAction.READ).permits
        )
        // Other classes still get the default.
        assertTrue(
            "USB must fall through to the default",
            broker.decide(
                policy,
                HardwareClass.USB,
                HardwareAction.READ,
                targetId = HardwareTargetId.Specific("dev-1")
            ).permits
        )
    }

    // --- wildcard confirmation rules ---

    @Test
    fun `USB LIST with a wildcard target requires confirmation under READ_WRITE`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val decision = broker.decide(
            policy,
            HardwareClass.USB,
            HardwareAction.LIST,
            targetId = HardwareTargetId.WildcardUsb
        )
        assertTrue(decision.permits)
        assertTrue(decision is HardwareDecision.AllowWithConfirmation)
        val reason = (decision as HardwareDecision.AllowWithConfirmation).reason
        assertTrue(
            "USB LIST wildcard reason must mention 'no specific VID/PID'",
            reason.contains("VID/PID")
        )
    }

    @Test
    fun `USB CONNECT with a wildcard target requires confirmation under READ_WRITE`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val decision = broker.decide(
            policy,
            HardwareClass.USB,
            HardwareAction.CONNECT,
            targetId = HardwareTargetId.WildcardUsb
        )
        assertTrue(decision is HardwareDecision.AllowWithConfirmation)
    }

    @Test
    fun `BLUETOOTH LIST with no address filter requires confirmation under READ_WRITE`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val decision = broker.decide(
            policy,
            HardwareClass.BLUETOOTH,
            HardwareAction.LIST,
            targetId = HardwareTargetId.Any
        )
        assertTrue(decision is HardwareDecision.AllowWithConfirmation)
        val reason = (decision as HardwareDecision.AllowWithConfirmation).reason
        assertTrue(
            "BLUETOOTH LIST no-filter reason must mention the address filter",
            reason.contains("address")
        )
    }

    @Test
    fun `BLUETOOTH LIST with a specific address does NOT require confirmation`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val decision = broker.decide(
            policy,
            HardwareClass.BLUETOOTH,
            HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("11:22:33:44:55:66")
        )
        assertTrue(
            "BLUETOOTH LIST with a specific address must be a plain Allow",
            decision is HardwareDecision.Allow
        )
    }

    @Test
    fun `LOCATION READ with no accuracy hint requires confirmation under READ_WRITE`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val decision = broker.decide(
            policy,
            HardwareClass.LOCATION,
            HardwareAction.READ,
            targetId = HardwareTargetId.Any
        )
        assertTrue(decision is HardwareDecision.AllowWithConfirmation)
    }

    @Test
    fun `LOCATION READ with a specific accuracy hint does NOT require confirmation`() {
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val decision = broker.decide(
            policy,
            HardwareClass.LOCATION,
            HardwareAction.READ,
            targetId = HardwareTargetId.Specific("fine")
        )
        assertTrue(
            "LOCATION READ with a specific accuracy hint must be a plain Allow",
            decision is HardwareDecision.Allow
        )
    }

    // --- audit log ---

    @Test
    fun `broker records allow and allow-with-confirmation in the audit log`() {
        val audit = HardwareAuditLog()
        val policy = HardwarePolicy(
            defaultMode = HardwareAccess.READ_WRITE,
            classAccess = mapOf(
                HardwareClass.BLUETOOTH to HardwareAccess.CONFIRM
            )
        )
        // A plain allow (USB, specific target).
        broker.decide(
            policy,
            HardwareClass.USB,
            HardwareAction.READ,
            targetId = HardwareTargetId.Specific("1234:5678"),
            audit = audit
        )
        // A confirmation (BLUETOOTH under CONFIRM).
        broker.decide(
            policy,
            HardwareClass.BLUETOOTH,
            HardwareAction.LIST,
            audit = audit
        )
        // A denial (default is READ_WRITE, but the default is
        // overridden to BLOCKED on a third class).
        val strictPolicy = policy.copy(
            classAccess = policy.classAccess + (HardwareClass.NFC to HardwareAccess.BLOCKED)
        )
        broker.decide(strictPolicy, HardwareClass.NFC, HardwareAction.READ, audit = audit)
        // Audit log should have 3 entries: USB allow, BLUETOOTH
        // confirmation, NFC deny. The deny is *not* recorded
        // by the broker (per the deny-path note in the source).
        val events = audit.snapshot()
        assertEquals("audit must record 2 events (allow + confirmation)", 2, events.size)
        val first = events[0]
        assertEquals(HardwareClass.USB, first.hardwareClass)
        assertEquals(HardwareAction.READ, first.action)
        assertTrue(first.decision is HardwareDecision.Allow)
        val second = events[1]
        assertEquals(HardwareClass.BLUETOOTH, second.hardwareClass)
        assertTrue(second.decision is HardwareDecision.AllowWithConfirmation)
    }

    @Test
    fun `audit log is thread-safe under concurrent record`() {
        val audit = HardwareAuditLog()
        val policy = HardwarePolicy(defaultMode = HardwareAccess.READ_WRITE)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { threadIdx ->
            Thread {
                start.await()
                repeat(50) {
                    broker.decide(
                        policy,
                        HardwareClass.USB,
                        HardwareAction.READ,
                        targetId = HardwareTargetId.Specific("dev-$threadIdx.$it"),
                        audit = audit
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals(8 * 50, audit.size())
    }

    // --- value type invariants ---

    @Test
    fun `HardwarePolicy accessFor returns the override or the default`() {
        val policy = HardwarePolicy(
            defaultMode = HardwareAccess.BLOCKED,
            classAccess = mapOf(HardwareClass.CAMERA to HardwareAccess.READ_ONLY)
        )
        assertEquals(HardwareAccess.READ_ONLY, policy.accessFor(HardwareClass.CAMERA))
        assertEquals(HardwareAccess.BLOCKED, policy.accessFor(HardwareClass.USB))
    }

    @Test
    fun `HardwareTargetId Specific rejects a blank id`() {
        try {
            HardwareTargetId.Specific("")
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { /* */ }
    }

    @Test
    fun `decision permits property distinguishes allowed from denied`() {
        val allow: HardwareDecision = HardwareDecision.Allow
        val confirm: HardwareDecision =
            HardwareDecision.AllowWithConfirmation("test")
        val deny: HardwareDecision = HardwareDecision.Deny("test")
        assertTrue(allow.permits)
        assertTrue(confirm.permits)
        assertFalse(deny.permits)
    }

    @Test
    fun `audit log snapshot returns a defensive copy`() {
        val audit = HardwareAuditLog()
        audit.record(
            AuditEvent(
                policy = HardwarePolicy(),
                hardwareClass = HardwareClass.USB,
                action = HardwareAction.LIST,
                target = HardwareTargetId.Any,
                decision = HardwareDecision.Allow
            )
        )
        val first = audit.snapshot()
        audit.clear()
        val second = audit.snapshot()
        assertEquals("first snapshot must retain the entry", 1, first.size)
        assertEquals("second snapshot after clear must be empty", 0, second.size)
    }
}
