package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * The first listing in the Vanguard Market: the
 * **Elysium Vanguard Linux** distribution.
 *
 * Phase 1 ships the **listing** (the typed
 * reference) + the **draft** (the unsigned
 * version). The actual distribution image is
 * built + published in a later phase; the
 * `contentHash` in this listing is the placeholder
 * for the Phase 7+ actual image.
 *
 * The "distribution" here is conceptual: the
 * platform's "Elysium Vanguard Linux" is a
 * curated Debian / Ubuntu base + the platform's
 * own packages (the runtime, the Market client,
 * the security stack). The actual base image
 * comes from `proot-distro` + the platform's
 * overlay; the listing is the signed manifest
 * of the image.
 */
object ElysiumVanguardDistroListing {

    /**
     * The publisher identity (matches the
     * `signatureKeyId` set on the listing).
     */
    const val PUBLISHER_ID: String = "publisher:elysium-vanguard"

    /**
     * The current version of the distribution.
     * Bumped when a new image is published.
     */
    const val VERSION: String = "1.0.0-TITAN"

    /**
     * The distribution's id in the catalog. The
     * format is `<group>:<name>:<version>` (the
     * same format the platform uses for content
     * addressing).
     */
    const val ID: String = "com.elysium.vanguard:distro:$VERSION"

    /**
     * The display name of the distribution.
     */
    const val NAME: String = "Elysium Vanguard Linux"

    /**
     * The placeholder content hash. The actual
     * hash is the SHA-256 of the published image
     * bytes; this is a placeholder until the image
     * is built (Phase 7+).
     */
    val CONTENT_HASH: ContentHash = ContentHash.of("elysium-vanguard-distro-placeholder")

    /**
     * The placeholder size. The actual size is the
     * byte count of the published image.
     */
    const val SIZE_BYTES: Long = 1_500_000_000L // 1.5 GB

    /**
     * The default tags.
     */
    val TAGS: List<String> = listOf(
        "linux",
        "debian-based",
        "elysium",
        "runtime",
        "market-client",
    )

    /**
     * The dependencies (other listings that must
     * be installed first). Phase 1 has no
     * dependencies; the runtime + the market
     * client are bundled in the image.
     */
    val DEPENDENCIES: List<String> = emptyList()

    /**
     * Build a `MarketListingDraft` for the
     * distribution. The publisher + signing key
     * are provided at publish time.
     */
    fun draft(): MarketListingDraft = MarketListingDraft(
        id = ID,
        name = NAME,
        type = MarketListingType.DISTRO,
        version = VERSION,
        contentHash = CONTENT_HASH,
        sizeBytes = SIZE_BYTES,
        dependencies = DEPENDENCIES,
        tags = TAGS,
    )
}
