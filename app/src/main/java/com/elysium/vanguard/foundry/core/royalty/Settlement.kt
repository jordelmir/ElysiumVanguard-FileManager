package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.FoundryError
import java.math.BigDecimal
import java.math.MathContext
import java.util.UUID

/**
 * Phase F5 first half (G6+G7, I-5.1) — the
 * **Settlement** + the **Royalty Calculator**.
 *
 * The [Settlement] is the typed result of a
 * royalty calculation. The settlement has:
 *   - `settlementId` — UUID.
 *   - `programId` — the program the settlement
 *     is for.
 *   - `transactionAmount` — the gross transaction
 *     amount (the basis for the royalty
 *     calculation).
 *   - `royaltyAmount` — the calculated royalty
 *     (the platform's cut of the transaction).
 *   - `currency` — ISO 4217 code (e.g. "USD").
 *   - `appliedRule` — the rule that was applied
 *     (the rule id, for audit).
 *   - `timestampMs` — when the settlement was
 *     computed.
 *   - `status` — the lifecycle state.
 *
 * The settlement is **immutable** (a data class;
 * no setters). A new settlement is a new value.
 * The settlement's lifecycle (a payment, a
 * refund) is a new `Settlement` value, not a
 * mutation of the existing one.
 *
 * Per ADR-0004: "RoyaltyContract must be ACTIVE
 * before a Settlement is computed". The
 * calculator checks the contract's status
 * before producing a settlement; an inactive
 * contract is a hard rejection.
 *
 * All money values are `BigDecimal` (per ADR-0001).
 * A `Double` would lose precision; a `BigDecimal`
 * preserves precision exactly.
 */
data class Settlement(
    val settlementId: SettlementId,
    val programId: VehicleProgramId,
    val transactionAmount: BigDecimal,
    val royaltyAmount: BigDecimal,
    val currency: String,
    val appliedRuleId: RoyaltyRuleId,
    val timestampMs: Long,
    val status: SettlementStatus,
) {
    init {
        require(transactionAmount >= BigDecimal.ZERO) {
            "Settlement.transactionAmount must be >= 0, got $transactionAmount"
        }
        require(royaltyAmount >= BigDecimal.ZERO) {
            "Settlement.royaltyAmount must be >= 0, got $royaltyAmount"
        }
        require(currency.length == 3) {
            "Settlement.currency must be a 3-letter ISO 4217 code, got: $currency"
        }
        require(currency.all { it.isUpperCase() }) {
            "Settlement.currency must be uppercase, got: $currency"
        }
        require(timestampMs > 0) {
            "Settlement.timestampMs must be > 0, got $timestampMs"
        }
    }
}

/**
 * The typed id of a settlement. The id is a
 * UUID (per the Foundry id convention).
 */
@JvmInline
value class SettlementId(val value: UUID) {
    companion object {
        fun random(): SettlementId = SettlementId(UUID.randomUUID())
        fun from(raw: String): Result<SettlementId> = try {
            Result.success(SettlementId(UUID.fromString(raw)))
        } catch (e: IllegalArgumentException) {
            Result.failure(FoundryError.InvalidUuidFormat("SettlementId", raw, e))
        }
    }
}

/**
 * The status of a settlement. The settlement
 * is a **lifecycle value**: a settlement
 * starts as `PENDING` (the calculation is done
 * but the payment hasn't been processed) +
 * ends as `PAID` (the payment is processed) +
 * or `REVERSED` (the payment is reversed).
 */
enum class SettlementStatus {
    /** The calculation is done; the payment
     *  hasn't been processed yet. */
    PENDING,

    /** The payment is processed. The
     *  settlement is final. */
    PAID,

    /** The payment is reversed (e.g. the
     *  transaction was refunded). The
     *  settlement is final. */
    REVERSED,
}

/**
 * The **Royalty Calculator** — the orchestrator
 * that takes a [RoyaltyContract] + a transaction
 * amount + produces a [Settlement].
 *
 * The calculator is a **pure-domain** interface
 * (no I/O, no Android dependencies). The
 * [DefaultRoyaltyCalculator] is the standard
 * implementation; a custom implementation
 * (e.g. for a specific royalty scheme) can
 * replace it.
 *
 * Per ADR-0004: "RoyaltyContract must be ACTIVE
 * before a Settlement is computed". The
 * calculator checks the contract's status;
 * an inactive contract is a hard rejection
 * (the calculator returns a typed error).
 */
interface RoyaltyCalculator {

    /**
     * Calculate the settlement for a
     * transaction. The function is **total**
     * on its inputs (every well-formed
     * contract + amount produces a
     * settlement), but rejects inactive
     * contracts.
     *
     * Returns:
     *   - `Result.success(Settlement)` on
     *     success.
     *   - `Result.failure(RoyaltyCalculatorError.InactiveContract)`
     *     when the contract is not active.
     */
    fun calculate(
        contract: RoyaltyContract,
        transactionAmount: BigDecimal,
        currency: String,
        timestampMs: Long,
    ): Result<Settlement>
}

/**
 * The default [RoyaltyCalculator]. The
 * calculator applies the contract's rule to
 * the transaction amount + returns a typed
 * [Settlement].
 *
 * The calculator is **pure-domain** (no I/O,
 * no Android dependencies). The calculator
 * is thread-safe (no mutable state).
 */
class DefaultRoyaltyCalculator : RoyaltyCalculator {

    override fun calculate(
        contract: RoyaltyContract,
        transactionAmount: BigDecimal,
        currency: String,
        timestampMs: Long,
    ): Result<Settlement> {
        // Per ADR-0004: the contract must be
        // ACTIVE before a settlement is computed.
        if (contract.status != RoyaltyContractStatus.ACTIVE) {
            return Result.failure(
                RoyaltyCalculatorError.InactiveContract(
                    contractId = contract.contractId,
                    currentStatus = contract.status,
                ),
            )
        }
        // Per the rule's kind, compute the
        // royalty amount.
        val royaltyAmount: BigDecimal = when (val rule = contract.rule) {
            is RoyaltyRule.PercentageRoyalty -> {
                val calculated = transactionAmount.multiply(
                    rule.percentage,
                    MathContext.DECIMAL64,
                )
                if (rule.minimumAmount != null && calculated < rule.minimumAmount) {
                    rule.minimumAmount
                } else {
                    calculated
                }
            }
            is RoyaltyRule.FixedAmountRoyalty -> {
                rule.amount
            }
            is RoyaltyRule.TieredRoyalty -> {
                // Find the first tier whose
                // upToVolume >= transactionAmount
                // (interpreted as `transactionAmount.toLong()` —
                // the tier is a volume count, not
                // a currency amount).
                val volume = transactionAmount.toLong()
                val tier = rule.tiers.firstOrNull { it.upToVolume >= volume }
                    ?: rule.tiers.last()
                transactionAmount.multiply(
                    tier.percentage,
                    MathContext.DECIMAL64,
                )
            }
        }
        return Result.success(
            Settlement(
                settlementId = SettlementId.random(),
                programId = contract.programId,
                transactionAmount = transactionAmount,
                royaltyAmount = royaltyAmount,
                currency = currency,
                appliedRuleId = contract.rule.ruleId,
                timestampMs = timestampMs,
                status = SettlementStatus.PENDING,
            ),
        )
    }
}

/**
 * The typed error envelope for the
 * [RoyaltyCalculator]. The errors are the
 * typed outcomes the calculator returns on
 * a failed calculation.
 */
sealed class RoyaltyCalculatorError(
    message: String,
) : RuntimeException(message) {

    /**
     * The contract is not `ACTIVE` (per
     * ADR-0004). The settlement is rejected.
     */
    data class InactiveContract(
        val contractId: RoyaltyContractId,
        val currentStatus: RoyaltyContractStatus,
    ) : RoyaltyCalculatorError(
        "Royalty contract $contractId is not ACTIVE " +
            "(current status: $currentStatus). A " +
            "settlement can only be computed for an " +
            "ACTIVE contract (per ADR-0004).",
    )
}
