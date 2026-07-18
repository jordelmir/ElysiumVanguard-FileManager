package com.elysium.vanguard.core.runtime.market

/**
 * The Market's read seam. The catalog is the
 * platform's signed distribution channel; the
 * `MarketCatalog` is the **read side** (list + search +
 * get-by-id). The **write side** (publish + sign + push)
 * is `MarketPublisher` (Phase 2).
 *
 * Phase 1 ships the in-memory implementation. Phase 2
 * ships the HTTP-backed implementation (the catalog
 * is fetched from the Vanguard Cloud per
 * `ADR-028-vanguard-cloud.md`).
 *
 * The interface is `Result`-based so the consumer
 * pattern-matches on the typed error. A
 * `MarketCatalogError` variant is added to the
 * `FoundryError` hierarchy when the HTTP implementation
 * is wired.
 */
interface MarketCatalog {

    /**
     * Get a listing by its id. Returns `null` if the
     * listing is not in the catalog.
     */
    fun getById(id: String): MarketListing?

    /**
     * Search the catalog. The search is total (the
     * catalog returns all matches, up to the query's
     * limit). The `totalCount` field on the result is
     * the count BEFORE the limit is applied.
     */
    fun search(query: MarketSearchQuery): Result<MarketSearchResult>

    /**
     * List all listings in the catalog. The list is
     * sorted by `name` (alphabetical). The `limit` cap
     * applies.
     */
    fun listAll(limit: Int = MarketSearchQuery.DEFAULT_LIMIT): Result<MarketSearchResult>

    /**
     * The total number of listings in the catalog
     * (before any filtering). Used for monitoring +
     * test assertions.
     */
    fun count(): Int
}
