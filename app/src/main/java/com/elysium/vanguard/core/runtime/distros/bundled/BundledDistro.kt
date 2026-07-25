package com.elysium.vanguard.core.runtime.distros.bundled

/**
 * PHASE 140 — a single bundled distro.
 *
 * A bundled distro is one whose rootfs is shipped in the APK
 * under `assets/distros/<id>.tar.gz`. The [BundledDistroRegistry]
 * enumerates the catalog; the [BundledRootfsExtractor] unpacks
 * the asset to `filesDir/distros/<id>/` and verifies its hash
 * on the first use.
 *
 * The data class is intentionally pure — no Android `Context`,
 * no `AssetManager` reference, no `filesDir`. The
 * [BundledRootfsExtractor] is the side-effecting layer; this
 * struct is what the catalog publishes to the rest of the
 * platform.
 *
 * @property id            Stable lowercase id, e.g. "alpine-mini".
 *                        Also the asset filename prefix.
 * @property displayName   Human-readable name shown in the
 *                        distro catalog.
 * @property family        "alpine", "debian", "arch", … —
 *                        the upstream family the rootfs came
 *                        from. Used by the launcher to set
 *                        distro-specific env vars.
 * @property architecture  "aarch64" / "armv7" / "x86_64" / …
 * @property version       The upstream release version, e.g.
 *                        "3.20.3". Not the Elysium build
 *                        version (which lives in the manifest).
 * @property assetPath     Path inside the APK `assets/` tree,
 *                        e.g. "distros/alpine-mini-aarch64.tar.gz".
 * @property sha256        SHA-256 of the asset bytes in hex.
 *                        The extractor recomputes it after
 *                        loading from `AssetManager` and
 *                        refuses to unpack if the hash does
 *                        not match (defense-in-depth against
 *                        corrupted downloads / asset
 *                        modifications).
 * @property sizeBytes     Compressed asset size in bytes
 *                        (the `.tar.gz` size, not the
 *                        extracted rootfs size).
 */
data class BundledDistro(
    val id: String,
    val displayName: String,
    val family: String,
    val architecture: String,
    val version: String,
    val assetPath: String,
    val sha256: String,
    val sizeBytes: Long,
)
