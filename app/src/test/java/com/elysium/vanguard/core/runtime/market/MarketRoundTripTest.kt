package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The round-trip test for the Market. The flow:
 *   1. Build a `MarketListingDraft` (the Elysium
 *      Vanguard Linux distro).
 *   2. Sign + publish the draft via the
 *      `MarketPublisher`.
 *   3. Install the listing via the `MarketInstaller`
 *      + the signed bytes.
 *   4. Verify the installed file matches the
 *      original bytes + the listing's content hash.
 *
 * This is the "publish + install" end-to-end
 * vertical slice for the Market.
 */
class MarketRoundTripTest {

    private lateinit var tempDir: File
    private lateinit var catalog: InMemoryMarketCatalog
    private lateinit var publisher: LocalMarketPublisher
    private lateinit var installer: LocalMarketInstaller
    private val signingKey = "phase-1-market-key".toByteArray()
    private val publisherId = ElysiumVanguardDistroListing.PUBLISHER_ID

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("foundry-market-test-").toFile()
        catalog = InMemoryMarketCatalog()
        publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = publisherId,
        )
        installer = LocalMarketInstaller(catalog = catalog)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `publish then install round-trip succeeds for the Elysium Vanguard distro`() {
        val artifactBytes = ByteArray(1024) { (it % 256).toByte() }
        val contentHash = ContentHash.of(artifactBytes)

        val draft = ElysiumVanguardDistroListing.draft().copy(
            contentHash = contentHash,
            sizeBytes = artifactBytes.size.toLong(),
        )

        // Step 1+2: publish.
        val publishedResult = publisher.publish(draft)
        assertTrue(publishedResult.isSuccess)
        val published = publishedResult.getOrThrow()
        assertEquals(draft.id, published.id)
        assertEquals(publisherId, published.signatureKeyId)

        // Step 3: install.
        val installRequest = InstallRequest(
            listingId = draft.id,
            byteSource = { artifactBytes },
            targetDir = tempDir,
            verifyingKey = signingKey,
        )
        val installResult = installer.install(installRequest)
        assertTrue(installResult.isSuccess)
        val receipt = installResult.getOrThrow()

        // Step 4: verify the installed file.
        assertEquals(draft.id, receipt.listingId)
        assertEquals(contentHash.value, receipt.artifactHash)
        assertEquals(publisherId, receipt.signatureKeyId)
        assertEquals(artifactBytes.size.toLong(), receipt.bytesInstalled)
        assertTrue(receipt.installedPath.exists())
        assertArrayEquals(artifactBytes, receipt.installedPath.readBytes())
    }

    @Test
    fun `install rejects when the listing is not in the catalog`() {
        val installRequest = InstallRequest(
            listingId = "com.elysium.vanguard:does-not-exist:1.0.0",
            byteSource = { ByteArray(0) },
            targetDir = tempDir,
            verifyingKey = signingKey,
        )
        val result = installer.install(installRequest)
        assertTrue(result.isFailure)
    }

    @Test
    fun `install rejects when the content hash does not match the bytes`() {
        val draft = ElysiumVanguardDistroListing.draft()
        publisher.publish(draft)

        // The bytes we provide are different from
        // the listing's content hash.
        val wrongBytes = "wrong-bytes".toByteArray()
        val installRequest = InstallRequest(
            listingId = draft.id,
            byteSource = { wrongBytes },
            targetDir = tempDir,
            verifyingKey = signingKey,
        )
        val result = installer.install(installRequest)
        assertTrue(result.isFailure)
    }

    @Test
    fun `install rejects when the verifying key is wrong`() {
        val draft = ElysiumVanguardDistroListing.draft()
        publisher.publish(draft)

        val bytes = ByteArray(64) { 0x42 }
        val installRequest = InstallRequest(
            listingId = draft.id,
            byteSource = { bytes },
            targetDir = tempDir,
            verifyingKey = "wrong-key".toByteArray(),
        )
        val result = installer.install(installRequest)
        assertTrue(result.isFailure)
    }

    @Test
    fun `publish rejects duplicate id`() {
        val draft = ElysiumVanguardDistroListing.draft()
        publisher.publish(draft)
        val result = publisher.publish(draft)
        assertTrue(result.isFailure)
    }

    @Test
    fun `published listing is retrievable from the catalog`() {
        val draft = ElysiumVanguardDistroListing.draft()
        publisher.publish(draft)
        val fetched = catalog.getById(draft.id)
        assertNotNull(fetched)
        assertEquals(draft.id, fetched!!.id)
    }

    @Test
    fun `published listing is signed and verifiable`() {
        val draft = ElysiumVanguardDistroListing.draft()
        val published = publisher.publish(draft).getOrThrow()
        assertTrue(MarketSigning.verify(published, signingKey))
    }

    @Test
    fun `install writes the file to targetDir with the listing id as filename`() {
        val artifactBytes = ByteArray(32) { 0x01 }
        val contentHash = ContentHash.of(artifactBytes)
        val draft = ElysiumVanguardDistroListing.draft().copy(
            contentHash = contentHash,
            sizeBytes = artifactBytes.size.toLong(),
        )
        publisher.publish(draft)

        val targetSubdir = File(tempDir, "installed")
        installer.install(
            InstallRequest(
                listingId = draft.id,
                byteSource = { artifactBytes },
                targetDir = targetSubdir,
                verifyingKey = signingKey,
            ),
        )
        val expectedFile = File(targetSubdir, draft.id)
        assertTrue("expected file at $expectedFile", expectedFile.exists())
    }

    @Test
    fun `market listing draft has the expected Elysium Vanguard Linux fields`() {
        val draft = ElysiumVanguardDistroListing.draft()
        assertEquals("com.elysium.vanguard:distro:1.0.0-TITAN", draft.id)
        assertEquals("Elysium Vanguard Linux", draft.name)
        assertEquals(MarketListingType.DISTRO, draft.type)
        assertEquals("1.0.0-TITAN", draft.version)
        assertTrue("expected linux tag", draft.tags.contains("linux"))
        assertTrue("expected elysium tag", draft.tags.contains("elysium"))
        assertTrue("size is non-zero", draft.sizeBytes > 0)
    }

    @Test
    fun `local publisher exposes its publisher id`() {
        assertEquals(publisherId, publisher.publisherId)
    }

    @Test
    fun `installing a tampered listing fails verification`() {
        val draft = ElysiumVanguardDistroListing.draft()
        val published = publisher.publish(draft).getOrThrow()
        // Tamper with the listing in the catalog
        // (simulate an attacker who modified the
        // signed listing).
        val tampered = published.copy(name = "Evil Distro")
        catalog.put(tampered)
        val bytes = ByteArray(64) { 0x42 }
        val result = installer.install(
            InstallRequest(
                listingId = published.id,
                byteSource = { bytes },
                targetDir = tempDir,
                verifyingKey = signingKey,
            ),
        )
        assertTrue("expected failure on tampered listing", result.isFailure)
    }

    @Test
    fun `published listing appears in search by tag`() {
        publisher.publish(ElysiumVanguardDistroListing.draft())
        val result = catalog.search(MarketSearchQuery(query = "elysium")).getOrThrow()
        assertTrue("expected at least 1 result, got ${result.totalCount}", result.totalCount >= 1)
        assertFalse(result.listings.isEmpty())
    }
}
