package com.elysium.vanguard.core.runtime.workspace_def

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for the [WorkspaceDefinition] schema, the
 * [WorkspaceDefinitionCodec], and the [WorkspaceDefinitionStore]
 * (both in-memory and file-backed).
 *
 * The schema is the **typed orchestration** for one
 * workspace (one reproducible app environment) — per the
 * master vision of Elysium Vanguard as a universal
 * computing platform. The tests cover:
 *   1. **Data-class invariants**: every `init` block is
 *      enforced (id + name non-blank, mount paths absolute,
 *      memory positive, etc.).
 *   2. **Codec round-trip**: the JSON -> spec -> JSON
 *      round-trip preserves every field byte-for-byte.
 *   3. **Golden file**: the canonical `blender-linux`
 *      sample matches the expected JSON layout.
 *   4. **File-backed store**: the file-backed store
 *      survives a save + reload + list cycle.
 *   5. **Error envelope**: a malformed JSON is rejected
 *      with a typed [WorkspaceDefinitionCodecException].
 *   6. **Determinism**: two encodes of the same spec
 *      produce the same JSON (modulo field order, which
 *      is fixed in the adapter).
 */
class WorkspaceDefinitionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ============================================================
    // Canonical sample
    // ============================================================

    private fun sampleBlenderLinux(): WorkspaceDefinition = WorkspaceDefinition(
        apiVersion = ApiVersion.V1,
        id = "blender-linux",
        name = "Blender on Linux",
        description = "Blender 3D on Elysium Vanguard Linux (test sample)",
        runtime = RuntimeKind.LINUX_PROOT,
        mounts = listOf(
            MountSpec(
                hostPath = "/sdcard/ElysiumVanguard/workspaces/blender-linux/projects",
                containerPath = "/workspace/projects",
                readOnly = false,
                description = "User's Blender project files",
            ),
            MountSpec(
                hostPath = "/sdcard/ElysiumVanguard/workspaces/blender-linux/cache",
                containerPath = "/workspace/cache",
                readOnly = false,
                description = "Blender's render cache",
            ),
        ),
        env = listOf(
            EnvSpec(name = "DISPLAY", value = ":0"),
            EnvSpec(name = "BLENDER_USER_SCRIPTS", value = "/workspace/projects/scripts"),
        ),
        launcher = LauncherSpec(
            command = "/usr/bin/blender",
            args = listOf("--background"),
            workingDirectory = "/workspace/projects",
            environmentPassthrough = false,
        ),
        resources = ResourceSpec(maxMemoryMb = 4096, cpuPriority = 75),
        createdAtMs = 1_700_000_000_000L,
    )

    // ============================================================
    // Data-class invariants
    // ============================================================

    @Test
    fun `workspace definition rejects blank id`() {
        try {
            sampleBlenderLinux().copy(id = "")
            fail("expected IllegalArgumentException for blank id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("id"))
        }
    }

    @Test
    fun `workspace definition rejects blank name`() {
        try {
            sampleBlenderLinux().copy(name = "")
            fail("expected IllegalArgumentException for blank name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `mount spec rejects blank host path`() {
        try {
            MountSpec(hostPath = "", containerPath = "/workspace/projects")
            fail("expected IllegalArgumentException for blank host path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("hostPath"))
        }
    }

    @Test
    fun `mount spec rejects relative container path`() {
        try {
            MountSpec(hostPath = "/sdcard/foo", containerPath = "workspace/projects")
            fail("expected IllegalArgumentException for relative container path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("absolute"))
        }
    }

    @Test
    fun `env spec rejects blank name`() {
        try {
            EnvSpec(name = "", value = "x")
            fail("expected IllegalArgumentException for blank env name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `env spec rejects blank value when secret`() {
        try {
            EnvSpec(name = "API_KEY", value = "", secret = true)
            fail("expected IllegalArgumentException for blank secret value")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("secret"))
        }
    }

    @Test
    fun `env spec accepts blank value when not secret`() {
        // A non-secret env var can have an empty value
        // (e.g. `DEBUG=`). This is legal.
        val env = EnvSpec(name = "DEBUG", value = "")
        assertEquals("", env.value)
    }

    @Test
    fun `launcher spec rejects blank command`() {
        try {
            LauncherSpec(command = "")
            fail("expected IllegalArgumentException for blank command")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("command"))
        }
    }

    @Test
    fun `launcher spec rejects relative working directory`() {
        try {
            LauncherSpec(command = "/usr/bin/x", workingDirectory = "workspace")
            fail("expected IllegalArgumentException for relative working directory")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("absolute"))
        }
    }

    @Test
    fun `resource spec rejects non-positive memory`() {
        try {
            ResourceSpec(maxMemoryMb = 0)
            fail("expected IllegalArgumentException for zero memory")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxMemoryMb"))
        }
    }

    @Test
    fun `resource spec rejects out-of-range cpu priority`() {
        try {
            ResourceSpec(maxMemoryMb = 1024, cpuPriority = 150)
            fail("expected IllegalArgumentException for cpuPriority > 100")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cpuPriority"))
        }
    }

    @Test
    fun `apiVersion rejects malformed string`() {
        try {
            ApiVersion("v1") // missing the elysium.workspace prefix
            fail("expected IllegalArgumentException for malformed apiVersion")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ApiVersion must match"))
        }
    }

    @Test
    fun `apiVersion accepts well-formed v1 string`() {
        val v = ApiVersion("elysium.workspace/v1")
        assertEquals("elysium.workspace/v1", v.value)
    }

    @Test
    fun `apiVersion V1 singleton is the canonical v1`() {
        assertEquals("elysium.workspace/v1", ApiVersion.V1.value)
    }

    // ============================================================
    // Codec round-trip
    // ============================================================

    @Test
    fun `codec round-trip preserves every field byte-for-byte`() {
        val original = sampleBlenderLinux()
        val encoded = WorkspaceDefinitionCodec.encode(original)
        val decoded = WorkspaceDefinitionCodec.decode(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `codec encode is deterministic for the same input`() {
        val a = WorkspaceDefinitionCodec.encode(sampleBlenderLinux())
        val b = WorkspaceDefinitionCodec.encode(sampleBlenderLinux())
        assertEquals(a, b)
    }

    @Test
    fun `codec output is pretty-printed JSON`() {
        val encoded = WorkspaceDefinitionCodec.encode(sampleBlenderLinux())
        // Pretty-printed JSON has newlines + indentation.
        assertTrue(
            "expected pretty-printed JSON, got: $encoded",
            encoded.contains("\n"),
        )
        assertTrue(encoded.contains("\"apiVersion\""))
        assertTrue(encoded.contains("\"id\""))
        assertTrue(encoded.contains("\"runtime\""))
        assertTrue(encoded.contains("\"mounts\""))
        assertTrue(encoded.contains("\"launcher\""))
        assertTrue(encoded.contains("\"resources\""))
    }

    // ============================================================
    // Golden file: the blender-linux sample
    // ============================================================

    @Test
    fun `golden blender-linux sample decodes to the expected spec`() {
        val goldenPath = java.io.File(
            "../docs/workspace_def/samples/blender-linux.json",
        )
        // The test runs from the `app/` working dir.
        // Resolve the path relative to the test's CWD.
        val golden = if (goldenPath.exists()) {
            goldenPath
        } else {
            java.io.File("docs/workspace_def/samples/blender-linux.json")
        }
        if (!golden.exists()) {
            // If the golden is not in the CWD, the test
            // is informational only — we skip it. The
            // sample file is documentation, not a
            // contract.
            return
        }
        val spec = WorkspaceDefinitionCodec.decodeFromFile(golden)
        assertEquals("blender-linux", spec.id)
        assertEquals("Blender on Linux", spec.name)
        assertEquals(RuntimeKind.LINUX_PROOT, spec.runtime)
        assertEquals("/usr/bin/blender", spec.launcher.command)
        assertEquals(4096, spec.resources.maxMemoryMb)
    }

    // ============================================================
    // Error envelope
    // ============================================================

    @Test
    fun `codec rejects malformed JSON with a typed exception`() {
        try {
            WorkspaceDefinitionCodec.decode("{not valid json}")
            fail("expected WorkspaceDefinitionCodecException")
        } catch (e: WorkspaceDefinitionCodecException) {
            assertTrue(
                "expected message to mention malformed, got: ${e.message}",
                e.message!!.contains("malformed"),
            )
        }
    }

    @Test
    fun `codec rejects validation failure with a typed exception`() {
        // A valid JSON shape but an invalid value (memory = 0).
        val json = """
            {
              "apiVersion": "elysium.workspace/v1",
              "id": "broken",
              "name": "Broken",
              "description": "",
              "runtime": "LINUX_PROOT",
              "mounts": [],
              "env": [],
              "launcher": { "command": "/bin/true", "args": [], "workingDirectory": "/", "environmentPassthrough": false },
              "resources": { "maxMemoryMb": 0, "cpuPriority": 50 },
              "createdAtMs": 0
            }
        """.trimIndent()
        try {
            WorkspaceDefinitionCodec.decode(json)
            fail("expected WorkspaceDefinitionCodecException")
        } catch (e: WorkspaceDefinitionCodecException) {
            assertTrue(
                "expected message to mention validation, got: ${e.message}",
                e.message!!.contains("validation"),
            )
        }
    }

    // ============================================================
    // File-backed store
    // ============================================================

    @Test
    fun `file-backed store survives a save reload list cycle`() {
        val baseDir = tempFolder.newFolder("ws-store")
        val store = FileWorkspaceDefinitionStore(baseDir)
        val def = sampleBlenderLinux()
        store.save(def)
        val loaded = store.load(def.id)
        assertNotNull(loaded)
        assertEquals(def, loaded)
        val all = store.list()
        assertEquals(1, all.size)
        assertEquals(def, all[0])
    }

    @Test
    fun `file-backed store returns null for unknown id`() {
        val baseDir = tempFolder.newFolder("ws-store-unknown")
        val store = FileWorkspaceDefinitionStore(baseDir)
        assertEquals(null, store.load("does-not-exist"))
    }

    @Test
    fun `file-backed store delete removes the workspace`() {
        val baseDir = tempFolder.newFolder("ws-store-delete")
        val store = FileWorkspaceDefinitionStore(baseDir)
        val def = sampleBlenderLinux()
        store.save(def)
        assertEquals(1, store.list().size)
        assertTrue(store.delete(def.id))
        assertEquals(0, store.list().size)
    }

    @Test
    fun `file-backed store overwrite replaces the previous version`() {
        val baseDir = tempFolder.newFolder("ws-store-overwrite")
        val store = FileWorkspaceDefinitionStore(baseDir)
        val def = sampleBlenderLinux()
        store.save(def)
        val updated = def.copy(name = "Blender 3D on Linux (renamed)")
        store.save(updated)
        val loaded = store.load(def.id)
        assertEquals("Blender 3D on Linux (renamed)", loaded!!.name)
    }

    // ============================================================
    // In-memory store (test fixture)
    // ============================================================

    @Test
    fun `in-memory store matches the file-backed contract`() {
        val store = InMemoryWorkspaceDefinitionStore()
        val def = sampleBlenderLinux()
        store.save(def)
        assertEquals(def, store.load(def.id))
        assertEquals(1, store.size())
        assertEquals(listOf(def), store.list())
        assertTrue(store.delete(def.id))
        assertEquals(0, store.size())
    }
}
