package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 3 / I-3.3 (continued) — the JVM tests
 * for [AssetGeometry] + [InMemoryAssetContentStore]
 * + [AssetCache] + [AssetStreamer].
 *
 * The asset streaming pipeline is the runtime
 * layer between the content store (slow) and the
 * 3D renderer (fast). The tests cover:
 *   - The AssetGeometry data class + content
 *     hash + format version + bytes.
 *   - The InMemoryAssetContentStore (put + fetch
 *     + content hash verification).
 *   - The AssetCache (get + put + LRU eviction +
 *     hit / miss / eviction stats).
 *   - The AssetStreamer (cache hit short-circuits
 *     the store; cache miss falls through to the
 *     store + caches the result).
 */
class AssetStreamerTest {

    // ============================================================
    // AssetGeometry
    // ============================================================

    @Test
    fun `AssetGeometry rejects blank format version`() {
        try {
            AssetGeometry(
                contentHash = ContentHash("0".repeat(64)),
                formatVersion = "",
                bytes = byteArrayOf(1, 2, 3),
            )
            fail("expected IllegalArgumentException for blank format version")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("formatVersion"))
        }
    }

    @Test
    fun `AssetGeometry rejects empty bytes`() {
        try {
            AssetGeometry(
                contentHash = ContentHash("0".repeat(64)),
                formatVersion = "glTF/2.0",
                bytes = byteArrayOf(),
            )
            fail("expected IllegalArgumentException for empty bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bytes"))
        }
    }

    @Test
    fun `AssetGeometry is equal by content`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val a = AssetGeometry(
            contentHash = ContentHash("a".repeat(64)),
            formatVersion = "glTF/2.0",
            bytes = bytes,
        )
        val b = AssetGeometry(
            contentHash = ContentHash("a".repeat(64)),
            formatVersion = "glTF/2.0",
            bytes = bytes.copyOf(),
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ============================================================
    // InMemoryAssetContentStore
    // ============================================================

    @Test
    fun `InMemoryAssetContentStore returns AssetNotFound for unknown hash`() {
        val store = InMemoryAssetContentStore()
        val result = store.fetch(ContentHash("9".repeat(64)))
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected AssetNotFound, got ${error?.javaClass}",
            error is AssetContentStore.AssetStoreError.AssetNotFound,
        )
    }

    @Test
    fun `InMemoryAssetContentStore put + fetch round-trip`() {
        val store = InMemoryAssetContentStore()
        val bytes = "geometry".toByteArray()
        val geometry = AssetGeometry(
            contentHash = ContentHash.of(String(bytes, Charsets.UTF_8)),
            formatVersion = "glTF/2.0",
            bytes = bytes,
        )
        store.put(geometry)
        val fetched = store.fetch(geometry.contentHash)
        assertTrue(fetched.isSuccess)
        assertEquals(geometry, fetched.getOrThrow())
    }

    // ============================================================
    // AssetCache
    // ============================================================

    @Test
    fun `AssetCache rejects non-positive max size`() {
        try {
            AssetCache(maxSizeBytes = 0L)
            fail("expected IllegalArgumentException for max size 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxSizeBytes"))
        }
    }

    @Test
    fun `AssetCache returns null for unknown hash`() {
        val cache = AssetCache()
        assertNull(cache.get(ContentHash("9".repeat(64))))
        assertEquals(1L, cache.missCount)
    }

    @Test
    fun `AssetCache put + get returns the geometry`() {
        val cache = AssetCache()
        val geometry = sampleGeometry("a")
        cache.put(geometry)
        val fetched = cache.get(geometry.contentHash)
        assertNotNull(fetched)
        assertEquals(geometry, fetched)
        assertEquals(1L, cache.hitCount)
    }

    @Test
    fun `AssetCache updates lastAccessedAtMs on get`() {
        val cache = AssetCache()
        val geometry = sampleGeometry("a")
        cache.put(geometry)
        // First get updates lastAccessedAtMs.
        val fetched1 = cache.get(geometry.contentHash)
        // Second get also updates.
        Thread.sleep(10)  // ensure clock advances
        val fetched2 = cache.get(geometry.contentHash)
        assertNotNull(fetched1)
        assertNotNull(fetched2)
        assertEquals(2L, cache.hitCount)
    }

    @Test
    fun `AssetCache evicts least-recently-used when full`() {
        // Create a cache with a small limit.
        val cache = AssetCache(maxSizeBytes = 100L)
        val g1 = sampleGeometry("a", sizeBytes = 60)
        val g2 = sampleGeometry("b", sizeBytes = 60)
        // The cache is 100 bytes. g1 fits (60 bytes).
        cache.put(g1)
        assertEquals(1, cache.size())
        // g2 doesn't fit (60 + 60 = 120 > 100); g1
        // should be evicted (the LRU is g1 because
        // g2 is the most recent).
        cache.put(g2)
        assertEquals(1, cache.size())
        assertEquals(1L, cache.evictionCount)
        // g1 is evicted; g2 is in the cache.
        assertNull(cache.get(g1.contentHash))
        assertNotNull(cache.get(g2.contentHash))
    }

    @Test
    fun `AssetCache clear removes all entries`() {
        val cache = AssetCache()
        cache.put(sampleGeometry("a"))
        cache.put(sampleGeometry("b"))
        assertEquals(2, cache.size())
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `AssetCache hit rate is correct`() {
        val cache = AssetCache()
        val g = sampleGeometry("a")
        cache.put(g)
        cache.get(g.contentHash)  // hit
        cache.get(g.contentHash)  // hit
        cache.get(ContentHash("9".repeat(64)))  // miss
        assertEquals(2L, cache.hitCount)
        assertEquals(1L, cache.missCount)
        assertEquals(2.0 / 3.0, cache.hitRate(), 0.001)
    }

    // ============================================================
    // AssetStreamer
    // ============================================================

    @Test
    fun `AssetStreamer cache hit short-circuits the store`() {
        val store = CountingAssetContentStore()
        val cache = AssetCache()
        val streamer = AssetStreamer(store, cache)
        val g = sampleGeometry("a")
        cache.put(g)
        // The streamer hits the cache; the store is not called.
        val result = streamer.stream(g.contentHash)
        assertTrue(result.isSuccess)
        assertEquals(0, store.fetchCount)
    }

    @Test
    fun `AssetStreamer cache miss fetches from the store and caches the result`() {
        val store = CountingAssetContentStore()
        val cache = AssetCache()
        val streamer = AssetStreamer(store, cache)
        val g = sampleGeometry("a")
        store.geometries[g.contentHash] = g
        val result = streamer.stream(g.contentHash)
        assertTrue(result.isSuccess)
        assertEquals(1, store.fetchCount)
        // The result is now in the cache.
        assertNotNull(cache.get(g.contentHash))
    }

    @Test
    fun `AssetStreamer cache miss followed by hit serves from the cache`() {
        val store = CountingAssetContentStore()
        val cache = AssetCache()
        val streamer = AssetStreamer(store, cache)
        val g = sampleGeometry("a")
        store.geometries[g.contentHash] = g
        streamer.stream(g.contentHash)  // miss → store
        streamer.stream(g.contentHash)  // hit → cache
        assertEquals(1, store.fetchCount)
    }

    @Test
    fun `AssetStreamer does not pollute the cache on store failure`() {
        val store = CountingAssetContentStore()
        val cache = AssetCache()
        val streamer = AssetStreamer(store, cache)
        val result = streamer.stream(ContentHash("9".repeat(64)))
        assertTrue(result.isFailure)
        // The cache is not polluted.
        assertEquals(0, cache.size())
    }

    @Test
    fun `AssetStreamer constructor rejects non-empty cache`() {
        val cache = AssetCache()
        cache.put(sampleGeometry("a"))
        try {
            AssetStreamer(InMemoryAssetContentStore(), cache)
            fail("expected IllegalArgumentException for non-empty cache")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty"))
        }
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun sampleGeometry(label: String, sizeBytes: Int = 100): AssetGeometry {
        // Build bytes whose content hash is deterministic
        // + matches the declared hash.
        val bytes = ByteArray(sizeBytes) { (it and 0xFF).toByte() }
        val hash = ContentHash.of(label)
        return AssetGeometry(
            contentHash = hash,
            formatVersion = "glTF/2.0",
            bytes = bytes,
        )
    }

    /**
     * A counting [AssetContentStore] that records
     * the fetch count + provides a pre-populated
     * geometries map. Used to verify the streamer
     * hits the cache vs falls through to the store.
     */
    private class CountingAssetContentStore : AssetContentStore {
        val geometries: MutableMap<ContentHash, AssetGeometry> = mutableMapOf()
        var fetchCount: Int = 0
            private set

        override fun fetch(contentHash: ContentHash): Result<AssetGeometry> {
            fetchCount += 1
            val geometry = geometries[contentHash]
                ?: return Result.failure(
                    AssetContentStore.AssetStoreError.AssetNotFound(contentHash),
                )
            return Result.success(geometry)
        }
    }
}
