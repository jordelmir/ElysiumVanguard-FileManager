package com.elysium.vanguard.core.runtime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ApplicationCapsule registry and lifecycle.
 */
class ApplicationCapsuleRegistryTest {

    @Test
    fun `registry stores and retrieves capsules`() {
        val registry = CapsuleRegistry()
        val capsule = createTestCapsule("app-1")
        registry.register(capsule)
        assertEquals(capsule, registry.get("app-1"))
    }

    @Test
    fun `registry returns null for unknown capsule`() {
        val registry = CapsuleRegistry()
        assertEquals(null, registry.get("nonexistent"))
    }

    @Test
    fun `registry lists all registered capsules`() {
        val registry = CapsuleRegistry()
        registry.register(createTestCapsule("app-1"))
        registry.register(createTestCapsule("app-2"))
        registry.register(createTestCapsule("app-3"))
        assertEquals(3, registry.list().size)
    }

    @Test
    fun `registry removes capsule`() {
        val registry = CapsuleRegistry()
        registry.register(createTestCapsule("app-1"))
        registry.remove("app-1")
        assertEquals(null, registry.get("app-1"))
        assertEquals(0, registry.list().size)
    }

    @Test
    fun `registry replace on duplicate id`() {
        val registry = CapsuleRegistry()
        val v1 = createTestCapsule("app-1").copy(version = "1.0.0")
        val v2 = createTestCapsule("app-1").copy(version = "2.0.0")
        registry.register(v1)
        registry.register(v2)
        assertEquals("2.0.0", registry.get("app-1")?.version)
        assertEquals(1, registry.list().size)
    }

    @Test
    fun `registry filter by type`() {
        val registry = CapsuleRegistry()
        registry.register(createTestCapsule("linux-app", CapsuleType.LINUX_PROOT))
        registry.register(createTestCapsule("wine-app", CapsuleType.WINDOWS_WINE))
        registry.register(createTestCapsule("remote-app", CapsuleType.REMOTE))
        val linuxApps = registry.listByType(CapsuleType.LINUX_PROOT)
        assertEquals(1, linuxApps.size)
        assertEquals("linux-app", linuxApps[0].capsuleId)
    }

    @Test
    fun `registry filter by capability`() {
        val registry = CapsuleRegistry()
        registry.register(
            createTestCapsule("app-with-display").copy(
                capabilities = setOf(RequiredCapability.PTY, RequiredCapability.DISPLAY)
            )
        )
        registry.register(
            createTestCapsule("app-terminal-only").copy(
                capabilities = setOf(RequiredCapability.PTY)
            )
        )
        val displayApps = registry.listByCapability(RequiredCapability.DISPLAY)
        assertEquals(1, displayApps.size)
        assertEquals("app-with-display", displayApps[0].capsuleId)
    }

    @Test
    fun `registry search by name`() {
        val registry = CapsuleRegistry()
        registry.register(createTestCapsule("gimp-editor").copy(displayName = "GIMP Image Editor"))
        registry.register(createTestCapsule("vim-editor").copy(displayName = "Vim Text Editor"))
        registry.register(createTestCapsule("firefox-browser").copy(displayName = "Firefox Browser"))
        val editors = registry.search("editor")
        assertEquals(2, editors.size)
    }

    @Test
    fun `capsule manifest roundtrip`() {
        val original = createTestCapsule("test-app")
        val json = original.toJson()
        val parsed = ApplicationCapsule.parse(json)
        assertNotNull(parsed)
        assertEquals(original.capsuleId, parsed!!.capsuleId)
        assertEquals(original.name, parsed.name)
        assertEquals(original.type, parsed.type)
    }

    @Test
    fun `capsule with all types generates valid json`() {
        CapsuleType.entries.forEach { type ->
            val capsule = createTestCapsule("test-${type.name.lowercase()}").copy(type = type)
            val json = capsule.toJson()
            assertTrue("JSON should contain type ${type.name}", json.contains("\"type\":\"${type.name}\""))
            val parsed = ApplicationCapsule.parse(json)
            assertNotNull("Should parse ${type.name} capsule", parsed)
        }
    }

    @Test
    fun `capsule lifecycle states`() {
        val states = listOf(
            CapsuleLifecycle.INSTALLED,
            CapsuleLifecycle.READY,
            CapsuleLifecycle.RUNNING,
            CapsuleLifecycle.SUSPENDED,
            CapsuleLifecycle.ERROR,
            CapsuleLifecycle.UNINSTALLED
        )
        assertEquals(6, states.size)
        assertTrue(states.contains(CapsuleLifecycle.INSTALLED))
        assertTrue(states.contains(CapsuleLifecycle.RUNNING))
    }

    private fun createTestCapsule(
        id: String,
        type: CapsuleType = CapsuleType.LINUX_PROOT
    ): ApplicationCapsule = ApplicationCapsule(
        capsuleId = id,
        name = "Test $id",
        displayName = "Test Application $id",
        version = "1.0.0",
        description = "Test application",
        type = type,
        metadata = CapsuleMetadata(author = "Test", license = "MIT", homepage = "", category = "test"),
        runtime = RuntimeRequirements(backend = BackendKind.PROOT_LINUX),
        capabilities = setOf(RequiredCapability.PTY),
        launch = LaunchConfiguration(command = listOf("/bin/sh")),
        permissions = emptySet()
    )
}

enum class CapsuleLifecycle {
    INSTALLED,
    READY,
    RUNNING,
    SUSPENDED,
    ERROR,
    UNINSTALLED
}

class CapsuleRegistry {
    private val capsules = mutableMapOf<String, ApplicationCapsule>()

    fun register(capsule: ApplicationCapsule) {
        capsules[capsule.capsuleId] = capsule
    }

    fun get(id: String): ApplicationCapsule? = capsules[id]

    fun remove(id: String) { capsules.remove(id) }

    fun list(): List<ApplicationCapsule> = capsules.values.toList()

    fun listByType(type: CapsuleType): List<ApplicationCapsule> =
        capsules.values.filter { it.type == type }

    fun listByCapability(capability: RequiredCapability): List<ApplicationCapsule> =
        capsules.values.filter { capability in it.capabilities }

    fun search(query: String): List<ApplicationCapsule> {
        val lower = query.lowercase()
        return capsules.values.filter {
            it.name.lowercase().contains(lower) ||
            it.displayName.lowercase().contains(lower) ||
            it.capsuleId.lowercase().contains(lower) ||
            it.description.lowercase().contains(lower)
        }
    }
}
