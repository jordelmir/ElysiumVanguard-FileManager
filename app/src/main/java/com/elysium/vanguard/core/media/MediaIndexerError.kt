package com.elysium.vanguard.core.media

/**
 * Phase 93 — the **Media Indexer Error**, the
 * typed error envelope for the media indexer
 * pipeline.
 *
 * Per `.ai/STANDARDS.md` section 7 +
 * `.ai/AGENTS.md` section 24.1: a free-form
 * string is never the value of an error. The
 * `code` is the canonical identifier; the
 * `message` is the human-readable detail.
 *
 * The error extends `RuntimeException` (the
 * canonical pattern for typed errors in the
 * Elysium codebase; mirrors `FoundryError`).
 */
sealed class MediaIndexerError(
    message: String,
    val code: String,
) : RuntimeException(message) {

    /**
     * The `MediaSource` raised an exception
     * during `discover()`. The cause is
     * the underlying exception.
     */
    data class SourceDiscoveryFailed(
        val underlying: Throwable,
    ) : MediaIndexerError(
        message = "MediaSource.discover() failed: " +
            (underlying.message ?: underlying::class.simpleName),
        code = "SOURCE_DISCOVERY_FAILED",
    )

    /**
     * The DAO raised an exception during
     * `upsert` / `update` / `delete`. The
     * cause is the underlying exception.
     */
    data class IndexWriteFailed(
        val mediaId: Long,
        val underlying: Throwable,
    ) : MediaIndexerError(
        message = "Index write failed for media $mediaId: " +
            (underlying.message ?: underlying::class.simpleName),
        code = "INDEX_WRITE_FAILED",
    )

    /**
     * The scan's `nowMs` is non-positive.
     * Raised at the boundary (per
     * `.ai/AGENTS.md` 24.1) — never inside
     * the domain.
     */
    data class InvalidNowMs(
        val nowMs: Long,
    ) : MediaIndexerError(
        message = "MediaIndexer.scan.nowMs must be > 0, got $nowMs",
        code = "INVALID_NOW_MS",
    )
}
