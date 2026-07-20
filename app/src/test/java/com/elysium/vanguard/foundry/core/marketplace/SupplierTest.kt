package com.elysium.vanguard.foundry.core.marketplace

import com.elysium.vanguard.foundry.core.ontology.ids.UserId
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Phase F6 second half (G8, I-6.2) — the JVM
 * tests for the Supplier Network (Supplier +
 * SupplierQualification + Region +
 * Certification + SupplierCapability +
 * SupplierRegistry).
 *
 * The tests cover:
 *   - Supplier invariants (blank name,
 *     non-2-letter countryCode, lowercase
 *     countryCode, non-letter countryCode,
 *     year before MIN_YEAR, year after
 *     MAX_YEAR, blank contactEmail, email
 *     without '@', email without '.').
 *   - Certification invariants (blank
 *     name, blank issuer, zero
 *     validUntilMs).
 *   - SupplierCapability invariants (blank
 *     capabilityName, blank vehicleDomain,
 *     zero minVolumePerYear,
 *     maxVolumePerYear < minVolumePerYear).
 *   - Region invariants (Country, Continental,
 *     Worldwide, includes() behavior).
 *   - SupplierQualification invariants
 *     (empty capabilities, empty regions,
 *     zero lastReviewedMs,
 *     offersCapability,
 *     servesCountry).
 *   - InMemorySupplierRegistry (register,
 *     duplicate supplier, addQualification,
 *     unknown supplier, duplicate
 *     qualification, getSupplier,
 *     getQualifications, findByCapability,
 *     findByCountry, findByRegion,
 *     findByCapabilityAndCountry).
 *   - Realistic scenario: a supplier
 *     registers, gets qualified for
 *     engine-blocks in NA + EU; a buyer
 *     creates an RFQ for engine-blocks
 *     in the US; the registry finds the
 *     supplier.
 */
class SupplierTest {

    // ============================================================
    // Supplier invariants
    // ============================================================

    @Test
    fun `Supplier accepts a well-formed configuration`() {
        val supplier = buildSupplier()
        assertEquals("Acme Powertrain Co.", supplier.name)
    }

    @Test
    fun `Supplier rejects blank name`() {
        try {
            buildSupplier(name = "")
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `Supplier rejects blank legalEntity`() {
        try {
            buildSupplier(legalEntity = "")
            fail("expected IllegalArgumentException for blank legalEntity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("legalEntity"))
        }
    }

    @Test
    fun `Supplier rejects non-2-letter countryCode`() {
        try {
            buildSupplier(countryCode = "USA")
            fail("expected IllegalArgumentException for 3-letter countryCode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("countryCode"))
        }
    }

    @Test
    fun `Supplier rejects lowercase countryCode`() {
        try {
            buildSupplier(countryCode = "us")
            fail("expected IllegalArgumentException for lowercase countryCode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("countryCode"))
        }
    }

    @Test
    fun `Supplier rejects non-letter countryCode`() {
        try {
            buildSupplier(countryCode = "U1")
            fail("expected IllegalArgumentException for digit countryCode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("countryCode"))
        }
    }

    @Test
    fun `Supplier rejects year before MIN_YEAR`() {
        try {
            buildSupplier(yearEstablished = 1799)
            fail("expected IllegalArgumentException for year < MIN_YEAR")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("yearEstablished"))
        }
    }

    @Test
    fun `Supplier rejects year after MAX_YEAR`() {
        try {
            buildSupplier(yearEstablished = 2101)
            fail("expected IllegalArgumentException for year > MAX_YEAR")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("yearEstablished"))
        }
    }

    @Test
    fun `Supplier rejects blank contactEmail`() {
        try {
            buildSupplier(contactEmail = "")
            fail("expected IllegalArgumentException for blank contactEmail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("contactEmail"))
        }
    }

    @Test
    fun `Supplier rejects email without @`() {
        try {
            buildSupplier(contactEmail = "acme.example.com")
            fail("expected IllegalArgumentException for email without @")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("contactEmail"))
        }
    }

    @Test
    fun `Supplier rejects email without dot`() {
        try {
            buildSupplier(contactEmail = "info@acme")
            fail("expected IllegalArgumentException for email without dot")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("contactEmail"))
        }
    }

    // ============================================================
    // Certification invariants
    // ============================================================

    @Test
    fun `Certification accepts a well-formed configuration`() {
        val cert = buildCertification()
        assertEquals("ISO 9001", cert.name)
    }

    @Test
    fun `Certification rejects blank name`() {
        try {
            buildCertification(name = "")
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `Certification rejects blank issuer`() {
        try {
            buildCertification(issuer = "")
            fail("expected IllegalArgumentException for blank issuer")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("issuer"))
        }
    }

    @Test
    fun `Certification rejects zero validUntilMs`() {
        try {
            buildCertification(validUntilMs = 0L)
            fail("expected IllegalArgumentException for zero validUntilMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("validUntilMs"))
        }
    }

    // ============================================================
    // SupplierCapability invariants
    // ============================================================

    @Test
    fun `SupplierCapability accepts a well-formed configuration`() {
        val cap = buildCapability()
        assertEquals("engine-blocks", cap.capabilityName)
    }

    @Test
    fun `SupplierCapability rejects blank capabilityName`() {
        try {
            buildCapability(capabilityName = "")
            fail("expected IllegalArgumentException for blank capabilityName")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("capabilityName"))
        }
    }

    @Test
    fun `SupplierCapability rejects blank vehicleDomain`() {
        try {
            buildCapability(vehicleDomain = "")
            fail("expected IllegalArgumentException for blank vehicleDomain")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("vehicleDomain"))
        }
    }

    @Test
    fun `SupplierCapability rejects zero minVolumePerYear`() {
        try {
            buildCapability(minVolumePerYear = 0)
            fail("expected IllegalArgumentException for zero minVolumePerYear")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minVolumePerYear"))
        }
    }

    @Test
    fun `SupplierCapability rejects maxVolumePerYear less than minVolumePerYear`() {
        try {
            buildCapability(
                minVolumePerYear = 100,
                maxVolumePerYear = 50,
            )
            fail("expected IllegalArgumentException for max < min")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxVolumePerYear"))
        }
    }

    // ============================================================
    // Region invariants
    // ============================================================

    @Test
    fun `Region Country accepts a well-formed configuration`() {
        val region = Region.Country(countryCode = "US")
        assertEquals("US", region.countryCode)
    }

    @Test
    fun `Region Country rejects non-2-letter countryCode`() {
        try {
            Region.Country(countryCode = "USA")
            fail("expected IllegalArgumentException for 3-letter countryCode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("countryCode"))
        }
    }

    @Test
    fun `Region Country rejects lowercase countryCode`() {
        try {
            Region.Country(countryCode = "us")
            fail("expected IllegalArgumentException for lowercase countryCode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("countryCode"))
        }
    }

    @Test
    fun `Region Continental accepts a well-formed configuration`() {
        val region = Region.Continental(continentCode = "EU")
        assertEquals("EU", region.continentCode)
    }

    @Test
    fun `Region Continental rejects invalid continent code`() {
        try {
            Region.Continental(continentCode = "ZZ")
            fail("expected IllegalArgumentException for invalid continent")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("continentCode"))
        }
    }

    @Test
    fun `Region Worldwide is a single instance`() {
        assertEquals(Region.Worldwide, Region.Worldwide)
    }

    @Test
    fun `Region Country includes returns true on exact match`() {
        assertTrue(Region.Country("US").includes("US"))
    }

    @Test
    fun `Region Country includes returns false on mismatch`() {
        assertTrue(!Region.Country("US").includes("DE"))
    }

    @Test
    fun `Region Continental includes returns true for a country in that continent`() {
        assertTrue(Region.Continental("EU").includes("DE"))
        assertTrue(Region.Continental("NA").includes("US"))
        assertTrue(Region.Continental("AS").includes("JP"))
    }

    @Test
    fun `Region Continental includes returns false for a country in another continent`() {
        assertTrue(!Region.Continental("EU").includes("US"))
        assertTrue(!Region.Continental("NA").includes("DE"))
    }

    @Test
    fun `Region Worldwide includes returns true for any country`() {
        assertTrue(Region.Worldwide.includes("US"))
        assertTrue(Region.Worldwide.includes("DE"))
        assertTrue(Region.Worldwide.includes("JP"))
    }

    // ============================================================
    // SupplierQualification invariants
    // ============================================================

    @Test
    fun `SupplierQualification accepts a well-formed configuration`() {
        val qual = buildQualification()
        assertEquals(1, qual.capabilities.size)
    }

    @Test
    fun `SupplierQualification rejects empty capabilities`() {
        try {
            buildQualification(capabilities = emptyList())
            fail("expected IllegalArgumentException for empty capabilities")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("capabilities"))
        }
    }

    @Test
    fun `SupplierQualification rejects empty regions`() {
        try {
            buildQualification(regions = emptyList())
            fail("expected IllegalArgumentException for empty regions")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("regions"))
        }
    }

    @Test
    fun `SupplierQualification rejects zero lastReviewedMs`() {
        try {
            buildQualification(lastReviewedMs = 0L)
            fail("expected IllegalArgumentException for zero lastReviewedMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("lastReviewedMs"))
        }
    }

    @Test
    fun `SupplierQualification offersCapability returns true on match`() {
        val qual = buildQualification()
        assertTrue(qual.offersCapability("engine-blocks"))
    }

    @Test
    fun `SupplierQualification offersCapability returns false on mismatch`() {
        val qual = buildQualification()
        assertTrue(!qual.offersCapability("transmissions"))
    }

    @Test
    fun `SupplierQualification servesCountry returns true for a country in the region`() {
        val qual = buildQualification(
            regions = listOf(Region.Country("US")),
        )
        assertTrue(qual.servesCountry("US"))
    }

    @Test
    fun `SupplierQualification servesCountry returns false for a country not in the region`() {
        val qual = buildQualification(
            regions = listOf(Region.Country("US")),
        )
        assertTrue(!qual.servesCountry("DE"))
    }

    @Test
    fun `SupplierQualification servesCountry returns true for a country in a continental region`() {
        val qual = buildQualification(
            regions = listOf(Region.Continental("EU")),
        )
        assertTrue(qual.servesCountry("DE"))
    }

    @Test
    fun `SupplierQualification servesCountry returns true for any country in worldwide`() {
        val qual = buildQualification(
            regions = listOf(Region.Worldwide),
        )
        assertTrue(qual.servesCountry("US"))
        assertTrue(qual.servesCountry("DE"))
        assertTrue(qual.servesCountry("JP"))
    }

    // ============================================================
    // InMemorySupplierRegistry — register + getSupplier
    // ============================================================

    @Test
    fun `register accepts a well-formed supplier`() {
        val registry = InMemorySupplierRegistry()
        val supplier = buildSupplier()
        val result = registry.register(supplier)
        assertTrue(result.isSuccess)
        assertEquals(1, registry.suppliers.size)
    }

    @Test
    fun `register rejects a duplicate supplier id`() {
        val registry = InMemorySupplierRegistry()
        val supplier = buildSupplier()
        registry.register(supplier)
        val result = registry.register(supplier)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is SupplierRegistryError.SupplierAlreadyRegistered,
        )
    }

    @Test
    fun `getSupplier returns the supplier by id`() {
        val registry = InMemorySupplierRegistry()
        val supplier = buildSupplier()
        registry.register(supplier)
        val fetched = registry.getSupplier(supplier.supplierId)
        assertEquals(supplier, fetched)
    }

    @Test
    fun `getSupplier returns null for an unknown id`() {
        val registry = InMemorySupplierRegistry()
        val fetched = registry.getSupplier(SupplierId.random())
        assertNull(fetched)
    }

    // ============================================================
    // InMemorySupplierRegistry — addQualification
    // ============================================================

    @Test
    fun `addQualification accepts a well-formed qualification`() {
        val registry = InMemorySupplierRegistry()
        val supplier = buildSupplier()
        registry.register(supplier)
        val qual = buildQualification(supplierId = supplier.supplierId)
        val result = registry.addQualification(qual)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `addQualification rejects an unknown supplier`() {
        val registry = InMemorySupplierRegistry()
        val qual = buildQualification(supplierId = SupplierId.random())
        val result = registry.addQualification(qual)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is SupplierRegistryError.SupplierNotFound)
    }

    @Test
    fun `addQualification rejects a duplicate qualification id`() {
        val registry = InMemorySupplierRegistry()
        val supplier = buildSupplier()
        registry.register(supplier)
        val qual = buildQualification(supplierId = supplier.supplierId)
        registry.addQualification(qual)
        val result = registry.addQualification(qual)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            ex is SupplierRegistryError.DuplicateQualificationId,
        )
    }

    @Test
    fun `getQualifications returns the qualifications for a supplier`() {
        val registry = InMemorySupplierRegistry()
        val supplier = buildSupplier()
        registry.register(supplier)
        val qual1 = buildQualification(supplierId = supplier.supplierId)
        val qual2 = buildQualification(
            supplierId = supplier.supplierId,
            capabilityName = "transmissions",
        )
        registry.addQualification(qual1)
        registry.addQualification(qual2)
        val quals = registry.getQualifications(supplier.supplierId)
        assertEquals(2, quals.size)
    }

    @Test
    fun `getQualifications returns empty for an unknown supplier`() {
        val registry = InMemorySupplierRegistry()
        val quals = registry.getQualifications(SupplierId.random())
        assertTrue(quals.isEmpty())
    }

    // ============================================================
    // InMemorySupplierRegistry — discovery
    // ============================================================

    @Test
    fun `findByCapability returns suppliers that offer the capability`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCapability("engine-blocks")
        assertEquals(2, suppliers.size)
    }

    @Test
    fun `findByCapability returns empty for an unknown capability`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCapability("turbines")
        assertTrue(suppliers.isEmpty())
    }

    @Test
    fun `findByCountry returns suppliers that serve the country`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCountry("US")
        // Both suppliers have a US region (acme)
        // OR a NA continent that includes US
        // (bosch). So both should match.
        assertEquals(2, suppliers.size)
    }

    @Test
    fun `findByCountry returns only the supplier in the country`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCountry("DE")
        // Only bosch serves DE directly.
        assertEquals(1, suppliers.size)
        assertEquals("Bosch GmbH", suppliers[0].name)
    }

    @Test
    fun `findByCountry returns empty for an unsupported country`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCountry("ZZ")
        assertTrue(suppliers.isEmpty())
    }

    @Test
    fun `findByRegion returns suppliers that serve the region`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByRegion(Region.Continental("EU"))
        // Only bosch serves EU directly.
        assertEquals(1, suppliers.size)
        assertEquals("Bosch GmbH", suppliers[0].name)
    }

    @Test
    fun `findByRegion returns suppliers that serve Worldwide`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByRegion(Region.Worldwide)
        // Neither supplier serves Worldwide.
        assertTrue(suppliers.isEmpty())
    }

    @Test
    fun `findByCapabilityAndCountry returns suppliers that match both`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCapabilityAndCountry(
            capabilityName = "engine-blocks",
            countryCode = "US",
        )
        // acme serves US directly.
        // bosch serves NA which includes US.
        // Both should match.
        assertEquals(2, suppliers.size)
    }

    @Test
    fun `findByCapabilityAndCountry returns empty when no match`() {
        val registry = buildRegistryWithTwoSuppliers()
        val suppliers = registry.findByCapabilityAndCountry(
            capabilityName = "turbines",
            countryCode = "US",
        )
        assertTrue(suppliers.isEmpty())
    }

    // ============================================================
    // Realistic scenario
    // ============================================================

    @Test
    fun `realistic scenario a supplier registers, gets qualified, buyer finds them by capability and country`() {
        // Step 1: Two suppliers register.
        val registry = InMemorySupplierRegistry()
        val acme = buildSupplier(
            supplierId = SupplierId(UUID.randomUUID()),
            name = "Acme Powertrain Co.",
            legalEntity = "Acme Powertrain Corporation, Inc.",
            countryCode = "US",
            yearEstablished = 1985,
            contactEmail = "info@acme-powertrain.com",
        )
        val bosch = buildSupplier(
            supplierId = SupplierId(UUID.randomUUID()),
            name = "Bosch GmbH",
            legalEntity = "Robert Bosch GmbH",
            countryCode = "DE",
            yearEstablished = 1886,
            contactEmail = "info@bosch.com",
        )
        registry.register(acme)
        registry.register(bosch)

        // Step 2: Both suppliers are qualified for
        // engine-blocks; acme serves US directly,
        // bosch serves the EU + NA continents.
        val acmeQual = buildQualification(
            supplierId = acme.supplierId,
            capabilityName = "engine-blocks",
            regions = listOf(Region.Country("US")),
        )
        val boschQual = buildQualification(
            supplierId = bosch.supplierId,
            capabilityName = "engine-blocks",
            regions = listOf(
                Region.Continental("EU"),
                Region.Continental("NA"),
            ),
        )
        registry.addQualification(acmeQual)
        registry.addQualification(boschQual)

        // Step 3: A buyer creates an RFQ for
        // engine-blocks in the US.
        val matchingSuppliers = registry.findByCapabilityAndCountry(
            capabilityName = "engine-blocks",
            countryCode = "US",
        )

        // Both suppliers match: acme serves US
        // directly; bosch serves NA which
        // includes US.
        assertEquals(2, matchingSuppliers.size)
        val supplierIds = matchingSuppliers.map { it.supplierId }.toSet()
        assertTrue(acme.supplierId in supplierIds)
        assertTrue(bosch.supplierId in supplierIds)

        // Step 4: A buyer creates an RFQ for
        // engine-blocks in Japan.
        val japanSuppliers = registry.findByCapabilityAndCountry(
            capabilityName = "engine-blocks",
            countryCode = "JP",
        )
        // No supplier serves JP.
        assertTrue(japanSuppliers.isEmpty())
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildSupplier(
        supplierId: SupplierId = SupplierId.random(),
        name: String = "Acme Powertrain Co.",
        legalEntity: String = "Acme Powertrain Corporation, Inc.",
        countryCode: String = "US",
        yearEstablished: Int = 1985,
        contactEmail: String = "info@acme-powertrain.com",
    ): Supplier = Supplier(
        supplierId = supplierId,
        name = name,
        legalEntity = legalEntity,
        countryCode = countryCode,
        yearEstablished = yearEstablished,
        contactEmail = contactEmail,
        signature = Signature("sig-supplier-${UUID.randomUUID()}"),
    )

    private fun buildCertification(
        name: String = "ISO 9001",
        issuer: String = "TÜV SÜD",
        validUntilMs: Long = 1_700_000_000_000L,
    ): Certification = Certification(
        name = name,
        issuer = issuer,
        validUntilMs = validUntilMs,
    )

    private fun buildCapability(
        capabilityName: String = "engine-blocks",
        vehicleDomain: String = "passenger-cars",
        minVolumePerYear: Int = 100,
        maxVolumePerYear: Int = 5000,
        certifications: List<Certification> = emptyList(),
    ): SupplierCapability = SupplierCapability(
        capabilityName = capabilityName,
        vehicleDomain = vehicleDomain,
        minVolumePerYear = minVolumePerYear,
        maxVolumePerYear = maxVolumePerYear,
        certifications = certifications,
    )

    private fun buildQualification(
        qualificationId: QualificationId = QualificationId.random(),
        supplierId: SupplierId = SupplierId.random(),
        capabilities: List<SupplierCapability> = listOf(buildCapability()),
        capabilityName: String? = null,
        regions: List<Region> = listOf(Region.Country("US")),
        lastReviewedMs: Long = 1_700_000_000_000L,
        reviewerId: UserId = UserId.random(),
    ): SupplierQualification {
        val resolvedCapabilities: List<SupplierCapability> =
            if (capabilityName != null) {
                listOf(buildCapability(capabilityName = capabilityName))
            } else {
                capabilities
            }
        return SupplierQualification(
            qualificationId = qualificationId,
            supplierId = supplierId,
            capabilities = resolvedCapabilities,
            regions = regions,
            lastReviewedMs = lastReviewedMs,
            reviewerId = reviewerId,
            signature = Signature("sig-qual-${UUID.randomUUID()}"),
        )
    }

    /**
     * Build a registry with two suppliers:
     *   - **acme** (US, serves Country "US")
     *   - **bosch** (DE, serves Continental "EU"
     *     + Continental "NA")
     *
     * Both are qualified for "engine-blocks".
     */
    private fun buildRegistryWithTwoSuppliers(): InMemorySupplierRegistry {
        val registry = InMemorySupplierRegistry()
        val acme = buildSupplier(
            supplierId = SupplierId(UUID.randomUUID()),
            name = "Acme Powertrain Co.",
            countryCode = "US",
        )
        val bosch = buildSupplier(
            supplierId = SupplierId(UUID.randomUUID()),
            name = "Bosch GmbH",
            countryCode = "DE",
        )
        registry.register(acme)
        registry.register(bosch)
        registry.addQualification(
            buildQualification(
                supplierId = acme.supplierId,
                regions = listOf(Region.Country("US")),
            ),
        )
        registry.addQualification(
            buildQualification(
                supplierId = bosch.supplierId,
                regions = listOf(
                    Region.Continental("EU"),
                    Region.Continental("NA"),
                ),
            ),
        )
        return registry
    }
}
