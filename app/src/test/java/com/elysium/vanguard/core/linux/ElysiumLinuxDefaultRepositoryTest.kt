package com.elysium.vanguard.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 75 (Elysium Linux vision alignment) — the
 * JVM tests for [ElysiumLinuxDefaultRepository].
 *
 * The tests cover:
 *   - The default repository is buildable +
 *     has the expected size.
 *   - The default repository contains the
 *     meta-package + the 5 runtime layer
 *     packages + the package manager.
 *   - Every manifest in the default repository
 *     verifies with the default signing key.
 *   - The meta-package's dependencies are
 *     present in the default repository (the
 *     user can install the meta-package
 *     without additional configuration).
 *   - The package name + version constants
 *     match the manifest's values.
 */
class ElysiumLinuxDefaultRepositoryTest {

    // ============================================================
    // Repository build + size
    // ============================================================

    @Test
    fun `build returns a repository with 7 packages`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        // 1 meta-package + 5 runtime layer
        // packages + 1 package manager = 7.
        assertEquals(7, repo.size())
    }

    @Test
    fun `build returns a repository with 7 unique package names`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val names = repo.listPackages()
        assertEquals(7, names.size)
        // The 7 names are unique.
        assertEquals(names.size, names.toSet().size)
    }

    // ============================================================
    // Repository contents
    // ============================================================

    @Test
    fun `default repository contains the Elysium Linux meta-package`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
            ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
        )
        assertNotNull("expected meta-package to be in the repository", manifest)
    }

    @Test
    fun `default repository contains the native runtime layer`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.NATIVE,
            ElysiumLinuxDefaultRepository.PackageVersions.NATIVE,
        )
        assertNotNull("expected native layer package to be in the repository", manifest)
    }

    @Test
    fun `default repository contains the Mesa Turnip runtime layer`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.MESA_TURNIP,
            ElysiumLinuxDefaultRepository.PackageVersions.MESA_TURNIP,
        )
        assertNotNull("expected Mesa Turnip layer package to be in the repository", manifest)
    }

    @Test
    fun `default repository contains the Box64 runtime layer`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.BOX64,
            ElysiumLinuxDefaultRepository.PackageVersions.BOX64,
        )
        assertNotNull("expected Box64 layer package to be in the repository", manifest)
    }

    @Test
    fun `default repository contains the FEX runtime layer`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.FEX,
            ElysiumLinuxDefaultRepository.PackageVersions.FEX,
        )
        assertNotNull("expected FEX layer package to be in the repository", manifest)
    }

    @Test
    fun `default repository contains the Wine runtime layer`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.WINE,
            ElysiumLinuxDefaultRepository.PackageVersions.WINE,
        )
        assertNotNull("expected Wine layer package to be in the repository", manifest)
    }

    @Test
    fun `default repository contains the package manager`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val manifest = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.PACKAGE_MANAGER,
            ElysiumLinuxDefaultRepository.PackageVersions.PACKAGE_MANAGER,
        )
        assertNotNull("expected package manager to be in the repository", manifest)
    }

    // ============================================================
    // Signature verification
    // ============================================================

    @Test
    fun `every manifest in the default repository verifies with the default signing key`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        // Verify each known (name, version)
        // pair. The build() helper calls
        // addManifest() which checks the
        // signature on add; this test
        // double-checks the verification.
        val knownPackages = listOf(
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO to
                ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
            ElysiumLinuxDefaultRepository.PackageNames.NATIVE to
                ElysiumLinuxDefaultRepository.PackageVersions.NATIVE,
            ElysiumLinuxDefaultRepository.PackageNames.MESA_TURNIP to
                ElysiumLinuxDefaultRepository.PackageVersions.MESA_TURNIP,
            ElysiumLinuxDefaultRepository.PackageNames.BOX64 to
                ElysiumLinuxDefaultRepository.PackageVersions.BOX64,
            ElysiumLinuxDefaultRepository.PackageNames.FEX to
                ElysiumLinuxDefaultRepository.PackageVersions.FEX,
            ElysiumLinuxDefaultRepository.PackageNames.WINE to
                ElysiumLinuxDefaultRepository.PackageVersions.WINE,
            ElysiumLinuxDefaultRepository.PackageNames.PACKAGE_MANAGER to
                ElysiumLinuxDefaultRepository.PackageVersions.PACKAGE_MANAGER,
        )
        for ((name, version) in knownPackages) {
            val manifest = repo.fetchManifest(name, version)
            assertNotNull("expected $name to be in the repository", manifest)
            val verifyResult = manifest!!.verifySignature(
                com.elysium.vanguard.foundry.core.ontology.primitives.Signature(
                    ElysiumLinuxDefaultRepository.DEFAULT_SIGNING_KEY,
                ),
            )
            assertTrue(
                "expected $name@${version.canonical} to verify with default " +
                    "key, got $verifyResult",
                verifyResult.isSuccess,
            )
        }
    }

    // ============================================================
    // Meta-package dependencies are present
    // ============================================================

    @Test
    fun `every dependency in the meta-package is present in the default repository`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val meta = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
            ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
        )!!
        for (dep in meta.dependencies) {
            // The dependency's version constraint
            // is the GTE constraint; the
            // repository's `latest(dep.packageName)`
            // returns the highest version.
            // We check that at least one version
            // is present.
            val versions = repo.listVersions(dep.packageName)
            if (versions.isEmpty()) {
                fail(
                    "expected dependency ${dep.packageName} to be in " +
                        "the default repository, got: empty list of " +
                        "versions. Meta-package dependencies: " +
                        "${meta.dependencies.map { it.packageName }}",
                )
            }
        }
    }

    @Test
    fun `meta-package has exactly 6 dependencies (5 layers + pkgmgr)`() {
        val repo = ElysiumLinuxDefaultRepository.build()
        val meta = repo.fetchManifest(
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
            ElysiumLinuxDefaultRepository.PackageVersions.DISTRO,
        )!!
        assertEquals(6, meta.dependencies.size)
    }

    // ============================================================
    // Constants
    // ============================================================

    @Test
    fun `PackageNames DISTRO constant matches the meta-package name`() {
        assertEquals(
            ElysiumLinuxDistroPackage.NAME,
            ElysiumLinuxDefaultRepository.PackageNames.DISTRO,
        )
    }

    @Test
    fun `PackageVersions DISTRO constant parses the meta-package version`() {
        val v = ElysiumLinuxDefaultRepository.PackageVersions.DISTRO
        assertEquals(
            ElysiumLinuxDistroPackage.VERSION,
            v.canonical,
        )
    }
}
