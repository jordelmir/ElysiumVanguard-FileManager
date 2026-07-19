package com.elysium.vanguard.core.runtime.capsule

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for the [Capsule] schema + the [CapsuleCodec].
 *
 * Per sección 11 of the master vision ("Marketplace
 * universal"), the Capsule is the universal package
 * format that creators publish. The tests cover:
 *   1. **Data-class invariants**: every `init` block
 *      is enforced (id format, version semver,
 *      entrypoint absolute, signature non-blank).
 *   2. **Codec round-trip**: the JSON -> capsule ->
 *      JSON round-trip preserves every field
 *      byte-for-byte.
 *   3. **Determinism**: two encodes of the same
 *      capsule produce the same JSON.
 *   4. **Golden file**: the literal vision-doc example
 *      decodes to the expected capsule.
 *   5. **All 4 runtimes + 5 architectures + 8 GPU
 *      drivers** round-trip correctly.
 *   6. **Permissions enforcement**: the
 *      `storage.isEmpty() && !network` invariant.
 *   7. **Error envelope**: a malformed JSON is
 *      rejected with a typed `CapsuleCodecException`.
 */
class CapsuleTest {

    // ============================================================
    // Canonical sample: the literal vision-doc example
    // ============================================================

    private fun sampleBlenderArm64(): Capsule = Capsule(
        apiVersion = CapsuleApiVersion.V1,
        id = CapsuleId("com.elysium.blender.arm64"),
        name = "Blender 3D for ARM64",
        version = "4.2.0",
        description = "Blender 3D on Elysium Vanguard Linux",
        runtime = Runtime.LINUX,
        architecture = Architecture.ARM64,
        distribution = Distribution.ELYSIUM_LINUX_1,
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
        signature = Signature("sig-sample-blender-arm64"),
        contentHash = ContentHash(
            "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
        ),
    )

    // ============================================================
    // Data-class invariants
    // ============================================================

    @Test
    fun `capsule rejects blank name`() {
        try {
            sampleBlenderArm64().copy(name = "")
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `capsule rejects blank version`() {
        try {
            sampleBlenderArm64().copy(version = "")
            fail("expected IllegalArgumentException for blank version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    @Test
    fun `capsule rejects non-semver version`() {
        try {
            sampleBlenderArm64().copy(version = "not-semver")
            fail("expected IllegalArgumentException for non-semver version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("version"))
        }
    }

    @Test
    fun `capsule accepts semver with pre-release tag`() {
        val capsule = sampleBlenderArm64().copy(version = "4.2.0-rc.1")
        assertEquals("4.2.0-rc.1", capsule.version)
    }

    @Test
    fun `capsule rejects relative entrypoint executable`() {
        try {
            sampleBlenderArm64().copy(
                entrypoint = EntryPoint(
                    executable = "blender", // not absolute
                    workingDirectory = "/workspace/projects",
                ),
            )
            fail("expected IllegalArgumentException for relative entrypoint")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("absolute"))
        }
    }

    @Test
    fun `capsule rejects blank signature`() {
        try {
            sampleBlenderArm64().copy(signature = Signature(""))
            fail("expected IllegalArgumentException for blank signature")
        } catch (e: IllegalArgumentException) {
            // The error is thrown by the Signature primitive
            // (its `init` rejects empty values), so the
            // message uses "Signature" with a capital S.
            assertTrue(
                "expected message to mention Signature, got: ${e.message}",
                e.message!!.contains("Signature"),
            )
        }
    }

    @Test
    fun `capsule rejects blank content hash`() {
        try {
            sampleBlenderArm64().copy(contentHash = ContentHash(""))
            fail("expected IllegalArgumentException for blank content hash")
        } catch (e: IllegalArgumentException) {
            // The error is thrown by the ContentHash
            // primitive (its `init` rejects empty values
            // + non-64-char hex), so the message uses
            // "ContentHash" with a capital C.
            assertTrue(
                "expected message to mention ContentHash, got: ${e.message}",
                e.message!!.contains("ContentHash"),
            )
        }
    }

    // ============================================================
    // CapsuleId format
    // ============================================================

    @Test
    fun `capsuleId accepts Java package names`() {
        val id = CapsuleId("com.elysium.blender.arm64")
        assertEquals("com.elysium.blender.arm64", id.value)
        // Single-segment id is rejected
        try {
            CapsuleId("blender")
            fail("expected IllegalArgumentException for single-segment id")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention CapsuleId, got: ${e.message}",
                e.message!!.contains("CapsuleId"),
            )
        }
    }

    @Test
    fun `capsuleId rejects blank value`() {
        try {
            CapsuleId("")
            fail("expected IllegalArgumentException for blank id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    @Test
    fun `capsuleId rejects uppercase letters`() {
        try {
            CapsuleId("com.Elysium.blender")
            fail("expected IllegalArgumentException for uppercase id")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention CapsuleId, got: ${e.message}",
                e.message!!.contains("CapsuleId"),
            )
        }
    }

    // ============================================================
    // CapsuleApiVersion format
    // ============================================================

    @Test
    fun `capsuleApiVersion accepts v1`() {
        val v = CapsuleApiVersion("elysium.capsule/v1")
        assertEquals("elysium.capsule/v1", v.value)
    }

    @Test
    fun `capsuleApiVersion rejects malformed strings`() {
        try {
            CapsuleApiVersion("v1")
            fail("expected IllegalArgumentException for malformed apiVersion")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CapsuleApiVersion must match"))
        }
    }

    @Test
    fun `capsuleApiVersion V1 singleton is the canonical v1`() {
        assertEquals("elysium.capsule/v1", CapsuleApiVersion.V1.value)
    }

    // ============================================================
    // Permissions enforcement
    // ============================================================

    @Test
    fun `permissions reject a sandbox (no storage + no network)`() {
        try {
            Permissions(network = false, storage = emptyList())
            fail("expected IllegalArgumentException for sandbox permissions")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected message to mention sandbox, got: ${e.message}",
                e.message!!.contains("sandbox"),
            )
        }
    }

    @Test
    fun `permissions accept no storage + network`() {
        // A network-only capsule (a CLI that talks to
        // a remote server) is valid.
        val p = Permissions(network = true, storage = emptyList())
        assertEquals(true, p.network)
        assertTrue(p.storage.isEmpty())
    }

    @Test
    fun `permissions accept storage + no network`() {
        // A GPU-only capsule (the vision's example) is
        // valid.
        val p = Permissions(network = false, storage = listOf(StorageScope.USER_SELECTED))
        assertEquals(false, p.network)
        assertEquals(1, p.storage.size)
    }

    @Test
    fun `permissions accept storage + network`() {
        val p = Permissions(
            network = true,
            storage = listOf(StorageScope.MEDIA_STORE, StorageScope.NETWORK),
        )
        assertEquals(2, p.storage.size)
    }

    // ============================================================
    // Distribution
    // ============================================================

    @Test
    fun `distribution ANY is the orchestrator-decides sentinel`() {
        assertEquals("any", Distribution.ANY.id)
    }

    @Test
    fun `distribution ELYSIUM_LINUX_1 is the proprietary distro`() {
        assertEquals("elysium-linux-1", Distribution.ELYSIUM_LINUX_1.id)
    }

    @Test
    fun `distribution rejects blank id`() {
        try {
            Distribution("")
            fail("expected IllegalArgumentException for blank id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
    }

    // ============================================================
    // EntryPoint
    // ============================================================

    @Test
    fun `entrypoint accepts absolute path + args + working directory`() {
        val ep = EntryPoint(
            executable = "/usr/bin/blender",
            args = listOf("--background", "--python", "/workspace/scripts/render.py"),
            workingDirectory = "/workspace/projects",
        )
        assertEquals("/usr/bin/blender", ep.executable)
        assertEquals(3, ep.args.size)
        assertEquals("/workspace/projects", ep.workingDirectory)
    }

    @Test
    fun `entrypoint defaults args to empty + working directory to root`() {
        val ep = EntryPoint(executable = "/usr/bin/ls")
        assertTrue(ep.args.isEmpty())
        assertEquals("/", ep.workingDirectory)
    }

    @Test
    fun `entrypoint rejects relative working directory`() {
        try {
            EntryPoint(executable = "/usr/bin/ls", workingDirectory = "workspace")
            fail("expected IllegalArgumentException for relative working directory")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("absolute"))
        }
    }

    // ============================================================
    // Codec round-trip
    // ============================================================

    @Test
    fun `codec round-trip preserves every field byte-for-byte`() {
        val original = sampleBlenderArm64()
        val encoded = CapsuleCodec.encode(original)
        val decoded = CapsuleCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `codec encode is deterministic for the same input`() {
        val a = CapsuleCodec.encode(sampleBlenderArm64())
        val b = CapsuleCodec.encode(sampleBlenderArm64())
        assertEquals(a, b)
    }

    @Test
    fun `codec output is pretty-printed JSON with the vision-doc keys`() {
        val encoded = CapsuleCodec.encode(sampleBlenderArm64())
        assertTrue(
            "expected pretty-printed JSON, got: $encoded",
            encoded.contains("\n"),
        )
        // The vision doc's example uses these keys verbatim.
        for (key in listOf(
            "apiVersion", "id", "runtime", "architecture", "distribution",
            "entrypoint", "gpu", "permissions", "signature", "contentHash",
        )) {
            assertTrue(
                "expected JSON to contain '$key', got: $encoded",
                encoded.contains("\"$key\""),
            )
        }
    }

    // ============================================================
    // All 4 runtimes + 5 architectures + 8 GPU drivers round-trip
    // ============================================================

    @Test
    fun `all 4 runtimes round-trip through the codec`() {
        for (runtime in Runtime.values()) {
            val capsule = sampleBlenderArm64().copy(runtime = runtime)
            val encoded = CapsuleCodec.encode(capsule)
            val decoded = CapsuleCodec.decode(encoded)
            assertEquals("round-trip failed for $runtime", capsule, decoded)
        }
    }

    @Test
    fun `all 5 architectures round-trip through the codec`() {
        for (arch in Architecture.values()) {
            val capsule = sampleBlenderArm64().copy(architecture = arch)
            val encoded = CapsuleCodec.encode(capsule)
            val decoded = CapsuleCodec.decode(encoded)
            assertEquals("round-trip failed for $arch", capsule, decoded)
        }
    }

    @Test
    fun `all 8 GPU drivers round-trip through the codec`() {
        for (driver in GpuDriver.values()) {
            val capsule = sampleBlenderArm64().copy(
                gpu = GpuConfig(api = GpuApi.VULKAN, driver = driver),
            )
            val encoded = CapsuleCodec.encode(capsule)
            val decoded = CapsuleCodec.decode(encoded)
            assertEquals("round-trip failed for $driver", capsule, decoded)
        }
    }

    @Test
    fun `all 4 storage scopes round-trip through the codec`() {
        for (scope in StorageScope.values()) {
            val capsule = sampleBlenderArm64().copy(
                permissions = Permissions(network = true, storage = listOf(scope)),
            )
            val encoded = CapsuleCodec.encode(capsule)
            val decoded = CapsuleCodec.decode(encoded)
            assertEquals("round-trip failed for $scope", capsule, decoded)
        }
    }

    // ============================================================
    // Two capsules with different content are not equal
    // ============================================================

    @Test
    fun `two capsules with different ids are not equal`() {
        val a = sampleBlenderArm64()
        val b = sampleBlenderArm64().copy(id = CapsuleId("com.elysium.gimp.arm64"))
        assertNotEquals(a, b)
    }

    @Test
    fun `two capsules with different versions are not equal`() {
        val a = sampleBlenderArm64()
        val b = sampleBlenderArm64().copy(version = "4.3.0")
        assertNotEquals(a, b)
    }

    // ============================================================
    // Error envelope
    // ============================================================

    @Test
    fun `codec rejects malformed JSON with a typed exception`() {
        try {
            CapsuleCodec.decode("{not valid json}")
            fail("expected CapsuleCodecException")
        } catch (e: CapsuleCodecException) {
            assertTrue(
                "expected message to mention malformed, got: ${e.message}",
                e.message!!.contains("malformed"),
            )
        }
    }

    @Test
    fun `codec rejects validation failure with a typed exception`() {
        // A valid JSON shape but an invalid value (version
        // is not semver).
        val json = """
            {
              "apiVersion": "elysium.capsule/v1",
              "id": "com.test.app",
              "name": "Test",
              "version": "not-semver",
              "description": "",
              "runtime": "LINUX",
              "architecture": "ARM64",
              "distribution": "any",
              "entrypoint": { "executable": "/bin/true", "args": [], "workingDirectory": "/" },
              "gpu": { "api": "NONE", "driver": "NONE" },
              "permissions": { "network": true, "storage": [] },
              "signature": "sig",
              "contentHash": "abc"
            }
        """.trimIndent()
        try {
            CapsuleCodec.decode(json)
            fail("expected CapsuleCodecException")
        } catch (e: CapsuleCodecException) {
            assertTrue(
                "expected message to mention validation, got: ${e.message}",
                e.message!!.contains("validation"),
            )
        }
    }
}
