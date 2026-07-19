package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.core.linux.ElysiumRootfsVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * Phase 74 — the **Elysium Linux** distribution
 * listing.
 *
 * The first Elysium Linux listing. The
 * distribution is the **first-party proprietary
 * distro** per sección 10 of the user's Elysium
 * Linux vision doc — NOT a renamed Ubuntu, NOT a
 * Debian-derivative. The distro is built from
 * scratch with:
 *   - **Mesa/Turnip** preconfigured (Vulkan on
 *     Adreno).
 *   - **Box64/FEX** integrated (x86_64 + x86
 *     user-mode translation).
 *   - **Wine** managed by versions (Windows PE
 *     execution).
 *   - **Elysium Package Manager** (`elysium-pm`)
 *     for signed packages.
 *   - **Reproducible build** (the rootfs is
 *     content-addressed by the canonical form).
 *
 * The legacy `ElysiumVanguardDistroListing`
 * (Phase 60) is the **Debian-based** distribution
 * (the Phase 1 placeholder). This listing is the
 * **Elysium Linux** distribution (the Phase 73+
 * first-party proprietary distro).
 *
 * The two are siblings, not the same:
 *   - `ElysiumVanguardDistroListing` = legacy
 *     Debian-based runtime (Phase 1-9).
 *   - `ElysiumLinuxDistroListing` = first-party
 *     proprietary distro (Phase 73+).
 *
 * A user picks **one** at install time. The
 * Market catalog exposes both; the user can
 * switch (a future Phase 7+ increment for
 * distro migration).
 *
 * Phase 74 ships the **listing** (the typed
 * reference) + the **draft** (the unsigned
 * version). The actual distribution image is
 * built + published in a later phase; the
 * `contentHash` in this listing is the
 * placeholder for the Phase 73 real image.
 */
object ElysiumLinuxDistroListing {

    /**
     * The publisher identity (matches the
     * `signatureKeyId` set on the listing).
     */
    const val PUBLISHER_ID: String = "publisher:elysium-linux"

    /**
     * The current version of the distribution.
     * Bumped when a new image is published.
     *
     * The version follows the Elysium Linux
     * rootfs version (Phase 73 third half,
     * I-73.3.4: `MAJOR.MINOR.PATCH` semver).
     */
    const val VERSION: String = "1.0.0"

    /**
     * The distribution's id in the catalog. The
     * format is `<group>:<name>:<version>` (the
     * same format the platform uses for content
     * addressing).
     */
    const val ID: String = "com.elysium.linux:distro:$VERSION"

    /**
     * The display name of the distribution.
     */
    const val NAME: String = "Elysium Linux"

    /**
     * The rootfs version of the distribution.
     * The version is the **canonical id** of the
     * rootfs tarball (`rootfs-v1.0.0.tar.zst`,
     * per Phase 73 third half I-73.3.4).
     */
    val ROOTFS_VERSION: ElysiumRootfsVersion = ElysiumRootfsVersion(
        major = 1,
        minor = 0,
        patch = 0,
    )

    /**
     * The placeholder content hash. The actual
     * hash is the SHA-256 of the published image
     * bytes; this is a placeholder until the
     * real Elysium Linux rootfs is built.
     */
    val CONTENT_HASH: ContentHash = ContentHash.of("elysium-linux-distro-placeholder")

    /**
     * The placeholder size. The actual size is
     * the byte count of the published image.
     * Elysium Linux is **smaller** than the
     * legacy Debian-based distro because the
     * minimal rootfs + the runtime layer tarballs
     * are smaller than a full Debian base.
     */
    const val SIZE_BYTES: Long = 800_000_000L // 800 MB

    /**
     * The default tags. The tags are how the
     * Market search filters listings; the
     * Elysium Linux tags are distinct from
     * the legacy Debian-based distro's tags
     * so a user can search for "first-party"
     * specifically.
     */
    val TAGS: List<String> = listOf(
        "linux",
        "elysium-linux",
        "first-party",
        "proprietary",
        "arm64",
        "runtime-layers",
        "mesa-turnip",
        "box64",
        "fex",
        "wine",
    )

    /**
     * The dependencies (other listings that
     * must be installed first). Phase 74 has no
     * dependencies; the runtime layers + the
     * package manager are bundled in the image.
     */
    val DEPENDENCIES: List<String> = emptyList()

    /**
     * The runtime layers included in the
     * distribution. The list is the canonical
     * set of layers the Elysium Linux rootfs
     * ships with (per Phase 73 third half
     * I-73.3.1 defaults).
     */
    val INCLUDED_RUNTIME_LAYERS: List<String> = listOf(
        "native",
        "mesa-turnip",
        "box64",
        "fex",
        "wine",
    )

    /**
     * The package manager included in the
     * distribution. The `elysium-pm` binary is
     * the canonical package manager for Elysium
     * Linux (per Phase 73 second half).
     */
    const val PACKAGE_MANAGER: String = "elysium-pm"

    /**
     * The CVE policy of the distribution. The
     * policy is the Elysium Linux standard
     * commitment (per Phase 73 third half
     * I-73.3.5):
     *   - CRITICAL: 24h response, 0h disclosure.
     *   - HIGH: 7d response, 24h disclosure.
     *   - MEDIUM: 30d response, 7d disclosure.
     *   - LOW: 90d response, 30d disclosure.
     *   - NONE: 365d response, 365d disclosure.
     */
    const val CVE_POLICY_SUMMARY: String =
        "CRITICAL=24h/0h, HIGH=7d/24h, MEDIUM=30d/7d, LOW=90d/30d"

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
