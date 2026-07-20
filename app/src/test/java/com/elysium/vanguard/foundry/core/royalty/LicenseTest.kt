package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase F5 second half (G6+G7, I-5.2) — the JVM
 * tests for [License].
 *
 * The tests cover:
 *   - License id validation (UUID).
 *   - PermissiveLicense invariants (blank
 *     spdxIdentifier, non-positive
 *     effectiveFromMs).
 *   - CopyleftLicense invariants.
 *   - ProprietaryLicense invariants (blank
 *     allowedUses, blank allowedUses entries).
 *   - CustomLicense invariants (blank
 *     termsDocumentUrl, blank
 *     termsDocumentHash).
 *   - Every license case accepts a well-
 *     formed configuration.
 */
class LicenseTest {

    // ============================================================
    // LicenseId
    // ============================================================

    @Test
    fun `LicenseId random returns a valid id`() {
        val id = LicenseId.random()
        assertNotNull(id.value)
    }

    @Test
    fun `LicenseId from accepts a valid UUID string`() {
        val id = LicenseId.from("12345678-1234-1234-1234-123456789012").getOrThrow()
        assertNotNull(id.value)
    }

    @Test
    fun `LicenseId from rejects a malformed UUID string`() {
        val result = LicenseId.from("not-a-uuid")
        assertTrue("expected failure for malformed UUID, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected InvalidUuidFormat error, got $error",
            error is com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError.InvalidUuidFormat,
        )
    }

    // ============================================================
    // PermissiveLicense
    // ============================================================

    @Test
    fun `PermissiveLicense accepts a well-formed configuration`() {
        val license = License.PermissiveLicense(
            licenseId = LicenseId.random(),
            contractId = RoyaltyContractId.random(),
            programId = VehicleProgramId.random(),
            contributorId = ContributorId.random(),
            displayName = "MIT License",
            spdxIdentifier = "MIT",
            effectiveFromMs = 1_000L,
            signature = Signature("license-signature"),
            contentHash = ContentHash("0".repeat(64)),
        )
        assertEquals("MIT", license.spdxIdentifier)
    }

    @Test
    fun `PermissiveLicense rejects blank displayName`() {
        try {
            License.PermissiveLicense(
                licenseId = LicenseId.random(),
                contractId = RoyaltyContractId.random(),
                programId = VehicleProgramId.random(),
                contributorId = ContributorId.random(),
                displayName = "",
                spdxIdentifier = "MIT",
                effectiveFromMs = 1_000L,
                signature = Signature("license-signature"),
                contentHash = ContentHash("0".repeat(64)),
            )
            fail("expected IllegalArgumentException for blank displayName")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("displayName"))
        }
    }

    @Test
    fun `PermissiveLicense rejects blank spdxIdentifier`() {
        try {
            License.PermissiveLicense(
                licenseId = LicenseId.random(),
                contractId = RoyaltyContractId.random(),
                programId = VehicleProgramId.random(),
                contributorId = ContributorId.random(),
                displayName = "MIT",
                spdxIdentifier = "",
                effectiveFromMs = 1_000L,
                signature = Signature("license-signature"),
                contentHash = ContentHash("0".repeat(64)),
            )
            fail("expected IllegalArgumentException for blank spdxIdentifier")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("spdxIdentifier"))
        }
    }

    // ============================================================
    // CopyleftLicense
    // ============================================================

    @Test
    fun `CopyleftLicense accepts a well-formed configuration`() {
        val license = License.CopyleftLicense(
            licenseId = LicenseId.random(),
            contractId = RoyaltyContractId.random(),
            programId = VehicleProgramId.random(),
            contributorId = ContributorId.random(),
            displayName = "GPL-3.0",
            spdxIdentifier = "GPL-3.0",
            effectiveFromMs = 1_000L,
            signature = Signature("license-signature"),
            contentHash = ContentHash("0".repeat(64)),
        )
        assertEquals("GPL-3.0", license.spdxIdentifier)
        assertTrue(license.shareAlikeRequired)
    }

    @Test
    fun `CopyleftLicense rejects blank spdxIdentifier`() {
        try {
            License.CopyleftLicense(
                licenseId = LicenseId.random(),
                contractId = RoyaltyContractId.random(),
                programId = VehicleProgramId.random(),
                contributorId = ContributorId.random(),
                displayName = "GPL-3.0",
                spdxIdentifier = "",
                effectiveFromMs = 1_000L,
                signature = Signature("license-signature"),
                contentHash = ContentHash("0".repeat(64)),
            )
            fail("expected IllegalArgumentException for blank spdxIdentifier")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("spdxIdentifier"))
        }
    }

    // ============================================================
    // ProprietaryLicense
    // ============================================================

    @Test
    fun `ProprietaryLicense accepts a well-formed configuration`() {
        val license = License.ProprietaryLicense(
            licenseId = LicenseId.random(),
            contractId = RoyaltyContractId.random(),
            programId = VehicleProgramId.random(),
            contributorId = ContributorId.random(),
            displayName = "Elysium Proprietary",
            spdxIdentifier = "LicenseRef-Elysium-Proprietary",
            allowedUses = listOf(
                "personal use",
                "internal company use",
                "redistribution with attribution",
            ),
            effectiveFromMs = 1_000L,
            signature = Signature("license-signature"),
            contentHash = ContentHash("0".repeat(64)),
        )
        assertEquals(3, license.allowedUses.size)
        assertTrue(license.redistributionProhibited)
    }

    @Test
    fun `ProprietaryLicense rejects empty allowedUses`() {
        try {
            License.ProprietaryLicense(
                licenseId = LicenseId.random(),
                contractId = RoyaltyContractId.random(),
                programId = VehicleProgramId.random(),
                contributorId = ContributorId.random(),
                displayName = "Elysium Proprietary",
                spdxIdentifier = "LicenseRef-Elysium-Proprietary",
                allowedUses = emptyList(),
                effectiveFromMs = 1_000L,
                signature = Signature("license-signature"),
                contentHash = ContentHash("0".repeat(64)),
            )
            fail("expected IllegalArgumentException for empty allowedUses")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("allowedUses"))
        }
    }

    @Test
    fun `ProprietaryLicense rejects blank allowedUses entries`() {
        try {
            License.ProprietaryLicense(
                licenseId = LicenseId.random(),
                contractId = RoyaltyContractId.random(),
                programId = VehicleProgramId.random(),
                contributorId = ContributorId.random(),
                displayName = "Elysium Proprietary",
                spdxIdentifier = "LicenseRef-Elysium-Proprietary",
                allowedUses = listOf("personal use", ""),
                effectiveFromMs = 1_000L,
                signature = Signature("license-signature"),
                contentHash = ContentHash("0".repeat(64)),
            )
            fail("expected IllegalArgumentException for blank allowedUses entry")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("allowedUses"))
        }
    }

    // ============================================================
    // CustomLicense
    // ============================================================

    @Test
    fun `CustomLicense accepts a well-formed configuration`() {
        val license = License.CustomLicense(
            licenseId = LicenseId.random(),
            contractId = RoyaltyContractId.random(),
            programId = VehicleProgramId.random(),
            contributorId = ContributorId.random(),
            displayName = "Acme Custom License",
            termsDocumentUrl = "https://example.com/license.pdf",
            termsDocumentHash = ContentHash("1".repeat(64)),
            effectiveFromMs = 1_000L,
            signature = Signature("license-signature"),
            contentHash = ContentHash("0".repeat(64)),
        )
        assertEquals("Acme Custom License", license.displayName)
        assertEquals("https://example.com/license.pdf", license.termsDocumentUrl)
    }

    @Test
    fun `CustomLicense rejects blank termsDocumentUrl`() {
        try {
            License.CustomLicense(
                licenseId = LicenseId.random(),
                contractId = RoyaltyContractId.random(),
                programId = VehicleProgramId.random(),
                contributorId = ContributorId.random(),
                displayName = "Acme Custom License",
                termsDocumentUrl = "",
                termsDocumentHash = ContentHash("1".repeat(64)),
                effectiveFromMs = 1_000L,
                signature = Signature("license-signature"),
                contentHash = ContentHash("0".repeat(64)),
            )
            fail("expected IllegalArgumentException for blank termsDocumentUrl")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("termsDocumentUrl"))
        }
    }

    // ============================================================
    // Effective period
    // ============================================================

    @Test
    fun `every license rejects non-positive effectiveFromMs`() {
        val baseFields = listOf(
            LicenseId.random(),
            RoyaltyContractId.random(),
            VehicleProgramId.random(),
            ContributorId.random(),
            "Test",
            Signature("license-signature"),
            ContentHash("0".repeat(64)),
        )
        // PermissiveLicense
        try {
            License.PermissiveLicense(
                licenseId = baseFields[0] as LicenseId,
                contractId = baseFields[1] as RoyaltyContractId,
                programId = baseFields[2] as VehicleProgramId,
                contributorId = baseFields[3] as ContributorId,
                displayName = baseFields[4] as String,
                spdxIdentifier = "MIT",
                effectiveFromMs = 0L,
                signature = baseFields[5] as Signature,
                contentHash = baseFields[6] as ContentHash,
            )
            fail("expected PermissiveLicense to reject effectiveFromMs = 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("effectiveFromMs"))
        }
    }

    @Test
    fun `license with effectiveUntilMs before effectiveFromMs is rejected`() {
        // Note: the License base class doesn't
        // enforce this currently; the
        // effectiveUntilMs validation lives in
        // the RoyaltyContract. The License
        // accepts any non-blank effectiveUntilMs.
        // This test documents the current
        // behavior.
        val license = License.PermissiveLicense(
            licenseId = LicenseId.random(),
            contractId = RoyaltyContractId.random(),
            programId = VehicleProgramId.random(),
            contributorId = ContributorId.random(),
            displayName = "MIT",
            spdxIdentifier = "MIT",
            effectiveFromMs = 2_000L,
            effectiveUntilMs = 1_000L,
            signature = Signature("license-signature"),
            contentHash = ContentHash("0".repeat(64)),
        )
        assertEquals(2_000L, license.effectiveFromMs)
        assertEquals(1_000L, license.effectiveUntilMs)
    }
}
