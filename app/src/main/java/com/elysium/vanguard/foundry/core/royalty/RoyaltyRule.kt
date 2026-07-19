package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.math.BigDecimal
import java.util.UUID

/**
 * Phase F5 first half (G6+G7, I-5.1) — the **Royalty
 * Rule**, the typed configuration of how royalties
 * are calculated for a [RoyaltyContract].
 *
 * Per ADR-0005: "Elysium 5% royalty is a configurable
 * `RoyaltyRule` (not hardcoded)". The rule is
 * **configurable** — the platform supports multiple
 * rule kinds; the 5% is one possible rule, not the
 * only one.
 *
 * The rule is a **sealed class** (not a flag or a
 * string id). Adding a 4th rule kind is a compile
 * error in every consumer that hasn't been
 * updated. The 3 cases reflect the **3 distinct
 * royalty strategies** the platform supports:
 *
 *   - **`PercentageRoyalty`** — a percentage of the
 *     transaction (the standard 5% rule).
 *   - **`FixedAmountRoyalty`** — a fixed amount per
 *     transaction (e.g. $0.10 per use).
 *   - **`TieredRoyalty`** — different rates at
 *     different volume tiers (e.g. 5% up to $10K,
 *     3% from $10K to $100K, 1% above $100K).
 *
 * The rule is **immutable** (a sealed class with
 * `val` fields; no setters). A new rule is a new
 * value. The rule's lifecycle (a rate change, a
 * tier adjustment) is a new `RoyaltyRule` value
 * + a new `RoyaltyContract` that references it.
 *
 * All money values are `BigDecimal` (per ADR-0001
 * "Money is `BigDecimal`, never `Double`/`Float`").
 * A `Double` would lose precision (e.g. `0.1 + 0.2
 * = 0.30000000000000004`), and a royalty calculation
 * that compounded the error would misallocate
 * millions. A `BigDecimal` preserves precision
 * exactly.
 */
sealed class RoyaltyRule {

    /**
     * The rule's canonical id. The id is a
     * UUID (per the Foundry id convention);
     * the id is the join key the contract
     * uses to reference the rule.
     */
    abstract val ruleId: RoyaltyRuleId

    /**
     * The rule's display name. The name is
     * what the UI shows in the contract
     * editor.
     */
    abstract val displayName: String

    /**
     * A percentage royalty. The royalty is
     * `transactionAmount * percentage`. The
     * rule MAY have a `minimumAmount` —
     * the calculated royalty is at least
     * the minimum (a $0.10 transaction at
     * 5% = $0.005, but the minimum is $0.01
     * → the royalty is $0.01).
     */
    data class PercentageRoyalty(
        override val ruleId: RoyaltyRuleId,
        override val displayName: String,
        val percentage: BigDecimal,
        val minimumAmount: BigDecimal? = null,
    ) : RoyaltyRule() {
        init {
            require(displayName.isNotBlank()) {
                "RoyaltyRule.PercentageRoyalty.displayName must not be blank"
            }
            require(percentage > BigDecimal.ZERO) {
                "RoyaltyRule.PercentageRoyalty.percentage must be > 0, " +
                    "got $percentage"
            }
            require(percentage <= BigDecimal.ONE) {
                "RoyaltyRule.PercentageRoyalty.percentage must be <= 1, " +
                    "got $percentage (a 100% rule means the entire " +
                    "transaction is the royalty)"
            }
            if (minimumAmount != null) {
                require(minimumAmount >= BigDecimal.ZERO) {
                    "RoyaltyRule.PercentageRoyalty.minimumAmount must be " +
                        ">= 0 when set, got $minimumAmount"
                }
            }
        }
    }

    /**
     * A fixed-amount royalty. The royalty is
     * a constant amount per transaction
     * (e.g. $0.10 per use, regardless of
     * transaction size). The rule has a
     * `currency` field (ISO 4217 code, e.g.
     * "USD", "EUR").
     */
    data class FixedAmountRoyalty(
        override val ruleId: RoyaltyRuleId,
        override val displayName: String,
        val amount: BigDecimal,
        val currency: String,
    ) : RoyaltyRule() {
        init {
            require(displayName.isNotBlank()) {
                "RoyaltyRule.FixedAmountRoyalty.displayName must not be blank"
            }
            require(amount >= BigDecimal.ZERO) {
                "RoyaltyRule.FixedAmountRoyalty.amount must be >= 0, " +
                    "got $amount"
            }
            require(currency.length == 3) {
                "RoyaltyRule.FixedAmountRoyalty.currency must be a 3-letter " +
                    "ISO 4217 code, got: $currency"
            }
            require(currency.all { it.isUpperCase() }) {
                "RoyaltyRule.FixedAmountRoyalty.currency must be uppercase, " +
                    "got: $currency"
            }
        }
    }

    /**
     * A tiered royalty. The royalty is a
     * different percentage at different
     * volume tiers. The tiers are sorted by
     * `upToVolume` ascending; the first tier
     * whose `upToVolume >= transactionAmount`
     * determines the rate.
     */
    data class TieredRoyalty(
        override val ruleId: RoyaltyRuleId,
        override val displayName: String,
        val tiers: List<Tier>,
    ) : RoyaltyRule() {
        init {
            require(displayName.isNotBlank()) {
                "RoyaltyRule.TieredRoyalty.displayName must not be blank"
            }
            require(tiers.isNotEmpty()) {
                "RoyaltyRule.TieredRoyalty.tiers must not be empty"
            }
            // Every tier has a non-negative
            // upToVolume + a percentage in
            // (0, 1].
            for ((index, tier) in tiers.withIndex()) {
                require(tier.upToVolume >= 0) {
                    "RoyaltyRule.TieredRoyalty.tiers[$index].upToVolume " +
                        "must be >= 0, got ${tier.upToVolume}"
                }
                require(tier.percentage > BigDecimal.ZERO) {
                    "RoyaltyRule.TieredRoyalty.tiers[$index].percentage " +
                        "must be > 0, got ${tier.percentage}"
                }
                require(tier.percentage <= BigDecimal.ONE) {
                    "RoyaltyRule.TieredRoyalty.tiers[$index].percentage " +
                        "must be <= 1, got ${tier.percentage}"
                }
            }
        }
    }

    /**
     * A single tier in a [TieredRoyalty]. The
     * tier has an `upToVolume` (the volume
     * threshold) + a `percentage` (the royalty
     * rate for transactions at or below the
     * threshold).
     */
    data class Tier(
        val upToVolume: Long,
        val percentage: BigDecimal,
    )
}

/**
 * The typed id of a royalty rule. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class RoyaltyRuleId(val value: UUID) {
    companion object {
        fun random(): RoyaltyRuleId = RoyaltyRuleId(UUID.randomUUID())
        fun from(raw: String): Result<RoyaltyRuleId> = try {
            Result.success(RoyaltyRuleId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("RoyaltyRuleId", raw, e))
        }
    }
}
