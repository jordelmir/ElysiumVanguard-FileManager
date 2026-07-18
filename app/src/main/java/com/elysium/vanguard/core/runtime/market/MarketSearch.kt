package com.elysium.vanguard.core.runtime.market

/**
 * A typed query against the `MarketCatalog`. The
 * query is a struct (not a free-form string) so the
 * catalog's search is total + deterministic.
 *
 * The `query` field is a case-insensitive substring
 * match against the listing's `name` + `tags`. An
 * empty `query` returns all listings.
 *
 * The `type` field filters by `MarketListingType`. A
 * `null` value matches all types.
 *
 * The `limit` field caps the number of results (per
 * the platform's "no unbounded result sets" rule in
 * `.ai/AGENTS.md` 24). The default is 100.
 */
data class MarketSearchQuery(
    val query: String = "",
    val type: MarketListingType? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit > 0) { "MarketSearchQuery limit must be positive, got $limit" }
        require(limit <= MAX_LIMIT) { "MarketSearchQuery limit must be <= $MAX_LIMIT, got $limit" }
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1_000
    }
}

/**
 * A typed result from a `MarketSearch`. The result is
 * the matching listings + the total count (the count
 * may exceed the limit; the limit caps only the
 * returned list, not the total).
 */
data class MarketSearchResult(
    val listings: List<MarketListing>,
    val totalCount: Int,
) {
    init {
        require(totalCount >= listings.size) {
            "MarketSearchResult totalCount must be >= listings.size, got totalCount=$totalCount listings.size=${listings.size}"
        }
    }
}
