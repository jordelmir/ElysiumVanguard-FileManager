package com.elysium.vanguard.foundry.core.royalty

import com.elysium.vanguard.foundry.core.ontology.ids.ContributorId
import com.elysium.vanguard.foundry.core.ontology.ids.VehicleProgramId
import com.elysium.vanguard.foundry.core.ontology.primitives.ContentHash
import com.elysium.vanguard.foundry.core.ontology.primitives.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Phase F5 first half (G6+G7, I-5.1) — the JVM
 * tests for [RoyaltyRule] + [Settlement] +
 * [RoyaltyContract] + [DefaultRoyaltyCalculator].
 *
 * The tests cover:
 *   - RoyaltyRule invariants (Percentage, FixedAmount,
 *     Tiered).
 *   - Settlement invariants.
 *   - RoyaltyContract invariants (status + signature
 *     + content hash).
 *   - DefaultRoyaltyCalculator: percentage rule,
 *     fixed-amount rule, tiered rule, minimum
 *     amount, inactive contract rejection.
 */
class RoyaltyCalculatorTest {

    // ============================================================
    // RoyaltyRule invariants
    // ============================================================

    @Test
    fun `PercentageRoyalty accepts a well-formed configuration`() {
        val rule = RoyaltyRule.PercentageRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "Elysium 5% royalty",
            percentage = BigDecimal("0.05"),
        )
        assertEquals(BigDecimal("0.05"), rule.percentage)
    }

    @Test
    fun `PercentageRoyalty rejects zero percentage`() {
        try {
            RoyaltyRule.PercentageRoyalty(
                ruleId = RoyaltyRuleId.random(),
                displayName = "zero",
                percentage = BigDecimal.ZERO,
            )
            fail("expected IllegalArgumentException for zero percentage")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("percentage"))
        }
    }

    @Test
    fun `PercentageRoyalty rejects negative percentage`() {
        try {
            RoyaltyRule.PercentageRoyalty(
                ruleId = RoyaltyRuleId.random(),
                displayName = "negative",
                percentage = BigDecimal("-0.1"),
            )
            fail("expected IllegalArgumentException for negative percentage")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("percentage"))
        }
    }

    @Test
    fun `PercentageRoyalty rejects percentage greater than 1`() {
        try {
            RoyaltyRule.PercentageRoyalty(
                ruleId = RoyaltyRuleId.random(),
                displayName = "too high",
                percentage = BigDecimal("1.1"),
            )
            fail("expected IllegalArgumentException for percentage > 1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("percentage"))
        }
    }

    @Test
    fun `PercentageRoyalty accepts a minimumAmount of 0`() {
        val rule = RoyaltyRule.PercentageRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "with minimum",
            percentage = BigDecimal("0.05"),
            minimumAmount = BigDecimal.ZERO,
        )
        assertEquals(BigDecimal.ZERO, rule.minimumAmount)
    }

    @Test
    fun `FixedAmountRoyalty accepts a well-formed configuration`() {
        val rule = RoyaltyRule.FixedAmountRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "Per-use fee",
            amount = BigDecimal("0.10"),
            currency = "USD",
        )
        assertEquals(BigDecimal("0.10"), rule.amount)
    }

    @Test
    fun `FixedAmountRoyalty rejects non-3-letter currency`() {
        try {
            RoyaltyRule.FixedAmountRoyalty(
                ruleId = RoyaltyRuleId.random(),
                displayName = "bad currency",
                amount = BigDecimal("0.10"),
                currency = "US",
            )
            fail("expected IllegalArgumentException for non-3-letter currency")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("currency"))
        }
    }

    @Test
    fun `FixedAmountRoyalty rejects lowercase currency`() {
        try {
            RoyaltyRule.FixedAmountRoyalty(
                ruleId = RoyaltyRuleId.random(),
                displayName = "lowercase",
                amount = BigDecimal("0.10"),
                currency = "usd",
            )
            fail("expected IllegalArgumentException for lowercase currency")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("currency"))
        }
    }

    @Test
    fun `TieredRoyalty accepts a well-formed configuration`() {
        val rule = RoyaltyRule.TieredRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "Volume tiers",
            tiers = listOf(
                RoyaltyRule.Tier(upToVolume = 10_000L, percentage = BigDecimal("0.05")),
                RoyaltyRule.Tier(upToVolume = 100_000L, percentage = BigDecimal("0.03")),
                RoyaltyRule.Tier(upToVolume = Long.MAX_VALUE, percentage = BigDecimal("0.01")),
            ),
        )
        assertEquals(3, rule.tiers.size)
    }

    @Test
    fun `TieredRoyalty rejects empty tiers`() {
        try {
            RoyaltyRule.TieredRoyalty(
                ruleId = RoyaltyRuleId.random(),
                displayName = "empty",
                tiers = emptyList(),
            )
            fail("expected IllegalArgumentException for empty tiers")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tiers"))
        }
    }

    // ============================================================
    // Settlement invariants
    // ============================================================

    @Test
    fun `Settlement accepts a well-formed configuration`() {
        val settlement = buildSettlement()
        assertEquals(SettlementStatus.PENDING, settlement.status)
    }

    @Test
    fun `Settlement rejects negative transactionAmount`() {
        try {
            buildSettlement(transactionAmount = BigDecimal("-1.0"))
            fail("expected IllegalArgumentException for negative transactionAmount")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("transactionAmount"))
        }
    }

    @Test
    fun `Settlement rejects negative royaltyAmount`() {
        try {
            buildSettlement(royaltyAmount = BigDecimal("-1.0"))
            fail("expected IllegalArgumentException for negative royaltyAmount")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("royaltyAmount"))
        }
    }

    @Test
    fun `Settlement rejects non-3-letter currency`() {
        try {
            buildSettlement(currency = "US")
            fail("expected IllegalArgumentException for non-3-letter currency")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("currency"))
        }
    }

    // ============================================================
    // RoyaltyContract invariants
    // ============================================================

    @Test
    fun `RoyaltyContract accepts a well-formed ACTIVE configuration`() {
        val contract = buildContract(status = RoyaltyContractStatus.ACTIVE)
        assertEquals(RoyaltyContractStatus.ACTIVE, contract.status)
    }

    @Test
    fun `RoyaltyContract ACTIVE requires signedByContributorAtMs`() {
        try {
            buildContract(
                status = RoyaltyContractStatus.ACTIVE,
                signedByContributorAtMs = null,
            )
            fail("expected IllegalArgumentException for ACTIVE without contributor signature")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'signedByContributorAtMs', got: ${e.message}",
                e.message!!.contains("signedByContributorAtMs"),
            )
        }
    }

    @Test
    fun `RoyaltyContract ACTIVE requires signedByPlatformAtMs`() {
        try {
            buildContract(
                status = RoyaltyContractStatus.ACTIVE,
                signedByPlatformAtMs = null,
            )
            fail("expected IllegalArgumentException for ACTIVE without platform signature")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'signedByPlatformAtMs', got: ${e.message}",
                e.message!!.contains("signedByPlatformAtMs"),
            )
        }
    }

    @Test
    fun `RoyaltyContract rejects effectiveUntilMs before effectiveFromMs`() {
        try {
            buildContract(
                effectiveFromMs = 2_000L,
                effectiveUntilMs = 1_000L,
            )
            fail("expected IllegalArgumentException for effectiveUntilMs < effectiveFromMs")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "expected error to mention 'effectiveUntilMs', got: ${e.message}",
                e.message!!.contains("effectiveUntilMs"),
            )
        }
    }

    @Test
    fun `every contract status has a non-blank displayLabel`() {
        for (status in RoyaltyContractStatus.values()) {
            assertTrue(
                "expected non-blank displayLabel for $status",
                status.displayLabel.isNotBlank(),
            )
        }
    }

    // ============================================================
    // DefaultRoyaltyCalculator — percentage rule
    // ============================================================

    @Test
    fun `percentage rule calculates 5% of the transaction`() {
        val rule = RoyaltyRule.PercentageRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "5%",
            percentage = BigDecimal("0.05"),
        )
        val contract = buildContract(rule = rule)
        val settlement = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("100.00"),
            currency = "USD",
            timestampMs = 1_000L,
        ).getOrThrow()
        // 100.00 * 0.05 = 5.00.
        assertEquals(0, BigDecimal("5.00").compareTo(settlement.royaltyAmount))
    }

    @Test
    fun `percentage rule with minimumAmount clamps to the minimum`() {
        val rule = RoyaltyRule.PercentageRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "5% with $0.01 minimum",
            percentage = BigDecimal("0.05"),
            minimumAmount = BigDecimal("0.01"),
        )
        val contract = buildContract(rule = rule)
        // Transaction $0.10 → 5% = $0.005, but
        // the minimum is $0.01.
        val settlement = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("0.10"),
            currency = "USD",
            timestampMs = 1_000L,
        ).getOrThrow()
        assertEquals(0, BigDecimal("0.01").compareTo(settlement.royaltyAmount))
    }

    // ============================================================
    // DefaultRoyaltyCalculator — fixed-amount rule
    // ============================================================

    @Test
    fun `fixed-amount rule returns the constant amount regardless of transaction`() {
        val rule = RoyaltyRule.FixedAmountRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "Per-use fee",
            amount = BigDecimal("0.50"),
            currency = "USD",
        )
        val contract = buildContract(rule = rule)
        val small = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("1.00"),
            currency = "USD",
            timestampMs = 1_000L,
        ).getOrThrow()
        val big = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("1000.00"),
            currency = "USD",
            timestampMs = 1_000L,
        ).getOrThrow()
        // Both transactions have the same
        // royalty: $0.50.
        assertEquals(0, BigDecimal("0.50").compareTo(small.royaltyAmount))
        assertEquals(0, BigDecimal("0.50").compareTo(big.royaltyAmount))
    }

    // ============================================================
    // DefaultRoyaltyCalculator — tiered rule
    // ============================================================

    @Test
    fun `tiered rule uses the first matching tier's percentage`() {
        val rule = RoyaltyRule.TieredRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "Volume tiers",
            tiers = listOf(
                RoyaltyRule.Tier(upToVolume = 10L, percentage = BigDecimal("0.05")),
                RoyaltyRule.Tier(upToVolume = 100L, percentage = BigDecimal("0.03")),
                RoyaltyRule.Tier(upToVolume = Long.MAX_VALUE, percentage = BigDecimal("0.01")),
            ),
        )
        val contract = buildContract(rule = rule)
        // Transaction 5 → first tier (5 <= 10)
        // → 5% of 5 = 0.25.
        val small = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("5.00"),
            currency = "USD",
            timestampMs = 1_000L,
        ).getOrThrow()
        assertEquals(0, BigDecimal("0.25").compareTo(small.royaltyAmount))
    }

    // ============================================================
    // DefaultRoyaltyCalculator — inactive contract
    // ============================================================

    @Test
    fun `calculator rejects an inactive contract (per ADR-0004)`() {
        val contract = buildContract(status = RoyaltyContractStatus.DRAFT)
        val result = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("100.00"),
            currency = "USD",
            timestampMs = 1_000L,
        )
        assertTrue("expected failure for inactive contract, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(
            "expected InactiveContract error, got $error",
            error is RoyaltyCalculatorError.InactiveContract,
        )
    }

    @Test
    fun `calculator rejects a TERMINATED contract`() {
        val contract = buildContract(status = RoyaltyContractStatus.TERMINATED)
        val result = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("100.00"),
            currency = "USD",
            timestampMs = 1_000L,
        )
        assertTrue("expected failure for terminated contract", result.isFailure)
    }

    @Test
    fun `calculator rejects an EXPIRED contract`() {
        val contract = buildContract(status = RoyaltyContractStatus.EXPIRED)
        val result = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("100.00"),
            currency = "USD",
            timestampMs = 1_000L,
        )
        assertTrue("expected failure for expired contract", result.isFailure)
    }

    @Test
    fun `calculator accepts a PENDING_SIGNATURE contract (per ADR-0004 the rule)`() {
        // Wait, per ADR-0004, only ACTIVE
        // contracts can be settled. A
        // PENDING_SIGNATURE contract is NOT
        // yet active (only the platform has
        // signed; the contributor has not).
        // The calculator MUST reject it.
        val contract = buildContract(status = RoyaltyContractStatus.PENDING_SIGNATURE)
        val result = DefaultRoyaltyCalculator().calculate(
            contract = contract,
            transactionAmount = BigDecimal("100.00"),
            currency = "USD",
            timestampMs = 1_000L,
        )
        assertTrue("expected failure for PENDING_SIGNATURE contract", result.isFailure)
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private fun buildSettlement(
        transactionAmount: BigDecimal = BigDecimal("100.00"),
        royaltyAmount: BigDecimal = BigDecimal("5.00"),
        currency: String = "USD",
    ): Settlement = Settlement(
        settlementId = SettlementId.random(),
        programId = VehicleProgramId.random(),
        transactionAmount = transactionAmount,
        royaltyAmount = royaltyAmount,
        currency = currency,
        appliedRuleId = RoyaltyRuleId.random(),
        timestampMs = 1_000L,
        status = SettlementStatus.PENDING,
    )

    private fun buildContract(
        rule: RoyaltyRule = RoyaltyRule.PercentageRoyalty(
            ruleId = RoyaltyRuleId.random(),
            displayName = "Elysium 5%",
            percentage = BigDecimal("0.05"),
        ),
        status: RoyaltyContractStatus = RoyaltyContractStatus.ACTIVE,
        effectiveFromMs: Long = 1_000L,
        effectiveUntilMs: Long? = null,
        signedByContributorAtMs: Long? = if (status == RoyaltyContractStatus.ACTIVE) 1_000L else null,
        signedByPlatformAtMs: Long? = if (status == RoyaltyContractStatus.ACTIVE) 1_000L else null,
    ): RoyaltyContract = RoyaltyContract(
        contractId = RoyaltyContractId.random(),
        programId = VehicleProgramId.random(),
        contributorId = ContributorId.random(),
        rule = rule,
        status = status,
        effectiveFromMs = effectiveFromMs,
        effectiveUntilMs = effectiveUntilMs,
        signedByContributorAtMs = signedByContributorAtMs,
        signedByPlatformAtMs = signedByPlatformAtMs,
        signature = Signature("contract-signature"),
        contentHash = ContentHash("0".repeat(64)),
    )
}
