package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCatalogTest {

    private val catalog = InMemoryMarketCatalog()
    private val key = "phase-1-market-key".toByteArray()
    private val now = Timestamp(1_700_000_000_000L)

    private fun listing(
        id: String = "com.elysium.vanguard:ubuntu-24.04:1.0.0",
        name: String = "Ubuntu 24.04",
        type: MarketListingType = MarketListingType.DISTRO,
        version: String = "1.0.0",
        contentHash: ContentHash = ContentHash.of("artifact-$id"),
        signatureKeyId: String = "publisher:canonical",
        sizeBytes: Long = 4_500_000_000L,
        dependencies: List<String> = emptyList(),
        tags: List<String> = listOf("linux", "lts"),
    ): MarketListing {
        val unsigned = MarketListing(
            id = id,
            name = name,
            type = type,
            version = version,
            contentHash = contentHash,
            signatureKeyId = signatureKeyId,
            signature = Signature.sign("placeholder", key),
            sizeBytes = sizeBytes,
            dependencies = dependencies,
            tags = tags,
            createdAt = now,
        )
        return MarketSigning.sign(unsigned, key)
    }

    @Test
    fun `put then getById returns the same listing`() {
        val l = listing()
        catalog.put(l)
        val fetched = catalog.getById(l.id)
        assertEquals(l, fetched)
    }

    @Test
    fun `getById returns null for unknown id`() {
        assertNull(catalog.getById("does-not-exist"))
    }

    @Test
    fun `put rejects duplicate id`() {
        val l = listing()
        catalog.put(l)
        val second = catalog.put(l)
        assertTrue(second.isFailure)
    }

    @Test
    fun `search with empty query returns all listings`() {
        catalog.put(listing(id = "a", name = "Alpha"))
        catalog.put(listing(id = "b", name = "Beta"))
        val result = catalog.search(MarketSearchQuery()).getOrThrow()
        assertEquals(2, result.totalCount)
        assertEquals(2, result.listings.size)
    }

    @Test
    fun `search filters by name substring`() {
        catalog.put(listing(id = "ubuntu-24.04", name = "Ubuntu 24.04 LTS"))
        catalog.put(listing(id = "fedora-41", name = "Fedora 41"))
        catalog.put(listing(id = "arch-rolling", name = "Arch Linux Rolling"))
        val result = catalog.search(MarketSearchQuery(query = "ubuntu")).getOrThrow()
        assertEquals(1, result.totalCount)
        assertEquals("ubuntu-24.04", result.listings.first().id)
    }

    @Test
    fun `search filters by tag substring`() {
        catalog.put(listing(id = "kde-app", name = "KDE Suite", tags = listOf("desktop", "kde")))
        catalog.put(listing(id = "gnome-app", name = "GNOME Apps", tags = listOf("desktop", "gnome")))
        val result = catalog.search(MarketSearchQuery(query = "kde")).getOrThrow()
        assertEquals(1, result.totalCount)
        assertEquals("kde-app", result.listings.first().id)
    }

    @Test
    fun `search is case insensitive`() {
        catalog.put(listing(id = "ubuntu-24.04", name = "Ubuntu 24.04"))
        val upper = catalog.search(MarketSearchQuery(query = "UBUNTU")).getOrThrow()
        val lower = catalog.search(MarketSearchQuery(query = "ubuntu")).getOrThrow()
        assertEquals(upper.totalCount, lower.totalCount)
        assertEquals(1, upper.totalCount)
    }

    @Test
    fun `search filters by type`() {
        catalog.put(listing(id = "d1", name = "Distro 1", type = MarketListingType.DISTRO))
        catalog.put(listing(id = "a1", name = "App 1", type = MarketListingType.APP))
        val result = catalog.search(MarketSearchQuery(type = MarketListingType.DISTRO)).getOrThrow()
        assertEquals(1, result.totalCount)
        assertEquals("d1", result.listings.first().id)
    }

    @Test
    fun `search respects limit`() {
        repeat(5) { i ->
            catalog.put(listing(id = "listing-$i", name = "Listing $i"))
        }
        val result = catalog.search(MarketSearchQuery(limit = 3)).getOrThrow()
        assertEquals(5, result.totalCount)
        assertEquals(3, result.listings.size)
    }

    @Test
    fun `search with limit zero is rejected`() {
        try {
            MarketSearchQuery(limit = 0)
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must be positive"))
        }
    }

    @Test
    fun `search with limit exceeding max is rejected`() {
        try {
            MarketSearchQuery(limit = MarketSearchQuery.MAX_LIMIT + 1)
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must be <="))
        }
    }

    @Test
    fun `listAll returns sorted by name`() {
        catalog.put(listing(id = "z", name = "Zeta"))
        catalog.put(listing(id = "a", name = "Alpha"))
        catalog.put(listing(id = "m", name = "Mu"))
        val result = catalog.listAll().getOrThrow()
        assertEquals(listOf("Alpha", "Mu", "Zeta"), result.listings.map { it.name })
    }

    @Test
    fun `count returns total listings`() {
        assertEquals(0, catalog.count())
        catalog.put(listing(id = "1"))
        catalog.put(listing(id = "2"))
        catalog.put(listing(id = "3"))
        assertEquals(3, catalog.count())
    }

    @Test
    fun `listing rejects negative size`() {
        try {
            listing(sizeBytes = -1L)
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sizeBytes"))
        }
    }

    @Test
    fun `listing rejects blank id`() {
        try {
            listing(id = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("id must not be blank"))
        }
    }

    @Test
    fun `listing rejects blank name`() {
        try {
            listing(name = "")
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name must not be blank"))
        }
    }

    @Test
    fun `listing canonical form is deterministic across declarations`() {
        val a = listing(id = "x", name = "X", version = "1.0.0")
        val b = listing(id = "x", name = "X", version = "1.0.0")
        assertEquals(a.canonicalForm(), b.canonicalForm())
    }

    @Test
    fun `listing canonical form differs for different content hashes`() {
        val a = listing(id = "x", contentHash = ContentHash.of("alpha"))
        val b = listing(id = "x", contentHash = ContentHash.of("beta"))
        assertTrue(a.canonicalForm() != b.canonicalForm())
    }

    @Test
    fun `listing canonical form is order independent for tags`() {
        val a = listing(id = "x", tags = listOf("linux", "lts", "server"))
        val b = listing(id = "x", tags = listOf("server", "linux", "lts"))
        assertEquals(a.canonicalForm(), b.canonicalForm())
    }

    @Test
    fun `listing canonical form is order independent for dependencies`() {
        val a = listing(id = "x", dependencies = listOf("d1", "d2", "d3"))
        val b = listing(id = "x", dependencies = listOf("d3", "d1", "d2"))
        assertEquals(a.canonicalForm(), b.canonicalForm())
    }

    @Test
    fun `signed listing carries the expected signature shape`() {
        val l = listing()
        val signatureValue = l.signature.value
        assertNotNull(signatureValue)
        assertTrue("signature should be non-empty", signatureValue.isNotEmpty())
    }
}
