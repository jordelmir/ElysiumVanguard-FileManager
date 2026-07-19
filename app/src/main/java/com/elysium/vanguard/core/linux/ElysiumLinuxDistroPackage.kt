package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 74 (third half) — the **Elysium Linux
 * meta-package**, the typed
 * [ElysiumPackageManifest] for
 * `com.elysium.linux.distro`.
 *
 * The meta-package is the **install path** for
 * the Elysium Linux distro. A user installs the
 * distro with:
 *
 * ```
 * pm install com.elysium.linux.distro@1.0.0
 * ```
 *
 * The package manager:
 *   1. Fetches the manifest from the repository.
 *   2. Verifies the manifest's signature.
 *   3. Resolves the transitive dependencies —
 *      the 5 runtime layer packages + the
 *      package manager itself.
 *   4. Installs the deps + the meta-package in
 *      dependency order (topological).
 *   5. Records the installed set.
 *
 * The meta-package's own files are:
 *   - `/etc/elysium/elysium-linux.conf` — the
 *     rootfs config (the distro's identifier +
 *     version).
 *   - `/etc/elysium/package-sources.list` —
 *     the Elysium Linux repository URLs.
 *   - `/usr/share/elysium-linux/README` — the
 *     user-facing README (a one-page summary
 *     of what's installed).
 *
 * The meta-package is **not** the rootfs itself
 * (the rootfs is a separate content bundle, the
 * `rootfs-v1.0.0.tar.zst` from
 * `ElysiumRootfsVersion`). The meta-package is
 * the **top-level install contract**; the
 * rootfs is a separate download.
 *
 * The meta-package is the **bridge** between the
 * distro's Market contract (Phase 74 first
 * half — `ElysiumLinuxDistroListing`) and the
 * package manager (Phase 73 second half —
 * `ElysiumPackageManager`). The user discovers
 * the distro through the Market + installs it
 * through the package manager.
 */
object ElysiumLinuxDistroPackage {

    /**
     * The meta-package's name. The name is a
     * reverse-DNS identifier following the
     * Phase 73 first half `PACKAGE_NAME_PATTERN`.
     */
    const val NAME: String = "com.elysium.linux.distro"

    /**
     * The meta-package's version. The version
     * follows the rootfs version (per Phase 73
     * third half I-73.3.4).
     */
    const val VERSION: String = "1.0.0"

    /**
     * The meta-package's description. The
     * description is the user-facing summary
     * the package manager shows in `pm list`.
     */
    const val DESCRIPTION: String =
        "Elysium Linux first-party proprietary distro. " +
            "Bundles the native ARM64 runtime + Mesa Turnip " +
            "(Vulkan on Adreno) + Box64 (x86_64 translator) + " +
            "FEX (x86 translator) + Wine (Windows PE) + the " +
            "Elysium package manager."

    /**
     * The native ARM64 runtime layer package.
     * The native layer is the **baseline** —
     * every Elysium Linux install has it.
     */
    private val nativeLayerDep: ElysiumPackageDependency =
        ElysiumPackageDependency(
            packageName = "com.elysium.runtime.native",
            constraint = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(1, 0, 0),
            ),
        )

    /**
     * The Mesa Turnip runtime layer package.
     * Turnip is Adreno-specific; the dependency
     * is per-ABI = ARM64.
     */
    private val mesaTurnipLayerDep: ElysiumPackageDependency =
        ElysiumPackageDependency(
            packageName = "com.elysium.runtime.mesa-turnip",
            constraint = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(24, 1, 0),
            ),
            abi = ElysiumAbi.ARM64,
        )

    /**
     * The Box64 runtime layer package.
     */
    private val box64LayerDep: ElysiumPackageDependency =
        ElysiumPackageDependency(
            packageName = "com.elysium.runtime.box64",
            constraint = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(0, 3, 2),
            ),
        )

    /**
     * The FEX runtime layer package.
     */
    private val fexLayerDep: ElysiumPackageDependency =
        ElysiumPackageDependency(
            packageName = "com.elysium.runtime.fex",
            constraint = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(2404, 0, 0),
            ),
        )

    /**
     * The Wine runtime layer package.
     */
    private val wineLayerDep: ElysiumPackageDependency =
        ElysiumPackageDependency(
            packageName = "com.elysium.runtime.wine",
            constraint = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(9, 0, 0),
            ),
        )

    /**
     * The Elysium package manager package.
     * The package manager is the **meta-package
     * manager itself** (the `elysium-pm` binary
     * from Phase 73 second half).
     */
    private val packageManagerDep: ElysiumPackageDependency =
        ElysiumPackageDependency(
            packageName = "com.elysium.pkgmgr",
            constraint = VersionConstraint(
                kind = ConstraintKind.GTE,
                version = ElysiumPackageVersion(1, 0, 0),
            ),
        )

    /**
     * The meta-package's dependencies. The
     * dependencies are the 5 runtime layer
     * packages + the package manager itself.
     *
     * The list is in **install order** (a
     * topological order: deps are listed
     * before the meta-package that depends on
     * them).
     */
    val DEPENDENCIES: List<ElysiumPackageDependency> = listOf(
        nativeLayerDep,
        mesaTurnipLayerDep,
        box64LayerDep,
        fexLayerDep,
        wineLayerDep,
        packageManagerDep,
    )

    /**
     * The meta-package's capabilities. The
     * capabilities are the **machine-readable
     * features** the distro provides.
     *
     * The list mirrors the runtime layers
     * included in the distro.
     */
    val PROVIDES: List<String> = listOf(
        "elysium-linux",
        "elysium-linux-distro",
        "elysium-runtime-native",
        "elysium-runtime-mesa-turnip",
        "elysium-runtime-box64",
        "elysium-runtime-fex",
        "elysium-runtime-wine",
    )

    /**
     * The meta-package's files. The files are
     * the **distro-level config + metadata** that
     * the meta-package installs (the runtime
     * layer tarballs are NOT here — they are
     * installed by their respective packages).
     */
    val FILES: List<ElysiumPackageFile> = listOf(
        ElysiumPackageFile(
            installPath = "/etc/elysium/elysium-linux.conf",
            contentHash = ContentHash.of(
                "elysium-linux.conf.placeholder",
            ),
        ),
        ElysiumPackageFile(
            installPath = "/etc/elysium/package-sources.list",
            contentHash = ContentHash.of(
                "elysium-linux-package-sources.placeholder",
            ),
        ),
        ElysiumPackageFile(
            installPath = "/usr/share/elysium-linux/README",
            contentHash = ContentHash.of(
                "elysium-linux-readme.placeholder",
            ),
        ),
    )

    /**
     * The meta-package's content hash. The hash
     * is the placeholder for the real package
     * (a non-blank value; the real hash is set
     * when the package is built).
     */
    val CONTENT_HASH: ContentHash = ContentHash.of(
        "elysium-linux-distro.placeholder",
    )

    /**
     * The default signing key. The production
     * key is published with the Elysium Linux
     * distribution team's certificate.
     */
    const val DEFAULT_SIGNING_KEY: String = "elysium-linux-publisher-key"

    /**
     * Build the [ElysiumPackageManifest] for
     * the meta-package. The build is a **typed
     * factory** (no I/O, no Android dependencies).
     * The publisher calls `manifest(signingKey)`
     * to get the unsigned manifest, then signs +
     * publishes it.
     */
    fun manifest(
        signingKey: String = DEFAULT_SIGNING_KEY,
    ): ElysiumPackageManifest {
        val v = ElysiumPackageVersion.parse(VERSION).getOrThrow()
        val unsigned = ElysiumPackageManifest(
            name = NAME,
            version = v,
            abi = ElysiumAbi.ARM64,
            description = DESCRIPTION,
            dependencies = DEPENDENCIES,
            provides = PROVIDES,
            files = FILES,
            scripts = ElysiumPackageScripts.NONE,
            contentHash = CONTENT_HASH,
            signature = Signature("placeholder"),
        )
        return unsigned.copy(
            signature = Signature.sign(
                payload = unsigned.canonicalForm.toByteArray(Charsets.UTF_8),
                key = signingKey.toByteArray(),
            ),
        )
    }
}
