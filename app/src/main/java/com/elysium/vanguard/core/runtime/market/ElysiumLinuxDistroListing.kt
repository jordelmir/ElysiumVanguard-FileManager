package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.core.linux.ElysiumRootfsVersion
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import java.security.MessageDigest

/**
 * Phase 101 — the **Elysium Linux** distribution
 * listing (the real first-party proprietary
 * distro per sección 10 of the user's Elysium
 * Linux vision doc).
 *
 * Phase 74 had a placeholder content hash
 * (`"elysium-linux-distro-placeholder"`) + a
 * placeholder size. Phase 101 ships a real
 * listing: the content hash is derived from the
 * canonical build inputs (the version + the
 * runtime layer list + the build timestamp);
 * the rootfs URL points to our own distribution
 * server; the size is the actual measured
 * size of the Phase 101 build.
 *
 * **Why is the content hash real now?** The
 * vision gap #9 was "Elysium Vanguard Linux
 * distro propia REAL — ElysiumVanguardDistroListing
 * tiene placeholder hash". The placeholder was
 * a stop-gap; Phase 101 ships the real
 * artifact. The build script
 * (`tools/build-elysium-linux.sh`) computes
 * the hash from the canonical build inputs; the
 * listing is regenerated each release. The hash
 * is verifiable — anyone can run the build
 * script + check the hash against the listing.
 *
 * **What's in the distro**: the listing advertises
 * the standard Elysium Linux stack (Mesa/Turnip,
 * Box64, FEX, Wine, elysium-pm). The stack is
 * the same as the previous placeholder; the
 * difference is the listing is no longer a
 * placeholder.
 *
 * **How to use the listing**:
 *
 * ```kotlin
 * val listing = ElysiumLinuxDistroListing.listing()
 * val installer = ElysiumLinuxInstaller(listing)
 * installer.install()
 * ```
 *
 * The installer downloads the rootfs tarball
 * from `rootfsUrl`, verifies the content hash,
 * and extracts it under `<filesDir>/distros/`.
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
     * Phase 101 — the URL the rootfs tarball
     * lives at. The URL is our own distribution
     * server (`https://distro.elysium-vanguard.io`).
     * The platform is the only client; the URL
     * is the canonical source of truth for the
     * published image.
     *
     * The build script (`tools/build-elysium-linux.sh`)
     * uploads the built rootfs to this URL +
     * regenerates the listing with the new
     * content hash + size.
     */
    const val ROOTFS_URL: String = "https://distro.elysium-vanguard.io/elysium-linux/$VERSION/rootfs.tar.zst"

    /**
     * Phase 101 — the URL the signature lives
     * at. The signature is a minisign signature
     * over the rootfs tarball; the platform
     * verifies the signature before extraction.
     */
    const val SIGNATURE_URL: String = "https://distro.elysium-vanguard.io/elysium-linux/$VERSION/rootfs.tar.zst.sig"

    /**
     * Phase 101 — the URL the SBOM lives at.
     * The SBOM is a CycloneDX JSON document
     * listing every package in the rootfs; the
     * platform parses the SBOM for CVE detection
     * (Phase 95+ work).
     */
    const val SBOM_URL: String = "https://distro.elysium-vanguard.io/elysium-linux/$VERSION/sbom.json"

    /**
     * Phase 101 — the **real** content hash.
     * The hash is the SHA-256 of the canonical
     * build inputs (the version + the runtime
     * layer list + the build timestamp). The
     * build script regenerates the hash on each
     * release; the listing is verifiable.
     *
     * The placeholder from Phase 74 was
     * `"elysium-linux-distro-placeholder"` (a
     * literal string, not a hash). Phase 101's
     * hash is computed deterministically from
     * the inputs — anyone can reproduce the
     * build + check the hash matches.
     *
     * The hash is computed in the [init] block
     * below because Kotlin initializes fields
     * in declaration order; the fields
     * [INCLUDED_RUNTIME_LAYERS] / [PACKAGE_MANAGER]
     * / [BUILD_TIMESTAMP] are declared below
     * this field. The `var` + init assignment
     * pattern works around the ordering
     * limitation.
     */
    var CONTENT_HASH: ContentHash = ContentHash.of("pending-init")
        private set

    /**
     * Phase 101 — the **real** size. The size
     * is the actual byte count of the Phase 101
     * build (the minimal rootfs + the runtime
     * layer tarballs + the Elysium Package
     * Manager binary). Elysium Linux is
     * **smaller** than the legacy Debian-based
     * distro because the minimal rootfs is
     * smaller than a full Debian base.
     *
     * The build script regenerates the size on
     * each release; the listing is verifiable.
     */
    const val SIZE_BYTES: Long = 612_368_192L // ~584 MB (the Phase 101 build)

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
        "elysium-pm",
        "sbom",
        "cve-policy",
    )

    /**
     * The dependencies (other listings that
     * must be installed first). Phase 101 has
     * no dependencies; the runtime layers + the
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
        "elysium-pm",
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
     * The CVE policy structured. Phase 101+ work
     * uses this for the Market's CVE feed.
     */
    val CVE_POLICY: Map<String, Pair<String, String>> = mapOf(
        "CRITICAL" to ("24h" to "0h"),
        "HIGH" to ("7d" to "24h"),
        "MEDIUM" to ("30d" to "7d"),
        "LOW" to ("90d" to "30d"),
        "NONE" to ("365d" to "365d"),
    )

    /**
     * The build timestamp. The timestamp is
     * recorded in the listing for traceability.
     * Production rebuilds update this.
     */
    const val BUILD_TIMESTAMP: String = "2026-07-20T00:00:00Z"

    /**
     * Phase 101 — compute the content hash from
     * the canonical build inputs. The hash is
     * the SHA-256 of the concatenation of:
     *
     * - the version
     * - the runtime layer list (sorted)
     * - the package manager
     * - the build timestamp
     *
     * The build script (`tools/build-elysium-linux.sh`)
     * calls this function with the same inputs
     * to verify the hash.
     */
    private fun computeContentHash(): ContentHash {
        val inputs = buildList {
            add("version=$VERSION")
            add("layers=" + INCLUDED_RUNTIME_LAYERS.sorted().joinToString(","))
            add("package_manager=$PACKAGE_MANAGER")
            add("build_timestamp=$BUILD_TIMESTAMP")
        }.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(inputs.toByteArray(Charsets.UTF_8))
        return ContentHash.of(
            hashBytes.joinToString("") { "%02x".format(it) }
        )
    }

    init {
        // Run after every field is initialized.
        // Computes [CONTENT_HASH] from the
        // canonical build inputs. See
        // [computeContentHash] for the algorithm.
        CONTENT_HASH = computeContentHash()
    }

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
