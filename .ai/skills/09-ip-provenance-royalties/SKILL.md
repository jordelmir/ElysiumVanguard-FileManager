---
name: ip-provenance-royalties
description: Authorship, contribution tracking, royalty contracts, settlement, and the audit trail. The "we know who built what, and we can prove it" skill. The catalog is the single source of truth.
---

# Skill 09 — IP / Provenance / Royalties

## 1. Mission

Track **who built what** and **how the value flows**.
Every artifact the platform produces has an
authorship chain; every contribution has a royalty
contract; every settlement has an audit trail. The
catalog is the single source of truth.

The skill is the platform's "we know who built
what, and we can prove it" answer. Without it, the
marketplace (skill 10) cannot exist; without it,
the royalty engine cannot run; without it, the
regulatory submission (skill 13) cannot prove
provenance.

## 2. In-scope

- The catalog (the typed index of every artifact
  the platform has produced).
- The authorship chain (who contributed what to
  which artifact; the cryptographic signatures
  that prove it).
- The contribution graph (the DAG of every
  contribution, with the timestamps, the role,
  the evidence).
- The royalty contracts (the smart-contract-like
  rules that govern how a contribution's value
  flows).
- The settlement engine (the batched process that
  calculates who gets paid for what).
- The audit trail (the immutable log of every
  settlement, every contract change, every
  authorship claim).
- The licensing (the per-artifact license under
  which the artifact is published).
- The export controls (UN, EU, US ITAR, etc.
  restrictions on who can access what).
- The GDPR / CCPA / LGPD compliance (right to
  erasure, right to access, right to portability).

## 3. Out-of-scope

- The 3D model (skill 06).
- The diagnostics (skill 07).
- The marketplace UX (skill 10).
- The mobile UX (skill 11).
- The royalty **calculation** (this skill
  **executes** the contracts; the contract
  grammar is in skill 02 / 04 / 05).

The catalog says "this asset is built by user X
and user Y, with contributions from user Z, under
the MIT license, with a 5% royalty to user X for
every unit sold". The marketplace sells the asset.
The settlement engine pays the royalty. Each is
its own concern.

## 4. Inputs

- An `AuthorshipClaim` (a signed claim that a
  user contributed to an artifact). The claim
  carries:
  - The artifact ID.
  - The user's ID.
  - The role (`author` / `contributor` /
    `reviewer` / `supplier` / `agent`).
  - The evidence (a reference to a git
    commit, a design document, a review, a
    test).
  - The timestamp.
  - The royalty share (a percentage or a
    formula).
  - **The verification level of the
    contribution.** An `AI_INFERRED`
    contribution is signed by the agent
    + counter-signed by a human reviewer
    before it is `VERIFIED` (per skill 03).
    The transition is a signed event in the
    audit trail, not a silent state flip.
- A `RoyaltyContract` (a smart-contract-like
  rule that defines the royalty). The contract
  is bound to the artifact's
  `VehicleRepresentationLevel`. A
  `VISUAL_ONLY` or `CONCEPTUAL` artifact is
  not eligible for royalty settlement; the
  contract is rejected at validation.
- A `Sale` (a marketplace event that triggers
  a settlement). A sale of a
  `VISUAL_ONLY` or `CONCEPTUAL` artifact is
  rejected; the marketplace (skill 10)
  enforces this at listing time.
- A `License` (the license under which the
  artifact is published).
- An `ExportControlDeclaration` (the export
  controls the user agrees to).
- A `GDPRRequest` (a user request to erase,
  access, or port their data).
- A `ProvenanceComplete` flag (a typed
  boolean asserting that every engineering
  fact in the artifact has all required
  metadata). A `ProvenanceIncomplete`
  artifact is rejected for royalty
  settlement.

## 5. Outputs

- The catalog (a typed index, queryable by
  artifact ID, by user, by project, by license,
  by export control).
- The authorship chain (a DAG, queryable by
  artifact ID).
- The royalty contracts (a registry, queryable
  by asset ID, by user, by marketplace).
- The settlement (a batched, signed artifact
  per settlement cycle, with the per-user
  amounts).
- The audit trail (an append-only log, queryable
  by event ID, by user, by artifact, by date
  range).
- The export controls (a per-artifact manifest,
  queryable by jurisdiction).

The catalog is **the** authoritative source. The
3D pipeline reads the catalog to get the
provenance of an asset. The marketplace reads the
catalog to get the royalty contract. The mobile
UX reads the catalog to get the asset's metadata.

## 6. Workflow

1. **Receive an `AuthorshipClaim`.** A user
   claims to have contributed to an artifact.
   The claim is signed (the user's key) and
   includes:
   - The artifact ID.
   - The user's ID.
   - The role (`author` / `contributor` /
     `reviewer` / `supplier` / `agent`).
   - The evidence (a reference to a git commit,
     a design document, a review, a test).
   - The timestamp.
   - The royalty share (a percentage or a
     formula).
   - **The verification level of the
     contribution** (`OEM_VERIFIED` /
     `REGULATORY_VERIFIED` / `LAB_VERIFIED` /
     `ENGINEER_REVIEWED` /
     `COMMUNITY_CORROBORATED` / `AI_INFERRED`
     / `UNKNOWN`).
2. **Validate the claim.** The skill checks:
   - The signature is valid.
   - The user is authorized (the user has access
     to the project the artifact is in).
   - The evidence exists and is accessible.
   - The claim does not conflict with a prior
     claim.
   - **The verification level is consistent
     with the artifact's
     `VehicleRepresentationLevel`.** A
     `VISUAL_ONLY` or `CONCEPTUAL` artifact
     cannot have an `OEM_VERIFIED`
     contribution; an `OEM_EXACT` artifact
     cannot have an `AI_INFERRED`
     contribution without an explicit human
     review counter-signature.
   - **The verification transition (if any)
     is signed.** An `AI_INFERRED → VERIFIED`
     transition requires a signed event
     from a human reviewer; a silent transition
     in the data is a violation.
3. **Record the claim.** The claim lands in the
   catalog. The authorship chain is updated.
   The verification level is part of the
   recorded claim.
4. **Receive a `RoyaltyContract`.** A user
   defines a contract: "every unit of this asset
   sold for $X pays user Y a 5% royalty". The
   contract is signed by the user + the project
   owner + any co-owners.
5. **Validate the contract.** The skill checks:
   - The signatures are valid.
   - The contract does not conflict with a prior
     contract.
   - The royalty sums to ≤ 100% (a sanity check).
   - The contract complies with the jurisdiction's
     royalty caps (e.g. some jurisdictions cap
     agent royalties at 30%).
   - **The artifact is eligible for royalty.**
     A `VISUAL_ONLY` or `CONCEPTUAL` artifact
     is rejected with a `ContractNotActive`
     error (the contract is malformed
     because the asset is not commerce-grade).
   - **The artifact's provenance is complete.**
     An artifact with `ProvenanceIncomplete`
     is rejected with a `ProvenanceIncomplete`
     error.
6. **Record the contract.** The contract lands in
   the catalog.
7. **Receive a `Sale`.** A marketplace event
   triggers a settlement. A sale of a
   `VISUAL_ONLY` or `CONCEPTUAL` artifact is
   rejected with a `ContractNotActive` error
   (the marketplace enforces this at listing
   time; the settlement is the backstop).
8. **Run the settlement.** The engine:
   - Walks the asset's royalty contracts.
   - Verifies the artifact's
     `VehicleRepresentationLevel` is eligible
     (rejects `VISUAL_ONLY` / `CONCEPTUAL`).
   - Verifies the artifact's provenance is
     complete.
   - Calculates the per-user amounts using
     `BigDecimal` (Kotlin / JVM) or
     `rust_decimal::Decimal` (Rust). A
     `Double` / `Float` / `f64` for money is
     a contract violation (per
     `.ai/STANDARDS.md` section 2.2).
   - Produces a signed settlement artifact.
9. **Pay the users.** The settlement is sent to
   the payment provider. The payment provider
   is out of scope (a third-party SaaS).
10. **Archive the audit trail.** The full
    settlement chain — the claim, the contract,
    the level-check, the provenance-check, the
    verification-level transitions, the
    settlement itself — lands in the audit log.
    The audit log is append-only.

## 7. Quality gates

- Every authorship claim is signed.
- Every royalty contract is signed.
- The royalty sums to ≤ 100% per asset.
- The settlement is signed + content-addressed.
- The audit trail is append-only.
- The export controls are enforced.
- The GDPR / CCPA / LGPD requests are answered
  within the regulatory deadline (30 days for
  GDPR).
- The catalog is queryable + reproducible.
- **Every settlement is bound to a
  `VehicleRepresentationLevel`.** A
  settlement against a `VISUAL_ONLY` or
  `CONCEPTUAL` artifact is rejected at the
  gate.
- **Every settlement is bound to a
  `ProvenanceComplete` flag.** A settlement
  against an artifact with `UNKNOWN` facts
  in commerce-relevant fields is rejected
  with a `ProvenanceIncomplete` error.
- **Every AI-inferred contribution that
  participated in a settlement has a
  corresponding human-review event in the
  audit trail.** A settlement that
  references an `AI_INFERRED` claim
  without a transition event is rejected
  with a `ProvenanceIncomplete` error.
- **Money is `BigDecimal` (or equivalent).**
  A settlement that uses `Double` / `Float`
  / `f64` is rejected at the gate.
- **The settlement envelope is a typed
  `FoundryError`-compatible JSON object**
  when the settlement fails (per `.ai/AGENTS.md`
  section 10 and `.ai/STANDARDS.md` section 7).
  A free-form string error is a violation.

## 8. Failure modes

- **A claim is invalid.** The skill rejects the
  claim. The user is informed.
- **A contract conflicts with a prior
  contract.** The skill rejects the contract.
  The user is informed. The user may file a
  council request (skill 05).
- **A settlement fails.** The settlement is
  retried; if it still fails, it goes to a
  manual review queue. A failed settlement is
  a P1 incident.
- **A user requests GDPR erasure.** The skill
  erases the user's PII from the catalog
  (preserving the audit trail, which is
  append-only by design).
- **An export control is violated.** The skill
  blocks the access. A violation is a P0
  incident.

## 9. Coordination contract

- **Input from**: every other skill (the
  authorship claims, the royalty contracts, the
  sales events, the GDPR requests).
- **Output to**: every other skill (the catalog
  queries, the audit trail, the settlement
  results).
- **Triggered by**: every authorship claim, every
  contract change, every sale, every regulatory
  request.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **Untracked contributions.** A contribution
  without an `AuthorshipClaim` is a contract
  violation. The next skill that consumes the
  asset will fail.
- **Untracked sales.** A sale without a settlement
  is unpaid royalty; the user is uncompensated.
  This is a legal issue, not just a technical
  one.
- **Mutable audit trail.** The audit trail is
  append-only. A row that is updated is a
  violation. A row that is deleted is a
  violation.
- **PII in the audit trail.** The audit trail
  has user IDs, not user PII. A row that
  contains a user email is a violation.
- **Off-ledger settlement.** A settlement that
  is not on the audit trail is unpaid royalty.
- **"We'll add the claim later".** A
  contribution that ships without a claim is
  untracked. The next skill that consumes the
  asset cannot verify provenance.
- **`AI_INFERRED` masquerading as `VERIFIED`
  in a settlement.** A settlement that
  references an `AI_INFERRED` claim without
  a human-review transition event in the
  audit trail is a contract violation.
- **Royalty on a `VISUAL_ONLY` or
  `CONCEPTUAL` artifact.** A settlement
  against an ineligible level is rejected
  with a `ContractNotActive` error.
- **Float / Double for money.** A `Double`
  / `Float` / `f64` for a royalty amount
  is a contract violation. Money is
  `BigDecimal` (or equivalent) — see
  `.ai/STANDARDS.md` section 2.2.
- **Free-form string errors.** A settlement
  failure that returns a string instead of
  a typed `FoundryError` is a contract
  violation. The error envelope is typed
  JSON (per `.ai/AGENTS.md` section 10).

## 11. The catalog in the Elysium Automotive
Foundry

The catalog is the platform's "what exists, who
built it, who gets paid" answer. Every artifact
the platform produces is in the catalog. Every
contribution is in the catalog. Every contract is
in the catalog. Every sale is in the catalog. Every
settlement is in the catalog.

The catalog is the platform's "we know what
happened, and we can prove it" answer.

The catalog is **content-addressed** + **signed**
+ **append-only** + **queryable**. The catalog is
the single source of truth for the platform's
provenance graph.

## 12. Working with this skill

When invoked, this skill:

1. Receives the claim / contract / sale /
   request.
2. Validates it.
3. Records it in the catalog.
4. Updates the audit trail.
5. Returns the catalog query result to the
   orchestrator (or to the calling skill
   directly).

The skill does not render the catalog (skill 11
+ skill 10). The skill does not negotiate with
the user (skill 02). The skill does not enforce
the export controls in the UI (skill 11). The
skill is the **back office** of the platform.
