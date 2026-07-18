package com.elysium.vanguard.core.runtime.workspaces

import com.elysium.vanguard.core.runtime.bridge.MountEntry
import com.elysium.vanguard.core.runtime.observability.RuntimeEvent
import com.elysium.vanguard.core.runtime.policy.MountAuditEntry
import com.elysium.vanguard.core.runtime.policy.MountAuditLog
import com.elysium.vanguard.core.runtime.policy.MountEnforcementResult
import com.elysium.vanguard.core.runtime.policy.MountPolicy
import com.elysium.vanguard.core.runtime.policy.MountPolicyEnforcer
import com.elysium.vanguard.core.runtime.policy.MountPolicyEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * Phase 50 — tests for [WorkspaceManager]'s
 * mount-policy integration.
 *
 * The manager is the orchestrator that drives
 * the [MountPolicyEnforcer] + the [MountAuditLog]
 * and publishes the appropriate
 * [RuntimeEvent.MountAllowedEvent] /
 * [RuntimeEvent.MountPolicyViolationEvent] on
 * the bus. The tests pin:
 *
 *   - enforceMountPolicy returns
 *     [MountEnforcementResult.Allowed] when the
 *     policy permits every mount.
 *   - enforceMountPolicy returns
 *     [MountEnforcementResult.Denied] when the
 *     policy denies one or more mounts.
 *   - Every decision is appended to the audit
 *     log (one entry per allowed + one per
 *     denied mount).
 *   - Every decision is published on the bus.
 *   - enforceMountPolicy returns
 *     [WorkspaceError.NotFound] for an unknown
 *     workspace id.
 *   - When the manager was constructed without
 *     an enforcer, the method returns Allowed
 *     (permissive backwards-compat).
 */
class WorkspaceManagerMountPolicyTest {

    private val store = InMemoryWorkspaceStore()
    private val eventBus = com.elysium.vanguard.core.runtime.observability.RecordingEventBus()
    private val fakeAuditLog = FakeMountAuditLog()
    private val manager = WorkspaceManager(
        store = store,
        eventBus = eventBus,
        mountPolicyEnforcer = MountPolicyEnforcer(),
        mountAuditLog = fakeAuditLog
    )

    @Test
    fun `enforceMountPolicy returns Allowed when the policy permits every mount`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(
                    hostPathPrefix = "/sdcard/photos",
                    readOnly = true
                )
            )
        )
        val result = manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(MountEntry("/sdcard/photos/jan.jpg", "/mnt/photos", readOnly = true))
        )
        assertTrue(result.isSuccess)
        val enforcement = result.getOrThrow()
        assertTrue(enforcement is MountEnforcementResult.Allowed)
        val allowed = (enforcement as MountEnforcementResult.Allowed).filteredMounts
        assertEquals(1, allowed.size)
    }

    @Test
    fun `enforceMountPolicy returns Denied when the policy denies a mount`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos")
            )
        )
        val result = manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(MountEntry("/sdcard/private/keys.txt", "/mnt/private"))
        )
        assertTrue(result.isSuccess)
        val enforcement = result.getOrThrow()
        assertTrue(enforcement is MountEnforcementResult.Denied)
        val denied = enforcement as MountEnforcementResult.Denied
        assertEquals(1, denied.violations.size)
        assertEquals("/sdcard/private/keys.txt", denied.violations[0].hostPath)
    }

    @Test
    fun `enforceMountPolicy appends one audit entry per allowed mount`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos"),
                MountPolicyEntry(hostPathPrefix = "/sdcard/videos")
            )
        )
        manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(
                MountEntry("/sdcard/photos/a.jpg", "/mnt/a"),
                MountEntry("/sdcard/videos/b.mp4", "/mnt/b")
            )
        )
        val audit = fakeAuditLog.entries
        assertEquals(2, audit.size)
        assertEquals(MountAuditEntry.DECISION_ALLOWED, audit[0].decision)
        assertEquals(MountAuditEntry.DECISION_ALLOWED, audit[1].decision)
    }

    @Test
    fun `enforceMountPolicy appends a Denied entry for each violation`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos")
            )
        )
        manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(
                MountEntry("/sdcard/photos/a.jpg", "/mnt/a"),
                MountEntry("/sdcard/private/keys.txt", "/mnt/private"),
                MountEntry("/sdcard/private/wallet.dat", "/mnt/wallet")
            )
        )
        val audit = fakeAuditLog.entries
        // 1 allowed + 2 denied.
        assertEquals(3, audit.size)
        val allowedCount = audit.count { it.decision == MountAuditEntry.DECISION_ALLOWED }
        val deniedCount = audit.count { it.decision == MountAuditEntry.DECISION_DENIED }
        assertEquals(1, allowedCount)
        assertEquals(2, deniedCount)
    }

    @Test
    fun `enforceMountPolicy publishes a MountAllowedEvent for each allowed mount`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos"),
                MountPolicyEntry(hostPathPrefix = "/sdcard/videos")
            )
        )
        manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(
                MountEntry("/sdcard/photos/a.jpg", "/mnt/a"),
                MountEntry("/sdcard/videos/b.mp4", "/mnt/b")
            )
        )
        val events = eventBus.events.filterIsInstance<RuntimeEvent.MountAllowedEvent>()
        assertEquals(2, events.size)
        assertEquals(ws.id, events[0].workspaceId)
        assertEquals("s-1", events[0].sessionId)
    }

    @Test
    fun `enforceMountPolicy publishes a MountPolicyViolationEvent for each denied mount`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(hostPathPrefix = "/sdcard/photos")
            )
        )
        manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(
                MountEntry("/sdcard/private/keys.txt", "/mnt/private")
            )
        )
        val events = eventBus.events.filterIsInstance<RuntimeEvent.MountPolicyViolationEvent>()
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(ws.id, event.workspaceId)
        assertEquals("s-1", event.sessionId)
        assertEquals("/sdcard/private/keys.txt", event.hostPath)
        assertTrue(
            "reason should mention the policy",
            event.reason.contains("allowlist", ignoreCase = true)
        )
    }

    @Test
    fun `enforceMountPolicy returns NotFound for an unknown workspace id`() {
        val result = manager.enforceMountPolicy(
            workspaceId = "ws-does-not-exist",
            sessionId = "s-1",
            policy = MountPolicy.LOCKED_DOWN,
            mounts = listOf(MountEntry("/sdcard/photos", "/mnt/photos"))
        )
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is WorkspaceError.NotFound)
    }

    @Test
    fun `enforceMountPolicy is permissive when no enforcer is configured`() {
        val bareManager = WorkspaceManager(store, eventBus)
        val ws = bareManager.createWorkspace("Work").getOrThrow()
        val result = bareManager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = MountPolicy.LOCKED_DOWN,
            mounts = listOf(MountEntry("/sdcard/private/keys.txt", "/mnt/private"))
        )
        assertTrue(result.isSuccess)
        val enforcement = result.getOrThrow()
        // Permissive: every mount passes, even
        // though the policy would deny it.
        assertTrue(enforcement is MountEnforcementResult.Allowed)
        val allowed = (enforcement as MountEnforcementResult.Allowed).filteredMounts
        assertEquals(1, allowed.size)
        // And the audit log was NOT written —
        // there is no enforcer, so the manager
        // is in permissive backwards-compat mode.
        assertTrue(fakeAuditLog.entries.isEmpty())
    }

    @Test
    fun `enforceMountPolicy tightens a writeable mount to read-only and records the decision`() {
        val ws = manager.createWorkspace("Work").getOrThrow()
        val policy = MountPolicy(
            entries = listOf(
                MountPolicyEntry(
                    hostPathPrefix = "/sdcard/photos",
                    readOnly = true
                )
            )
        )
        manager.enforceMountPolicy(
            workspaceId = ws.id,
            sessionId = "s-1",
            policy = policy,
            mounts = listOf(
                MountEntry(
                    "/sdcard/photos/jan.jpg",
                    "/mnt/photos",
                    readOnly = false
                )
            )
        )
        val event = eventBus.events
            .filterIsInstance<RuntimeEvent.MountAllowedEvent>()
            .single()
        assertTrue(
            "the event should record the tightened read-only flag",
            event.readOnly
        )
    }
}

/**
 * Hand-rolled [MountAuditLog] for unit tests.
 * Records every appended entry in a thread-safe
 * list.
 */
internal class FakeMountAuditLog : MountAuditLog {
    private val recorded = Collections.synchronizedList(mutableListOf<MountAuditEntry>())
    val entries: List<MountAuditEntry>
        get() = synchronized(recorded) { recorded.toList() }
    var clearedCount: Int = 0
        private set

    override fun append(entry: MountAuditEntry) {
        recorded += entry
    }

    override fun readAll(): List<MountAuditEntry> = entries

    override fun clear() {
        synchronized(recorded) { recorded.clear() }
        clearedCount++
    }

    override fun size(): Long = entries.size.toLong()

    @Suppress("unused")
    fun assertNotEmpty(message: String = "expected non-empty audit log") {
        assertFalse(message, entries.isEmpty())
    }
}
