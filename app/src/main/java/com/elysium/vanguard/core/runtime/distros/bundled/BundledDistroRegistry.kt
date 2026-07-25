package com.elysium.vanguard.core.runtime.distros.bundled

/**
 * PHASE 140 — the catalog of every bundled distro.
 *
 * The APK ships one rootfs per entry under
 * `assets/distros/<id>.tar.gz`. The hash + size are pinned
 * here; the [BundledRootfsExtractor] verifies them before
 * unpacking.
 *
 * Today the catalog is a single entry (Alpine minirootfs
 * 3.20.3 for aarch64). Future bundled distros — Debian
 * slim, Ubuntu core, Arch ARM, etc. — are added here as
 * additional entries.
 *
 * IMPORTANT: when adding a new bundled distro, the
 * `sha256` MUST be the SHA-256 of the **uncompressed tar
 * bytes inside the gz** — i.e. `sha256sum
 * <asset.tar.gz>` in the order it is on disk. The
 * extractor reads the bytes off `AssetManager.open(...)`
 * and hashes that exact byte stream. Do NOT use the
 * decompressed tar hash.
 */
object BundledDistroRegistry {

    /**
     * Alpine minirootfs 3.20.3 for aarch64 (ARM64).
     *
     * Source: `https://dl-cdn.alpinelinux.org/alpine/v3.20/
     * releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz`
     * (decompressed to `.tar` for the asset — see the
     * build.gradle note on `noCompress`).
     *
     * Pinned: 2026-07-25.
     * Size:   9 113 600 bytes (uncompressed tar).
     * SHA-256: `909cd5ea844eecebecee4b5e80e98709c7cfbb2e95748c787c211bf1698aaae2`
     *
     * Why Alpine first? It is the smallest *real* distro
     * that ships with a package manager (`apk`), a working
     * shell (`/bin/ash` via busybox), and a real Linux
     * userspace — about 4 MB compressed vs Debian's 30+ MB
     * and Ubuntu's 60+ MB. Once the user has a working
     * shell inside a real distro, they can `apk add` any
     * other package they need.
     */
    val ALPINE_MINI_AARCH64: BundledDistro = BundledDistro(
        id = "alpine-mini",
        displayName = "Alpine Linux (mini, aarch64)",
        family = "alpine",
        architecture = "aarch64",
        version = "3.20.3",
        assetPath = "distros/alpine-mini-aarch64.tar",
        sha256 = "909cd5ea844eecebecee4b5e80e98709c7cfbb2e95748c787c211bf1698aaae2",
        sizeBytes = 9_113_600L,
    )

    /**
     * The full catalog, in display order. Today the order
     * is "smallest first" so the user gets a working shell
     * in seconds; the catalog can later sort by family
     * (alpine, debian, arch, …) without changing the
     * registry contract.
     */
    val ALL: List<BundledDistro> = listOf(
        ALPINE_MINI_AARCH64,
    )

    fun findById(id: String): BundledDistro? = ALL.firstOrNull { it.id == id }
}
