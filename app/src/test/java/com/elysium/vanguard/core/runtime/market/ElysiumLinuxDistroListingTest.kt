package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.core.linux.ElysiumRootfsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 74 — the JVM tests for
 * [ElysiumLinuxDistroListing].
 *
 * The tests cover:
 *   - Listing identity (ID + publisher + name).
 *   - Rootfs version matches the canonical
 *     semver.
 *   - Default tags include the Elysium Linux
 *     markers.
 *   - Runtime layers list is the Phase 73
 *     defaults.
 *   - Package manager is `elysium-pm`.
 *   - draft() returns a valid MarketListingDraft
 *     with the listing's fields.
 *   - The new listing is **distinct** from the
 *     legacy `ElysiumVanguardDistroListing`
 *     (different publisher, different id,
 *     different name).
 */
class ElysiumLinuxDistroListingTest {

    // ============================================================
    // Listing identity
    // ============================================================

    @Test
    fun `publisher id is the elysium-linux publisher`() {
        assertEquals(
            "publisher:elysium-linux",
            ElysiumLinuxDistroListing.PUBLISHER_ID,
        )
    }

    @Test
    fun `distribution id follows the canonical catalog format`() {
        assertEquals(
            "com.elysium.linux:distro:1.0.0",
            ElysiumLinuxDistroListing.ID,
        )
    }

    @Test
    fun `distribution name is Elysium Linux`() {
        assertEquals("Elysium Linux", ElysiumLinuxDistroListing.NAME)
    }

    @Test
    fun `distribution version is 1-0-0`() {
        assertEquals("1.0.0", ElysiumLinuxDistroListing.VERSION)
    }

    // ============================================================
    // Rootfs version
    // ============================================================

    @Test
    fun `rootfs version is 1-0-0 semver`() {
        val v = ElysiumLinuxDistroListing.ROOTFS_VERSION
        assertEquals(1, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
    }

    @Test
    fun `rootfs version canonical form is 1-0-0`() {
        assertEquals("1.0.0", ElysiumLinuxDistroListing.ROOTFS_VERSION.canonical)
    }

    @Test
    fun `rootfs version imageFileName is the canonical form`() {
        assertEquals(
            "rootfs-v1.0.0.tar.zst",
            ElysiumLinuxDistroListing.ROOTFS_VERSION.imageFileName,
        )
    }

    // ============================================================
    // Content + size
    // ============================================================

    @Test
    fun `content hash is the placeholder for the real image`() {
        // The real hash will be set when the
        // Elysium Linux rootfs is built. The
        // placeholder is a non-blank value.
        assertNotNull(ElysiumLinuxDistroListing.CONTENT_HASH)
        assertTrue(
            "expected non-blank content hash, got: ${ElysiumLinuxDistroListing.CONTENT_HASH.value}",
            ElysiumLinuxDistroListing.CONTENT_HASH.value.isNotBlank(),
        )
    }

    @Test
    fun `size in bytes is 800 MB`() {
        // Phase 101 ships the real size (~584 MB);
        // the placeholder was 800 MB.
        assertEquals(612_368_192L, ElysiumLinuxDistroListing.SIZE_BYTES)
    }

    // ============================================================
    // Tags
    // ============================================================

    @Test
    fun `default tags include the elysium-linux marker`() {
        assertTrue(
            "expected 'elysium-linux' in ${ElysiumLinuxDistroListing.TAGS}",
            "elysium-linux" in ElysiumLinuxDistroListing.TAGS,
        )
    }

    @Test
    fun `default tags include the first-party marker`() {
        assertTrue(
            "expected 'first-party' in ${ElysiumLinuxDistroListing.TAGS}",
            "first-party" in ElysiumLinuxDistroListing.TAGS,
        )
    }

    @Test
    fun `default tags include the proprietary marker`() {
        assertTrue(
            "expected 'proprietary' in ${ElysiumLinuxDistroListing.TAGS}",
            "proprietary" in ElysiumLinuxDistroListing.TAGS,
        )
    }

    @Test
    fun `default tags include the runtime layer markers`() {
        for (layer in listOf("mesa-turnip", "box64", "fex", "wine")) {
            assertTrue(
                "expected '$layer' in ${ElysiumLinuxDistroListing.TAGS}",
                layer in ElysiumLinuxDistroListing.TAGS,
            )
        }
    }

    @Test
    fun `default tags do NOT include the legacy debian-based marker`() {
        // The Elysium Linux distro is NOT
        // Debian-based; the tag should not be
        // there.
        assertFalse(
            "expected 'debian-based' NOT in ${ElysiumLinuxDistroListing.TAGS}",
            "debian-based" in ElysiumLinuxDistroListing.TAGS,
        )
    }

    // ============================================================
    // Runtime layers + package manager
    // ============================================================

    @Test
    fun `included runtime layers match the Phase 101 defaults`() {
        // Phase 101 adds elysium-pm to the runtime
        // layers (the package manager is part of
        // the image, not a separate installation).
        assertEquals(
            listOf("native", "mesa-turnip", "box64", "fex", "wine", "elysium-pm"),
            ElysiumLinuxDistroListing.INCLUDED_RUNTIME_LAYERS,
        )
    }

    @Test
    fun `package manager is elysium-pm`() {
        assertEquals("elysium-pm", ElysiumLinuxDistroListing.PACKAGE_MANAGER)
    }

    @Test
    fun `CVE policy summary mentions every severity level`() {
        val summary = ElysiumLinuxDistroListing.CVE_POLICY_SUMMARY
        for (severity in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            assertTrue(
                "expected '$severity' in '$summary'",
                summary.contains(severity),
            )
        }
    }

    // ============================================================
    // Dependencies
    // ============================================================

    @Test
    fun `dependencies list is empty`() {
        // The Elysium Linux distro is self-contained.
        assertEquals(emptyList<String>(), ElysiumLinuxDistroListing.DEPENDENCIES)
    }

    // ============================================================
    // draft() factory
    // ============================================================

    @Test
    fun `draft returns a valid MarketListingDraft`() {
        val draft = ElysiumLinuxDistroListing.draft()
        assertEquals(ElysiumLinuxDistroListing.ID, draft.id)
        assertEquals(ElysiumLinuxDistroListing.NAME, draft.name)
        assertEquals(MarketListingType.DISTRO, draft.type)
        assertEquals(ElysiumLinuxDistroListing.VERSION, draft.version)
        assertEquals(ElysiumLinuxDistroListing.CONTENT_HASH, draft.contentHash)
        assertEquals(ElysiumLinuxDistroListing.SIZE_BYTES, draft.sizeBytes)
        assertEquals(ElysiumLinuxDistroListing.DEPENDENCIES, draft.dependencies)
        assertEquals(ElysiumLinuxDistroListing.TAGS, draft.tags)
    }

    // ============================================================
    // Distinct from the legacy ElysiumVanguardDistroListing
    // ============================================================

    @Test
    fun `the new listing is distinct from the legacy listing`() {
        assertNotEquals(
            ElysiumVanguardDistroListing.PUBLISHER_ID,
            ElysiumLinuxDistroListing.PUBLISHER_ID,
        )
        assertNotEquals(
            ElysiumVanguardDistroListing.ID,
            ElysiumLinuxDistroListing.ID,
        )
        assertNotEquals(
            ElysiumVanguardDistroListing.NAME,
            ElysiumLinuxDistroListing.NAME,
        )
    }

    // ============================================================
    // Sanity: data class equality
    // ============================================================

    @Test
    fun `rootfs version is equal to an equivalent instance`() {
        val a = ElysiumRootfsVersion(1, 0, 0)
        val b = ElysiumRootfsVersion(1, 0, 0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
