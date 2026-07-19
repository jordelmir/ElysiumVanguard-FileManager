package com.elysium.vanguard.foundry.core.scene

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError

/**
 * Phase 3 / I-3.3 (continued) — the **Asset Content
 * Store** interface.
 *
 * The store is the seam between the platform and
 * the content-addressed storage (per skill 06
 * section 5 — Asset streaming). The store:
 *   - Maps a [ContentHash] to an [AssetGeometry]
 *     (the bytes + the metadata).
 *   - The production implementation reads from a
 *     content-addressed disk cache (Phase 4
 *     milestone).
 *   - The test implementation is an in-memory map.
 *
 * The store is the **only** seam the platform
 * uses to fetch a geometry. The platform doesn't
 * care whether the bytes come from disk, network,
 * or a hard-coded map.
 */
interface AssetContentStore {

    /**
     * Fetch the geometry for a [ContentHash].
     * Returns a `Result<AssetGeometry>`:
     *   - `Result.success(geometry)` when the
     *     geometry is available.
     *   - `Result.failure(AssetNotFound(...))`
     *     when the hash is not in the store.
     *   - `Result.failure(ArtifactIntegrityFailure(...))`
     *     when the geometry's bytes don't match
     *     the declared hash (corrupted).
     */
    fun fetch(contentHash: ContentHash): Result<AssetGeometry>

    /**
     * The errors the store can return.
     */
    sealed class AssetStoreError(
        message: String,
    ) : RuntimeException(message) {

        /**
         * The content hash is not in the store.
         * The store doesn't have the geometry.
         */
        data class AssetNotFound(
            val contentHash: ContentHash,
        ) : AssetStoreError("Asset not found: ${contentHash.value}")

        /**
         * The geometry's bytes don't match the
         * declared content hash. The asset is
         * corrupted.
         */
        data class Corrupted(
            val contentHash: ContentHash,
            val reason: String,
        ) : AssetStoreError("Asset corrupted (${contentHash.value}): $reason")
    }
}

/**
 * An in-memory [AssetContentStore] for testing.
 * The store is a `Map<ContentHash, AssetGeometry>`;
 * the `fetch` method looks up the geometry +
 * verifies the bytes' content hash matches the
 * declared hash.
 *
 * The store is thread-safe (the underlying map
 * is `ConcurrentHashMap`).
 */
class InMemoryAssetContentStore(
    geometries: Map<ContentHash, AssetGeometry> = emptyMap(),
) : AssetContentStore {

    private val byHash: java.util.concurrent.ConcurrentHashMap<String, AssetGeometry> =
        java.util.concurrent.ConcurrentHashMap<String, AssetGeometry>().apply {
            geometries.forEach { (hash, geometry) -> put(hash.value, geometry) }
        }

    override fun fetch(contentHash: ContentHash): Result<AssetGeometry> {
        val geometry = byHash[contentHash.value]
            ?: return Result.failure(AssetContentStore.AssetStoreError.AssetNotFound(contentHash))
        // Verify the bytes match the declared hash.
        val recomputed = ContentHash.of(geometry.bytes.toString(Charsets.UTF_8))
        if (recomputed != contentHash) {
            return Result.failure(
                AssetContentStore.AssetStoreError.Corrupted(
                    contentHash = contentHash,
                    reason = "expected ${contentHash.value}, got ${recomputed.value}",
                ),
            )
        }
        return Result.success(geometry)
    }

    /**
     * Add a geometry to the store. The store
     * verifies the bytes' content hash matches
     * the declared hash before storing.
     */
    fun put(geometry: AssetGeometry): Result<Unit> {
        val recomputed = ContentHash.of(geometry.bytes.toString(Charsets.UTF_8))
        if (recomputed != geometry.contentHash) {
            return Result.failure(
                FoundryError.ArtifactIntegrityFailure(
                    artifactId = geometry.contentHash.value,
                    reason = "expected ${geometry.contentHash.value}, got ${recomputed.value}",
                ),
            )
        }
        byHash[geometry.contentHash.value] = geometry
        return Result.success(Unit)
    }
}
