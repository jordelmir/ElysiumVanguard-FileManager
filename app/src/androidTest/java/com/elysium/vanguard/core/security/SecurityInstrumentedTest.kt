package com.elysium.vanguard.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 64 — Instrumented test for the security
 * stack on a real Android device.
 *
 * The test verifies the `DeviceIntegrityChecker`
 * on the actual device. The check is real:
 *   - The rooted check looks for `su` binary
 *     in the device's filesystem.
 *   - The debugger check looks at the
 *     `ApplicationInfo.flags` + the
 *     `Debug.isDebuggerConnected()`.
 *   - The signature check reads the APK's
 *     signature from `PackageManager`.
 *
 * The test asserts the *minimum* invariants:
 *   - The `DeviceIntegrity.appPackageName`
 *     matches the app's package name.
 *   - The signature digest is non-null.
 *   - The `isTrusted` value is consistent
 *     with the `failures` list.
 */
@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `device_integrity_check_runs_and_reports_app_package_name`() {
        val checker = DeviceIntegrityChecker(context)
        val integrity = checker.check()
        assertEquals(context.packageName, integrity.appPackageName)
    }

    @Test
    fun `device_integrity_check_returns_a_signature_digest`() {
        val checker = DeviceIntegrityChecker(context)
        val integrity = checker.check()
        // The signature digest should be non-null on
        // any properly-signed app.
        assertTrue(
            "expected a signature digest, got ${integrity.appSignatureDigest}",
            integrity.appSignatureDigest != null,
        )
    }

    @Test
    fun `device_integrity_isTrusted_is_consistent_with_failures_list`() {
        val checker = DeviceIntegrityChecker(context)
        val integrity = checker.check()
        if (integrity.isTrusted) {
            assertTrue(
                "trusted integrity must have no failures, got ${integrity.failures}",
                integrity.failures.isEmpty(),
            )
        } else {
            assertFalse(
                "untrusted integrity must have at least one failure",
                integrity.failures.isEmpty(),
            )
        }
    }

    @Test
    fun `device_integrity_failures_list_contains_right_enum_values`() {
        val checker = DeviceIntegrityChecker(context)
        val integrity = checker.check()
        // The failures list must only contain valid
        // IntegrityFailure values.
        for (failure in integrity.failures) {
            assertTrue(
                "unexpected failure type: $failure",
                failure == IntegrityFailure.ROOTED ||
                    failure == IntegrityFailure.DEBUGGER_ATTACHED ||
                    failure == IntegrityFailure.SIGNATURE_INVALID,
            )
        }
    }
}
