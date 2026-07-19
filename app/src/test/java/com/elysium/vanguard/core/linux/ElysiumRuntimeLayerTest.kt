package com.elysium.vanguard.core.linux

import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 73 third half (I-73.3.1) — the JVM tests
 * for [ElysiumRuntimeLayer] (sealed class with 5
 * cases) + [ElysiumRuntimeLayerId] +
 * [ElysiumRuntimeCapability] + [ElysiumRuntimeLayerManifest]
 * + [ElysiumRuntimeLayerCatalog] +
 * [ElysiumRuntimeLayerDefaults].
 *
 * These are the foundational types for the Elysium
 * Linux distro's runtime layer system. The tests
 * cover:
 *   - Layer id validation (pattern + blank rejection).
 *   - Each layer kind's capabilities (Native, MesaTurnip,
 *     Box64, Fex, Wine).
 *   - Manifest invariants (blank fields, empty file
 *     list rejection).
 *   - Signature verification (correct key passes,
 *     wrong key fails).
 *   - Catalog: addLayer (signature verification on
 *     add), find, latest, latestForAbi, listVersions,
 *     listLayerIds, size, asLayer.
 *   - Defaults: every default layer is signed +
 *     verifies with the default signing key.
 */
class ElysiumRuntimeLayerTest {

    // ============================================================
    // ElysiumRuntimeLayerId
    // ============================================================

    @Test
    fun `layerId rejects blank value`() {
        try {
            ElysiumRuntimeLayerId("")
            fail("expected IllegalArgumentException for blank value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("value"))
        }
    }

    @Test
    fun `layerId rejects uppercase value`() {
        try {
            ElysiumRuntimeLayerId("Mesa-Turnip")
            fail("expected IllegalArgumentException for uppercase value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("value"))
        }
    }

    @Test
    fun `layerId rejects value with underscore`() {
        try {
            ElysiumRuntimeLayerId("mesa_turnip")
            fail("expected IllegalArgumentException for underscore value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("value"))
        }
    }

    @Test
    fun `layerId accepts lowercase hyphen-separated value`() {
        val id = ElysiumRuntimeLayerId("mesa-turnip")
        assertEquals("mesa-turnip", id.value)
    }

    @Test
    fun `layerId has well-known constants`() {
        assertEquals("native", ElysiumRuntimeLayerId.NATIVE.value)
        assertEquals("mesa-turnip", ElysiumRuntimeLayerId.MESA_TURNIP.value)
        assertEquals("box64", ElysiumRuntimeLayerId.BOX64.value)
        assertEquals("fex", ElysiumRuntimeLayerId.FEX.value)
        assertEquals("wine", ElysiumRuntimeLayerId.WINE.value)
    }

    // ============================================================
    // ElysiumRuntimeLayer - per-kind capabilities
    // ============================================================

    @Test
    fun `Native layer has EXECUTE_NATIVE capability`() {
        val layer = ElysiumRuntimeLayer.Native(version = ElysiumPackageVersion(1, 0, 0))
        assertEquals(ElysiumRuntimeLayerId.NATIVE, layer.id)
        assertEquals(
            setOf(ElysiumRuntimeCapability.EXECUTE_NATIVE),
            layer.capabilities,
        )
    }

    @Test
    fun `MesaTurnip layer has GPU_VULKAN and GPU_VULKAN_TURNIP capabilities`() {
        val layer = ElysiumRuntimeLayer.MesaTurnip(version = ElysiumPackageVersion(24, 1, 0))
        assertEquals(ElysiumRuntimeLayerId.MESA_TURNIP, layer.id)
        assertTrue(
            "expected GPU_VULKAN in ${layer.capabilities}",
            ElysiumRuntimeCapability.GPU_VULKAN in layer.capabilities,
        )
        assertTrue(
            "expected GPU_VULKAN_TURNIP in ${layer.capabilities}",
            ElysiumRuntimeCapability.GPU_VULKAN_TURNIP in layer.capabilities,
        )
    }

    @Test
    fun `Box64 layer has EXECUTE_X86_64 capability`() {
        val layer = ElysiumRuntimeLayer.Box64(version = ElysiumPackageVersion(0, 3, 2))
        assertEquals(ElysiumRuntimeLayerId.BOX64, layer.id)
        assertEquals(
            setOf(ElysiumRuntimeCapability.EXECUTE_X86_64),
            layer.capabilities,
        )
    }

    @Test
    fun `Fex layer has EXECUTE_X86 capability`() {
        val layer = ElysiumRuntimeLayer.Fex(version = ElysiumPackageVersion(2404, 0, 0))
        assertEquals(ElysiumRuntimeLayerId.FEX, layer.id)
        assertEquals(
            setOf(ElysiumRuntimeCapability.EXECUTE_X86),
            layer.capabilities,
        )
    }

    @Test
    fun `Wine layer has EXECUTE_WINDOWS capability`() {
        val layer = ElysiumRuntimeLayer.Wine(version = ElysiumPackageVersion(9, 0, 0))
        assertEquals(ElysiumRuntimeLayerId.WINE, layer.id)
        assertEquals(
            setOf(ElysiumRuntimeCapability.EXECUTE_WINDOWS),
            layer.capabilities,
        )
    }

    @Test
    fun `Native layer has ARM64 as default hostAbi`() {
        val layer = ElysiumRuntimeLayer.Native(version = ElysiumPackageVersion(1, 0, 0))
        assertEquals(ElysiumAbi.ARM64, layer.hostAbi)
    }

    @Test
    fun `Box64 layer has ARM64 as default hostAbi`() {
        val layer = ElysiumRuntimeLayer.Box64(version = ElysiumPackageVersion(0, 3, 2))
        assertEquals(ElysiumAbi.ARM64, layer.hostAbi)
    }

    // ============================================================
    // ElysiumRuntimeLayerManifest invariants
    // ============================================================

    @Test
    fun `manifest rejects blank displayName`() {
        try {
            buildSignedManifest(displayName = "")
            fail("expected IllegalArgumentException for blank displayName")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("displayName"))
        }
    }

    @Test
    fun `manifest rejects blank description`() {
        try {
            buildSignedManifest(description = "")
            fail("expected IllegalArgumentException for blank description")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("description"))
        }
    }

    @Test
    fun `manifest rejects blank homepage`() {
        try {
            buildSignedManifest(homepage = "")
            fail("expected IllegalArgumentException for blank homepage")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("homepage"))
        }
    }

    @Test
    fun `manifest rejects empty file list`() {
        try {
            buildSignedManifest(files = emptyList())
            fail("expected IllegalArgumentException for empty file list")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("files"))
        }
    }

    @Test
    fun `manifest rejects blank provides entry`() {
        try {
            buildSignedManifest(provides = listOf("", "valid-capability"))
            fail("expected IllegalArgumentException for blank provides entry")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("provides"))
        }
    }

    // ============================================================
    // ElysiumRuntimeLayerManifest signature
    // ============================================================

    @Test
    fun `manifest verifySignature accepts a correctly signed manifest`() {
        val key = TEST_KEY
        val manifest = buildSignedManifest(signingKey = key)
        val verifyResult = manifest.verifySignature(Signature(key))
        assertTrue(
            "expected signature verification success, got $verifyResult",
            verifyResult.isSuccess,
        )
    }

    @Test
    fun `manifest verifySignature rejects a wrong key`() {
        val manifest = buildSignedManifest(signingKey = TEST_KEY)
        val verifyResult = manifest.verifySignature(Signature("wrong-key"))
        assertTrue("expected failure for wrong key", verifyResult.isFailure)
        val error = verifyResult.exceptionOrNull()
        assertTrue(
            "expected SignatureMismatch, got $error",
            error is ElysiumRuntimeLayerVerificationError.SignatureMismatch,
        )
    }

    @Test
    fun `manifest canonical form excludes the signature`() {
        val manifest = buildSignedManifest()
        val canonical = manifest.canonicalForm
        assertFalse(
            "expected canonical to NOT include the signature, got: $canonical",
            canonical.contains("signature"),
        )
    }

    @Test
    fun `manifest canonical is deterministic for the same inputs`() {
        val a = buildSignedManifest()
        val b = buildSignedManifest()
        assertEquals(a.canonicalForm, b.canonicalForm)
    }

    @Test
    fun `manifest canonical is sensitive to the version`() {
        val a = buildSignedManifest(version = "1.0.0")
        val b = buildSignedManifest(version = "1.0.1")
        assertFalse(
            "expected different canonical forms for different versions",
            a.canonicalForm == b.canonicalForm,
        )
    }

    @Test
    fun `manifest canonical sorts capabilities deterministically`() {
        val a = buildSignedManifest(
            capabilities = setOf(
                ElysiumRuntimeCapability.GPU_VULKAN,
                ElysiumRuntimeCapability.GPU_VULKAN_TURNIP,
            ),
        )
        val b = buildSignedManifest(
            capabilities = setOf(
                ElysiumRuntimeCapability.GPU_VULKAN_TURNIP,
                ElysiumRuntimeCapability.GPU_VULKAN,
            ),
        )
        assertEquals(a.canonicalForm, b.canonicalForm)
    }

    // ============================================================
    // ElysiumRuntimeLayerCatalog
    // ============================================================

    @Test
    fun `catalog addLayer stores a verified manifest`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        val manifest = buildSignedManifest(signingKey = TEST_KEY)
        val result = catalog.addLayer(manifest, TEST_KEY)
        assertTrue("expected addLayer success, got $result", result.isSuccess)
        assertNotNull(catalog.find(manifest.id, manifest.version))
    }

    @Test
    fun `catalog addLayer rejects a manifest with a wrong signature`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        val manifest = buildSignedManifest(signingKey = "publisher-key")
        val result = catalog.addLayer(manifest, TEST_KEY)
        assertTrue("expected addLayer failure for wrong signature", result.isFailure)
    }

    @Test
    fun `catalog find returns null for a missing layer`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        assertNull(
            catalog.find(
                ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumPackageVersion(24, 1, 0),
            ),
        )
    }

    @Test
    fun `catalog latest returns the highest semver version`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        for (v in listOf("24.0.0", "24.1.0", "23.3.5")) {
            catalog.addLayer(
                buildSignedManifest(
                    id = ElysiumRuntimeLayerId.MESA_TURNIP,
                    version = v,
                    signingKey = TEST_KEY,
                ),
                TEST_KEY,
            )
        }
        val latest = catalog.latest(ElysiumRuntimeLayerId.MESA_TURNIP)
        assertNotNull(latest)
        assertEquals("24.1.0", latest!!.version.canonical)
    }

    @Test
    fun `catalog latest returns null for a missing layer`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        assertNull(catalog.latest(ElysiumRuntimeLayerId.MESA_TURNIP))
    }

    @Test
    fun `catalog latestForAbi returns the highest version for the requested ABI`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        // Two ARM64 versions + one X86_64 version.
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.MESA_TURNIP,
                version = "24.0.0",
                hostAbi = ElysiumAbi.ARM64,
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.MESA_TURNIP,
                version = "24.1.0",
                hostAbi = ElysiumAbi.ARM64,
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.MESA_TURNIP,
                version = "23.0.0",
                hostAbi = ElysiumAbi.X86_64,
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        val arm64 = catalog.latestForAbi(
            ElysiumRuntimeLayerId.MESA_TURNIP,
            ElysiumAbi.ARM64,
        )
        assertNotNull(arm64)
        assertEquals("24.1.0", arm64!!.version.canonical)
        assertEquals(ElysiumAbi.ARM64, arm64.hostAbi)
        // The X86_64 build is for a different host.
        val x86_64 = catalog.latestForAbi(
            ElysiumRuntimeLayerId.MESA_TURNIP,
            ElysiumAbi.X86_64,
        )
        assertNotNull(x86_64)
        assertEquals("23.0.0", x86_64!!.version.canonical)
        assertEquals(ElysiumAbi.X86_64, x86_64.hostAbi)
    }

    @Test
    fun `catalog latestForAbi returns null when no layer for the requested ABI`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.MESA_TURNIP,
                version = "24.0.0",
                hostAbi = ElysiumAbi.ARM64,
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        // No X86_64 build in the catalog.
        assertNull(
            catalog.latestForAbi(
                ElysiumRuntimeLayerId.MESA_TURNIP,
                ElysiumAbi.X86_64,
            ),
        )
    }

    @Test
    fun `catalog listVersions returns versions sorted descending`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        for (v in listOf("24.0.0", "24.1.0", "23.3.5")) {
            catalog.addLayer(
                buildSignedManifest(
                    id = ElysiumRuntimeLayerId.MESA_TURNIP,
                    version = v,
                    signingKey = TEST_KEY,
                ),
                TEST_KEY,
            )
        }
        val versions = catalog.listVersions(ElysiumRuntimeLayerId.MESA_TURNIP)
            .map { it.version.canonical }
        assertEquals(listOf("24.1.0", "24.0.0", "23.3.5"), versions)
    }

    @Test
    fun `catalog listVersions returns empty list for a missing layer`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        assertEquals(
            emptyList<ElysiumRuntimeLayerManifest>(),
            catalog.listVersions(ElysiumRuntimeLayerId.MESA_TURNIP),
        )
    }

    @Test
    fun `catalog listLayerIds returns ids sorted alphabetically`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        for (id in listOf("wine", "box64", "mesa-turnip")) {
            catalog.addLayer(
                buildSignedManifest(
                    id = ElysiumRuntimeLayerId(id),
                    version = "1.0.0",
                    signingKey = TEST_KEY,
                ),
                TEST_KEY,
            )
        }
        assertEquals(
            listOf("box64", "mesa-turnip", "wine"),
            catalog.listLayerIds().map { it.value },
        )
    }

    @Test
    fun `catalog size counts the total manifests across all ids`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.BOX64,
                version = "0.3.0",
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.BOX64,
                version = "0.3.2",
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        catalog.addLayer(
            buildSignedManifest(
                id = ElysiumRuntimeLayerId.WINE,
                version = "9.0.0",
                signingKey = TEST_KEY,
            ),
            TEST_KEY,
        )
        assertEquals(3, catalog.size())
    }

    @Test
    fun `catalog asLayer reconstructs the typed layer for a known id`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        val manifest = buildSignedManifest(
            id = ElysiumRuntimeLayerId.BOX64,
            version = "0.3.2",
            signingKey = TEST_KEY,
        )
        catalog.addLayer(manifest, TEST_KEY)
        val layer = catalog.asLayer(
            ElysiumRuntimeLayerId.BOX64,
            ElysiumPackageVersion.parse("0.3.2").getOrThrow(),
        )
        assertNotNull(layer)
        assertTrue(
            "expected Box64 layer, got $layer",
            layer is ElysiumRuntimeLayer.Box64,
        )
    }

    @Test
    fun `catalog asLayer returns null for a missing layer`() {
        val catalog = ElysiumRuntimeLayerCatalog()
        assertNull(
            catalog.asLayer(
                ElysiumRuntimeLayerId.BOX64,
                ElysiumPackageVersion.parse("0.3.2").getOrThrow(),
            ),
        )
    }

    // ============================================================
    // ElysiumRuntimeLayerDefaults
    // ============================================================

    @Test
    fun `every default layer verifies with the default signing key`() {
        for (manifest in ElysiumRuntimeLayerDefaults.ALL) {
            val verifyResult = manifest.verifySignature(
                Signature(ElysiumRuntimeLayerDefaults.DEFAULT_SIGNING_KEY),
            )
            assertTrue(
                "expected default layer ${manifest.id.value}@${manifest.version.canonical} " +
                    "to verify with the default signing key, got $verifyResult",
                verifyResult.isSuccess,
            )
        }
    }

    @Test
    fun `every default layer has at least one file`() {
        for (manifest in ElysiumRuntimeLayerDefaults.ALL) {
            assertTrue(
                "expected default layer ${manifest.id.value} to have at least one file",
                manifest.files.isNotEmpty(),
            )
        }
    }

    @Test
    fun `every default layer has at least one capability`() {
        for (manifest in ElysiumRuntimeLayerDefaults.ALL) {
            assertTrue(
                "expected default layer ${manifest.id.value} to have at least one capability",
                manifest.capabilities.isNotEmpty(),
            )
        }
    }

    @Test
    fun `the default catalog contains every well-known layer`() {
        val ids = ElysiumRuntimeLayerDefaults.ALL.map { it.id.value }.toSet()
        assertTrue(
            "expected native layer in default catalog, got $ids",
            ElysiumRuntimeLayerId.NATIVE.value in ids,
        )
        assertTrue(
            "expected mesa-turnip layer in default catalog, got $ids",
            ElysiumRuntimeLayerId.MESA_TURNIP.value in ids,
        )
        assertTrue(
            "expected box64 layer in default catalog, got $ids",
            ElysiumRuntimeLayerId.BOX64.value in ids,
        )
        assertTrue(
            "expected fex layer in default catalog, got $ids",
            ElysiumRuntimeLayerId.FEX.value in ids,
        )
        assertTrue(
            "expected wine layer in default catalog, got $ids",
            ElysiumRuntimeLayerId.WINE.value in ids,
        )
    }

    // ============================================================
    // ElysiumRuntimeLayerVerificationError
    // ============================================================

    @Test
    fun `UnsupportedAbi error message mentions both ABIs`() {
        val error = ElysiumRuntimeLayerVerificationError.UnsupportedAbi(
            layerId = "mesa-turnip",
            version = ElysiumPackageVersion(24, 1, 0),
            layerHostAbi = ElysiumAbi.ARM64,
            requestedHostAbi = ElysiumAbi.X86_64,
        )
        val message = error.message ?: ""
        assertTrue(
            "expected message to mention 'arm64-v8a', got: $message",
            message.contains("arm64-v8a"),
        )
        assertTrue(
            "expected message to mention 'x86_64', got: $message",
            message.contains("x86_64"),
        )
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildSignedManifest(
        id: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId.MESA_TURNIP,
        version: String = "24.1.0",
        hostAbi: ElysiumAbi = ElysiumAbi.ARM64,
        displayName: String = "Mesa Turnip",
        capabilities: Set<ElysiumRuntimeCapability> = setOf(
            ElysiumRuntimeCapability.GPU_VULKAN,
            ElysiumRuntimeCapability.GPU_VULKAN_TURNIP,
        ),
        description: String = "Test layer",
        homepage: String = "https://example.com/",
        deps: List<ElysiumPackageDependency> = emptyList(),
        provides: List<String> = listOf("elysium-runtime-test"),
        files: List<ElysiumPackageFile> = listOf(
            ElysiumPackageFile(
                installPath = "/usr/lib/elysium/runtime/test/file.so",
                contentHash = com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash("0".repeat(64)),
                permissions = FilePermissions(mode = 0x1A4),
            ),
        ),
        signingKey: String = TEST_KEY,
    ): ElysiumRuntimeLayerManifest {
        val v = ElysiumPackageVersion.parse(version).getOrThrow()
        val unsigned = ElysiumRuntimeLayerManifest(
            id = id,
            version = v,
            hostAbi = hostAbi,
            displayName = displayName,
            capabilities = capabilities,
            dependencies = deps,
            provides = provides,
            files = files,
            description = description,
            homepage = homepage,
            contentHash = com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash("0".repeat(64)),
            signature = Signature("placeholder"),
        )
        return unsigned.copy(
            signature = Signature.sign(
                payload = unsigned.canonicalForm.toByteArray(Charsets.UTF_8),
                key = signingKey.toByteArray(),
            ),
        )
    }

    companion object {
        const val TEST_KEY: String = "elysium-linux-test-signing-key"
    }
}
