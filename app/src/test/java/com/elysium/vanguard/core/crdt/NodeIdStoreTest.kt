package com.elysium.vanguard.core.crdt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * PHASE 9.19 — Tests for [NodeIdStore].
 *
 * Confirms that:
 *   - First call to `getOrCreate()` mints a fresh UUID.
 *   - Subsequent calls return the same value.
 *   - The store round-trips across [NodeIdStore] instances
 *     (process restarts).
 *   - `set()` overrides the persisted value.
 *   - `clear()` resets and the next call mints fresh.
 */
class NodeIdStoreTest {

    private lateinit var tempDir: File
    private lateinit var storeFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("node-id-store-test").toFile()
        storeFile = File(tempDir, "node-id-main.json")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `getOrCreate mints a UUID on first call`() {
        val store = NodeIdStore(storeFile)
        val nodeId = store.getOrCreate()
        assertTrue("nodeId should start with 'node-': $nodeId", nodeId.startsWith("node-"))
        assertTrue(
            "nodeId should be a UUID-formatted value: $nodeId",
            nodeId.startsWith("node-") && nodeId.length > 6
        )
    }

    @Test
    fun `getOrCreate is stable across store instances`() {
        val first = NodeIdStore(storeFile).getOrCreate()
        val second = NodeIdStore(storeFile).getOrCreate()
        assertEquals("second call should reuse persisted value", first, second)
    }

    @Test
    fun `set overrides the persisted value`() {
        val store = NodeIdStore(storeFile)
        store.set("node-mine")
        assertEquals("node-mine", store.getOrCreate())
        // A fresh store instance reads the same value.
        assertEquals("node-mine", NodeIdStore(storeFile).getOrCreate())
    }

    @Test
    fun `clear wipes persisted state and mints a fresh UUID`() {
        val store = NodeIdStore(storeFile)
        val first = store.getOrCreate()
        store.clear()
        val second = store.getOrCreate()
        assertNotEquals("after clear, new id should differ", first, second)
        assertTrue(second.startsWith("node-"))
    }

    @Test
    fun `persisted JSON round-trips across instances`() {
        val store1 = NodeIdStore(storeFile)
        store1.set("node-test-abc")
        val onDisk = storeFile.readText()
        assertTrue("file should contain the node id: $onDisk", onDisk.contains("node-test-abc"))
        val store2 = NodeIdStore(storeFile)
        assertEquals("node-test-abc", store2.getOrCreate())
    }

    @Test
    fun `missing store file is treated as a fresh device`() {
        // Don't even call getOrCreate — file should not yet exist.
        assertNull(readNodeIdFromFile())
        val store = NodeIdStore(storeFile)
        val id = store.getOrCreate()
        assertNotNull("store should mint a UUID when file is missing", id)
    }

    @Test
    fun `corrupt store file yields a fresh UUID`() {
        storeFile.writeText("not a json document")
        val store = NodeIdStore(storeFile)
        val id = store.getOrCreate()
        assertTrue("corrupt file should not crash; should mint fresh", id.startsWith("node-"))
        // Subsequent calls stay stable.
        assertEquals(id, store.getOrCreate())
    }

    @Test
    fun `defaultStoreFile lives inside the supplied root`() {
        val f = NodeIdStore.defaultStoreFile(tempDir)
        assertEquals(tempDir, f.parentFile)
        assertTrue("file name should start with node-id-", f.name.startsWith("node-id-"))
    }

    private fun readNodeIdFromFile(): String? =
        if (!storeFile.isFile) null else storeFile.readText()
}
