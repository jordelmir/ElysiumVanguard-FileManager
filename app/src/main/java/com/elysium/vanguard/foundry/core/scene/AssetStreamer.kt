package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash

/**
 * Phase 3 / I-3.3 (continued) — the **Asset
 * Streamer**.
 *
 * The streamer is the pipeline that fetches a
 * geometry from the [AssetContentStore] + caches
 * the result in the [AssetCache]. The streamer:
 *   - Checks the cache first (a cache hit
 *     returns immediately; no store call).
 *   - On a cache miss, fetches from the store
 *     + puts the result in the cache.
 *   - On a store failure, returns the failure
 *     (the cache is NOT polluted).
 *
 * The streamer is the **typical entry point** for
 * the 3D renderer. The renderer calls
 * `streamer.stream(contentHash)` once per asset
 * per frame; the cache keeps the hot assets in
 * memory.
 *
 * The streamer is **thread-safe** (the cache +
 * the store are both thread-safe; the streamer
 * is a stateless composition).
 */
class AssetStreamer(
    private val store: AssetContentStore,
    private val cache: AssetCache = AssetCache(),
) {
    init {
        require(cache.size() == 0) {
            "AssetStreamer: cache must be empty at construction, " +
                "got ${cache.size()} entries"
        }
    }

    /**
     * Stream a geometry by [ContentHash]. The
     * streamer:
     *   1. Checks the cache. A hit returns
     *      the cached geometry.
     *   2. On a miss, fetches from the store.
     *   3. On a store success, puts the
     *      geometry in the cache + returns
     *      the geometry.
     *   4. On a store failure, returns the
     *      failure (the cache is not polluted).
     */
    fun stream(contentHash: ContentHash): Result<AssetGeometry> {
        val cached = cache.get(contentHash)
        if (cached != null) {
            return Result.success(cached)
        }
        val fetched = store.fetch(contentHash)
        if (fetched.isFailure) {
            return fetched
        }
        val geometry = fetched.getOrThrow()
        cache.put(geometry)
        return Result.success(geometry)
    }

    /**
     * The underlying cache. The streamer
     * exposes the cache for the observability
     * surface (hit / miss / eviction stats).
     */
    val cacheStats: AssetCache get() = cache
}
