package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import java.util.UUID

/**
 * Phase F5 first half (G6+G7, I-5.1) — the
 * **Royalty Contract**, the typed legal
 * contract that defines the royalty
 * arrangement between the platform and a
 * contributor.
 *
 * Per ADR-0004: "RoyaltyContract must be
 * ACTIVE before a Settlement is computed".
 * The contract is the **legal envelope**;
 * the settlement is the **calculated
 * result**.
 *
 * The contract has:
 *   - `contractId` — UUID.
 *   - `programId` — the program the contract
 *     is for.
 *   - `contributorId` — the contributor the
 *     contract is with.
 *   - `rule` — the [RoyaltyRule] (the
 *     configuration of how royalties are
 *     calculated).
 *   - `status` — the lifecycle state (per
 *     [RoyaltyContractStatus]).
 *   - `effectiveFromMs` — when the contract
 *     becomes effective.
 *   - `effectiveUntilMs` — when the contract
 *     expires (null = no expiration).
 *   - `signedByContributorAtMs` — when the
 *     contributor signed (null = unsigned).
 *   - `signedByPlatformAtMs` — when the
 *     platform signed (null = unsigned).
 *   - `signature` — the signature on the
 *     canonical form.
 *   - `contentHash` — the SHA-256 of the
 *     canonical form.
 *
 * The contract is **immutable** (a data class;
 * no setters). A new contract is a new value.
 * The contract's lifecycle (a signature, a
 * termination) is a new `RoyaltyContract`
 * value, not a mutation of the existing one.
 *
 * The contract is **append-only** in the
 * sense that signed contracts cannot be
 * modified (a re-sign produces a new
 * `RoyaltyContract` with a new id + content
 * hash).
 */
data class RoyaltyContract(
    val contractId: RoyaltyContractId,
    val programId: VehicleProgramId,
    val contributorId: ContributorId,
    val rule: RoyaltyRule,
    val status: RoyaltyContractStatus,
    val effectiveFromMs: Long,
    val effectiveUntilMs: Long? = null,
    val signedByContributorAtMs: Long? = null,
    val signedByPlatformAtMs: Long? = null,
    val signature: Signature,
    val contentHash: ContentHash,
) {
    init {
        require(effectiveFromMs > 0) {
            "RoyaltyContract.effectiveFromMs must be > 0, got $effectiveFromMs"
        }
        if (effectiveUntilMs != null) {
            require(effectiveUntilMs > effectiveFromMs) {
                "RoyaltyContract.effectiveUntilMs must be > " +
                    "effectiveFromMs, got effectiveUntilMs=" +
                    "$effectiveUntilMs effectiveFromMs=$effectiveFromMs"
            }
        }
        require(signature.value.isNotBlank()) {
            "RoyaltyContract.signature must not be blank"
        }
        require(contentHash.value.isNotBlank()) {
            "RoyaltyContract.contentHash must not be blank"
        }
        // Status invariants: a contract is
        // ACTIVE only when both parties have
        // signed.
        if (status == RoyaltyContractStatus.ACTIVE) {
            require(signedByContributorAtMs != null) {
                "RoyaltyContract.signedByContributorAtMs must be " +
                    "set when status is ACTIVE"
            }
            require(signedByPlatformAtMs != null) {
                "RoyaltyContract.signedByPlatformAtMs must be " +
                    "set when status is ACTIVE"
            }
        }
    }
}

/**
 * The typed id of a royalty contract. The
 * id is a UUID (per the Foundry id
 * convention).
 */
@JvmInline
value class RoyaltyContractId(val value: UUID) {
    companion object {
        fun random(): RoyaltyContractId = RoyaltyContractId(UUID.randomUUID())
        fun from(raw: String): Result<RoyaltyContractId> = try {
            Result.success(RoyaltyContractId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("RoyaltyContractId", raw, e))
        }
    }
}

/**
 * The status of a royalty contract. The
 * status is the **lifecycle state** of the
 * contract — a contract starts as `DRAFT`
 * + ends as `ACTIVE` (both parties signed)
 * + can be `TERMINATED` (either party
 * cancelled) + can be `EXPIRED` (the
 * effective period ended).
 */
enum class RoyaltyContractStatus(val displayLabel: String) {
    /** The contract is a draft. The contract
     *  is not yet signed; the platform has
     *  NOT committed to the rule. */
    DRAFT("Draft"),

    /** The contract is awaiting the
     *  contributor's signature. The platform
     *  has signed; the contributor has not
     *  yet signed. */
    PENDING_SIGNATURE("Pending Signature"),

    /** The contract is active. Both parties
     *  have signed; the rule is in effect. A
     *  settlement CAN be computed for this
     *  contract (per ADR-0004). */
    ACTIVE("Active"),

    /** The contract is terminated. Either
     *  party cancelled the contract; the
     *  rule is no longer in effect. A
     *  settlement CANNOT be computed for
     *  this contract. */
    TERMINATED("Terminated"),

    /** The contract is expired. The
     *  effective period ended; the rule is
     *  no longer in effect. A settlement
     *  CANNOT be computed for this
     *  contract. */
    EXPIRED("Expired"),
}
