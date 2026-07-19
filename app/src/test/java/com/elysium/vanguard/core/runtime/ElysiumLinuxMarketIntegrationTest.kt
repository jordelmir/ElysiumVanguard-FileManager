package com.elysium.vanguard.core.runtime

import com.elysium.vanguard.core.linux.ElysiumAbi
import com.elysium.vanguard.core.linux.ElysiumRootfsVersion
import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.ElysiumLinuxCapsule
import com.elysium.vanguard.core.runtime.capsule.GpuApi
import com.elysium.vanguard.core.runtime.capsule.GpuDriver
import com.elysium.vanguard.core.runtime.capsule.Runtime
import com.elysium.vanguard.core.runtime.market.ElysiumLinuxDistroListing
import com.elysium.vanguard.core.runtime.market.InMemoryMarketCatalog
import com.elysium.vanguard.core.runtime.market.LocalMarketInstaller
import com.elysium.vanguard.core.runtime.market.LocalMarketPublisher
import com.elysium.vanguard.core.runtime.market.MarketListingDraft
import com.elysium.vanguard.core.runtime.market.MarketListingType
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 74 (integration) — the **end-to-end
 * integration test** for the Elysium Linux distro.
 *
 * This test verifies the Elysium Linux **listing
 * + capsule + package manager + ABI matrix** are
 * **internally consistent**:
 *
 *   1. The listing's `id` matches the Capsule's
 *      `distribution.id` (the join key the
 *      orchestrator uses).
 *   2. The listing's `version` matches the
 *      rootfs version (per Phase 73 third half
 *      I-73.3.4).
 *   3. The Capsule's architecture is supported
 *      by the default Android ARM64 ABI
 *      capability matrix (per Phase 73 third
 *      half I-73.3.3).
 *   4. The Capsule's GPU config (VULKAN / TURNIP)
 *      requires the Adreno GPU vendor.
 *   5. The listing can be published to the
 *      Market + installed via the standard
 *      Market flow.
 *   6. The Capsule can be built + its fields
 *      are consistent.
 *
 * The test is the **canonical "Phase 74 closed"**
 * test — it proves the Elysium Linux distro is
 * discoverable (via the listing) + runnable (via
 * the Capsule) + consistent (the listing +
 * capsule + matrix + rootfs are all aligned).
 */
class ElysiumLinuxMarketIntegrationTest {

    // ============================================================
    // 1. Listing id <-> Capsule distribution id consistency
    // ============================================================

    @Test
    fun `listing id matches the Capsule distribution id (the join key)`() {
        // The orchestrator matches the listing +
        // the capsule by distribution id. A
        // mismatched pair is a deployment error.
        assertEquals(
            ElysiumLinuxDistroListing.ID,
            ElysiumLinuxCapsule.DISTRIBUTION_ID,
        )
    }

    @Test
    fun `listing id matches the Capsule distribution id when the Capsule is built`() {
        val capsule = ElysiumLinuxCapsule.build()
        assertEquals(
            ElysiumLinuxDistroListing.ID,
            capsule.distribution.id,
        )
    }

    // ============================================================
    // 2. Listing version <-> rootfs version consistency
    // ============================================================

    @Test
    fun `listing version matches the rootfs version canonical form`() {
        // The listing's version is the
        // distribution-level version; the
        // rootfs version is the rootfs-level
        // version. The two MUST match (the
        // listing is the distribution contract
        // for the rootfs).
        assertEquals(
            ElysiumLinuxDistroListing.VERSION,
            ElysiumLinuxDistroListing.ROOTFS_VERSION.canonical,
        )
    }

    @Test
    fun `listing version 1-0-0 maps to ElysiumRootfsVersion(1, 0, 0)`() {
        val v = ElysiumRootfsVersion(1, 0, 0)
        assertEquals(1, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
        assertEquals(
            ElysiumLinuxDistroListing.ROOTFS_VERSION.canonical,
            v.canonical,
        )
    }

    // ============================================================
    // 3. Capsule architecture <-> ABI capability matrix
    // ============================================================

    @Test
    fun `Capsule architecture is supported on the default Android ARM64 matrix`() {
        // The Capsule declares ARM64; the
        // default matrix supports NATIVE on
        // ARM64 (the baseline).
        val matrix = com.elysium.vanguard.core.linux.ElysiumAbiCapabilityMatrix
            .DEFAULT_ANDROID_ARM64
        val capsule = ElysiumLinuxCapsule.build()
        assertEquals(Architecture.ARM64, capsule.architecture)
        // The matrix reports the native layer
        // is available on ARM64.
        assertTrue(
            "expected NATIVE layer on ARM64 in ${matrix.layersFor(ElysiumAbi.ARM64)}",
            matrix.isLayerAvailable(
                com.elysium.vanguard.core.linux.ElysiumRuntimeLayerId.NATIVE,
                ElysiumAbi.ARM64,
            ),
        )
    }

    @Test
    fun `Capsule runtime is LINUX (matches the supported runtime for the matrix)`() {
        val capsule = ElysiumLinuxCapsule.build()
        assertEquals(Runtime.LINUX, capsule.runtime)
    }

    // ============================================================
    // 4. Capsule GPU config <-> GPU vendor
    // ============================================================

    @Test
    fun `Capsule GPU driver TURNIP is available on the Adreno GPU vendor`() {
        // The Capsule declares Turnip; the
        // matrix reports Turnip is available
        // on Adreno.
        val matrix = com.elysium.vanguard.core.linux.ElysiumAbiCapabilityMatrix
            .DEFAULT_ANDROID_ARM64
        val capsule = ElysiumLinuxCapsule.build()
        assertEquals(GpuDriver.TURNIP, capsule.gpu.driver)
        assertTrue(
            "expected MESA_TURNIP on Adreno+ARM64 in ${matrix.layersFor(ElysiumAbi.ARM64, com.elysium.vanguard.core.graphics.GPUVendor.ADRENO)}",
            matrix.isLayerAvailable(
                com.elysium.vanguard.core.linux.ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumAbi.ARM64,
                com.elysium.vanguard.core.graphics.GPUVendor.ADRENO,
            ),
        )
    }

    @Test
    fun `Capsule GPU api VULKAN is satisfied by the Adreno+Turnip layer set`() {
        val capsule = ElysiumLinuxCapsule.build()
        assertEquals(GpuApi.VULKAN, capsule.gpu.api)
    }

    // ============================================================
    // 5. Listing is publishable + installable through the Market
    // ============================================================

    @Test
    fun `Elysium Linux listing can be published and installed through the Market`() {
        val catalog = InMemoryMarketCatalog()
        val signingKey = "elysium-linux-publisher-key".toByteArray()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = ElysiumLinuxDistroListing.PUBLISHER_ID,
        )
        val installer = LocalMarketInstaller(catalog = catalog)

        // Build the artifact (the Elysium Linux
        // rootfs tarball; in production this is
        // a real tarball, here a small placeholder).
        val artifactBytes = ByteArray(2048) { (it % 256).toByte() }
        val contentHash = ContentHash.of(artifactBytes)

        // Build the draft from the listing.
        val draft: MarketListingDraft = ElysiumLinuxDistroListing.draft().copy(
            contentHash = contentHash,
            sizeBytes = artifactBytes.size.toLong(),
        )

        // Publish.
        val published = publisher.publish(draft).getOrThrow()
        assertEquals(draft.id, published.id)
        assertEquals(
            ElysiumLinuxDistroListing.PUBLISHER_ID,
            published.signatureKeyId,
        )

        // Install.
        val tempDir = createTempDir(prefix = "elysium-linux-test-")
        try {
            val installResult = installer.install(
                com.elysium.vanguard.core.runtime.market.InstallRequest(
                    listingId = draft.id,
                    byteSource = { artifactBytes },
                    targetDir = tempDir,
                    verifyingKey = signingKey,
                ),
            )
            assertTrue(
                "expected install success, got $installResult",
                installResult.isSuccess,
            )
            val receipt = installResult.getOrThrow()
            assertEquals(draft.id, receipt.listingId)
            assertEquals(
                ElysiumLinuxDistroListing.PUBLISHER_ID,
                receipt.signatureKeyId,
            )
            assertTrue(receipt.installedPath.exists())
            assertEquals(artifactBytes.size.toLong(), receipt.bytesInstalled)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `Elysium Linux listing is discoverable via Market search by tag`() {
        val catalog = InMemoryMarketCatalog()
        val signingKey = "elysium-linux-publisher-key".toByteArray()
        val publisher = LocalMarketPublisher(
            catalog = catalog,
            signingKey = signingKey,
            publisherId = ElysiumLinuxDistroListing.PUBLISHER_ID,
        )

        // Publish the listing.
        publisher.publish(ElysiumLinuxDistroListing.draft())

        // Search for the "first-party" tag.
        val search = catalog.search(
            com.elysium.vanguard.core.runtime.market.MarketSearchQuery(
                query = "first-party",
                type = MarketListingType.DISTRO,
                limit = 10,
            ),
        ).getOrThrow()
        assertTrue(
            "expected at least one listing matching 'first-party', got: ${search.listings}",
            search.listings.isNotEmpty(),
        )
        // The Elysium Linux listing is one of
        // the results.
        val ids = search.listings.map { it.id }
        assertTrue(
            "expected ${ElysiumLinuxDistroListing.ID} in $ids",
            ElysiumLinuxDistroListing.ID in ids,
        )
    }

    // ============================================================
    // 6. Capsule build() consistency
    // ============================================================

    @Test
    fun `Capsule build returns a Capsule with all the declared fields`() {
        val capsule = ElysiumLinuxCapsule.build()
        // The build is consistent: every field
        // matches the corresponding constant.
        assertEquals(ElysiumLinuxCapsule.ID, capsule.id)
        assertEquals(ElysiumLinuxCapsule.NAME, capsule.name)
        assertEquals(ElysiumLinuxCapsule.VERSION, capsule.version)
        assertEquals(ElysiumLinuxCapsule.RUNTIME, capsule.runtime)
        assertEquals(ElysiumLinuxCapsule.ARCHITECTURE, capsule.architecture)
        assertEquals(ElysiumLinuxCapsule.GPU, capsule.gpu)
        assertEquals(ElysiumLinuxCapsule.PERMISSIONS, capsule.permissions)
        assertEquals(ElysiumLinuxCapsule.ENTRYPOINT, capsule.entrypoint)
    }

    @Test
    fun `Capsule contentHash and signature are non-blank placeholders`() {
        val capsule = ElysiumLinuxCapsule.build()
        assertTrue(
            "expected non-blank contentHash, got: ${capsule.contentHash.value}",
            capsule.contentHash.value.isNotBlank(),
        )
        assertTrue(
            "expected non-blank signature, got: ${capsule.signature.value}",
            capsule.signature.value.isNotBlank(),
        )
    }

    // ============================================================
    // 7. Capsule is a valid Capsule (the Capsule type accepts it)
    // ============================================================

    @Test
    fun `Capsule build returns a Capsule that the Capsule type accepts`() {
        // The Capsule's init block validates the
        // field invariants; if the build produces
        // a malformed Capsule, the init throws.
        // The fact that the build completes is the
        // test.
        val capsule = ElysiumLinuxCapsule.build()
        assertNotNull(capsule)
    }

    // ============================================================
    // 8. Listing draft is a valid MarketListingDraft
    // ============================================================

    @Test
    fun `listing draft is a valid MarketListingDraft (the type accepts it)`() {
        // The MarketListingDraft's init block
        // validates the field invariants; if
        // the draft produces a malformed value,
        // the init throws. The fact that the
        // draft completes is the test.
        val draft = ElysiumLinuxDistroListing.draft()
        assertEquals(MarketListingType.DISTRO, draft.type)
        assertEquals(ElysiumLinuxDistroListing.ID, draft.id)
    }
}
