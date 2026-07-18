package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * A signed, content-addressed item in the Vanguard Market.
 *
 * The Market is the platform's signed distribution channel.
 * A `MarketListing` is the **typed pointer** to an item
 * (a Linux distro, an app, a Wine profile, etc.). The
 * artifact's bytes are in the content-addressed store; the
 * listing is the signed reference.
 *
 * Per `docs/adr/ADR-028-vanguard-cloud.md` + the Phase 59
 * design:
 *   - The listing is **content-addressed**: `contentHash`
 *     is the SHA-256 of the artifact's bytes. A mismatch
 *     is a hard rejection.
 *   - The listing is **signed**: `signature` binds the
 *     listing to the producer (a publisher, a maintainer,
 *     a project). The `signatureKeyId` identifies the
 *     public key to use for verification.
 *   - The listing is **typed**: `type` is one of the 12
 *     `MarketListingType` values.
 *   - The listing may have **dependencies**: a list of
 *     other `MarketListing.id`s that must be installed
 *     first.
 *   - The listing is **versioned**: `version` is a semver
 *     string.
 *
 * The listing's canonical form (the input to the signature)
 * is built from the fields excluding `signature` itself.
 * The signing/verification is performed by `MarketSigning`.
 */
data class MarketListing(
    val id: String,
    val name: String,
    val type: MarketListingType,
    val version: String,
    val contentHash: ContentHash,
    val signatureKeyId: String,
    val signature: Signature,
    val sizeBytes: Long,
    val dependencies: List<String>,
    val tags: List<String>,
    val createdAt: Timestamp,
) {
    init {
        require(id.isNotBlank()) { "MarketListing id must not be blank" }
        require(name.isNotBlank()) { "MarketListing name must not be blank" }
        require(version.isNotBlank()) { "MarketListing version must not be blank" }
        require(signatureKeyId.isNotBlank()) { "MarketListing signatureKeyId must not be blank" }
        require(sizeBytes >= 0) { "MarketListing sizeBytes must be non-negative, got $sizeBytes" }
    }

    /**
     * Build the canonical form of the listing for signing
     * or verification. The canonical form EXCLUDES the
     * `signature` field (the signature is computed over
     * the canonical form).
     *
     * The format is stable: the same `(id, name, type,
     * version, contentHash, signatureKeyId, sizeBytes,
     * dependencies, tags, createdAt)` always produces
     * the same canonical string. Sorted dependencies +
     * sorted tags ensure determinism.
     */
    fun canonicalForm(): String = buildString {
        append("market-listing:v1")
        append("|id=").append(id)
        append("|name=").append(name)
        append("|type=").append(type.name)
        append("|version=").append(version)
        append("|contentHash=").append(contentHash.value)
        append("|signatureKeyId=").append(signatureKeyId)
        append("|sizeBytes=").append(sizeBytes)
        append("|dependencies=").append(dependencies.sorted().joinToString(","))
        append("|tags=").append(tags.sorted().joinToString(","))
        append("|createdAt=").append(createdAt.epochMs)
    }
}
