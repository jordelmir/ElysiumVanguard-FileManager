package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * Phase 75 (Elysium Linux vision alignment) — the
 * **Elysium Linux Default Repository**, the
 * pre-populated [ElysiumRepository] for the
 * Elysium Linux distro.
 *
 * Per the user's Elysium Vanguard Universal
 * Platform vision: the platform ships with a
 * **first-party proprietary distro** (Elysium
 * Linux) that the user can install out of the
 * box with `pm install com.elysium.linux.distro`.
 *
 * The default repository is the **canonical
 * source** of the Elysium Linux packages:
 *   - The **meta-package** (`com.elysium.linux.distro`)
 *     — installs the distro + bundles the runtime
 *     layers as dependencies (per Phase 74 third
 *     half).
 *   - The **runtime layer packages** — one
 *     [ElysiumPackageManifest] per default
 *     runtime layer (Native / MesaTurnip / Box64 /
 *     Fex / Wine, per Phase 73 third half I-73.3.1
 *     `ElysiumRuntimeLayerDefaults`).
 *   - The **package manager package**
 *     (`com.elysium.pkgmgr`) — the `elysium-pm`
 *     binary.
 *
 * The default repository is built from the
 * [ElysiumLinuxDistroPackage.manifest] + the
 * [ElysiumRuntimeLayerDefaults.ALL] +
 * [packageManagerPackage]. The repository is
 * **complete** (every declared dependency is
 * present); the user can install the distro
 * with no additional configuration.
 *
 * The default repository is the **single source
 * of truth** for the Elysium Linux packages. A
 * future Phase 73+ increment can add a
 * `HttpElysiumRepository` (a remote repository
 * that fetches packages from the Elysium Linux
 * distribution server) + a `MultiElysiumRepository`
 * that aggregates multiple repositories.
 *
 * The default repository is **pure-domain**
 * (no I/O, no Android dependencies). The test
 * implementation is an in-memory map; the
 * production implementation is a remote HTTP
 * server (a future Phase 7+ increment).
 */
object ElysiumLinuxDefaultRepository {

    /**
     * The default signing key. The real
     * production key is published with the
     * Elysium Linux distribution team's
     * certificate.
     */
    const val DEFAULT_SIGNING_KEY: String =
        ElysiumLinuxDistroPackage.DEFAULT_SIGNING_KEY

    /**
     * Build the **default Elysium Linux
     * repository**. The build is a **typed
     * factory** (no I/O, no Android dependencies).
     * The publisher calls `build()` to get a
     * ready-to-use repository.
     *
     * The repository is pre-populated with:
     *   - The `com.elysium.linux.distro`
     *     meta-package.
     *   - 5 runtime layer packages (one per
     *     default layer).
     *   - The `com.elysium.pkgmgr` package
     *     manager.
     *
     * Total: 7 packages.
     */
    fun build(
        signingKey: String = DEFAULT_SIGNING_KEY,
    ): ElysiumRepository {
        val repo = InMemoryElysiumRepository()
        // Add the meta-package.
        addManifest(repo, ElysiumLinuxDistroPackage.manifest(signingKey), signingKey)
        // Add the runtime layer packages (one
        // per default layer; built from the
        // layer's name + version + ABI).
        for (layer in ElysiumRuntimeLayerDefaults.ALL) {
            val layerManifest = buildRuntimeLayerManifest(layer, signingKey)
            addManifest(repo, layerManifest, signingKey)
        }
        // Add the package manager package.
        addManifest(repo, packageManagerManifest(signingKey), signingKey)
        return repo
    }

    /**
     * The canonical Elysium Linux package
     * names. The constants are the **typed
     * references** to the packages in the
     * default repository; a consumer (the
     * package manager, the UI) uses the
     * constants to look up the manifest.
     */
    object PackageNames {
        /** The Elysium Linux meta-package
         *  (the install path for the distro). */
        const val DISTRO: String = ElysiumLinuxDistroPackage.NAME

        /** The native ARM64 runtime layer
         *  package. */
        const val NATIVE: String = "com.elysium.runtime.native"

        /** The Mesa Turnip runtime layer
         *  package. */
        const val MESA_TURNIP: String = "com.elysium.runtime.mesa-turnip"

        /** The Box64 runtime layer package. */
        const val BOX64: String = "com.elysium.runtime.box64"

        /** The FEX runtime layer package. */
        const val FEX: String = "com.elysium.runtime.fex"

        /** The Wine runtime layer package. */
        const val WINE: String = "com.elysium.runtime.wine"

        /** The Elysium package manager
         *  package. */
        const val PACKAGE_MANAGER: String = "com.elysium.pkgmgr"
    }

    /**
     * The canonical Elysium Linux package
     * versions. The constants are the
     * **typed references** to the package
     * versions; a consumer uses the
     * constants to construct a
     * [ElysiumPackageVersion] for an install
     * request.
     */
    object PackageVersions {
        /** The Elysium Linux meta-package
         *  version. */
        val DISTRO: ElysiumPackageVersion =
            ElysiumPackageVersion.parse(
                ElysiumLinuxDistroPackage.VERSION,
            ).getOrThrow()

        /** The native layer version. */
        val NATIVE: ElysiumPackageVersion =
            ElysiumPackageVersion(1, 0, 0)

        /** The Mesa Turnip version. */
        val MESA_TURNIP: ElysiumPackageVersion =
            ElysiumPackageVersion(24, 1, 0)

        /** The Box64 version. */
        val BOX64: ElysiumPackageVersion =
            ElysiumPackageVersion(0, 3, 2)

        /** The FEX version. */
        val FEX: ElysiumPackageVersion =
            ElysiumPackageVersion(2404, 0, 0)

        /** The Wine version. */
        val WINE: ElysiumPackageVersion =
            ElysiumPackageVersion(9, 0, 0)

        /** The package manager version. */
        val PACKAGE_MANAGER: ElysiumPackageVersion =
            ElysiumPackageVersion(1, 0, 0)
    }

    /**
     * Add a manifest to the repository. The
     * method is the typed wrapper around
     * [InMemoryElysiumRepository.addManifest] +
     * a typed result check.
     */
    private fun addManifest(
        repo: InMemoryElysiumRepository,
        manifest: ElysiumPackageManifest,
        expectedSigningKey: String,
    ) {
        val result = repo.addManifest(manifest, expectedSigningKey)
        check(result.isSuccess) {
            "ElysiumLinuxDefaultRepository.build: failed to add " +
                "manifest ${manifest.name}@${manifest.version.canonical}: " +
                "${result.exceptionOrNull()?.message}"
        }
    }

    /**
     * Build a runtime layer package manifest
     * from an [ElysiumRuntimeLayerManifest]
     * (per Phase 73 third half I-73.3.1). The
     * layer package is the **install path**
     * for the runtime layer binaries.
     */
    private fun buildRuntimeLayerManifest(
        layer: ElysiumRuntimeLayerManifest,
        signingKey: String,
    ): ElysiumPackageManifest {
        val unsigned = ElysiumPackageManifest(
            name = "com.elysium.runtime.${layer.id.value}",
            version = layer.version,
            abi = layer.hostAbi,
            description = layer.description,
            dependencies = layer.dependencies,
            provides = layer.provides,
            files = layer.files,
            scripts = ElysiumPackageScripts.NONE,
            contentHash = layer.contentHash,
            signature = Signature("placeholder"),
        )
        return unsigned.copy(
            signature = Signature.sign(
                payload = unsigned.canonicalForm.toByteArray(Charsets.UTF_8),
                key = signingKey.toByteArray(),
            ),
        )
    }

    /**
     * Build the package manager manifest.
     * The package manager is the
     * `com.elysium.pkgmgr` binary that
     * installs + upgrades + removes
     * Elysium Linux packages.
     */
    private fun packageManagerManifest(
        signingKey: String,
    ): ElysiumPackageManifest {
        val unsigned = ElysiumPackageManifest(
            name = PackageNames.PACKAGE_MANAGER,
            version = PackageVersions.PACKAGE_MANAGER,
            abi = ElysiumAbi.ARM64,
            description = "Elysium package manager binary. " +
                "The `elysium-pm` CLI tool for installing, " +
                "upgrading, and removing Elysium Linux " +
                "packages.",
            dependencies = emptyList(),
            provides = listOf(
                "elysium-package-manager",
                "elysium-pm",
            ),
            files = listOf(
                ElysiumPackageFile(
                    installPath = "/usr/bin/elysium-pm",
                    contentHash = ContentHash("0".repeat(64)),
                    permissions = FilePermissions(mode = 0x1ED),
                ),
            ),
            scripts = ElysiumPackageScripts.NONE,
            contentHash = ContentHash("0".repeat(64)),
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
