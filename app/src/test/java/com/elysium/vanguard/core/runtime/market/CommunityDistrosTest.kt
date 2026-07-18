package com.elysium.vanguard.core.runtime.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the `CommunityDistros` object.
 * The object defines the first batch of
 * community distros; the tests verify the
 * shape + the searchability of the batch.
 */
class CommunityDistrosTest {

    @Test
    fun `community distros has 8 entries`() {
        assertEquals(8, CommunityDistros.ALL.size)
    }

    @Test
    fun `all community distros are of type DISTRO`() {
        for (distro in CommunityDistros.ALL) {
            assertEquals(MarketListingType.DISTRO, distro.type)
        }
    }

    @Test
    fun `all community distros have non-blank name and id`() {
        for (distro in CommunityDistros.ALL) {
            assertTrue("id must not be blank: ${distro.id}", distro.id.isNotBlank())
            assertTrue("name must not be blank: ${distro.name}", distro.name.isNotBlank())
        }
    }

    @Test
    fun `all community distros have unique ids`() {
        val ids = CommunityDistros.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ubuntu 24_04 has the expected fields`() {
        val ubuntu = CommunityDistros.ubuntu_24_04
        assertEquals("com.elysium.vanguard:distro:ubuntu-24.04:1.0.0", ubuntu.id)
        assertEquals("Ubuntu 24.04 LTS", ubuntu.name)
        assertTrue(ubuntu.tags.contains("ubuntu"))
        assertTrue(ubuntu.tags.contains("lts"))
    }

    @Test
    fun `arch linux has the expected fields`() {
        val arch = CommunityDistros.arch_linux
        assertEquals("com.elysium.vanguard:distro:arch:1.0.0", arch.id)
        assertEquals("Arch Linux (Rolling)", arch.name)
        assertTrue(arch.tags.contains("rolling-release"))
    }

    @Test
    fun `fedora 41 has the expected fields`() {
        val fedora = CommunityDistros.fedora_41
        assertEquals("com.elysium.vanguard:distro:fedora-41:1.0.0", fedora.id)
        assertEquals("Fedora 41", fedora.name)
        assertTrue(fedora.tags.contains("rpm"))
        assertTrue(fedora.tags.contains("fedora"))
    }

    @Test
    fun `alpine 3_20 is the smallest distro`() {
        val alpine = CommunityDistros.alpine_3_20
        // Alpine is famously tiny.
        assertTrue(
            "alpine should be smaller than 500MB, got ${alpine.sizeBytes}",
            alpine.sizeBytes < 500_000_000L,
        )
    }

    @Test
    fun `all community distros can be published to an in-memory catalog`() {
        val catalog = InMemoryMarketCatalog()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = "test-key".toByteArray(),
            publisherId = "publisher:test",
        )
        for (distro in CommunityDistros.ALL) {
            val result = publisher.publish(distro)
            assertTrue("publish should succeed for ${distro.id}", result.isSuccess)
        }
        assertEquals(8, catalog.count())
    }

    @Test
    fun `community distros are searchable by tag`() {
        val catalog = InMemoryMarketCatalog()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = "test-key".toByteArray(),
            publisherId = "publisher:test",
        )
        for (distro in CommunityDistros.ALL) {
            publisher.publish(distro)
        }
        // "rolling" appears in 3 distros (Arch, openSUSE, Void — all have
        // "(Rolling)" in their name AND "rolling-release" in their tags).
        val rollingResult = catalog.search(MarketSearchQuery(query = "rolling")).getOrThrow()
        assertEquals(3, rollingResult.totalCount)

        // "musl" appears in 2 distros (Alpine, Void — musl libc).
        val muslResult = catalog.search(MarketSearchQuery(query = "musl")).getOrThrow()
        assertEquals(2, muslResult.totalCount)

        // "debian" appears in 2 distros (Ubuntu with "debian-based" tag,
        // Debian with "debian" tag + "Debian" in the name).
        val debianResult = catalog.search(MarketSearchQuery(query = "debian")).getOrThrow()
        assertEquals(2, debianResult.totalCount)
    }

    @Test
    fun `community distros are searchable by name`() {
        val catalog = InMemoryMarketCatalog()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = "test-key".toByteArray(),
            publisherId = "publisher:test",
        )
        for (distro in CommunityDistros.ALL) {
            publisher.publish(distro)
        }
        val ubuntuResult = catalog.search(MarketSearchQuery(query = "Ubuntu")).getOrThrow()
        assertEquals(1, ubuntuResult.totalCount)

        val nixosResult = catalog.search(MarketSearchQuery(query = "NixOS")).getOrThrow()
        assertEquals(1, nixosResult.totalCount)
    }

    @Test
    fun `community distros are all signed and verifiable`() {
        val catalog = InMemoryMarketCatalog()
        val signingKey = "community-distros-key".toByteArray()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = "publisher:community",
        )
        for (distro in CommunityDistros.ALL) {
            publisher.publish(distro)
        }
        for (distro in CommunityDistros.ALL) {
            val signed = catalog.getById(distro.id)
            assertTrue("listing must exist: ${distro.id}", signed != null)
            assertTrue(
                "signature must verify: ${distro.id}",
                MarketSigning.verify(signed!!, signingKey),
            )
        }
    }

    @Test
    fun `combined catalog has the platform distro plus 8 community distros`() {
        val catalog = InMemoryMarketCatalog()
        val signingKey = "all-distros-key".toByteArray()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = "publisher:all",
        )
        publisher.publish(ElysiumVanguardDistroListing.draft())
        for (distro in CommunityDistros.ALL) {
            publisher.publish(distro)
        }
        assertEquals(9, catalog.count())

        val listAll = catalog.listAll().getOrThrow()
        assertEquals(9, listAll.totalCount)
        // The platform distro is in the catalog (name starts with "Elysium").
        val platformDistro = catalog.getById(ElysiumVanguardDistroListing.ID)
        assertTrue("platform distro must be in the catalog", platformDistro != null)
        assertEquals(ElysiumVanguardDistroListing.NAME, platformDistro!!.name)
    }
}
