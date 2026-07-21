package com.elysium.vanguard.core.runtime.workspace_def

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 104 — JSON codec tests for the new
 * workspace policy fields. The codec must:
 *
 *   1. Round-trip the three new fields byte-stable.
 *   2. Back-compat decode a Phase 66 JSON file (no
 *      `gpu` / `network` / `backup` keys) using the
 *      safe defaults (NONE / DENY_ALL / NONE).
 *   3. Refuse to decode a malformed policy
 *      (e.g. NETWORK mode=DENY_ALL + allowedHosts) by
 *      surfacing the typed [WorkspaceDefinitionCodecException].
 */
class WorkspaceDefinitionPolicyCodecTest {

    @Test
    fun `round-trip preserves the new policy fields byte-stable`() {
        val original = sampleBlenderLinux().copy(
            gpu = GpuAccessSpec(
                kind = GpuAccessKind.FULL_3D,
                vendor = GpuVendor.ADRENO,
                driverEnvOverrides = mapOf("MESA_LOADER_DRIVER_OVERRIDE" to "panfrost"),
            ),
            network = NetworkPolicySpec(
                mode = NetworkAccessMode.ALLOW_LIST,
                allowedHosts = listOf("api.example.com", "*.cdn.example.com"),
                allowedPorts = setOf(443, 8443),
                dnsAllowed = true,
            ),
            backup = BackupPolicySpec.SCHEDULED_15MIN,
        )
        val json = WorkspaceDefinitionCodec.encode(original)
        val decoded = WorkspaceDefinitionCodec.decode(json)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip with default policy fields is byte-stable`() {
        // When the user doesn't override the policy
        // fields, the defaults must round-trip
        // identically (GpuAccessSpec.NONE,
        // NetworkPolicySpec.DEFAULT, BackupPolicySpec.NONE).
        val original = sampleBlenderLinux()
        val json = WorkspaceDefinitionCodec.encode(original)
        val decoded = WorkspaceDefinitionCodec.decode(json)
        assertEquals(original, decoded)
        assertEquals(GpuAccessSpec.NONE, decoded.gpu)
        assertEquals(NetworkPolicySpec.DEFAULT, decoded.network)
        assertEquals(BackupPolicySpec.NONE, decoded.backup)
    }

    @Test
    fun `back-compat decode a Phase 66 file (no policy keys) yields the safe defaults`() {
        // A JSON file written before Phase 104 has no
        // `gpu` / `network` / `backup` keys. The codec
        // must still decode it (no exception) and
        // populate the policy fields with the safe
        // defaults: NONE GPU + DENY_ALL network + NONE
        // backup. This is the "red denegada por
        // defecto" principle from the vision.
        val legacyJson = """
        {
          "apiVersion": "elysium.workspace/v1",
          "id": "blender-legacy",
          "name": "Blender (legacy)",
          "description": "Pre-Phase-104 file",
          "runtime": "LINUX_PROOT",
          "mounts": [
            {
              "hostPath": "/sdcard/ElysiumVanguard/workspaces/blender-legacy/projects",
              "containerPath": "/workspace/projects",
              "readOnly": false,
              "description": "Projects"
            }
          ],
          "env": [],
          "launcher": {
            "command": "/usr/bin/blender",
            "args": [],
            "workingDirectory": "/workspace",
            "environmentPassthrough": false
          },
          "resources": {
            "maxMemoryMb": 4096,
            "cpuPriority": 50
          },
          "createdAtMs": 1700000000000
        }
        """.trimIndent()
        val decoded = WorkspaceDefinitionCodec.decode(legacyJson)
        assertEquals("blender-legacy", decoded.id)
        // The default policy fields are populated.
        assertEquals(GpuAccessSpec.NONE, decoded.gpu)
        assertEquals(NetworkPolicySpec.DEFAULT, decoded.network)
        assertEquals(BackupPolicySpec.NONE, decoded.backup)
    }

    @Test
    fun `decode rejects a malformed NetworkPolicySpec via the typed codec exception`() {
        // NETWORK mode=DENY_ALL + non-empty allowedHosts
        // is a misconfiguration that the data class's
        // init block rejects. The codec wraps the
        // IAE in a typed WorkspaceDefinitionCodecException.
        val badJson = """
        {
          "apiVersion": "elysium.workspace/v1",
          "id": "bad-net",
          "name": "Bad Network",
          "description": "misconfig",
          "runtime": "LINUX_PROOT",
          "mounts": [
            {
              "hostPath": "/sdcard/x",
              "containerPath": "/workspace"
            }
          ],
          "env": [],
          "launcher": {
            "command": "/bin/sh",
            "args": [],
            "workingDirectory": "/",
            "environmentPassthrough": false
          },
          "resources": {
            "maxMemoryMb": 1024,
            "cpuPriority": 50
          },
          "network": {
            "mode": "DENY_ALL",
            "allowedHosts": ["api.example.com"]
          },
          "createdAtMs": 1700000000000
        }
        """.trimIndent()
        try {
            WorkspaceDefinitionCodec.decode(badJson)
            org.junit.Assert.fail("expected WorkspaceDefinitionCodecException")
        } catch (e: WorkspaceDefinitionCodecException) {
            assertTrue(
                "error must mention validation: ${e.message}",
                e.message!!.contains("validation")
            )
        }
    }

    @Test
    fun `encode writes the gpu network and backup keys explicitly`() {
        // The JSON must contain the three new keys
        // (not omit them when the user uses the
        // defaults) so the round-trip is deterministic.
        val json = WorkspaceDefinitionCodec.encode(sampleBlenderLinux())
        assertTrue("gpu key must be present: $json", json.contains("\"gpu\""))
        assertTrue("network key must be present: $json", json.contains("\"network\""))
        assertTrue("backup key must be present: $json", json.contains("\"backup\""))
    }

    private fun sampleBlenderLinux(): WorkspaceDefinition = WorkspaceDefinition(
        apiVersion = ApiVersion.V1,
        id = "blender-linux-104",
        name = "Blender on Linux (Phase 104)",
        description = "Phase 104 sample with full policy fields",
        runtime = RuntimeKind.LINUX_PROOT,
        mounts = listOf(
            MountSpec(
                hostPath = "/sdcard/ElysiumVanguard/workspaces/blender/projects",
                containerPath = "/workspace/projects",
                readOnly = false,
            ),
        ),
        env = emptyList(),
        launcher = LauncherSpec(command = "/usr/bin/blender"),
        resources = ResourceSpec(maxMemoryMb = 4096, cpuPriority = 75),
        createdAtMs = 1_700_000_000_000L,
    )
}
