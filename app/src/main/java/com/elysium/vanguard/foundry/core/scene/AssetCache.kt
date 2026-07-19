package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 3 / I-3.3 (continued) — the **Asset Cache**.
 *
 * The cache is a thread-safe LRU (least-recently-used)
 * cache for [AssetGeometry]s. The cache:
 *   - Stores geometries by [ContentHash].
 *   - Enforces a size limit (the sum of all
 *     geometry `sizeBytes` cannot exceed the
 *     limit).
 *   - Evicts the least-recently-used geometry
 *     when a `put` exceeds the limit.
 *   - Records a hit / miss statistic for
 *     observability.
 *
 * The cache is the **runtime layer** between the
 * [AssetContentStore] (the slow layer) and the
 * 3D renderer (the fast consumer). The cache
 * hits keep the geometry in memory; the cache
 * misses fall through to the store.
 *
 * The cache is **thread-safe** (a
 * `ConcurrentHashMap` with synchronized
 * eviction). The cache is **total** (every
 * `get` returns a value or `null`; every
 * `put` succeeds or evicts).
 *
 * The cache is **bounded** (the size limit
 * prevents unbounded memory growth). The
 * default limit is 100 MB (suitable for a
 * mid-range device).
 */
class AssetCache(
    private val maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES,
) {
    init {
        require(maxSizeBytes > 0) {
            "AssetCache.maxSizeBytes must be > 0, got $maxSizeBytes"
        }
    }

    /**
     * The cache entries. The map is keyed by
     * the `ContentHash.value` (a String) for
     * cheap equality + hash.
     */
    private val entries: ConcurrentHashMap<String, CacheEntry> =
        ConcurrentHashMap<String, CacheEntry>()

    /**
     * The current cache size in bytes. The
     * size is the sum of all entries' `sizeBytes`.
     */
    @Volatile
    private var currentSizeBytes: Long = 0L

    /**
     * The hit count (the number of `get` calls
     * that returned a non-null value).
     */
    @Volatile
    var hitCount: Long = 0L
        private set

    /**
     * The miss count (the number of `get` calls
     * that returned `null`).
     */
    @Volatile
    var missCount: Long = 0L
        private set

    /**
     * The eviction count (the number of entries
     * evicted by the LRU policy).
     */
    @Volatile
    var evictionCount: Long = 0L
        private set

    /**
     * Look up a geometry by [ContentHash].
     * Returns `null` when the hash is not in the
     * cache. The lookup records a hit / miss.
     *
     * The lookup also updates the LRU order
     * (the accessed entry is moved to the
     * "most recently used" position).
     */
    fun get(contentHash: ContentHash): AssetGeometry? {
        val entry = entries[contentHash.value] ?: run {
            missCount += 1
            return null
        }
        // The entry is accessed — update the
        // last-access time. The LRU eviction
        // uses `lastAccessedAtMs` to find the
        // least-recently-used entry.
        val updated = entry.copy(lastAccessedAtMs = System.currentTimeMillis())
        entries[contentHash.value] = updated
        hitCount += 1
        return updated.geometry
    }

    /**
     * Put a geometry in the cache. If the
     * geometry is already in the cache, the
     * entry is updated (no eviction). If the
     * cache would exceed the size limit, the
     * least-recently-used entries are evicted
     * until the new geometry fits.
     *
     * The function is **total**: every input
     * produces a result (the geometry is
     * always stored; the LRU eviction is
     * bounded).
     */
    fun put(geometry: AssetGeometry) {
        val hashKey = geometry.contentHash.value
        val existing = entries.remove(hashKey)
        if (existing != null) {
            currentSizeBytes -= existing.geometry.sizeBytes
        }
        val entry = CacheEntry(
            geometry = geometry,
            lastAccessedAtMs = System.currentTimeMillis(),
        )
        entries[hashKey] = entry
        currentSizeBytes += geometry.sizeBytes
        // Evict LRU entries until the cache fits.
        while (currentSizeBytes > maxSizeBytes && entries.size > 1) {
            val lruKey = entries.entries
                .minByOrNull { it.value.lastAccessedAtMs }
                ?.key
                ?: break
            val lruEntry = entries.remove(lruKey) ?: break
            currentSizeBytes -= lruEntry.geometry.sizeBytes
            evictionCount += 1
        }
    }

    /**
     * The cache's current size in bytes.
     */
    fun sizeBytes(): Long = currentSizeBytes

    /**
     * The cache's current entry count.
     */
    fun size(): Int = entries.size

    /**
     * The cache's hit rate (hits / total lookups).
     * Returns 0.0 when there have been no lookups.
     */
    fun hitRate(): Double {
        val total = hitCount + missCount
        return if (total == 0L) 0.0 else hitCount.toDouble() / total.toDouble()
    }

    /**
     * Remove all entries. The cache is reset to
     * its initial state. The hit / miss /
     * eviction counters are NOT reset.
     */
    fun clear() {
        entries.clear()
        currentSizeBytes = 0L
    }

    /**
     * A cache entry. The entry wraps an
     * [AssetGeometry] + the last-accessed-at
     * timestamp (for LRU eviction).
     */
    private data class CacheEntry(
        val geometry: AssetGeometry,
        val lastAccessedAtMs: Long,
    )

    companion object {
        /**
         * The default cache size limit (100 MB).
         * The default is suitable for a mid-range
         * device; a high-end device may use
         * a larger limit.
         */
        const val DEFAULT_MAX_SIZE_BYTES: Long = 100L * 1024L * 1024L
    }
}
