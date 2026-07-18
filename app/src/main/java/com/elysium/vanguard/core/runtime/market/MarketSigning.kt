package com.elysium.vanguard.core.runtime.market

import com.elysium.vanguard.foundry.core.ontology.primitives.Signature

/**
 * The Market's signing layer. Sign + verify a
 * `MarketListing`.
 *
 * Phase 1 implementation: HMAC-SHA-256 (matching the
 * Foundry `Signature` primitive). The Phase 1 signer
 * uses a per-publisher symmetric key derived from the
 * `signatureKeyId`. The Phase 2 hardening replaces
 * HMAC with Ed25519 (then ML-DSA-65) for asymmetric
 * verification — the public key is published alongside
 * the listing, the private key stays with the publisher.
 *
 * Why a separate object (not a primitive method): the
 * Market's signing is **institutionally scoped** — the
 * signer is the publisher (a maintainer, a project), not
 * the host. The `Signature` primitive is general-purpose;
 * the Market's signing is a domain operation.
 */
object MarketSigning {

    /**
     * Sign a listing with the given key. The signature
     * is computed over the listing's canonical form.
     * Returns the signed listing with the signature
     * field set.
     */
    fun sign(listing: MarketListing, key: ByteArray): MarketListing =
        listing.copy(
            signature = Signature.sign(listing.canonicalForm(), key),
        )

    /**
     * Verify a listing's signature. Returns `true` if
     * the signature matches the canonical form under
     * the given key; `false` otherwise.
     *
     * A failed verification is a hard rejection: the
     * catalog MUST NOT install an unverified listing.
     */
    fun verify(listing: MarketListing, key: ByteArray): Boolean {
        val expected = Signature.sign(listing.canonicalForm(), key)
        return expected == listing.signature
    }
}
