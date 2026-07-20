# Phase F5 third half (G6+G7, I-5.3) — Double-Entry Ledger

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** F5 / Foundry / Commercial foundation
> **Predecessor:** Phase F5 first half (Royalty) + second half (License)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.royalty.*`)

---

## TL;DR

The Foundry's **double-entry ledger** is operational.
The platform's accounting layer that records every
settlement. The ledger:

- Records every settlement as a **debit + a credit**
  (the platform's cut + the contributor's cut).
- Is **append-only** (a settled entry cannot be
  modified; a correction is a new compensating entry).
- Is **balanced** (the sum of all debits equals the
  sum of all credits at any point in time).
- Is the **audit artifact**: a regulator (a tax
  authority, a financial auditor) can inspect the
  ledger + reconstruct every transaction.

**Phase F5 (G6+G7) is now CLOSED.** The Foundry's
commercial foundation is fully specified:
- **Phase F5 first half**: `RoyaltyRule` +
  `RoyaltyContract` + `Settlement` +
  `DefaultRoyaltyCalculator` (the financial
  arrangement).
- **Phase F5 second half**: `License` (the legal
  envelope).
- **Phase F5 third half** (this): `DoubleEntryLedger`
  (the accounting layer).

The commercial pipeline is end-to-end: a contributor
signs a `RoyaltyContract` + a `License`; the platform
computes a `Settlement`; the settlement is recorded
in the `DoubleEntryLedger` for audit.

---

## What shipped

### `DoubleEntryLedger` (sealed class, 1 implementation)

The platform's accounting layer. The class has:

```kotlin
sealed class DoubleEntryLedger {
    abstract val entries: List<LedgerEntry>
    abstract fun append(entry: LedgerEntry): Result<Unit>
    abstract fun totalDebits(currency: String): BigDecimal
    abstract fun totalCredits(currency: String): BigDecimal
    fun isBalanced(currency: String): Boolean
}
```

The class is **sealed** (the only implementation is
`InMemoryDoubleEntryLedger`; a future Phase 7+
increment can add a `PersistentDoubleEntryLedger`).

The ledger is **append-only**: a new entry appends
to the existing list; existing entries are never
modified. The ledger's state is the list of all
entries (in append order).

### `InMemoryDoubleEntryLedger` (class)

The in-memory implementation. The class is
**thread-safe** (the underlying list is a
`CopyOnWriteArrayList` for safe iteration during
total computation).

### `LedgerEntry` (data class)

The typed entry. The entry has:

- **`entryId`** — UUID.
- **`transactionId`** — the transaction id (the
  settlement id from Phase F5 first half).
- **`debitAccount`** — the account that "loses" the
  money (per standard accounting terminology).
- **`creditAccount`** — the account that "gains" the
  money.
- **`amount`** — `BigDecimal` in the entry's currency
  (per ADR-0001).
- **`currency`** — 3-letter ISO 4217 code.
- **`description`** — human-readable.
- **`timestampMs`** — millis since epoch.
- **`signature`** — the entry's signature.

The entry is **immutable** (a data class; no
setters). A new entry is a new value. The ledger's
lifecycle (a correction, a reversal) is a new
`LedgerEntry` value, not a mutation of the existing
one.

### `Account` (data class) + `AccountType` (enum)

The typed account. The account has:

- **`accountId`** — UUID.
- **`displayName`** — human-readable.
- **`type`** — the typed account kind.

The `AccountType` enum has 5 values:

| Type | Meaning |
| --- | --- |
| `PLATFORM_REVENUE` | The platform's revenue account. |
| `CONTRIBUTOR_RECEIVABLE` | The contributor's receivable account. |
| `CUSTOMER_CASH` | The customer's cash account. |
| `EXPENSE` | A general expense account. |
| `LIABILITY` | A general liability account. |

### `LedgerEntryId` + `AccountId` (UUID value classes)

The typed ids. Both follow the Foundry id
convention (UUID-based, with `random()` + `from()`
factories).

### The realistic settlement scenario

The canonical settlement scenario: the customer
pays $100; the platform keeps $5 (5% royalty);
the contributor gets $95. The settlement produces
**two** ledger entries (the platform's accounting
requirement):

1. `CUSTOMER_CASH` → `PLATFORM_REVENUE`: $5
   (the platform's cut).
2. `CUSTOMER_CASH` → `CONTRIBUTOR_RECEIVABLE`: $95
   (the contributor's cut).

The total is $100 ($5 + $95). The ledger is
balanced (the total debits = the total credits =
$100).

---

## Design decisions

### Why a sealed class for `DoubleEntryLedger`, not a single class?

A sealed class is **type-safe + extensible**. The
test implementation is `InMemoryDoubleEntryLedger`;
a future Phase 7+ increment can add a
`PersistentDoubleEntryLedger` (backed by SQLite or
PostgreSQL) without changing the consumers. A
sealed class is the **right abstraction** for a
"single canonical ledger" pattern.

A single class would conflate the contract with
the implementation; the test would need a complex
mock to swap in a different storage backend.

### Why is the ledger append-only?

A double-entry ledger is **immutable** (per
standard accounting practice). A settled entry
cannot be modified; a correction is a new
**compensating** entry (the correction reverses
the original entry + records the new value).

The append-only design provides:
- **Audit immutability** — the auditor can verify
  that no entry was modified after the fact.
- **Concurrency safety** — multiple appenders
  don't conflict (each append is independent).
- **Simplicity** — the ledger's state is the
  current list; no rollback or compensation
  logic in the ledger itself.

### Why is the entry's amount positive, with debit/credit encoded by account roles?

Per standard accounting: the **amount is always
positive**; the direction is encoded by the
**debit/credit account roles**. A $5 platform
revenue entry has `amount = 5.00` +
`creditAccount = PLATFORM_REVENUE` (the platform
"gains" the money).

A naive "signed amount" design (positive = credit,
negative = debit) is harder to read + easier to
misinterpret. The standard accounting design is
**explicit** (the account roles make the direction
unambiguous).

### Why is the entry's `currency` a 3-letter ISO 4217 code?

The currency is a **machine-readable** reference to
the canonical currency. The 3-letter ISO 4217
code is the international standard (USD, EUR, JPY,
etc.). Using a free-form string would lose
interoperability with external accounting systems;
using the 3-letter code provides a standard
mapping.

### Why is the entry's `signature` mandatory?

The entry is an **audit** artifact. An entry
without a signature is **not auditable** — the
auditor cannot verify the entry was created by the
platform.

The signature is computed over the entry's
canonical form (the form excludes the signature
itself, like the manifest's signature). A future
Phase 7+ increment may require a **multi-signature**
for high-value entries (e.g. entries > $10K
require two platform signatures).

### Why is `isBalanced` a method, not a field?

A field would compute the balance eagerly (when
the entry is added). A method computes the balance
on-demand (when the auditor asks). The method is
**lazy** + **always-fresh** (the auditor always
sees the current state).

The performance difference is negligible (the
total is a single fold over the entries). The
semantic difference is significant (a method
encodes the "check" verb; a field encodes the
"is balanced" state).

---

## Tests

18 new tests in `DoubleEntryLedgerTest`. The tests
cover:

- **LedgerEntry invariants** (8 tests): well-formed
  configuration, blank transactionId, zero amount,
  negative amount, non-3-letter currency, lowercase
  currency, blank description, non-positive timestamp.
- **Account invariants** (3 tests): well-formed
  configuration, blank displayName, every
  AccountType has a non-blank displayLabel.
- **InMemoryDoubleEntryLedger** (6 tests): empty
  ledger has zero totals, append stores in append
  order, totalDebits returns the sum, totalCredits
  returns the sum, isBalanced returns true, isBalanced
  returns false.
- **Realistic scenario** (1 test): a settlement
  produces two balanced ledger entries (the
  platform's $5 cut + the contributor's $95 cut).

**Total foundry tests:** ~680 (was ~662; +18 new).
**Total project tests:** 3083 (was 3065, +18 new).

---

## Phase F5 — CLOSED

With the ledger shipped, **Phase F5 (G6+G7) is
closed**. The Foundry's commercial foundation is
fully specified:

- **Phase F5 first half** (commit `cb5fbb9`):
  `RoyaltyRule` + `RoyaltyContract` + `Settlement` +
  `DefaultRoyaltyCalculator`.
- **Phase F5 second half** (commit `4687701`):
  `License`.
- **Phase F5 third half** (this): `DoubleEntryLedger`.

The commercial pipeline is end-to-end:

1. A contributor signs a `RoyaltyContract` (with
   a `RoyaltyRule` + an effective period).
2. A contributor grants a `License` (with the
   permitted uses + the SPDX identifier).
3. A transaction produces a `Settlement` (per
   the rule + the transaction amount).
4. The settlement is recorded in the
   `DoubleEntryLedger` (as a debit + a credit).
5. An auditor can inspect the ledger +
   reconstruct every transaction.

The pipeline is the **commercial core** that the
marketplace (Phase F6) + the production hardening
(Phase F7) build on.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/royalty/DoubleEntryLedger.kt` | new | ledger sealed class + in-memory impl + entry + account + types |
| `app/src/test/java/com/elysium/vanguard/foundry/core/royalty/DoubleEntryLedgerTest.kt` | new | 18 JVM tests |

---

## The role in the bigger picture

The double-entry ledger is the **audit artifact**
of the platform. Every settlement is recorded in
the ledger; the ledger's totals are balanced; the
ledger's entries are immutable.

The ledger is the **bridge** between the financial
arrangement (the `RoyaltyContract` + the
`RoyaltyRule`) + the legal envelope (the `License`)
+ the platform's accounting system. The bridge
ensures:

- The platform's revenue is tracked (the
  `PLATFORM_REVENUE` account).
- The contributor's receivable is tracked (the
  `CONTRIBUTOR_RECEIVABLE` account).
- The customer's cash is tracked (the `CUSTOMER_CASH`
  account).
- The total is always balanced (the sum of all
  debits equals the sum of all credits).

The ledger is the **compliance artifact** for
financial regulators + tax authorities. The
ledger's immutability + balanced totals + signed
entries are the **non-negotiable** properties that
make the ledger auditable.

The next concrete sub-task: **Foundry Phase F6
(G8) — Marketplace + Supplier Network** (the
commercial layer that uses the royalty foundation
+ the license + the ledger to power the
marketplace).
