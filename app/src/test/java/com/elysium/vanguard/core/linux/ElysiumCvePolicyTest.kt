package com.elysium.vanguard.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 73 third half (I-73.3.5) — the JVM tests
 * for [ElysiumCvePolicy] + [ElysiumCveSeverity] +
 * [ElysiumCveId] + [ElysiumCveStatus] +
 * [ElysiumCveRecord] + [ElysiumCveAffectedPackage].
 *
 * These tests cover:
 *   - ElysiumCveSeverity: fromCvss mapping,
 *     displayLabel, range.
 *   - ElysiumCveId: pattern validation, blank
 *     rejection.
 *   - ElysiumCvePolicy: construction invariants,
 *     responseSlaFor, disclosureDelayFor,
 *     meetsResponseSla, DEFAULT constant.
 *   - ElysiumCveRecord: invariants (status vs
 *     disclosedAtMs vs patchedAtMs), validation.
 *   - ElysiumCveAffectedPackage: invariants.
 */
class ElysiumCvePolicyTest {

    // ============================================================
    // ElysiumCveSeverity
    // ============================================================

    @Test
    fun `fromCvss maps 9-0-10-0 to CRITICAL`() {
        for (score in listOf(9.0, 9.5, 10.0)) {
            assertEquals(
                "expected CRITICAL for $score",
                ElysiumCveSeverity.CRITICAL,
                ElysiumCveSeverity.fromCvss(score),
            )
        }
    }

    @Test
    fun `fromCvss maps 7-0-8-99 to HIGH`() {
        for (score in listOf(7.0, 7.5, 8.99)) {
            assertEquals(
                "expected HIGH for $score",
                ElysiumCveSeverity.HIGH,
                ElysiumCveSeverity.fromCvss(score),
            )
        }
    }

    @Test
    fun `fromCvss maps 4-0-6-99 to MEDIUM`() {
        for (score in listOf(4.0, 5.0, 6.99)) {
            assertEquals(
                "expected MEDIUM for $score",
                ElysiumCveSeverity.MEDIUM,
                ElysiumCveSeverity.fromCvss(score),
            )
        }
    }

    @Test
    fun `fromCvss maps 0-1-3-99 to LOW`() {
        for (score in listOf(0.1, 1.0, 3.99)) {
            assertEquals(
                "expected LOW for $score",
                ElysiumCveSeverity.LOW,
                ElysiumCveSeverity.fromCvss(score),
            )
        }
    }

    @Test
    fun `fromCvss maps 0-0 to NONE`() {
        assertEquals(
            ElysiumCveSeverity.NONE,
            ElysiumCveSeverity.fromCvss(0.0),
        )
    }

    @Test
    fun `fromCvss rejects negative scores`() {
        try {
            ElysiumCveSeverity.fromCvss(-0.1)
            fail("expected IllegalArgumentException for negative score")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(">= 0"))
        }
    }

    @Test
    fun `fromCvss rejects scores above 10`() {
        try {
            ElysiumCveSeverity.fromCvss(10.1)
            fail("expected IllegalArgumentException for score > 10")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("<= 10"))
        }
    }

    @Test
    fun `every severity has a non-blank displayLabel`() {
        for (severity in ElysiumCveSeverity.values()) {
            assertTrue(
                "expected non-blank displayLabel for $severity",
                severity.displayLabel.isNotBlank(),
            )
        }
    }

    // ============================================================
    // ElysiumCveId
    // ============================================================

    @Test
    fun `cveId accepts the canonical format`() {
        val id = ElysiumCveId("CVE-2024-1234")
        assertEquals("CVE-2024-1234", id.value)
    }

    @Test
    fun `cveId accepts a 5-digit serial`() {
        val id = ElysiumCveId("CVE-2024-12345")
        assertEquals("CVE-2024-12345", id.value)
    }

    @Test
    fun `cveId rejects a 3-digit serial`() {
        try {
            ElysiumCveId("CVE-2024-123")
            fail("expected IllegalArgumentException for 3-digit serial")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'CVE-', got: ${e.message}",
                e.message!!.contains("CVE-"),
            )
        }
    }

    @Test
    fun `cveId rejects lowercase`() {
        try {
            ElysiumCveId("cve-2024-1234")
            fail("expected IllegalArgumentException for lowercase")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CVE-"))
        }
    }

    @Test
    fun `cveId rejects blank value`() {
        try {
            ElysiumCveId("")
            fail("expected IllegalArgumentException for blank value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    // ============================================================
    // ElysiumCvePolicy
    // ============================================================

    @Test
    fun `policy rejects empty severityResponseHours`() {
        try {
            ElysiumCvePolicy(
                severityResponseHours = emptyMap(),
                severityDisclosureDelayHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 0,
                ),
            )
            fail("expected IllegalArgumentException for empty responseHours")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("severityResponseHours"))
        }
    }

    @Test
    fun `policy rejects empty severityDisclosureDelayHours`() {
        try {
            ElysiumCvePolicy(
                severityResponseHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 24,
                ),
                severityDisclosureDelayHours = emptyMap(),
            )
            fail("expected IllegalArgumentException for empty disclosureHours")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("severityDisclosureDelayHours"))
        }
    }

    @Test
    fun `policy requires every severity in both maps`() {
        try {
            ElysiumCvePolicy(
                severityResponseHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 24,
                    // HIGH / MEDIUM / LOW / NONE are missing
                ),
                severityDisclosureDelayHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 0,
                    ElysiumCveSeverity.HIGH to 24,
                    ElysiumCveSeverity.MEDIUM to 24 * 7,
                    ElysiumCveSeverity.LOW to 24 * 30,
                    ElysiumCveSeverity.NONE to 24 * 365,
                ),
            )
            fail("expected IllegalArgumentException for missing severity")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'missing', got: ${e.message}",
                e.message!!.contains("missing"),
            )
        }
    }

    @Test
    fun `policy rejects non-positive response hours`() {
        try {
            ElysiumCvePolicy(
                severityResponseHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 0,
                    ElysiumCveSeverity.HIGH to 24 * 7,
                    ElysiumCveSeverity.MEDIUM to 24 * 30,
                    ElysiumCveSeverity.LOW to 24 * 90,
                    ElysiumCveSeverity.NONE to 24 * 365,
                ),
                severityDisclosureDelayHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 0,
                    ElysiumCveSeverity.HIGH to 24,
                    ElysiumCveSeverity.MEDIUM to 24 * 7,
                    ElysiumCveSeverity.LOW to 24 * 30,
                    ElysiumCveSeverity.NONE to 24 * 365,
                ),
            )
            fail("expected IllegalArgumentException for zero response hours")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("> 0"))
        }
    }

    @Test
    fun `policy rejects negative response hours`() {
        try {
            ElysiumCvePolicy(
                severityResponseHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to -1,
                    ElysiumCveSeverity.HIGH to 24 * 7,
                    ElysiumCveSeverity.MEDIUM to 24 * 30,
                    ElysiumCveSeverity.LOW to 24 * 90,
                    ElysiumCveSeverity.NONE to 24 * 365,
                ),
                severityDisclosureDelayHours = mapOf(
                    ElysiumCveSeverity.CRITICAL to 0,
                    ElysiumCveSeverity.HIGH to 24,
                    ElysiumCveSeverity.MEDIUM to 24 * 7,
                    ElysiumCveSeverity.LOW to 24 * 30,
                    ElysiumCveSeverity.NONE to 24 * 365,
                ),
            )
            fail("expected IllegalArgumentException for negative response hours")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("> 0"))
        }
    }

    @Test
    fun `policy allows zero disclosure delay hours`() {
        // A 0-hour delay is valid for CRITICAL
        // (no embargo; immediate disclosure).
        val policy = ElysiumCvePolicy.DEFAULT
        assertEquals(0, policy.disclosureDelayFor(ElysiumCveSeverity.CRITICAL))
    }

    @Test
    fun `responseSlaFor returns the hours for a severity`() {
        val policy = ElysiumCvePolicy.DEFAULT
        assertEquals(24, policy.responseSlaFor(ElysiumCveSeverity.CRITICAL))
        assertEquals(24 * 7, policy.responseSlaFor(ElysiumCveSeverity.HIGH))
        assertEquals(24 * 30, policy.responseSlaFor(ElysiumCveSeverity.MEDIUM))
        assertEquals(24 * 90, policy.responseSlaFor(ElysiumCveSeverity.LOW))
    }

    @Test
    fun `disclosureDelayFor returns the hours for a severity`() {
        val policy = ElysiumCvePolicy.DEFAULT
        assertEquals(0, policy.disclosureDelayFor(ElysiumCveSeverity.CRITICAL))
        assertEquals(24, policy.disclosureDelayFor(ElysiumCveSeverity.HIGH))
        assertEquals(24 * 7, policy.disclosureDelayFor(ElysiumCveSeverity.MEDIUM))
        assertEquals(24 * 30, policy.disclosureDelayFor(ElysiumCveSeverity.LOW))
    }

    @Test
    fun `meetsResponseSla returns true when the patch is within the SLA`() {
        val policy = ElysiumCvePolicy.DEFAULT
        val disclosedAtMs = 1_000_000L
        // 12 hours later (within the 24h CRITICAL SLA)
        val patchedAtMs = disclosedAtMs + 12L * 60L * 60L * 1000L
        assertTrue(
            policy.meetsResponseSla(
                ElysiumCveSeverity.CRITICAL,
                disclosedAtMs,
                patchedAtMs,
            ),
        )
    }

    @Test
    fun `meetsResponseSla returns false when the patch is outside the SLA`() {
        val policy = ElysiumCvePolicy.DEFAULT
        val disclosedAtMs = 1_000_000L
        // 48 hours later (exceeds the 24h CRITICAL SLA)
        val patchedAtMs = disclosedAtMs + 48L * 60L * 60L * 1000L
        assertFalse(
            policy.meetsResponseSla(
                ElysiumCveSeverity.CRITICAL,
                disclosedAtMs,
                patchedAtMs,
            ),
        )
    }

    // ============================================================
    // ElysiumCveRecord
    // ============================================================

    @Test
    fun `record rejects cvssScore out of range`() {
        try {
            buildRecord(cvssScore = 11.0)
            fail("expected IllegalArgumentException for cvssScore > 10")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cvssScore"))
        }
    }

    @Test
    fun `record rejects blank description`() {
        try {
            buildRecord(description = "")
            fail("expected IllegalArgumentException for blank description")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description"))
        }
    }

    @Test
    fun `record rejects empty affected packages`() {
        try {
            buildRecord(affectedPackages = emptyList())
            fail("expected IllegalArgumentException for empty affected packages")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("affectedPackages"))
        }
    }

    @Test
    fun `record UNDISCLOSED rejects disclosedAtMs set`() {
        try {
            buildRecord(
                status = ElysiumCveStatus.UNDISCLOSED,
                disclosedAtMs = 1_000L,
            )
            fail("expected IllegalArgumentException for disclosedAtMs set on UNDISCLOSED")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'UNDISCLOSED', got: ${e.message}",
                e.message!!.contains("UNDISCLOSED"),
            )
        }
    }

    @Test
    fun `record DISCLOSED_PATCHED requires both timestamps`() {
        try {
            buildRecord(
                status = ElysiumCveStatus.DISCLOSED_PATCHED,
                disclosedAtMs = 1_000L,
                patchedAtMs = null,
            )
            fail("expected IllegalArgumentException for missing patchedAtMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'patchedAtMs', got: ${e.message}",
                e.message!!.contains("patchedAtMs"),
            )
        }
    }

    @Test
    fun `record WONT_FIX rejects patchedAtMs set`() {
        try {
            buildRecord(
                status = ElysiumCveStatus.WONT_FIX,
                patchedAtMs = 1_000L,
            )
            fail("expected IllegalArgumentException for patchedAtMs set on WONT_FIX")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'WONT_FIX', got: ${e.message}",
                e.message!!.contains("WONT_FIX"),
            )
        }
    }

    @Test
    fun `record DISCLOSED_PATCHED with both timestamps is accepted`() {
        val record = buildRecord(
            status = ElysiumCveStatus.DISCLOSED_PATCHED,
            disclosedAtMs = 1_000L,
            patchedAtMs = 2_000L,
        )
        assertEquals(ElysiumCveStatus.DISCLOSED_PATCHED, record.status)
        assertEquals(1_000L, record.disclosedAtMs)
        assertEquals(2_000L, record.patchedAtMs)
    }

    // ============================================================
    // ElysiumCveAffectedPackage
    // ============================================================

    @Test
    fun `affectedPackage rejects blank packageName`() {
        try {
            ElysiumCveAffectedPackage(
                packageName = "",
                fixedInVersion = ElysiumPackageVersion(1, 2, 4),
                affectedVersions = VersionConstraint(
                    kind = ConstraintKind.GTE,
                    version = ElysiumPackageVersion(1, 0, 0),
                ),
            )
            fail("expected IllegalArgumentException for blank packageName")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("packageName"))
        }
    }

    @Test
    fun `affectedPackage accepts a well-formed configuration`() {
        val ap = ElysiumCveAffectedPackage(
            packageName = "com.elysium.runtime.openssl",
            fixedInVersion = ElysiumPackageVersion(3, 0, 8),
            affectedVersions = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(3, 0, 0),
            ),
        )
        assertEquals("com.elysium.runtime.openssl", ap.packageName)
        assertEquals(
            ElysiumPackageVersion(3, 0, 8),
            ap.fixedInVersion,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildRecord(
        cveId: ElysiumCveId = ElysiumCveId("CVE-2024-1234"),
        severity: ElysiumCveSeverity = ElysiumCveSeverity.CRITICAL,
        cvssScore: Double = 9.5,
        description: String = "A critical vulnerability in Elysium Linux",
        affectedPackages: List<ElysiumCveAffectedPackage> = listOf(
            ElysiumCveAffectedPackage(
                packageName = "com.elysium.runtime.openssl",
                fixedInVersion = ElysiumPackageVersion(3, 0, 8),
                affectedVersions = VersionConstraint(
                    kind = ConstraintKind.GTE,
                    version = ElysiumPackageVersion(3, 0, 0),
                ),
            ),
        ),
        status: ElysiumCveStatus = ElysiumCveStatus.DISCLOSED_PATCHED,
        disclosedAtMs: Long? = 1_000L,
        patchedAtMs: Long? = 2_000L,
    ): ElysiumCveRecord = ElysiumCveRecord(
        cveId = cveId,
        severity = severity,
        cvssScore = cvssScore,
        description = description,
        affectedPackages = affectedPackages,
        status = status,
        disclosedAtMs = disclosedAtMs,
        patchedAtMs = patchedAtMs,
    )
}
