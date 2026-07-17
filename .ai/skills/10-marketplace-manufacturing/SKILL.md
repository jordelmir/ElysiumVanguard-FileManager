---
name: marketplace-manufacturing
description: The marketplace, the escrow, the supplier / engineer / lab / manufacturer integration, the order lifecycle, the fulfillment chain. The "you can buy a vehicle design (or a part, or a service) here" surface.
---

# Skill 10 — Marketplace + Manufacturing

## 1. Mission

Build and maintain the **marketplace** where users
discover, evaluate, purchase, and receive vehicle
designs (or parts, or services). Build and maintain
the **supplier / engineer / lab / manufacturer
integration** that turns a purchase into a
fulfillable, shippable product.

The marketplace is the platform's "you can buy a
vehicle design (or a part, or a service) here"
surface. The supplier integration is the
platform's "the supplier can fulfill the order"
back office.

## 2. In-scope

- The marketplace catalog (browse, search, filter,
  compare, save).
- The listing (a saleable artifact, with price,
  license, royalty contract, delivery method).
- The order (a buyer's intent to purchase).
- The escrow (the holding of the payment until
  the order is fulfilled).
- The fulfillment (the chain from order to
  delivery: design delivery, manufacturing,
  shipping, installation).
- The supplier integration (the API the
  suppliers, engineers, labs, and manufacturers
  use to fulfill orders).
- The reviews (the buyer's feedback on the
  order, the supplier, the product).
- The disputes (the buyer's claim that the order
  is not as described; the supplier's claim that
  the buyer is in breach).
- The refunds (the resolution of a dispute).

## 3. Out-of-scope

- The royalty calculation (skill 09).
- The 3D asset (skill 06).
- The diagnostic (skill 07).
- The mobile UX (skill 11).
- The payment provider (a third-party SaaS — this
  skill is the integration layer).

The marketplace lists the asset. The settlement
engine (skill 09) pays the royalty. The diagnostic
(skill 07) provides the field data. Each is its
own concern.

## 4. Inputs

- A `Listing` (a saleable artifact, with the
  catalog reference, the price, the license, the
  royalty contract).
- A `Buyer` (a user with intent to purchase).
- A `Supplier` (a user with intent to fulfill
  the order).
- An `Order` (a buyer's intent to purchase a
  listing).
- A `Fulfillment` (a supplier's progress on the
  order).
- A `Review` (a buyer's feedback).
- A `Dispute` (a buyer's or supplier's claim).
- A `Refund` (the resolution of a dispute).

## 5. Outputs

- The marketplace catalog (browse, search,
  filter, compare, save).
- The listing page (the buyer's view of a
  listing).
- The order (a signed contract between the
  buyer and the supplier, with the escrow
  state).
- The fulfillment status (the supplier's
  progress on the order).
- The review (the buyer's feedback).
- The dispute resolution (the platform's
  decision on a dispute).
- The settlement (the per-user amounts from
  skill 09, the per-order fees, the per-
  marketplace fees).

The marketplace emits the events (skill 08) that
trigger the settlements (skill 09). The
marketplace consumes the catalog (skill 09) to
know what to list.

## 6. Workflow

1. **Receive a `Listing`.** A supplier (or a
   project owner) creates a listing. The
   marketplace validates:
   - The asset exists in the catalog.
   - The royalty contracts (skill 09) are
     valid.
   - The license is set.
   - The price is set.
   - The export controls (skill 09) are
     honored.
2. **List.** The listing is in the marketplace
   catalog. The buyer can browse, search,
   filter, compare, save.
3. **Receive an `Order`.** A buyer places an
   order. The marketplace:
   - Reserves the escrow.
   - Notifies the supplier.
   - Emits a `Sale` event (skill 08) that
     triggers the settlement (skill 09).
4. **Fulfill.** The supplier (or the
   manufacturer, or the lab) fulfills the
   order. The marketplace tracks the
   fulfillment status. The fulfillment is
   digital (a download link), physical (a
   shipped part), or service (a scheduled
   appointment).
5. **Release the escrow.** When the fulfillment
   is complete + the buyer's review window has
   passed, the marketplace releases the
   escrow to the supplier.
6. **Handle disputes.** A buyer or supplier
   can file a dispute. The marketplace routes
   the dispute to the AI council (skill 05)
   for arbitration. The council's decision is
   binding.
7. **Issue refunds.** If the dispute is
   resolved in the buyer's favor, the
   marketplace issues a refund.
8. **Pay the supplier.** The settlement (skill
   09) pays the supplier, the royalty
   recipients, the platform fees.

## 7. Quality gates

- Every listing is in the catalog.
- Every order has a signed contract.
- Every escrow is reserved before fulfillment
  starts.
- Every fulfillment is tracked.
- Every dispute is arbitrated.
- Every refund is signed.
- Every settlement is in the audit trail.
- The marketplace's SLOs are met (browse
  latency < 200ms p99; order placement < 1s
  p99; settlement cycle < 24h).

## 8. Failure modes

- **A listing is invalid.** The marketplace
  rejects the listing. The supplier is
  informed.
- **A buyer fails the KYC check.** The
  marketplace blocks the order. The buyer is
  informed.
- **A supplier fails the fulfillment SLA.** The
  marketplace refunds the buyer. The supplier
  is informed.
- **A dispute is unresolved.** The marketplace
  escalates to the AI council.
- **A settlement fails.** The marketplace
  retries. The supplier is informed.
- **The platform is breached.** The marketplace
  freezes all orders. The platform's incident
  response (skill 00) is invoked.

## 9. Coordination contract

- **Input from**: skill 09 (catalog), skill 11
  (mobile), the user, the supplier.
- **Output to**: skill 08 (events), skill 09
  (settlements), skill 11 (mobile).
- **Triggered by**: every listing, every order,
  every fulfillment, every review, every
  dispute.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **Off-platform orders.** A supplier that
  encourages the buyer to pay outside the
  platform is a contract violation. The
  supplier is delisted.
- **Untracked settlements.** A sale without a
  settlement is unpaid royalty. The marketplace
  is the trigger; skill 09 is the engine.
- **Mutable reviews.** A review that the
  supplier can delete is a contract violation.
  Reviews are append-only.
- **Faked ratings.** A rating that the platform
  inserts to manipulate the marketplace is a
  contract violation. Ratings come from real
  orders.
- **"We'll verify the supplier later".** A
  supplier that is not KYC'd + vetted is a
  contract violation. The supplier is gated
  before the listing is live.
- **Hidden fees.** A fee that the buyer does
  not see at checkout is a contract violation.
  Every fee is in the order's itemized
  breakdown.
- **Undisclosed conflicts of interest.** A
  supplier that is also a platform employee is
  a contract violation. The conflict is
  disclosed in the listing.

## 11. The marketplace in the Elysium Automotive
Foundry

The marketplace is the platform's "you can buy a
vehicle design (or a part, or a service) here"
surface. A user lists a vehicle design (or a
part, or a service) → another user buys it →
the supplier fulfills it → the royalty engine
pays the contributors → the audit trail records
the full chain.

The marketplace is the platform's "we know who
bought what, and we know who got paid" answer.

## 12. Working with this skill

When invoked, this skill:

1. Receives the listing / order / fulfillment /
   review / dispute.
2. Validates it.
3. Persists it (in the catalog, in the event
   store).
4. Triggers the next step (the supplier, the
   settlement, the AI council).
5. Returns the result to the orchestrator (or
   to the calling skill directly).

The skill does not render the marketplace UI
(skill 11). The skill does not calculate the
royalty (skill 09). The skill does not enforce
the KYC (skill 12). The skill is the **market
logic** that makes the transactions flow.
