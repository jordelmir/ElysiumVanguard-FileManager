package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * The first batch of community distros in
 * the Vanguard Market. Each distro is a
 * `MarketListingDraft` ready to be published
 * by the `LocalMarketPublisher`.
 *
 * Phase 1 listings are **placeholders**: the
 * `contentHash` and `sizeBytes` are stand-ins
 * for the actual image bytes. The Phase 7+
 * build pipeline produces the real image +
 * updates the constants + re-publishes the
 * listing.
 *
 * The distros cover the most common Linux
 * families:
 *   - Debian-based (Ubuntu, Debian)
 *   - RPM-based (Fedora, openSUSE)
 *   - Source-based (Arch, Void, Alpine)
 *   - Declarative (NixOS)
 *
 * Each distro has its own runtime profile:
 *   - Ubuntu: proot-friendly, large ecosystem
 *   - Fedora: bleeding-edge packages
 *   - Arch: rolling release, minimal base
 *   - openSUSE: stable + Tumbleweed
 *   - Debian: ultra-stable, server focus
 *   - Alpine: musl, container focus
 *   - Void: musl + runit, independent
 *   - NixOS: declarative, reproducible
 */
object CommunityDistros {

    val ubuntu_24_04 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:ubuntu-24.04:1.0.0",
        name = "Ubuntu 24.04 LTS",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("ubuntu-24.04-lts-placeholder"),
        sizeBytes = 1_800_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "debian-based", "lts", "ubuntu", "proot-friendly"),
    )

    val fedora_41 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:fedora-41:1.0.0",
        name = "Fedora 41",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("fedora-41-placeholder"),
        sizeBytes = 1_600_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "rpm", "fedora", "bleeding-edge"),
    )

    val arch_linux = MarketListingDraft(
        id = "com.elysium.vanguard:distro:arch:1.0.0",
        name = "Arch Linux (Rolling)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("arch-linux-placeholder"),
        sizeBytes = 900_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "arch", "rolling-release", "minimal"),
    )

    val opensuse_tumbleweed = MarketListingDraft(
        id = "com.elysium.vanguard:distro:opensuse-tumbleweed:1.0.0",
        name = "openSUSE Tumbleweed (Rolling)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("opensuse-tumbleweed-placeholder"),
        sizeBytes = 1_400_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "rpm", "opensuse", "rolling-release"),
    )

    val debian_12 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:debian-12:1.0.0",
        name = "Debian 12 (Bookworm)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("debian-12-bookworm-placeholder"),
        sizeBytes = 1_500_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "debian", "stable", "server"),
    )

    val alpine_3_20 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:alpine-3.20:1.0.0",
        name = "Alpine Linux 3.20",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("alpine-3.20-placeholder"),
        sizeBytes = 250_000_000L, // Alpine is tiny
        dependencies = emptyList(),
        tags = listOf("linux", "musl", "alpine", "container", "minimal"),
    )

    val void_linux = MarketListingDraft(
        id = "com.elysium.vanguard:distro:void-linux:1.0.0",
        name = "Void Linux (Rolling)",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("void-linux-placeholder"),
        sizeBytes = 700_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "musl", "void", "runit", "independent"),
    )

    val nixos_24_05 = MarketListingDraft(
        id = "com.elysium.vanguard:distro:nixos-24.05:1.0.0",
        name = "NixOS 24.05",
        type = MarketListingType.DISTRO,
        version = "1.0.0",
        contentHash = ContentHash.of("nixos-24.05-placeholder"),
        sizeBytes = 1_700_000_000L,
        dependencies = emptyList(),
        tags = listOf("linux", "nix", "nixos", "declarative", "reproducible"),
    )

    /**
     * The 8 community distros in the Phase 1
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
}
