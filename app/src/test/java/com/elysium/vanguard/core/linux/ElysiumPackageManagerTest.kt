package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 73 second half — the JVM tests for
 * [ElysiumRepository] + [ElysiumPackageManager]
 * (in-memory implementation).
 *
 * These are the runtime-side tests for the
 * Elysium Linux distro's package management.
 * The tests cover:
 *   - Repository: add / fetch / list / size.
 *   - Package manager install: happy path,
 *     manifest-not-found, signature-mismatch,
 *     unsatisfiable dep, cyclic dep, atomic
 *     rollback, transitive dep resolution.
 *   - Package manager upgrade: happy path,
 *     not-an-upgrade, not-installed.
 *   - Package manager remove: happy path,
 *     dependent packages, not installed.
 *   - listInstalled, isInstalled, installedVersion.
 */
class ElysiumPackageManagerTest {

    // ============================================================
    // ElysiumRepository — InMemoryElysiumRepository
    // ============================================================

    @Test
    fun `repository addManifest stores the manifest under the version key`() {
        val repo = InMemoryElysiumRepository()
        val m = buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY)
        val result = repo.addManifest(m, TEST_KEY)
        assertTrue("addManifest expected success", result.isSuccess)
        val fetched = repo.fetchManifest(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertNotNull(fetched)
        assertEquals(m, fetched)
    }

    @Test
    fun `repository addManifest rejects a manifest with a wrong signature`() {
        val repo = InMemoryElysiumRepository()
        val m = buildManifest("com.example.a", "1.0.0", signingKey = "wrong-key")
        val result = repo.addManifest(m, TEST_KEY)
        assertTrue("expected addManifest failure for wrong signature", result.isFailure)
    }

    @Test
    fun `repository fetchManifest returns null for a missing package`() {
        val repo = InMemoryElysiumRepository()
        val fetched = repo.fetchManifest(
            "com.example.missing",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertNull(fetched)
    }

    @Test
    fun `repository fetchManifest returns null for a missing version`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val fetched = repo.fetchManifest(
            "com.example.a",
            ElysiumPackageVersion.parse("2.0.0").getOrThrow(),
        )
        assertNull(fetched)
    }

    @Test
    fun `repository listVersions returns versions sorted descending`() {
        val repo = InMemoryElysiumRepository()
        for (v in listOf("1.0.0", "2.0.0", "1.5.0", "1.0.1")) {
            repo.addManifest(
                buildManifest("com.example.a", v, signingKey = TEST_KEY),
                TEST_KEY,
            )
        }
        val versions = repo.listVersions("com.example.a")
        assertEquals(
            listOf("2.0.0", "1.5.0", "1.0.1", "1.0.0"),
            versions.map { it.canonical },
        )
    }

    @Test
    fun `repository listVersions returns empty list for a missing package`() {
        val repo = InMemoryElysiumRepository()
        assertEquals(emptyList<ElysiumPackageVersion>(), repo.listVersions("com.example.missing"))
    }

    @Test
    fun `repository listPackages returns names sorted alphabetically`() {
        val repo = InMemoryElysiumRepository()
        for (name in listOf("com.example.b", "com.example.a", "com.example.c")) {
            repo.addManifest(
                buildManifest(name, "1.0.0", signingKey = TEST_KEY),
                TEST_KEY,
            )
        }
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c"),
            repo.listPackages(),
        )
    }

    @Test
    fun `repository size counts the total manifests across packages`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest("com.example.a", "2.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest("com.example.b", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        assertEquals(3, repo.size())
    }

    // ============================================================
    // Package Manager — install happy path
    // ============================================================

    @Test
    fun `install a leaf package with no dependencies`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.leaf", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.leaf",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue("expected install success, got $result", result is ElysiumPackageInstallResult.Success)
        val success = result as ElysiumPackageInstallResult.Success
        assertEquals(ElysiumPackageInstallResult.Operation.INSTALL, success.operation)
        assertEquals("com.example.leaf", success.packageName)
        assertEquals(1, success.installedPackages.size)
        assertEquals("com.example.leaf", success.installedPackages[0].name)
    }

    @Test
    fun `install records the install timestamp from the clock`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.leaf", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 42_000L })
        pm.install(
            "com.example.leaf",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertEquals(42_000L, pm.installedVersion(
            "com.example.leaf",
        )?.let { pm.listInstalled().single { it.name == "com.example.leaf" }.installedAtMs })
    }

    // ============================================================
    // Package Manager — install failure cases
    // ============================================================

    @Test
    fun `install returns ManifestNotFound when the manifest is missing`() {
        val repo = InMemoryElysiumRepository()
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.missing",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected ManifestNotFound, got $reason",
            reason is ElysiumPackageInstallError.ManifestNotFound,
        )
    }

    @Test
    fun `install returns SignatureVerificationFailed when the signature does not match`() {
        val repo = InMemoryElysiumRepository()
        // The manifest is signed with a different
        // key than the package manager expects.
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = "publisher-key"),
            "publisher-key",
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected SignatureVerificationFailed, got $reason",
            reason is ElysiumPackageInstallError.SignatureVerificationFailed,
        )
    }

    @Test
    fun `install returns AlreadyInstalled when the package is already at the target version`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.leaf", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.leaf",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        val second = pm.install(
            "com.example.leaf",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $second",
            second is ElysiumPackageInstallResult.Failure,
        )
        val reason = (second as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected AlreadyInstalled, got $reason",
            reason is ElysiumPackageInstallError.AlreadyInstalled,
        )
    }

    @Test
    fun `install returns UnsatisfiableDependency when a required dep is not in the repo`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest(
                name = "com.example.app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    ElysiumPackageDependency(
                        packageName = "com.example.missing-dep",
                        constraint = VersionConstraint(
                            kind = ConstraintKind.GTE,
                            version = ElysiumPackageVersion(1, 0, 0),
                        ),
                    ),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected UnsatisfiableDependency, got $reason",
            reason is ElysiumPackageInstallError.UnsatisfiableDependency,
        )
    }

    @Test
    fun `install returns CyclicDependency when the manifest declares a cycle`() {
        val repo = InMemoryElysiumRepository()
        // A depends on B; B depends on A.
        repo.addManifest(
            buildManifest(
                name = "com.example.a",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.b", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.b",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.a", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected CyclicDependency, got $reason",
            reason is ElysiumPackageInstallError.CyclicDependency,
        )
    }

    // ============================================================
    // Package Manager — install with transitive deps
    // ============================================================

    @Test
    fun `install resolves transitive dependencies in dependency order`() {
        val repo = InMemoryElysiumRepository()
        // app -> lib -> runtime
        repo.addManifest(
            buildManifest(
                name = "com.example.runtime",
                version = "1.0.0",
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.lib",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.runtime", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.lib", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue(
            "expected install success, got $result",
            result is ElysiumPackageInstallResult.Success,
        )
        val success = result as ElysiumPackageInstallResult.Success
        // All three packages are installed.
        val installedNames = success.installedPackages.map { it.name }.toSet()
        assertEquals(
            setOf("com.example.app", "com.example.lib", "com.example.runtime"),
            installedNames,
        )
    }

    @Test
    fun `install picks the latest version that satisfies a GTE constraint`() {
        val repo = InMemoryElysiumRepository()
        // lib has versions 1.0.0, 1.5.0, 2.0.0.
        for (v in listOf("1.0.0", "1.5.0", "2.0.0")) {
            repo.addManifest(
                buildManifest("com.example.lib", v, signingKey = TEST_KEY),
                TEST_KEY,
            )
        }
        // app depends on lib >= 1.4.0
        repo.addManifest(
            buildManifest(
                name = "com.example.app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.lib", ConstraintKind.GTE, 1, 4, 0),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.install(
            "com.example.app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue("expected success, got $result", result is ElysiumPackageInstallResult.Success)
        // The latest lib version (2.0.0) is installed.
        assertEquals(
            ElysiumPackageVersion.parse("2.0.0").getOrThrow(),
            pm.installedVersion("com.example.lib"),
        )
    }

    @Test
    fun `install reuses an already-installed dependency when picking a new app`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.lib", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.first-app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.lib", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.second-app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.lib", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.first-app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        val result = pm.install(
            "com.example.second-app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertTrue("expected success, got $result", result is ElysiumPackageInstallResult.Success)
        // The lib is still at 1.0.0; the second
        // app just re-uses it.
        assertEquals(
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
            pm.installedVersion("com.example.lib"),
        )
        // The lib was not re-installed (its
        // installedAtMs is the original install
        // time, not the second install time).
        val libInstall = pm.listInstalled().single { it.name == "com.example.lib" }
        assertEquals(1000L, libInstall.installedAtMs)
    }

    // ============================================================
    // Package Manager — upgrade
    // ============================================================

    @Test
    fun `upgrade replaces an installed package with a newer version`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest("com.example.a", "2.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        val result = pm.upgrade(
            "com.example.a",
            ElysiumPackageVersion.parse("2.0.0").getOrThrow(),
        )
        assertTrue("expected upgrade success, got $result", result is ElysiumPackageInstallResult.Success)
        assertEquals(
            ElysiumPackageVersion.parse("2.0.0").getOrThrow(),
            pm.installedVersion("com.example.a"),
        )
    }

    @Test
    fun `upgrade returns NotInstalled when the package is not installed`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "2.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.upgrade(
            "com.example.a",
            ElysiumPackageVersion.parse("2.0.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected NotInstalled, got $reason",
            reason is ElysiumPackageInstallError.NotInstalled,
        )
    }

    @Test
    fun `upgrade returns NotAnUpgrade when the target is not newer`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest("com.example.a", "0.9.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        val result = pm.upgrade(
            "com.example.a",
            ElysiumPackageVersion.parse("0.9.0").getOrThrow(),
        )
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected NotAnUpgrade, got $reason",
            reason is ElysiumPackageInstallError.NotAnUpgrade,
        )
    }

    // ============================================================
    // Package Manager — remove
    // ============================================================

    @Test
    fun `remove deletes the package from the installed set`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        val result = pm.remove("com.example.a")
        assertTrue("expected remove success, got $result", result is ElysiumPackageInstallResult.Success)
        assertEquals(false, pm.isInstalled("com.example.a"))
    }

    @Test
    fun `remove returns NotInstalled when the package is not installed`() {
        val repo = InMemoryElysiumRepository()
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        val result = pm.remove("com.example.missing")
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected NotInstalled, got $reason",
            reason is ElysiumPackageInstallError.NotInstalled,
        )
    }

    @Test
    fun `remove returns DependentPackages when another installed package depends on it`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.lib", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.lib", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        val result = pm.remove("com.example.lib")
        assertTrue(
            "expected Failure, got $result",
            result is ElysiumPackageInstallResult.Failure,
        )
        val reason = (result as ElysiumPackageInstallResult.Failure).reason
        assertTrue(
            "expected DependentPackages, got $reason",
            reason is ElysiumPackageInstallError.DependentPackages,
        )
        // The lib is still installed.
        assertEquals(true, pm.isInstalled("com.example.lib"))
    }

    @Test
    fun `remove succeeds when the depending package was also removed first`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.lib", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        repo.addManifest(
            buildManifest(
                name = "com.example.app",
                version = "1.0.0",
                signingKey = TEST_KEY,
                deps = listOf(
                    dep("com.example.lib", ConstraintKind.GTE, 1, 0, 0),
                ),
            ),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.app",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        // Remove the app first (allowed — no
        // dependents on the app).
        pm.remove("com.example.app")
        // Then remove the lib.
        val result = pm.remove("com.example.lib")
        assertTrue("expected remove success, got $result", result is ElysiumPackageInstallResult.Success)
        assertEquals(false, pm.isInstalled("com.example.lib"))
    }

    // ============================================================
    // Package Manager — listInstalled / isInstalled / installedVersion
    // ============================================================

    @Test
    fun `listInstalled returns packages sorted alphabetically by name`() {
        val repo = InMemoryElysiumRepository()
        for (name in listOf("com.example.b", "com.example.a", "com.example.c")) {
            repo.addManifest(
                buildManifest(name, "1.0.0", signingKey = TEST_KEY),
                TEST_KEY,
            )
        }
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        for (name in listOf("com.example.b", "com.example.a", "com.example.c")) {
            pm.install(
                name,
                ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
            )
        }
        val installed = pm.listInstalled().map { it.name }
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c"),
            installed,
        )
    }

    @Test
    fun `listInstalled is empty when no packages are installed`() {
        val repo = InMemoryElysiumRepository()
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        assertEquals(emptyList<InstalledPackage>(), pm.listInstalled())
    }

    @Test
    fun `isInstalled returns true only for installed packages`() {
        val repo = InMemoryElysiumRepository()
        repo.addManifest(
            buildManifest("com.example.a", "1.0.0", signingKey = TEST_KEY),
            TEST_KEY,
        )
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        pm.install(
            "com.example.a",
            ElysiumPackageVersion.parse("1.0.0").getOrThrow(),
        )
        assertEquals(true, pm.isInstalled("com.example.a"))
        assertEquals(false, pm.isInstalled("com.example.missing"))
    }

    @Test
    fun `installedVersion returns null for a non-installed package`() {
        val repo = InMemoryElysiumRepository()
        val pm = InMemoryElysiumPackageManager(repo, TEST_KEY, clock = { 1000L })
        assertNull(pm.installedVersion("com.example.missing"))
    }

    // ============================================================
    // Package Manager — installed metadata
    // ============================================================

    @Test
    fun `InstalledPackage canonicalId is name plus version plus contentHash`() {
        val pkg = InstalledPackage(
            name = "com.example.a",
            version = ElysiumPackageVersion(1, 0, 0),
            installedAtMs = 1000L,
            contentHash = ContentHash("a".repeat(64)),
        )
        assertEquals(
            "com.example.a@1.0.0:${"a".repeat(64)}",
            pkg.canonicalId,
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun dep(
        name: String,
        kind: ConstraintKind,
        major: Int,
        minor: Int,
        patch: Int,
    ): ElysiumPackageDependency = ElysiumPackageDependency(
        packageName = name,
        constraint = VersionConstraint(
            kind = kind,
            version = ElysiumPackageVersion(major, minor, patch),
        ),
    )

    /**
     * Build a manifest, signing it with the
     * given key. The signature is computed over
     * the canonical form (which excludes the
     * signature field itself).
     */
    private fun buildManifest(
        name: String,
        version: String,
        signingKey: String,
        deps: List<ElysiumPackageDependency> = emptyList(),
        description: String = "Test package",
    ): ElysiumPackageManifest {
        val v = ElysiumPackageVersion.parse(version).getOrThrow()
        val unsigned = ElysiumPackageManifest(
            name = name,
            version = v,
            abi = ElysiumAbi.ARM64,
            description = description,
            dependencies = deps,
            provides = emptyList(),
            files = listOf(
                ElysiumPackageFile(
                    installPath = "/usr/share/$name/file.bin",
                    contentHash = ContentHash("0".repeat(64)),
                ),
            ),
            scripts = ElysiumPackageScripts.NONE,
            contentHash = ContentHash("0".repeat(64)),
            signature = Signature("placeholder"),
        )
        val signed = unsigned.copy(
            signature = Signature.sign(
                payload = unsigned.canonicalForm.toByteArray(Charsets.UTF_8),
                key = signingKey.toByteArray(),
            ),
        )
        return signed
    }

    companion object {
        const val TEST_KEY: String = "elysium-test-signing-key"
    }
}
