package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The Phase 1 in-memory implementation of the
 * `MarketCatalog`. The catalog is the read-side cache
 * for the Vanguard Market; the production catalog is
 * fetched from the Vanguard Cloud per
 * `ADR-028-vanguard-cloud.md`.
 *
 * Concurrency: the catalog uses a `ReentrantReadWriteLock`
 * so `getById` + `search` + `listAll` + `count` are
 * concurrent (many readers) and the `put` (used by the
 * `MarketPublisher` in Phase 2) is exclusive.
 *
 * The `search` + `listAll` are deterministic: same query
 * + same catalog state -> same result.
 */
class InMemoryMarketCatalog : MarketCatalog {

    private val listings: MutableMap<String, MarketListing> = LinkedHashMap()
    private val lock = ReentrantReadWriteLock()

    /**
     * Add a listing to the catalog. Used by the
     * `MarketPublisher` (Phase 2) and by the test suite.
     * Not part of the read-side `MarketCatalog` interface
     * (the read side is read-only by design).
     */
    fun put(listing: MarketListing): Result<Unit> = lock.write {
        if (listings.containsKey(listing.id)) {
            return@write Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MarketCatalog.listings",
                    reason = "listing with id ${listing.id} already exists",
                ),
            )
        }
        listings[listing.id] = listing
        Result.success(Unit)
    }

    override fun getById(id: String): MarketListing? = lock.read {
        listings[id]
    }

    override fun search(query: MarketSearchQuery): Result<MarketSearchResult> = lock.read {
        val normalized = query.query.trim().lowercase()
        val matches = listings.values.filter { listing ->
            (query.type == null || listing.type == query.type) &&
                (normalized.isEmpty() ||
                    listing.name.lowercase().contains(normalized) ||
                    listing.tags.any { it.lowercase().contains(normalized) })
        }
        Result.success(
            MarketSearchResult(
                listings = matches.take(query.limit),
                totalCount = matches.size,
            ),
        )
    }

    override fun listAll(limit: Int): Result<MarketSearchResult> = lock.read {
        val sorted = listings.values.sortedBy { it.name }
        Result.success(
            MarketSearchResult(
                listings = sorted.take(limit),
                totalCount = sorted.size,
            ),
        )
    }

    override fun count(): Int = lock.read {
        listings.size
    }
}
