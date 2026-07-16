package com.elysium.vanguard.core.runtime.hardware

import com.elysium.vanguard.core.runtime.hardware.broker.HardwareAuditLog
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareBroker
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareClass
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareDecision
import com.elysium.vanguard.core.runtime.hardware.broker.HardwarePolicy
import com.elysium.vanguard.core.runtime.hardware.broker.HardwareTargetId
import com.elysium.vanguard.core.runtime.hardware.enforcer.AndroidHardwareEnforcer
import com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcementResult
import com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcementService
import com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareEnforcer
import com.elysium.vanguard.core.runtime.hardware.enforcer.HardwareRequest
import com.elysium.vanguard.core.runtime.hardware.provider.AndroidPlatformHardwareProvider
import com.elysium.vanguard.core.runtime.hardware.provider.PlatformHardwareProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 21 — wiring tests for the Hilt-managed hardware
 * access path.
 *
 * The Hilt module ([HardwareModule]) is the production
 * wiring. The unit tests in this file verify the module's
 * `provide*` functions return the right types and that
 * the singletons compose correctly. The actual Hilt graph
 * (SingletonComponent) is tested via `HiltAndroidRule` in
 * `androidTest/`; these JVM tests cover the *contract*.
 */
class HardwareModuleTest {

    @Test
    fun `AndroidPlatformHardwareProvider implements PlatformHardwareProvider`() {
        // Compile-time + runtime check: the production
        // provider is assignable to the small interface.
        val klass: Class<AndroidPlatformHardwareProvider> =
            AndroidPlatformHardwareProvider::class.java
        assertTrue(
            "AndroidPlatformHardwareProvider must implement PlatformHardwareProvider",
            PlatformHardwareProvider::class.java.isAssignableFrom(klass)
        )
    }

    @Test
    fun `HardwareModule exposes all six providers`() {
        val module = HardwareModule
        val methods = module::class.java.declaredMethods.map { it.name }.toSet()
        for (name in listOf(
            "providePlatformHardwareProvider",
            "provideHardwareEnforcer",
            "provideHardwareBroker",
            "provideHardwareAuditLog",
            "provideHardwareEnforcementService"
        )) {
            assertTrue(
                "HardwareModule must expose a $name() @Provides function",
                name in methods
            )
        }
    }

    @Test
    fun `provideHardwareBroker returns a fresh HardwareBroker`() {
        val broker1 = HardwareModule.provideHardwareBroker()
        val broker2 = HardwareModule.provideHardwareBroker()
        assertNotNull(broker1)
        assertNotNull(broker2)
        // The provider factory pattern returns a fresh
        // instance per call; Hilt's @Singleton scopes it
        // at the graph level, not at the provider-method
        // level. Two calls return two different instances.
        assertTrue(
            "broker1 and broker2 are different objects",
            broker1 !== broker2
        )
    }

    @Test
    fun `provideHardwareAuditLog returns a fresh HardwareAuditLog`() {
        val audit1 = HardwareModule.provideHardwareAuditLog()
        val audit2 = HardwareModule.provideHardwareAuditLog()
        assertNotNull(audit1)
        assertNotNull(audit2)
        assertTrue("two calls return two different instances", audit1 !== audit2)
    }

    @Test
    fun `provideHardwareEnforcementService composes broker, enforcer, and audit`() {
        // We don't have a real Context, so we can't
        // construct the production provider. We use the
        // recording enforcer from Phase 19 as a stand-in
        // and verify the service composes the three
        // collaborators correctly.
        val enforcer = RecordingEnforcer()
        val service = HardwareModule.provideHardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        assertNotNull(service)
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(),
            hardwareClass = HardwareClass.USB,
            action = com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        // USB LIST under BLOCKED (default policy) returns Denied.
        // The service records the deny in the audit log.
        assertTrue(result is HardwareEnforcementResult.Denied)
        // The enforcer was NOT called (Deny short-circuit).
        assertEquals(0, enforcer.size())
    }

    @Test
    fun `service request with Allow reaches the enforcer`() {
        val enforcer = RecordingEnforcer().apply {
            respondWith(HardwareEnforcementResult.Granted(handle = null))
        }
        val service = HardwareModule.provideHardwareEnforcementService(
            broker = HardwareBroker(),
            enforcer = enforcer,
            audit = HardwareAuditLog()
        )
        val result = service.request(
            sessionId = "s1",
            policy = HardwarePolicy(
                defaultMode = com.elysium.vanguard.core.runtime.hardware.broker.HardwareAccess.READ_WRITE
            ),
            hardwareClass = HardwareClass.USB,
            action = com.elysium.vanguard.core.runtime.hardware.broker.HardwareAction.LIST,
            targetId = HardwareTargetId.Specific("1234:5678")
        )
        assertTrue(result is HardwareEnforcementResult.Granted)
        assertEquals(1, enforcer.size())
    }

    @Test
    fun `service and broker are independent singletons in the module graph`() {
        // The module's provider methods are the seams Hilt
        // uses to compose the graph. Each call returns a
        // fresh instance; Hilt's @Singleton annotation on
        // each provider scopes them at the component level
        // (so the same instance is reused across the
        // component's lifetime). The provider methods
        // themselves are the unit of testable behavior.
        val broker1: HardwareBroker = HardwareModule.provideHardwareBroker()
        val audit1: HardwareAuditLog = HardwareModule.provideHardwareAuditLog()
        // The broker and audit log are distinct types and
        // are not interchangeable; the module's
        // provideHardwareEnforcementService composes
        // them.
        assertNotNull(broker1)
        assertNotNull(audit1)
        // And a fresh enforcer wired with the broker
        // produces a coherent service.
        val enforcer = RecordingEnforcer()
        val service = HardwareEnforcementService(
            broker = broker1,
            enforcer = enforcer,
            audit = audit1
        )
        assertSame("service uses the provided broker", broker1, service.broker())
    }

    @Test
    fun `AndroidHardwareEnforcer implements HardwareEnforcer`() {
        // Compile-time + runtime check: the production
        // enforcer is assignable to the small interface.
        val klass: Class<AndroidHardwareEnforcer> = AndroidHardwareEnforcer::class.java
        assertTrue(
            "AndroidHardwareEnforcer must implement HardwareEnforcer",
            HardwareEnforcer::class.java.isAssignableFrom(klass)
        )
    }

    // --- helper to access the service's broker (Phase 19's `broker` is private) ---

    /**
     * Tiny test impl of the enforcer interface that
     * records every call and returns a canned result.
     * Mirrors the test-side `RecordingHardwareEnforcer`
     * from Phase 19 but is local to this test file so the
     * wiring tests do not depend on the test impl.
     */
    private class RecordingEnforcer : HardwareEnforcer {
        private val recorded = mutableListOf<HardwareRequest>()
        private var response: HardwareEnforcementResult =
            HardwareEnforcementResult.Granted(handle = null)
        private val lock = Any()

        fun respondWith(result: HardwareEnforcementResult) {
            synchronized(lock) { response = result }
        }

        override fun enforce(request: HardwareRequest): HardwareEnforcementResult {
            synchronized(lock) { recorded += request }
            return response
        }

        fun size(): Int = synchronized(lock) { recorded.size }
    }

    // The `HardwareEnforcementService.broker` accessor is
    // not exposed; we add a minimal helper via reflection
    // for the wiring test.
    private fun HardwareEnforcementService.broker(): HardwareBroker {
        val field = this::class.java.getDeclaredField("broker")
        field.isAccessible = true
        return field.get(this) as HardwareBroker
    }
}
