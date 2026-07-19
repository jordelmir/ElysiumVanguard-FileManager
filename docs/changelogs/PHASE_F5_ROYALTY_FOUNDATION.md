# Phase F5 first half (G6+G7, I-5.1) — Royalty Foundation

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** F5 / Foundry / Commercial foundation
> **Predecessor:** Foundry Phase F4 (AI council)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.royalty.*`)

---

## TL;DR

The Foundry's **commercial foundation** is operational. The
platform can now:

1. **Configure royalties** with a typed `RoyaltyRule`
   (3 kinds: Percentage, FixedAmount, Tiered).
2. **Sign a contract** between the platform + a
   contributor (`RoyaltyContract` with status
   DRAFT → PENDING_SIGNATURE → ACTIVE).
3. **Calculate a settlement** from an ACTIVE contract +
   a transaction amount (`DefaultRoyaltyCalculator` +
   `Settlement`).
4. **Reject inactive contracts** (per ADR-0004: a
   settlement can only be computed for an ACTIVE contract).

This is the **first of three sub-tasks** in Phase F5
(G6+G7). The remaining sub-tasks:

- **I-5.2** — `License` (the typed usage license for a
  contract).
- **I-5.3** — Double-entry ledger integration
  (the platform's accounting layer that records every
  settlement).

---

## What shipped

### `RoyaltyRule` (sealed class, 3 cases)

The typed configuration of how royalties are calculated.
The 3 cases are:

```kotlin
sealed class RoyaltyRule {
    data class PercentageRoyalty(
        val percentage: BigDecimal,        // 0.05 = 5%
        val minimumAmount: BigDecimal? = null,
    ) : RoyaltyRule()

    data class FixedAmountRoyalty(
        val amount: BigDecimal,             // e.g. 0.10
        val currency: String,               // ISO 4217
    ) : RoyaltyRule()

    data class TieredRoyalty(
        val tiers: List<Tier>,
    ) : RoyaltyRule() {
        data class Tier(
            val upToVolume: Long,
            val percentage: BigDecimal,
        )
    }
}
```

Per ADR-0005: "Elysium 5% royalty is a configurable
`RoyaltyRule` (not hardcoded)". The 5% is one possible
rule, not the only one. The platform supports multiple
rule kinds; the user picks.

### `RoyaltyContract` (data class)

The typed legal contract between the platform + a
contributor. The contract has:

- **`contractId`** — UUID.
- **`programId`** — the program the contract is for.
- **`contributorId`** — the contributor the contract is
  with.
- **`rule`** — the `RoyaltyRule`.
- **`status`** — the lifecycle state
  (DRAFT / PENDING_SIGNATURE / ACTIVE / TERMINATED /
  EXPIRED).
- **`effectiveFromMs`** — when the contract becomes
  effective.
- **`effectiveUntilMs`** — when the contract expires
  (null = no expiration).
- **`signedByContributorAtMs`** — when the contributor
  signed (null = unsigned).
- **`signedByPlatformAtMs`** — when the platform signed
  (null = unsigned).
- **`signature`** — the signature on the canonical form.
- **`contentHash`** — the SHA-256 of the canonical form.

The init block enforces: a contract is `ACTIVE` only when
both parties have signed (both timestamps are set).

### `Settlement` (data class)

The typed result of a royalty calculation. The settlement
has:

- **`settlementId`** — UUID.
- **`programId`** — the program the settlement is for.
- **`transactionAmount`** — the gross transaction amount.
- **`royaltyAmount`** — the calculated royalty.
- **`currency`** — ISO 4217 code.
- **`appliedRuleId`** — the rule that was applied (for
  audit).
- **`timestampMs`** — when the settlement was computed.
- **`status`** — the lifecycle state
  (PENDING / PAID / REVERSED).

All money values are `BigDecimal` (per ADR-0001).

### `RoyaltyCalculator` (interface) + `DefaultRoyaltyCalculator` (class)

The orchestrator that takes a `RoyaltyContract` + a
transaction amount + produces a `Settlement`.

```kotlin
interface RoyaltyCalculator {
    fun calculate(
        contract: RoyaltyContract,
        transactionAmount: BigDecimal,
        currency: String,
        timestampMs: Long,
    ): Result<Settlement>
}
```

The default implementation:

1. **Rejects inactive contracts.** Per ADR-0004, a
   settlement can only be computed for an ACTIVE
   contract. The calculator returns a typed
   `RoyaltyCalculatorError.InactiveContract` on
   rejection.
2. **Applies the rule's kind** to the transaction amount.
3. **Returns the settlement** with `status = PENDING` (the
   payment hasn't been processed yet).

### `RoyaltyCalculatorError` (sealed class)

```kotlin
sealed class RoyaltyCalculatorError : RuntimeException {
    data class InactiveContract(
        val contractId: RoyaltyContractId,
        val currentStatus: RoyaltyContractStatus,
    ) : RoyaltyCalculatorError(...)
}
```

The error envelope is the typed outcome the calculator
returns on a failed calculation.

---

## Design decisions

### Why a sealed class for `RoyaltyRule`, not a single class with a flag?

A sealed class is **type-safe + exhaustive**. The
calculator's `when (val rule = contract.rule)` is
exhaustive — adding a 4th rule kind is a compile error in
every consumer that hasn't been updated.

A single class with a `kind: RuleKind` flag would lose
the type-safety: the calculator would need a `when
(rule.kind)` that may be incomplete, and a typo is a
silent default.

The 3 cases reflect the **3 distinct royalty strategies**
the platform supports. The sealed class captures the
distinction at the type level.

### Why is `RoyaltyContract.ACTIVE` the only valid state for settlement?

Per ADR-0004: "RoyaltyContract must be ACTIVE before a
Settlement is computed". The settlement is a **legal +
financial** artifact; computing a settlement for an
inactive contract is a **deployment error**.

The calculator's `InactiveContract` error envelope is
the typed rejection. The init block of `RoyaltyContract`
also enforces the invariant: an ACTIVE contract requires
both `signedByContributorAtMs` + `signedByPlatformAtMs`
to be set (a contract cannot be ACTIVE without both
signatures).

### Why is the calculator thread-safe?

The calculator is **stateless** (no mutable fields). A
`DefaultRoyaltyCalculator` instance can be shared across
threads safely. The Hilt module can inject a single
singleton; the production code can call
`calculator.calculate(...)` from any thread.

### Why is `RoyaltyContractStatus.EXPIRED` separate from `TERMINATED`?

The two are **different lifecycle events**:

- **`TERMINATED`** — either party cancelled the contract
  (a deliberate action).
- **`EXPIRED`** — the effective period ended (a passive
  event).

A `TERMINATED` contract is an active cancellation
(may have implications for the contributor); an
`EXPIRED` contract is a passive end (no implications).
The platform may want to handle them differently (e.g.
a TERMINATED contract may require a notice; an EXPIRED
contract is silent).

### Why is the `appliedRuleId` on the settlement, not the `appliedRule` itself?

The settlement stores the **rule's id** (a UUID), not
the rule itself. This is **storage efficiency** + **audit
immutability**:

- The rule may change over time (a rate adjustment, a
  tier revision). The settlement is the **historical
  record** of what the rule was at the time of the
  settlement.
- Storing the rule by id means the settlement is
  **immutable** — it doesn't reference a mutable rule
  that could change.
- The audit log can join `appliedRuleId` to the rule's
  history to reconstruct "what was the rule at the time
  of this settlement?".

### Why BigDecimal, not Double, for money?

Per ADR-0001: "Money is `BigDecimal`, never `Double`/`Float`".
A `Double` would lose precision (e.g. `0.1 + 0.2 =
0.30000000000000004`); a royalty calculation that
compounded the error would misallocate millions. A
`BigDecimal` preserves precision exactly.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. `BigDecimal("1_000.00")` rejected with NumberFormatException

**Symptom:** `NumberFormatException` at the
`fixed-amount rule returns the constant amount regardless
of transaction` test.

**Root cause:** Kotlin's number literals support
underscores (`1_000L` is valid), but `BigDecimal(String)`
does NOT interpret underscores. The string `"1_000.00"`
is not a valid `BigDecimal` literal.

**Fix:** Replaced `BigDecimal("1_000.00")` with
`BigDecimal("1000.00")` (no underscores).

This is a **test-discovered** bug — the test surfaced a
common Kotlin gotcha (underscores in number literals
are a Kotlin language feature, not a BigDecimal feature).

---

## Tests

27 new tests in `RoyaltyCalculatorTest`. The tests cover:

- **RoyaltyRule invariants** (10 tests): Percentage
  (happy + zero rejected + negative rejected + > 1
  rejected + minimumAmount = 0), FixedAmount (happy +
  non-3-letter rejected + lowercase rejected), Tiered
  (happy + empty rejected).
- **Settlement invariants** (4 tests): happy + negative
  transactionAmount + negative royaltyAmount + non-3-letter
  currency.
- **RoyaltyContract invariants** (5 tests): ACTIVE happy
  + ACTIVE without contributor signature + ACTIVE without
  platform signature + effectiveUntilMs < effectiveFromMs
  + every status has a non-blank displayLabel.
- **DefaultRoyaltyCalculator — percentage rule** (2 tests):
  5% of $100 = $5, minimumAmount clamps to the minimum.
- **DefaultRoyaltyCalculator — fixed-amount rule** (1
  test): constant amount regardless of transaction size.
- **DefaultRoyaltyCalculator — tiered rule** (1 test): first
  matching tier's percentage.
- **DefaultRoyaltyCalculator — inactive contract** (4
  tests): DRAFT rejected, TERMINATED rejected, EXPIRED
  rejected, PENDING_SIGNATURE rejected (per ADR-0004 the
  rule).

**Total foundry tests:** ~647 (was ~620; +27 new).
**Total project tests:** 3013 (was 2986, +27 new).

---

## What's next — Phase F5 second half (G6+G7, I-5.2)

`License` — the typed usage license for a contract. The
license is the **legal envelope** that defines what the
contributor can do with the contribution (use, modify,
distribute, sublicense). The license is bound to the
contract + the program.

The license is a `data class` with:

- **`licenseId`** — UUID.
- **`contractId`** — the contract the license is bound
  to.
- **`programId`** — the program the license is for.
- **`kind`** — the license kind (MIT / Apache-2.0 /
  Elysium-Proprietary / custom).
- **`terms`** — the license text (or a reference to
  the canonical terms).
- **`signature`** — the signature binding the license
  to the contract.
- **`effectiveFromMs`** + **`effectiveUntilMs`** — the
  effective period.

A future Phase 7+ increment may add a **License
Validator** that checks a user's usage against the
license terms.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/royalty/RoyaltyRule.kt` | new | sealed class + 3 rule cases + RoyaltyRuleId |
| `app/src/main/java/com/elysium/vanguard/foundry/core/royalty/RoyaltyContract.kt` | new | data class + status enum + RoyaltyContractId |
| `app/src/main/java/com/elysium/vanguard/foundry/core/royalty/Settlement.kt` | new | data class + status enum + calculator interface + default impl + error envelope |
| `app/src/test/java/com/elysium/vanguard/foundry/core/royalty/RoyaltyCalculatorTest.kt` | new | 27 JVM tests |

---

## The role in the bigger picture

The royalty foundation is the **commercial layer** of the
Foundry. The contributor submits a contribution; the
contribution is bound to a `RoyaltyContract`; the
contract defines the `RoyaltyRule`; the rule is applied
to every transaction to compute a `Settlement`; the
settlement is recorded in the platform's double-entry
ledger (Phase F5 third half).

The royalty foundation enforces:

- **ADR-0004** — the contract must be ACTIVE before a
  settlement is computed.
- **ADR-0005** — the 5% is a configurable `RoyaltyRule`,
  not hardcoded.
- **ADR-0001** — money is `BigDecimal`, never `Double`/
  `Float`.

The foundation is the **commercial core** that the
marketplace (Phase F6) + the production hardening (Phase
F7) build on. Without the royalty foundation, the
marketplace has no way to compensate contributors; the
production hardening has no way to enforce financial
commitments.
