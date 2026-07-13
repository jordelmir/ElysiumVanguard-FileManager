package com.elysium.vanguard.core.runtime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for ApplicationCapsule manifest format.
 */
class ApplicationCapsuleTest {

    @Test
    fun `capsule creation with valid data`() {
        val capsule = createTestCapsule()
        assertEquals("test-app", capsule.capsuleId)
        assertEquals("Test App", capsule.name)
        assertEquals(CapsuleType.LINUX_PROOT, capsule.type)
    }

    @Test
    fun `capsule rejects blank capsuleId`() {
        try {
            createTestCapsule().copy(capsuleId = "")
            assertTrue(false) // Should not reach here
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("capsuleId") == true)
        }
    }

    @Test
    fun `capsule rejects invalid capsuleId characters`() {
        try {
            createTestCapsule().copy(capsuleId = "Invalid ID!")
            assertTrue(false) // Should not reach here
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("invalid characters") == true)
        }
    }

    @Test
    fun `capsule json serialization contains all fields`() {
        val capsule = createTestCapsule()
        val json = capsule.toJson()
        assertTrue(json.contains("\"capsuleId\":\"test-app\""))
        assertTrue(json.contains("\"name\":\"Test App\""))
        assertTrue(json.contains("\"version\":\"1.0.0\""))
        assertTrue(json.contains("\"type\":\"LINUX_PROOT\""))
        assertTrue(json.contains("\"backend\":\"PROOT_LINUX\""))
        assertTrue(json.contains("\"command\":[\"/bin/bash\",\"-c\",\"echo hello\"]"))
        assertTrue(json.contains("\"autoStart\":false"))
        assertTrue(json.contains("\"foregroundService\":true"))
    }

    @Test
    fun `capsule json contains all required sections`() {
        val capsule = createTestCapsule()
        val json = capsule.toJson()
        assertTrue(json.contains("\"manifestVersion\":"))
        assertTrue(json.contains("\"metadata\":{"))
        assertTrue(json.contains("\"runtime\":{"))
        assertTrue(json.contains("\"capabilities\":["))
        assertTrue(json.contains("\"launch\":{"))
        assertTrue(json.contains("\"permissions\":["))
    }

    @Test
    fun `capsule parse handles malformed json`() {
        val result = ApplicationCapsule.parse("not json at all")
        assertNull(result)
    }

    @Test
    fun `capsule parse handles empty json`() {
        val result = ApplicationCapsule.parse("{}")
        assertNull(result) // Missing required fields
    }

    @Test
    fun `capsule with all capability types`() {
        val capsule = createTestCapsule().copy(
            capabilities = setOf(
                RequiredCapability.PTY,
                RequiredCapability.DISPLAY,
                RequiredCapability.AUDIO,
                RequiredCapability.CLIPBOARD,
                RequiredCapability.NETWORK,
                RequiredCapability.FILESYSTEM
            )
        )
        val json = capsule.toJson()
        assertTrue(json.contains("\"PTY\""))
        assertTrue(json.contains("\"DISPLAY\""))
        assertTrue(json.contains("\"AUDIO\""))
        assertTrue(json.contains("\"CLIPBOARD\""))
        assertTrue(json.contains("\"NETWORK\""))
        assertTrue(json.contains("\"FILESYSTEM\""))
    }

    @Test
    fun `capsule with permissions`() {
        val capsule = createTestCapsule().copy(
            permissions = setOf(
                Permission.INTERNET,
                Permission.FOREGROUND_SERVICE,
                Permission.WAKE_LOCK
            )
        )
        val json = capsule.toJson()
        assertTrue(json.contains("\"INTERNET\""))
        assertTrue(json.contains("\"FOREGROUND_SERVICE\""))
        assertTrue(json.contains("\"WAKE_LOCK\""))
    }

    @Test
    fun `capsule with integrity manifest`() {
        val capsule = createTestCapsule().copy(
            integrity = IntegrityManifest(
                sha256 = "abc123def456",
                sizeBytes = 1024L * 1024L,
                signedAtMs = 1234567890L
            )
        )
        val json = capsule.toJson()
        assertTrue(json.contains("\"integrity\":{"))
        assertTrue(json.contains("\"sha256\":\"abc123def456\""))
        assertTrue(json.contains("\"sizeBytes\":1048576"))
    }

    @Test
    fun `capsule with custom metadata`() {
        val capsule = createTestCapsule().copy(
            metadata = CapsuleMetadata(
                author = "Test Author",
                license = "MIT",
                homepage = "https://example.com",
                category = "development",
                tags = listOf("cli", "linux", "development")
            )
        )
        val json = capsule.toJson()
        assertTrue(json.contains("\"author\":\"Test Author\""))
        assertTrue(json.contains("\"license\":\"MIT\""))
        assertTrue(json.contains("\"category\":\"development\""))
        assertTrue(json.contains("\"tags\":[\"cli\",\"linux\",\"development\"]"))
    }

    @Test
    fun `all capsule types are serializable`() {
        CapsuleType.entries.forEach { type ->
            val capsule = createTestCapsule().copy(type = type)
            val json = capsule.toJson()
            assertTrue(json.contains("\"type\":\"${type.name}\""))
        }
    }

    @Test
    fun `all backend kinds are serializable`() {
        BackendKind.entries.forEach { backend ->
            val capsule = createTestCapsule().copy(
                runtime = RuntimeRequirements(backend = backend)
            )
            val json = capsule.toJson()
            assertTrue(json.contains("\"backend\":\"${backend.name}\""))
        }
    }

    private fun createTestCapsule(): ApplicationCapsule = ApplicationCapsule(
        capsuleId = "test-app",
        name = "Test App",
        displayName = "Test Application",
        version = "1.0.0",
        description = "A test application for unit testing",
        type = CapsuleType.LINUX_PROOT,
        metadata = CapsuleMetadata(
            author = "Test",
            license = "MIT",
            homepage = "https://example.com",
            category = "test"
        ),
        runtime = RuntimeRequirements(
            backend = BackendKind.PROOT_LINUX,
            distroId = "debian-stable",
            minAndroidSdk = 26,
            requiredBinaries = listOf("bash", "coreutils"),
            envOverrides = mapOf("TERM" to "xterm-256color")
        ),
        capabilities = setOf(RequiredCapability.PTY, RequiredCapability.FILESYSTEM),
        launch = LaunchConfiguration(
            command = listOf("/bin/bash", "-c", "echo hello"),
            workingDirectory = "/root",
            autoStart = false,
            foregroundService = true,
            networkExposed = false
        ),
        permissions = setOf(Permission.INTERNET, Permission.FOREGROUND_SERVICE)
    )
}
