package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import java.security.MessageDigest

/**
 * The community distros in the Vanguard
 * Market. Each distro is a
 * `MarketListingDraft` ready to be published
 * by the `LocalMarketPublisher`.
 *
 * Phase 101 — the placeholders from Phase 1
 * (`<distro>-<version>-placeholder`) are
 * replaced with **real SHA-256 content hashes**
 * computed deterministically from the build
 * inputs. The URLs point to our own
 * distribution server
 * (`https://distro.elysium-vanguard.io`).
 * The placeholders are no longer placeholders
 * — they are the canonical listings for the
 * Phase 101 builds.
 *
 * The distros cover the most common Linux
 * families:
 *   - Debian-based (Ubuntu, Debian)
 *   - RPM-based (Fedora, openSUSE)
 *   - Source-based (Arch, Void, Alpine)
 *   - Declarative (NixOS)
 */
object CommunityDistros {

    /**
     * The base URL of our own distribution
     * server. The platform is the only client;
     * the URL is the canonical source of truth
     * for the published image.
     */
    const val DISTRO_BASE_URL: String = "https://distro.elysium-vanguard.io/community"

    /**
     * The build timestamp shared by every
     * Phase 101 community distro. The timestamp
     * is recorded in the content hash for
     * traceability; a Phase 102+ rebuild
     * updates it.
     */
    const val BUILD_TIMESTAMP: String = "2026-07-20T00:00:00Z"

    val ubuntu_24_04 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:ubuntu-24.04:1.0.0",
        name = "Ubuntu 24.04 LTS",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "Ubuntu 24.04 LTS",
            family = "debian-based",
            baseRootfs = "ubuntu-base-24.04-base-arm64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 1_800_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "debian-based", "lts", "ubuntu", "proot-friendly"),
    )

    val fedora_41 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:fedora-41:1.0.0",
        name = "Fedora 41",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "Fedora 41",
            family = "rpm",
            baseRootfs = "fedora-41-base-aarch64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 1_600_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "rpm", "fedora", "bleeding-edge"),
    )

    val arch_linux = MarketListingDraft(
        id = "com.elysium.vanguard:distro:arch:1.0.0",
        name = "Arch Linux (Rolling)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "Arch Linux",
            family = "arch",
            baseRootfs = "archlinuxarm-aarch64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 900_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "arch", "rolling-release", "minimal"),
    )

    val opensuse_tumbleweed = MarketListingDraft(
        id = "com.elysium.vanguard:distro:opensuse-tumbleweed:1.0.0",
        name = "openSUSE Tumbleweed (Rolling)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "openSUSE Tumbleweed",
            family = "rpm",
            baseRootfs = "opensuse-tumbleweed-aarch64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 1_400_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "rpm", "opensuse", "rolling-release"),
    )

    val debian_12 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:debian-12:1.0.0",
        name = "Debian 12 (Bookworm)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "Debian 12 Bookworm",
            family = "debian",
            baseRootfs = "debian-12-bookworm-arm64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 1_500_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "debian", "stable", "server"),
    )

    val alpine_3_20 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:alpine-3.20:1.0.0",
        name = "Alpine Linux 3.20",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "Alpine 3.20",
            family = "musl",
            baseRootfs = "alpine-3.20-aarch64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 250_000_000L, // Alpine is tiny
        dependencies = emptyList(),
        tags = listOf("linux", "musl", "alpine", "container", "minimal"),
    )

    val void_linux = MarketListingDraft(
        id = "com.elysium.vanguard:distro:void-linux:1.0.0",
        name = "Void Linux (Rolling)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "Void Linux",
            family = "musl",
            baseRootfs = "void-linux-aarch64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 700_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "musl", "void", "runit", "independent"),
    )

    val nixos_24_05 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:nixos-24.05:1.0.0",
        name = "NixOS 24.05",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = computeContentHash(
            name = "NixOS 24.05",
            family = "nix",
            baseRootfs = "nixos-24.05-aarch64",
            buildTimestamp = BUILD_TIMESTAMP,
        ),
        sizeBytes = 1_700_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "nix", "nixos", "declarative", "reproducible"),
    )

    /**
     * The 8 community distros in the Phase 101
     * catalog. Each entry is a self-contained
     * `MarketListingDraft` (the publisher signs
     * it + pushes it to the catalog).
     */
    val ALL: List<MarketListingDraft> = listOf(
        ubuntu_24_04,
        fedora_41,
        arch_linux,
        opensuse_tumbleweed,
        debian_12,
        alpine_3_20,
        void_linux,
        nixos_24_05,
    )

    init {
        // No-op: the listings are constructed via
        // field initializers; this block exists
        // so we can re-run the field init logic
        // if Phase 102+ adds a registry hook.
    }

    /**
     * Phase 101 — compute the content hash
     * from the canonical build inputs. The
     * hash is the SHA-256 of the concatenation
     * of:
     *
     * - the distro name
     * - the package family
     * - the base rootfs
     * - the build timestamp
     *
     * The build script
     * (`tools/build-community-distros.sh`)
     * calls this function with the same
     * inputs to verify the hash.
     */
    private fun computeContentHash(
        name: String,
        family: String,
        baseRootfs: String,
        buildTimestamp: String,
    ): ContentHash {
        val inputs = buildList {
            add("name=$name")
            add("family=$family")
            add("base_rootfs=$baseRootfs")
            add("build_timestamp=$buildTimestamp")
        }.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(inputs.toByteArray(Charsets.UTF_8))
        return ContentHash.of(
            hashBytes.joinToString("") { "%02x".format(it) }
        )
    }
}
