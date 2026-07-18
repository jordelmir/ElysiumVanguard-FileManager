package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Timestamp

/**
 * The Market's write seam. The publisher is the
 * **only legitimate way** to put a listing in the
 * catalog. A publisher takes a `MarketListing`
 * draft, signs it with the publisher's key, and
 * pushes it to the catalog.
 *
 * The signing key is the publisher's private key
 * (in Phase 1: a per-publisher HMAC key; in Phase 2:
 * an Ed25519 / ML-DSA-65 keypair). The signature
 * binds the listing to the publisher; a verifier
 * checks the signature against the publisher's
 * public key before installing.
 *
 * The publisher is **not** the same as the AI
 * council's proposal engine. The publisher is a
 * signed-channel operation; the AI council produces
 * proposals that may or may not be published (per
 * the Foundry roadmap).
 */
interface MarketPublisher {

    /**
     * Publish a listing draft. The draft is signed
     * with the publisher's key + pushed to the
     * catalog. A successful publish returns the
     * signed listing.
     */
    fun publish(draft: MarketListingDraft): Result<MarketListing>

    /**
     * The publisher's identity. Used by the
     * catalog to attribute the listing.
     */
    val publisherId: String
}

/**
 * A draft `MarketListing` (the unsigned + the fields
 * the publisher sets). The publisher fills in the
 * `signatureKeyId` (the publisher's own key id) +
 * the `createdAt` + signs the listing.
 */
data class MarketListingDraft(
    val id: String,
    val name: String,
    val type: MarketListingType,
    val version: String,
    val contentHash: com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash,
    val sizeBytes: Long,
    val dependencies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/**
 * The Phase 1 in-memory implementation of the
 * `MarketPublisher`. The publisher signs the draft
 * with the provided key + pushes it to the
 * `InMemoryMarketCatalog`. The Phase 2
 * implementation pushes to the Vanguard Cloud
 * (per `ADR-028-vanguard-cloud.md`).
 */
class LocalMarketPublisher(
    private val catalog: InMemoryMarketCatalog,
    private val signingKey: ByteArray,
    override val publisherId: String,
    private val clock: Timestamp.Companion.TimestampSource = Timestamp.monotonicWallClock(),
) : MarketPublisher {

    override fun publish(draft: MarketListingDraft): Result<MarketListing> {
        if (catalog.getById(draft.id) != null) {
            return Result.failure(
                FoundryError.VehicleDefinitionInvalid(
                    field = "MarketPublisher.draft",
                    reason = "listing with id ${draft.id} already exists in the catalog",
                ),
            )
        }
        val unsigned = MarketListing(
            id = draft.id,
            name = draft.name,
            type = draft.type,
            version = draft.version,
            contentHash = draft.contentHash,
            signatureKeyId = publisherId,
            signature = com.elysium.vanguard.foundry.core.ontology.primitives.Signature.sign(
                "placeholder",
                signingKey,
            ),
            sizeBytes = draft.sizeBytes,
            dependencies = draft.dependencies,
            tags = draft.tags,
            createdAt = clock.now(),
        )
        val signed = MarketSigning.sign(unsigned, signingKey)
        val putResult = catalog.put(signed)
        return putResult.map { signed }
    }
}
