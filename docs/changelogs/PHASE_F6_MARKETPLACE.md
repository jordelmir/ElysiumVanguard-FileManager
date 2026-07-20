# Phase F6 first half (G8, I-6.1) — Marketplace

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** F6 / Foundry / Marketplace + Supplier Network
> **Predecessor:** Phase F5 (Commercial foundation, closed)
> **Vertical:** Foundry (`com.elysium.vanguard.foundry.core.marketplace.*`)

---

## TL;DR

The Foundry's **Marketplace** is operational. The
B2B marketplace primitives for the Foundry's
contributor program: **RFQ (Request for Quote) +
Offer + Order**.

The marketplace is the **commercial layer** that
uses the royalty foundation (Phase F5 first half),
the license (Phase F5 second half), and the
double-entry ledger (Phase F5 third half) to power
B2B transactions. The marketplace is the **3
primitives**:

1. **`RFQ` (Request for Quote)** — a buyer requests
   quotes from suppliers for a specific vehicle
   component.
2. **`Offer`** — a supplier responds to an RFQ
   with a quote (price + delivery time).
3. **`Order`** — a buyer accepts an offer + creates
   an order (the transaction is committed).

The 3 primitives form the **canonical B2B
marketplace flow**:
1. The buyer creates an RFQ.
2. Suppliers submit Offers.
3. The buyer accepts an Offer → creates an Order.
4. The Order is recorded in the double-entry ledger.

This is the **first of two sub-tasks** in Phase F6
(G8). The remaining sub-task:

- **I-6.2** — `Supplier` + `SupplierQualification` (the
  supplier network).

---

## What shipped

### `RFQ` (sealed class, 2 cases)

The typed Request for Quote. The 2 cases are:

```kotlin
sealed class RFQ {
    abstract val rfqId: RFQId
    abstract val buyerId: UserId
    abstract val programId: VehicleProgramId
    abstract val componentSpec: String
    abstract val quantity: Int
    abstract val maxBudget: BigDecimal
    abstract val currency: String
    abstract val deadlineMs: Long
    abstract val signature: Signature
    abstract val status: RFQStatus

    data class Open(...) : RFQ()
    data class Closed(..., val acceptedOfferId: OfferId?) : RFQ()
}
```

- **`RFQ.Open`** — suppliers can submit offers.
- **`RFQ.Closed`** — the buyer has accepted an
  offer (or the deadline has passed); no more
  offers are accepted.

### `Offer` (data class)

The supplier's response to an RFQ. The offer has:

- **`offerId`** — UUID.
- **`rfqId`** — the RFQ the offer is for.
- **`supplierId`** — the supplier (a [UserId]).
- **`pricePerUnit`** — `BigDecimal` in the offer's
  currency.
- **`currency`** — 3-letter ISO 4217 code.
- **`deliveryTimeDays`** — the supplier's estimated
  delivery time in days.
- **`validUntilMs`** — the offer's expiration
  timestamp.
- **`status`** — the offer's lifecycle state
  (PENDING / ACCEPTED / REJECTED / EXPIRED).
- **`signature`** — the offer's signature.

### `Order` (data class)

The buyer's acceptance of an offer. The order has:

- **`orderId`** — UUID.
- **`rfqId`** — the RFQ the order is for.
- **`offerId`** — the offer the order is accepting.
- **`buyerId`** + **`supplierId`** + **`programId`**.
- **`totalAmount`** — the total order amount
  (pricePerUnit * quantity).
- **`currency`** — 3-letter ISO 4217 code.
- **`status`** — the order's lifecycle state
  (PENDING / FULFILLED / CANCELLED).
- **`timestampMs`** — when the order was created.
- **`signature`** — the order's signature.

### `RFQStatus` + `OfferStatus` + `OrderStatus` (enums)

The typed lifecycle states. The enums reflect the
canonical B2B marketplace flow:

| RFQ | Offer | Order |
| --- | --- | --- |
| OPEN | PENDING | PENDING |
| CLOSED | ACCEPTED | FULFILLED |
|  | REJECTED | CANCELLED |
|  | EXPIRED |  |

### `RFQId` + `OfferId` + `OrderId` (UUID value classes)

The typed ids. All three follow the Foundry id
convention (UUID-based, with `random()` + `from()`
factories).

### The realistic scenario

The canonical B2B marketplace scenario: a buyer
needs 10 V8 engine blocks; a supplier offers them
at $4,500 per unit + 30 days delivery; the buyer
accepts the offer; the order is created.

The total order amount is $45,000 (10 * $4,500).
The order is recorded in the double-entry ledger
(per Phase F5 third half).

---

## Design decisions

### Why a sealed class for `RFQ`, not a single class with a status flag?

A sealed class is **type-safe + exhaustive**. The
consumer (the marketplace orchestrator) uses
`when (rfq)` to dispatch by case:
- `is RFQ.Open` — accept offers.
- `is RFQ.Closed` — reject offers; the RFQ is
  final.

A single class with a flag would lose the type
safety; the consumer would need to check the
status. The sealed class captures the **2 distinct
lifecycle states** the RFQ can have.

### Why is the `Offer` a data class, not a sealed class?

The `Offer` is a **single class** (not a sealed
class with multiple cases) because the offer's
lifecycle is encoded in a **status enum** (PENDING /
ACCEPTED / REJECTED / EXPIRED). The offer's
immutability is preserved by the data class (a
status change produces a new `Offer` value via
`.copy()`, not a mutation).

A sealed class would be overkill for a single
lifecycle axis; the status enum is the simpler
representation.

### Why is the `Order` a data class, not a sealed class?

Same reasoning as the `Offer` — the order's
lifecycle is encoded in a **status enum** (PENDING /
FULFILLED / CANCELLED). The order's immutability is
preserved by the data class.

A future Phase 7+ increment may add a `Cancelled`
case with a `cancellationReason` field; the data
class can be extended with a `cancellationReason`
property without breaking the existing consumers.

### Why is the `Order.totalAmount` a separate field, not computed?

The `Order.totalAmount` is a **separate field** (not
computed from `pricePerUnit * quantity`) because:

- The order is **immutable** (the data class
  captures the total at the time of the order).
- The order's price may not equal the offer's
  price (the buyer may have negotiated a
  different price; the order captures the
  negotiated price).
- The auditor needs to see the total amount
  without computing it (a single field is
  auditable; a computed value requires the offer).

A future Phase 7+ increment may add a
`negotiationHistory` field to capture the price
negotiation.

---

## Tests

20 new tests in `MarketplaceTest`. The tests cover:

- **RFQ.Open invariants** (6 tests): well-formed
  configuration, blank componentSpec, zero quantity,
  negative maxBudget, non-3-letter currency,
  non-positive deadline.
- **RFQ.Closed** (1 test): status is CLOSED.
- **Offer invariants** (5 tests): well-formed
  configuration, zero pricePerUnit, non-3-letter
  currency, non-positive deliveryTimeDays,
  non-positive validUntilMs.
- **Order invariants** (4 tests): well-formed
  configuration, zero totalAmount, non-3-letter
  currency, non-positive timestamp.
- **Status enums** (3 tests): RFQStatus, OfferStatus,
  OrderStatus have the expected cases.
- **Realistic scenario** (1 test): the canonical
  B2B marketplace flow (RFQ → Offer → accepted Offer
  → Order).

**Total foundry tests:** ~700 (was ~680; +20 new).
**Total project tests:** 3103 (was 3083, +20 new).

---

## What's next — Phase F6 second half (G8, I-6.2)

`Supplier` + `SupplierQualification` — the supplier
network. The supplier is the **typed identity** of
a supplier (a human or an organization); the
qualification is the **typed record** of the
supplier's capabilities (the parts they can
supply, the regions they serve, the certifications
they hold).

The supplier network is the **directory** the
marketplace uses to find suppliers. The supplier
network is the **typed discovery** layer for the
marketplace: the buyer creates an RFQ; the
marketplace queries the supplier network; the
matching suppliers submit offers.

The supplier network is **pure-domain** (no I/O, no
Android dependencies). The test implementation is
an in-memory registry; the production implementation
is a distributed registry (a future Phase 7+
increment).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/foundry/core/marketplace/Marketplace.kt` | new | RFQ sealed class + Offer + Order + status enums + ids |
| `app/src/test/java/com/elysium/vanguard/foundry/core/marketplace/MarketplaceTest.kt` | new | 20 JVM tests |

---

## The role in the bigger picture

The marketplace is the **commercial layer** that
ties together the Foundry's three commercial
primitives:

- **The royalty foundation** (Phase F5 first
  half) — the financial arrangement.
- **The license** (Phase F5 second half) — the
  legal envelope.
- **The double-entry ledger** (Phase F5 third
  half) — the accounting record.

The marketplace uses the three primitives:

- A buyer creates an **RFQ** (the request).
- Suppliers submit **Offers** (the response).
- The buyer accepts an Offer → creates an **Order**
  (the commit).
- The Order is recorded in the **double-entry
  ledger** (the audit).
- The Order is bound to a **RoyaltyContract** +
  a **License** (the legal + financial arrangement).

The marketplace is the **bridge** between the
commercial primitives + the actual B2B
transactions. The bridge is the **commercial
pipeline**: the pipeline that turns a buyer's need
into a fulfilled order with a financial settlement
+ a legal license + an audit record.
