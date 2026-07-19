package com.elysium.vanguard.core.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 74 (third half) — the JVM tests for
 * [ElysiumLinuxDistroPackage].
 *
 * The tests cover:
 *   - Package identity (name, version).
 *   - Dependencies: the 5 runtime layer
 *     packages + the package manager.
 *   - Provides: the canonical capabilities the
 *     distro provides.
 *   - Files: the distro-level config + metadata.
 *   - manifest() factory: returns a valid signed
 *     manifest with all the declared fields.
 *   - Signature verification: the manifest
 *     verifies with the default signing key.
 *   - Cross-consistency: the meta-package
 *     references match the runtime layer
 *     defaults from Phase 73 third half
 *     I-73.3.1.
 */
class ElysiumLinuxDistroPackageTest {

    // ============================================================
    // Package identity
    // ============================================================

    @Test
    fun `package name is the reverse-DNS elysium linux distro namespace`() {
        assertEquals(
            "com.elysium.linux.distro",
            ElysiumLinuxDistroPackage.NAME,
        )
    }

    @Test
    fun `package version is 1-0-0`() {
        assertEquals("1.0.0", ElysiumLinuxDistroPackage.VERSION)
    }

    // ============================================================
    // Dependencies
    // ============================================================

    @Test
    fun `dependencies include the native runtime layer`() {
        val names = ElysiumLinuxDistroPackage.DEPENDENCIES.map { it.packageName }
        assertTrue(
            "expected 'com.elysium.runtime.native' in $names",
            "com.elysium.runtime.native" in names,
        )
    }

    @Test
    fun `dependencies include the Mesa Turnip runtime layer`() {
        val names = ElysiumLinuxDistroPackage.DEPENDENCIES.map { it.packageName }
        assertTrue(
            "expected 'com.elysium.runtime.mesa-turnip' in $names",
            "com.elysium.runtime.mesa-turnip" in names,
        )
    }

    @Test
    fun `dependencies include the Box64 runtime layer`() {
        val names = ElysiumLinuxDistroPackage.DEPENDENCIES.map { it.packageName }
        assertTrue(
            "expected 'com.elysium.runtime.box64' in $names",
            "com.elysium.runtime.box64" in names,
        )
    }

    @Test
    fun `dependencies include the FEX runtime layer`() {
        val names = ElysiumLinuxDistroPackage.DEPENDENCIES.map { it.packageName }
        assertTrue(
            "expected 'com.elysium.runtime.fex' in $names",
            "com.elysium.runtime.fex" in names,
        )
    }

    @Test
    fun `dependencies include the Wine runtime layer`() {
        val names = ElysiumLinuxDistroPackage.DEPENDENCIES.map { it.packageName }
        assertTrue(
            "expected 'com.elysium.runtime.wine' in $names",
            "com.elysium.runtime.wine" in names,
        )
    }

    @Test
    fun `dependencies include the package manager itself`() {
        val names = ElysiumLinuxDistroPackage.DEPENDENCIES.map { it.packageName }
        assertTrue(
            "expected 'com.elysium.pkgmgr' in $names",
            "com.elysium.pkgmgr" in names,
        )
    }

    @Test
    fun `dependencies count is 6 (5 layers + pkgmgr)`() {
        assertEquals(6, ElysiumLinuxDistroPackage.DEPENDENCIES.size)
    }

    // ============================================================
    // Provides
    // ============================================================

    @Test
    fun `provides include the elysium-linux marker`() {
        assertTrue(
            "expected 'elysium-linux' in ${ElysiumLinuxDistroPackage.PROVIDES}",
            "elysium-linux" in ElysiumLinuxDistroPackage.PROVIDES,
        )
    }

    @Test
    fun `provides include every runtime layer capability`() {
        for (capability in listOf(
            "elysium-runtime-native",
            "elysium-runtime-mesa-turnip",
            "elysium-runtime-box64",
            "elysium-runtime-fex",
            "elysium-runtime-wine",
        )) {
            assertTrue(
                "expected '$capability' in ${ElysiumLinuxDistroPackage.PROVIDES}",
                capability in ElysiumLinuxDistroPackage.PROVIDES,
            )
        }
    }

    // ============================================================
    // Files
    // ============================================================

    @Test
    fun `files include the elysium-linux conf file`() {
        val paths = ElysiumLinuxDistroPackage.FILES.map { it.installPath }
        assertTrue(
            "expected '/etc/elysium/elysium-linux.conf' in $paths",
            "/etc/elysium/elysium-linux.conf" in paths,
        )
    }

    @Test
    fun `files include the package sources list`() {
        val paths = ElysiumLinuxDistroPackage.FILES.map { it.installPath }
        assertTrue(
            "expected '/etc/elysium/package-sources.list' in $paths",
            "/etc/elysium/package-sources.list" in paths,
        )
    }

    @Test
    fun `files include the user-facing README`() {
        val paths = ElysiumLinuxDistroPackage.FILES.map { it.installPath }
        assertTrue(
            "expected '/usr/share/elysium-linux/README' in $paths",
            "/usr/share/elysium-linux/README" in paths,
        )
    }

    @Test
    fun `every file has an absolute path`() {
        for (file in ElysiumLinuxDistroPackage.FILES) {
            assertTrue(
                "expected absolute path, got: ${file.installPath}",
                file.installPath.startsWith("/"),
            )
        }
    }

    // ============================================================
    // manifest() factory
    // ============================================================

    @Test
    fun `manifest returns a valid signed manifest`() {
        val manifest = ElysiumLinuxDistroPackage.manifest()
        assertEquals(ElysiumLinuxDistroPackage.NAME, manifest.name)
        assertEquals(
            ElysiumPackageVersion.parse(ElysiumLinuxDistroPackage.VERSION).getOrThrow(),
            manifest.version,
        )
        assertEquals(ElysiumAbi.ARM64, manifest.abi)
        assertEquals(ElysiumLinuxDistroPackage.DESCRIPTION, manifest.description)
        assertEquals(ElysiumLinuxDistroPackage.DEPENDENCIES, manifest.dependencies)
        assertEquals(ElysiumLinuxDistroPackage.PROVIDES, manifest.provides)
        assertEquals(ElysiumLinuxDistroPackage.FILES, manifest.files)
        assertEquals(ElysiumLinuxDistroPackage.CONTENT_HASH, manifest.contentHash)
    }

    @Test
    fun `manifest verifies with the default signing key`() {
        val manifest = ElysiumLinuxDistroPackage.manifest()
        val verifyResult = manifest.verifySignature(
            com.elysium.vanguard.foundry.core.ontology.primitives.Signature(
                ElysiumLinuxDistroPackage.DEFAULT_SIGNING_KEY,
            ),
        )
        assertTrue(
            "expected manifest to verify with default key, got $verifyResult",
            verifyResult.isSuccess,
        )
    }

    @Test
    fun `manifest rejects a wrong signing key`() {
        val manifest = ElysiumLinuxDistroPackage.manifest()
        val verifyResult = manifest.verifySignature(
            com.elysium.vanguard.foundry.core.ontology.primitives.Signature(
                "wrong-key",
            ),
        )
        assertTrue(
            "expected manifest to reject wrong key, got $verifyResult",
            verifyResult.isFailure,
        )
    }

    // ============================================================
    // Cross-consistency with the runtime layer defaults
    // ============================================================

    @Test
    fun `every runtime layer in the dependencies has a matching default manifest`() {
        // For each runtime layer dependency,
        // the Phase 73 third half I-73.3.1
        // defaults include a manifest with the
        // matching id + version constraint. This
        // test verifies the meta-package's
        // dependencies are installable from the
        // defaults.
        val defaults = ElysiumRuntimeLayerDefaults.ALL
        val depsByName = ElysiumLinuxDistroPackage.DEPENDENCIES
            .associateBy { it.packageName }
        for (default in defaults) {
            val expectedName = "com.elysium.runtime.${default.id.value}"
            val dep = depsByName[expectedName]
            assertNotNull(
                "expected a dependency for $expectedName (the ${default.id.value} layer)",
                dep,
            )
            // The dependency's constraint version
            // matches the default's version.
            assertEquals(
                "expected version ${default.version.canonical} for $expectedName, " +
                    "got ${dep!!.constraint.version.canonical}",
                default.version,
                dep.constraint.version,
            )
        }
    }
}
