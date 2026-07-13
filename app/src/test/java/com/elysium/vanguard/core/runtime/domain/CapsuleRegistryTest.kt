package com.elysium.vanguard.core.runtime.domain

import com.elysium.vanguard.core.database.runtime.ApplicationCapsuleDao
import com.elysium.vanguard.core.database.runtime.ApplicationCapsuleEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

class CapsuleRegistryTest {

    private val clock = AtomicLong(1_700_000_000_000L)
    private val store = ConcurrentHashMap<String, ApplicationCapsuleEntity>()
    private val dao: ApplicationCapsuleDao = mock()

    @Before
    fun setUp() {
        store.clear()
        runBlocking {
            whenever(dao.getById(any())).thenAnswer { invocation ->
                val id = invocation.arguments[0] as String
                store[id]
            }
            whenever(dao.upsert(any())).thenAnswer { invocation ->
                val entity = invocation.arguments[0] as ApplicationCapsuleEntity
                store[entity.id] = entity
                Unit
            }
            whenever(dao.listAll()).thenAnswer { store.values.toList() }
            whenever(dao.observeAll()).thenReturn(flowOf(store.values.toList()))
            whenever(dao.count()).thenAnswer { store.size }
            whenever(dao.update(any())).thenAnswer { invocation ->
                val entity = invocation.arguments[0] as ApplicationCapsuleEntity
                store[entity.id] = entity
                Unit
            }
            whenever(dao.deleteById(any())).thenAnswer { invocation ->
                val id = invocation.arguments[0] as String
                store.remove(id)
                Unit
            }
            whenever(dao.listByType(any())).thenAnswer { invocation ->
                val type = invocation.arguments[0] as String
                store.values.filter { it.capsuleType == type }
            }
            whenever(dao.search(any())).thenAnswer { invocation ->
                val query = invocation.arguments[0] as String
                store.values.filter { it.name.contains(query, ignoreCase = true) }
            }
        }
    }

    @Test
    fun `save persists capsule and computes integrity hash`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        val capsule = capsule("gimp-editor", CapsuleType.LINUX_PROOT)
        val result = registry.save(capsule)
        assertTrue(result.isSuccess)
        val stored = store[capsule.capsuleId]
        assertNotNull(stored)
        assertEquals(capsule.name, stored!!.name)
        assertEquals(capsule.version, stored.version)
        assertEquals(CapsuleType.LINUX_PROOT.name, stored.capsuleType)
        // SHA-256 of the manifest, hex-encoded -> 64 chars.
        assertEquals(64, stored.integrityHash.length)
    }

    @Test
    fun `getById returns the deserialized capsule`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        val capsule = capsule("vim-editor", CapsuleType.LINUX_PROOT)
        registry.save(capsule)
        val loaded = registry.getById("vim-editor")
        assertNotNull(loaded)
        assertEquals("vim-editor", loaded!!.capsuleId)
        assertEquals("Test vim-editor", loaded.displayName)
    }

    @Test
    fun `getById returns null for unknown id`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        assertNull(registry.getById("missing"))
    }

    @Test
    fun `listAll returns every saved capsule`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        registry.save(capsule("a", CapsuleType.LINUX_PROOT))
        registry.save(capsule("b", CapsuleType.WINDOWS_WINE))
        registry.save(capsule("c", CapsuleType.REMOTE))
        val all = registry.listAll()
        assertEquals(3, all.size)
        assertTrue(all.any { it.capsuleId == "a" })
        assertTrue(all.any { it.capsuleId == "b" })
        assertTrue(all.any { it.capsuleId == "c" })
    }

    @Test
    fun `listByType filters by capsule type`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        registry.save(capsule("a", CapsuleType.LINUX_PROOT))
        registry.save(capsule("b", CapsuleType.WINDOWS_WINE))
        registry.save(capsule("c", CapsuleType.REMOTE))
        val proot = registry.listByType(CapsuleType.LINUX_PROOT)
        assertEquals(1, proot.size)
        assertEquals("a", proot[0].capsuleId)
    }

    @Test
    fun `search matches by name`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        registry.save(capsule("gimp", CapsuleType.LINUX_PROOT).copy(displayName = "GIMP Image Editor"))
        registry.save(capsule("vim", CapsuleType.LINUX_PROOT).copy(displayName = "Vim Editor"))
        val results = registry.search("gimp")
        assertEquals(1, results.size)
        assertEquals("gimp", results[0].capsuleId)
    }

    @Test
    fun `recordLaunch bumps launch count and timestamp`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        registry.save(capsule("a", CapsuleType.LINUX_PROOT))
        registry.recordLaunch("a")
        val after = store["a"]!!
        assertEquals(1, after.launchCount)
        assertTrue(after.lastLaunchedAtMs > 0L)
        registry.recordLaunch("a")
        val after2 = store["a"]!!
        assertEquals(2, after2.launchCount)
    }

    @Test
    fun `recordLaunch fails for unknown id`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        val result = registry.recordLaunch("missing")
        assertTrue(result.isFailure)
    }

    @Test
    fun `delete removes the capsule`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        registry.save(capsule("a", CapsuleType.LINUX_PROOT))
        assertEquals(1, registry.count())
        registry.delete("a")
        assertEquals(0, registry.count())
    }

    @Test
    fun `count returns the number of stored capsules`() = runBlocking {
        val registry = CapsuleRegistry(dao) { clock.incrementAndGet() }
        assertEquals(0, registry.count())
        registry.save(capsule("a", CapsuleType.LINUX_PROOT))
        registry.save(capsule("b", CapsuleType.LINUX_PROOT))
        assertEquals(2, registry.count())
    }

    @Test
    fun `capsule filters narrow by capability`() {
        val a = capsule("a", CapsuleType.LINUX_PROOT).copy(
            capabilities = setOf(RequiredCapability.PTY, RequiredCapability.DISPLAY)
        )
        val b = capsule("b", CapsuleType.LINUX_PROOT).copy(
            capabilities = setOf(RequiredCapability.PTY)
        )
        val display = CapsuleFilters.byCapability(listOf(a, b), RequiredCapability.DISPLAY)
        assertEquals(1, display.size)
        assertEquals("a", display[0].capsuleId)
    }

    @Test
    fun `capsule filters narrow by query`() {
        val a = capsule("gimp", CapsuleType.LINUX_PROOT).copy(displayName = "GIMP Image Editor")
        val b = capsule("vim", CapsuleType.LINUX_PROOT).copy(displayName = "Vim Text Editor")
        val c = capsule("firefox", CapsuleType.REMOTE).copy(displayName = "Firefox Browser")
        val editors = CapsuleFilters.byQuery(listOf(a, b, c), "editor")
        assertEquals(2, editors.size)
        assertTrue(editors.any { it.capsuleId == "gimp" })
        assertTrue(editors.any { it.capsuleId == "vim" })
    }

    private fun capsule(id: String, type: CapsuleType) = ApplicationCapsule(
        capsuleId = id,
        name = "Test $id",
        displayName = "Test $id",
        version = "1.0.0",
        description = "Test capsule",
        type = type,
        metadata = CapsuleMetadata(author = "Test", license = "MIT", homepage = "", category = "test"),
        runtime = RuntimeRequirements(backend = BackendKind.PROOT_LINUX),
        capabilities = setOf(RequiredCapability.PTY),
        launch = LaunchConfiguration(command = listOf("/bin/sh")),
        permissions = emptySet()
    )
}
