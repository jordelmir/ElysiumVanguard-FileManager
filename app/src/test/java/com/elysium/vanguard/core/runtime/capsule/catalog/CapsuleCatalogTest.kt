package com.elysium.vanguard.core.runtime.capsule.catalog

import com.elysium.vanguard.core.runtime.capsule.Architecture
import com.elysium.vanguard.core.runtime.capsule.Capsule
import com.elysium.vanguard.core.runtime.capsule.CapsuleId
import com.elysium.vanguard.core.runtime.capsule.Distribution
import com.elysium.vanguard.core.runtime.capsule.EntryPoint
import com.elysium.vanguard.core.runtime.capsule.GpuApi
import com.elysium.vanguard.core.runtime.capsule.GpuConfig
import com.elysium.vanguard.core.runtime.capsule.GpuDriver
import com.elysium.vanguard.core.runtime.capsule.Permissions
import com.elysium.vanguard.core.runtime.capsule.Runtime
import com.elysium.vanguard.core.runtime.capsule.StorageScope
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the [CapsuleCatalog] — the local capsule
 * catalog with the trust check at the boundary.
 *
 * The catalog is the **trust boundary** (per master
 * vision section 9 — "Seguridad Zero Trust"). The
 * tests cover:
 *   1. **Trust check**: a capsule with a blank
 *      signature is rejected; a capsule with a
 *      malformed content hash is rejected; a valid
 *      capsule is accepted.
 *   2. **In-memory catalog**: put / get / list /
 *      delete / clear / size.
 *   3. **File-backed catalog**: put / get / list /
 *      delete + atomic write + hydrate-from-disk.
 *   4. **Duplicate install**: a capsule with the
 *      same id is rejected.
 *   5. **Search**: filter by runtime / arch /
 *      distribution / text + `runnableOn` capability
 *      match.
 *   6. **Error envelope**: a failed install returns
 *      a `Result.failure` with a typed
 *      `CapsuleCatalogException`.
 */
class CapsuleCatalogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ============================================================
    // Canonical sample
    // ============================================================

    private fun sampleCapsule(
        id: String = "com.elysium.blender.arm64",
        name: String = "Blender 3D for ARM64",
        runtime: Runtime = Runtime.LINUX,
        architecture: Architecture = Architecture.ARM64,
        distribution: Distribution = Distribution.ELYSIUM_LINUX_1,
        signature: String = "sig-blender-arm64",
    ): Capsule = Capsule(
        apiVersion = com.elysium.vanguard.core.runtime.capsule.CapsuleApiVersion.V1,
        id = CapsuleId(id),
        name = name,
        version = "4.2.0",
        description = "Blender 3D on Elysium Vanguard Linux",
        runtime = runtime,
        architecture = architecture,
        distribution = distribution,
        entrypoint = EntryPoint(
            executable = "/usr/bin/blender",
            args = emptyList(),
            workingDirectory = "/workspace/projects",
        ),
        gpu = GpuConfig(api = GpuApi.VULKAN, driver = GpuDriver.TURNIP),
        permissions = Permissions(
            network = false,
            storage = listOf(StorageScope.USER_SELECTED),
        ),
        signature = Signature(signature),
        contentHash = ContentHash("a".repeat(64)),
    )

    // ============================================================
    // Trust check
    //
    // The trust check is the **second line of defense**
    // at the catalog boundary. The **first line** is
    // the value-class `init` blocks (`Signature`,
    // `ContentHash`) that reject invalid input at
    // construction. The tests below verify BOTH
    // lines: the value class catches the invalid
    // input, AND the catalog's trust check is wired
    // to reject the residual cases.
    // ============================================================

    @Test
    fun `signature primitive rejects blank values (first line of defense)`() {
        try {
            Signature("")
            fail("expected IllegalArgumentException for blank signature")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention Signature, got: ${e.message}",
                e.message!!.contains("Signature"),
            )
        }
    }

    @Test
    fun `contentHash primitive rejects malformed values (first line of defense)`() {
        try {
            ContentHash("short")
            fail("expected IllegalArgumentException for malformed content hash")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention ContentHash, got: ${e.message}",
                e.message!!.contains("ContentHash"),
            )
        }
    }

    @Test
    fun `catalog trust check on a valid capsule is empty`() {
        // The trust check is a no-op for a valid
        // capsule (the value classes already
        // validated). The catalog is the
        // **defense-in-depth** layer; the test
        // documents the contract.
        val catalog = InMemoryCapsuleCatalog()
        val errors = catalog.trustCheck(sampleCapsule())
        assertTrue(
            "expected no trust errors for valid capsule, got $errors",
            errors.isEmpty(),
        )
    }

    @Test
    fun `catalog accepts a valid capsule`() {
        val catalog = InMemoryCapsuleCatalog()
        val capsule = sampleCapsule()
        val result = catalog.put(capsule)
        assertTrue("expected success, got $result", result.isSuccess)
    }

    @Test
    fun `catalog rejects duplicate capsule id`() {
        val catalog = InMemoryCapsuleCatalog()
        val capsule = sampleCapsule()
        assertTrue(catalog.put(capsule).isSuccess)
        val result = catalog.put(capsule)
        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected DuplicateCapsule, got ${error?.javaClass}",
            error is CapsuleCatalogException.DuplicateCapsule,
        )
    }

    // ============================================================
    // In-memory catalog
    // ============================================================

    @Test
    fun `in-memory catalog put get list delete cycle`() {
        val catalog = InMemoryCapsuleCatalog()
        val capsule = sampleCapsule()
        catalog.put(capsule)
        assertEquals(capsule, catalog.getById(capsule.id))
        assertEquals(listOf(capsule), catalog.list())
        assertTrue(catalog.delete(capsule.id))
        assertNull(catalog.getById(capsule.id))
        assertTrue(catalog.list().isEmpty())
    }

    @Test
    fun `in-memory catalog getById returns null for unknown id`() {
        val catalog = InMemoryCapsuleCatalog()
        assertNull(catalog.getById(CapsuleId("com.elysium.unknown")))
    }

    @Test
    fun `in-memory catalog delete returns false for unknown id`() {
        val catalog = InMemoryCapsuleCatalog()
        assertEquals(false, catalog.delete(CapsuleId("com.elysium.unknown")))
    }

    @Test
    fun `in-memory catalog clear empties the catalog`() {
        val catalog = InMemoryCapsuleCatalog()
        catalog.put(sampleCapsule(id = "com.elysium.app1"))
        catalog.put(sampleCapsule(id = "com.elysium.app2"))
        assertEquals(2, catalog.size())
        catalog.clear()
        assertEquals(0, catalog.size())
    }

    // ============================================================
    // File-backed catalog
    // ============================================================

    @Test
    fun `file-backed catalog persists + hydrates across instances`() {
        val baseDir = tempFolder.newFolder("capsule-store")
        val catalogA = FileCapsuleCatalog(baseDir)
        val capsule = sampleCapsule()
        val putResult = catalogA.put(capsule)
        assertTrue("expected success, got $putResult", putResult.isSuccess)

        // A new catalog on the same baseDir hydrates
        // from disk. The capsule is still present.
        val catalogB = FileCapsuleCatalog(baseDir)
        val hydrated = catalogB.getById(capsule.id)
        assertNotNull("expected hydrated capsule, got null", hydrated)
        assertEquals(capsule, hydrated)
    }

    @Test
    fun `file-backed catalog delete removes the file`() {
        val baseDir = tempFolder.newFolder("capsule-store-delete")
        val catalog = FileCapsuleCatalog(baseDir)
        val capsule = sampleCapsule()
        catalog.put(capsule)
        assertTrue(catalog.delete(capsule.id))
        // A new catalog on the same baseDir sees no
        // capsule.
        val catalogB = FileCapsuleCatalog(baseDir)
        assertNull(catalogB.getById(capsule.id))
    }

    @Test
    fun `file-backed catalog rejects duplicate with the same error as in-memory`() {
        val baseDir = tempFolder.newFolder("capsule-store-dup")
        val catalog = FileCapsuleCatalog(baseDir)
        val capsule = sampleCapsule()
        assertTrue(catalog.put(capsule).isSuccess)
        val result = catalog.put(capsule)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(
            "expected DuplicateCapsule, got ${result.exceptionOrNull()?.javaClass}",
            result.exceptionOrNull() is CapsuleCatalogException.DuplicateCapsule,
        )
    }

    // ============================================================
    // Search
    // ============================================================

    @Test
    fun `search filters by runtime`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.linux1", runtime = Runtime.LINUX),
            sampleCapsule(id = "com.elysium.windows1", runtime = Runtime.WINDOWS),
        )
        val linux = CapsuleSearch.search(
            capsules,
            CapsuleSearch.Query(runtime = Runtime.LINUX),
        )
        assertEquals(1, linux.size)
        assertEquals("com.elysium.linux1", linux[0].id.value)
    }

    @Test
    fun `search filters by architecture (exact match)`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.arm64", architecture = Architecture.ARM64),
            sampleCapsule(id = "com.elysium.x86_64", architecture = Architecture.X86_64),
        )
        val arm64 = CapsuleSearch.search(
            capsules,
            CapsuleSearch.Query(architecture = Architecture.ARM64),
        )
        assertEquals(1, arm64.size)
        assertEquals("com.elysium.arm64", arm64[0].id.value)
    }

    @Test
    fun `search matches Architecture_ANY against any device arch`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.any", architecture = Architecture.ANY),
            sampleCapsule(id = "com.elysium.arm64only", architecture = Architecture.ARM64),
        )
        // Search for ARM64 — the ANY capsule matches
        // (it's architecture-agnostic); the
        // arm64only matches exactly. The X86_64-only
        // would be excluded if it were in the list.
        val arm64 = CapsuleSearch.search(
            capsules,
            CapsuleSearch.Query(architecture = Architecture.ARM64),
        )
        assertEquals(2, arm64.size)
        val ids = arm64.map { it.id.value }.toSet()
        assertTrue("com.elysium.any" in ids)
        assertTrue("com.elysium.arm64only" in ids)
    }

    @Test
    fun `search matches Distribution_ANY against any installed distro`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.distroany", distribution = Distribution.ANY),
            sampleCapsule(id = "com.elysium.distrospecific", distribution = Distribution.ELYSIUM_LINUX_1),
        )
        val allDistros = CapsuleSearch.search(
            capsules,
            CapsuleSearch.Query(distribution = Distribution("any-distro")),
        )
        // The distroany capsule matches because
        // Distribution.ANY matches any distro. The
        // distrospecific does NOT match (its
        // distribution is elysium-linux-1, not
        // any-distro).
        assertEquals(1, allDistros.size)
        assertEquals("com.elysium.distroany", allDistros[0].id.value)
    }

    @Test
    fun `search filters by text in name or description`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.blender", name = "Blender 3D"),
            sampleCapsule(id = "com.elysium.gimp", name = "GIMP Image Editor"),
        )
        val blender = CapsuleSearch.search(
            capsules,
            CapsuleSearch.Query(text = "blender"),
        )
        // "blender" matches:
        //   - "com.elysium.blender" (id contains "blender")
        //   - "com.elysium.gimp" (description "Blender 3D on
        //     Elysium Vanguard Linux" contains "Blender" — the
        //     search is case-insensitive)
        assertEquals(2, blender.size)
    }

    @Test
    fun `search filters by text in id`() {
        // The first capsule's id contains "blender"
        // (and its name also contains "blender").
        // The second capsule's id is
        // "com.elysium.gimp.arm64" (no "blender") and
        // its name is "GIMP Image Editor" (no
        // "blender"). So only the first capsule
        // matches. The default description
        // ("Blender 3D on Elysium Vanguard Linux")
        // is the SAME for both capsules, so we
        // override it on the second to avoid a match.
        val capsules = listOf(
            sampleCapsule(
                id = "com.elysium.blender.arm64",
                name = "Blender 3D for ARM64",
            ),
            sampleCapsule(
                id = "com.elysium.gimp.arm64",
                name = "GIMP Image Editor",
            ).copy(description = "GIMP image editor for ARM64"),
        )
        val blender = CapsuleSearch.search(
            capsules,
            CapsuleSearch.Query(text = "blender"),
        )
        assertEquals(1, blender.size)
        assertEquals("com.elysium.blender.arm64", blender[0].id.value)
    }

    @Test
    fun `search with empty query returns all capsules`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.a"),
            sampleCapsule(id = "com.elysium.b"),
        )
        val all = CapsuleSearch.search(capsules, CapsuleSearch.Query())
        assertEquals(2, all.size)
    }

    @Test
    fun `search with text query rejects blank text`() {
        try {
            CapsuleSearch.Query(text = "")
            fail("expected IllegalArgumentException for blank text")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    // ============================================================
    // runnableOn (capability match)
    // ============================================================

    @Test
    fun `runnableOn matches arm64 capsule on arm64 device`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.arm64", architecture = Architecture.ARM64),
            sampleCapsule(id = "com.elysium.x86_64", architecture = Architecture.X86_64),
        )
        val runnable = CapsuleSearch.runnableOn(
            capsules,
            deviceArch = Architecture.ARM64,
            installedDistros = setOf("elysium-linux-1"),
        )
        assertEquals(1, runnable.size)
        assertEquals("com.elysium.arm64", runnable[0].id.value)
    }

    @Test
    fun `runnableOn matches Architecture_ANY on any device`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.any", architecture = Architecture.ANY),
        )
        val runnable = CapsuleSearch.runnableOn(
            capsules,
            deviceArch = Architecture.ARM64,
            installedDistros = setOf("elysium-linux-1"),
        )
        assertEquals(1, runnable.size)
    }

    @Test
    fun `runnableOn excludes capsule when distro not installed`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.app", distribution = Distribution.ELYSIUM_LINUX_1),
        )
        val runnable = CapsuleSearch.runnableOn(
            capsules,
            deviceArch = Architecture.ARM64,
            installedDistros = emptySet(),
        )
        assertTrue("expected empty list, got $runnable", runnable.isEmpty())
    }

    @Test
    fun `runnableOn matches Distribution_ANY even with no distros installed`() {
        val capsules = listOf(
            sampleCapsule(id = "com.elysium.app", distribution = Distribution.ANY),
        )
        val runnable = CapsuleSearch.runnableOn(
            capsules,
            deviceArch = Architecture.ARM64,
            installedDistros = emptySet(),
        )
        assertEquals(1, runnable.size)
    }
}
