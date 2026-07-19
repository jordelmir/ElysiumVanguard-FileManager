package com.elysium.vanguard.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 76 — the test suite for the app signature
 * check surface.
 *
 * The check has two layers:
 *
 * 1. [DeviceIntegrityConfig] — a typed config
 *    whose `init` block fails fast when a release
 *    build is published without an expected
 *    publisher signature. This is the fail-secure
 *    surface.
 *
 * 2. [DeviceIntegrityChecker.compareSignatureDigests] —
 *    the pure-comparison function the checker's
 *    Android-side signature path delegates to.
 *    The function is `internal` so the JVM test
 *    suite can drive every (actual, expected,
 *    dev/prod) combination without standing up
 *    the Android `PackageManager`.
 *
 * The tests below cover the full truth table.
 */
class DeviceIntegrityConfigAndCheckerTest {

    // --- DeviceIntegrityConfig ---

    @Test
    fun `config defaults are dev mode`() {
        val config = DeviceIntegrityConfig()
        assertNull(config.expectedPublisherSignatureSha256)
        assertFalse(config.productionBuild)
    }

    @Test
    fun `config getters return the supplied values`() {
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "abc123",
            productionBuild = false,
        )
        assertEquals("abc123", config.expectedPublisherSignatureSha256)
        assertFalse(config.productionBuild)
    }

    @Test
    fun `config init does not fail when productionBuild is false and expected is null`() {
        // The dev default: both permissive. No
        // exception is expected.
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = null,
            productionBuild = false,
        )
        assertNotNull(config)
    }

    @Test
    fun `config init does not fail when productionBuild is false and expected is set`() {
        // A release-candidate that hasn't yet
        // flipped the production flag. The init
        // block does not enforce the expected.
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "abc",
            productionBuild = false,
        )
        assertNotNull(config)
    }

    @Test
    fun `config init does not fail when productionBuild is true and expected is set`() {
        // The correct production config: expected
        // + productionBuild = true.
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "abc",
            productionBuild = true,
        )
        assertNotNull(config)
    }

    @Test
    fun `config init fails fast when productionBuild is true and expected is null`() {
        // The misconfiguration that the init
        // block exists to catch. A release build
        // published with `productionBuild = true`
        // but no expected signature is a security
        // regression: the integrity check would
        // always pass.
        try {
            DeviceIntegrityConfig(
                expectedPublisherSignatureSha256 = null,
                productionBuild = true,
            )
            fail("expected IllegalStateException for productionBuild=true with null expected")
        } catch (e: IllegalStateException) {
            assertTrue(
                "error message must mention the productionBuild requirement: ${e.message}",
                e.message!!.contains("productionBuild=true"),
            )
            assertTrue(
                "error message must mention expectedPublisherSignatureSha256: ${e.message}",
                e.message!!.contains("expectedPublisherSignatureSha256"),
            )
        }
    }

    @Test
    fun `config is a data class with value-based equality`() {
        val a = DeviceIntegrityConfig("abc", false)
        val b = DeviceIntegrityConfig("abc", false)
        val c = DeviceIntegrityConfig("abc", true)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    // --- compareSignatureDigests (dev mode) ---

    @Test
    fun `compare returns invalid when dev mode and actual digest is null`() {
        val (valid, observed) = compareSignatureDigestsInternal(
            actualDigest = null,
            config = DeviceIntegrityConfig(),
        )
        assertFalse("dev mode with no actual signature must be invalid", valid)
        assertNull(observed)
    }

    @Test
    fun `compare returns valid when dev mode and actual digest is present`() {
        val (valid, observed) = compareSignatureDigestsInternal(
            actualDigest = "abcdef",
            config = DeviceIntegrityConfig(),
        )
        assertTrue("dev mode with an actual signature must be valid", valid)
        assertEquals("abcdef", observed)
    }

    // --- compareSignatureDigests (production mode) ---

    @Test
    fun `compare returns invalid when production mode and actual digest is null`() {
        // A tampered app that has no signature at
        // all. The production-mode check must fail
        // even though the expected matches "what
        // the app is supposed to be".
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "expected",
            productionBuild = true,
        )
        val (valid, observed) = compareSignatureDigestsInternal(
            actualDigest = null,
            config = config,
        )
        assertFalse("production mode with no actual signature must be invalid", valid)
        assertNull(observed)
    }

    @Test
    fun `compare returns valid when production mode and actual matches expected`() {
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "abc123",
            productionBuild = true,
        )
        val (valid, observed) = compareSignatureDigestsInternal(
            actualDigest = "abc123",
            config = config,
        )
        assertTrue("matching digests must be valid", valid)
        assertEquals("abc123", observed)
    }

    @Test
    fun `compare returns invalid when production mode and actual differs from expected`() {
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "expected",
            productionBuild = true,
        )
        val (valid, observed) = compareSignatureDigestsInternal(
            actualDigest = "different",
            config = config,
        )
        assertFalse("mismatched digests must be invalid", valid)
        // The observed digest is still recorded for
        // the audit log + the human-readable
        // integrity report.
        assertEquals("different", observed)
    }

    @Test
    fun `compare is case insensitive on the digest comparison`() {
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "abcdef",
            productionBuild = true,
        )
        val (valid, _) = compareSignatureDigestsInternal(
            actualDigest = "ABCDEF",
            config = config,
        )
        assertTrue(
            "digests with different casing must be treated as equal",
            valid,
        )
    }

    @Test
    fun `compare rejects empty actual digest in production mode`() {
        val config = DeviceIntegrityConfig(
            expectedPublisherSignatureSha256 = "expected",
            productionBuild = true,
        )
        val (valid, _) = compareSignatureDigestsInternal(
            actualDigest = "",
            config = config,
        )
        assertFalse("empty actual digest in production mode must be invalid", valid)
    }
}
