---
name: ip-provenance-royalties
description: Implements contribution records, intellectual-property provenance, contracts, licensing and deterministic royalty calculations.
---

# Skill 09 — IP, Provenance and Royalties

## 1. Mission

Create **enforceable technical records**
supporting ownership and revenue
distribution **without pretending that
software alone creates legal rights**.

The application **records evidence** and
**executes configured agreements**. It
does **not** replace qualified legal
counsel. A royalty settlement is a
technical event; a royalty settlement
is also a legal event. The platform
records the former; the legal counsel
drafts the latter. The platform never
presumes to create legal rights by code.

## 2. Critical correction (configurable participation)

**Do not** hardcode:

> "Elysium always owns 5% of every
> vehicle created."

That model would be **commercially
hostile and legally weak**. The
platform implements **configurable
participation models**; a 5% royalty
(or any other percentage) applies
**only when an active signed agreement
explicitly defines it**.

The platform's participation models
are:

- **`TOOL_ONLY`.** The platform
  is a tool. Elysium has **no
  ownership** in the
  contribution + **no royalty**
  on the commercial release. A
  team that uses the platform
  without a marketplace
  agreement pays nothing.
- **`MARKETPLACE_LICENSE`.** A
  per-license fee (a flat fee
  per active project + per
  commercial release). The fee
  is in the agreement; the
  platform executes it.
- **`ELYSIUM_VALIDATED`.** A
  royalty on the validated
  components only (the parts
  the Elysium lab verified
  + the engineering fact
  templates the platform
  authored). The royalty is
  per the agreement; the
  components are
  Elysium-authored.
- **`ELYSIUM_INCUBATED`.** A
  royalty on the full
  commercial release. The
  release is incubated; the
  team is supported; the
  royalty is per the
  agreement.
- **`JOINT_VENTURE`.** A
  revenue share + a
  decision-making share. The
  terms are in the agreement.
- **`CUSTOM_AGREEMENT`.** A
  bespoke agreement. The
  terms are in the agreement;
  the platform executes them
  as configured.

A 5% royalty applies only when an
`ACTIVE` signed agreement (per
section 4) explicitly defines it.
A platform that hardcodes a 5%
royalty is a contract violation;
the verifier (skill 14) rejects
the platform.

## 3. Rights model

The platform models **14 rights
fields** per contract. A contract
without all 14 is a contract
violation.

- **Party.** A legal entity
  (a person, an organization)
  bound by the contract.
- **Contributor.** A person
  (or an AI agent) who
  contributed to the work
  product.
- **Organization.** A legal
  entity the contributor is
  associated with.
- **Work product.** The
  artifact the contract
  governs (a `VehicleDefinition`,
  a `PartDefinition`, a
  `Procedure`).
- **Background IP.** The IP
  the party brought into the
  project (a patent, a
  trademark, a trade secret).
- **Foreground IP.** The IP
  the work product generates.
- **Third-party IP.** The IP
  the work product uses that
  belongs to a third party
  (a license, a royalty).
- **License.** The license
  the work product is
  published under (MIT,
  Apache-2.0, proprietary,
  etc.).
- **Territory.** The
  geographic scope (the EU,
  the US, the BR, the CN,
  worldwide).
- **Field of use.** The
  business scope (the
  passenger vehicles, the
  commercial vehicles, the
  racing).
- **Exclusivity.** Whether
  the license is exclusive
  (only the licensee) or
  non-exclusive (the licensor
  may license others).
- **Sublicensing rights.**
  Whether the licensee may
  sublicense the work product.
- **Royalty rule.** The
  per-contract royalty
  formula (per section 7).
- **Equity interest.** The
  per-party equity share (for
  a joint venture).
- **Effective period.** The
  start + the end dates of
  the contract.
- **Contract version.** The
  signed version (per section
  4).

A contract without all 14 fields
is a contract violation; the
verifier rejects the contract.

## 4. Contract immutability

A signed contract version is
**immutable**. An amendment
creates a **new version** with:

- **Effective date.** When
  the new version takes
  effect.
- **Parties.** The legal
  entities bound by the new
  version.
- **Superseded clauses.** The
  clauses of the previous
  version that the new
  version supersedes.
- **New signatures.** The
  per-party signatures on the
  new version.
- **Migration policy.** The
  per-`RevenueEvent` rule
  for events between the old
  version's effective date
  and the new version's
  effective date (e.g.
  "events before 2029-04-01
  are governed by v1;
  events after 2029-04-01
  are governed by v2").

**Historical sales MUST be
calculated under the contract
version effective at the event
time.** A settlement against the
wrong version is a `RoyaltyCalculationRejected`
error.

A contract version that is mutated
after signing is a contract
violation; the audit trail
records the mutation attempt +
escalates to the security skill
(skill 12) as a P0 incident.

## 5. Contribution record

Every **significant contribution**
records **12 fields**. A contribution
without all 12 is a contract
violation.

- **Actor.** The user (or the
  AI agent) who authored the
  contribution.
- **Project.** The `ProjectId`
  the contribution belongs to.
- **Artifact.** The
  `ArtifactId` the
  contribution affects.
- **Base revision.** The
  `RevisionId` the
  contribution is based on.
- **Contribution type.** A
  typed enum (`AUTHOR`,
  `CONTRIBUTOR`, `REVIEWER`,
  `SUPPLIER`, `AGENT`).
- **Human-authored content.**
  The bytes the human
  authored (the human-
  authored prose, the
  human-authored code, the
  human-authored diagram).
- **AI assistance.** The
  AI tool used + the
  version + the prompt +
  the temperature (per skill
  05 section 5 — the
  evidence policy).
- **Dependencies.** The
  dependencies the
  contribution uses (the
  libraries, the standards,
  the other contributions).
- **Timestamp.** The
  ISO-8601 timestamp the
  contribution was recorded.
- **Hash.** The content hash
  of the contribution.
- **Acceptance.** The
  acceptance state
  (`PROPOSED`, `ACCEPTED`,
  `REJECTED`).
- **Ownership claim.** The
  per-party ownership claim
  (the percentage, the role).
- **Review status.** The
  review state (`PENDING`,
  `REVIEWED`,
  `COUNTER_SIGNED`).

**Do not** infer ownership
percentages automatically from
commit count or token count. A
"5 commits = 5% ownership" rule
is a contract violation; the
ownership is in the signed
agreement.

## 6. Royalty basis

The platform supports
**configurable royalty definitions**.
A definition has:

- **`Royalty Due = Royalty-Bearing
  Net Sales × Contractual
  Royalty Rate`**

A more complex definition (a
tiered rate, a minimum guarantee,
a territory-specific rate) is
configured per contract.

**Permitted deductions MUST be
explicitly modeled.** A deduction
exists only when the contract
explicitly allows it. A deduction
field that exists in the schema
but is not in the contract is a
no-op. A deduction silently
applied is a contract violation.

### 6.1 Revenue event

A revenue event is the input to
the calculation engine. The
shape is:

```json
{
  "eventType": "VEHICLE_SALE",
  "eventId": "SALE-001",
  "projectId": "PROJECT-001",
  "commercialReleaseId": "RELEASE-2029-01",
  "occurredAt": "2029-04-02T14:10:00Z",
  "territory": "CR",
  "currency": "USD",
  "grossAmount": "20000.00",
  "claimedDeductions": [
    {
      "type": "INDIRECT_TAX",
      "amount": "1800.00"
    }
  ]
}
```

A revenue event without all
required fields is rejected. A
deduction that is not in the
contract is rejected with a typed
`RoyaltyCalculationRejected` error.

## 7. Calculation engine

The royalty calculation engine is
the **authoritative** implementation
of the contract's royalty rule.
The engine MUST have **10
properties**:

- **Deterministic.** The same
  inputs produce the same
  outputs, byte-for-byte.
- **Versioned.** The engine's
  version is recorded in the
  audit trail; a calculation
  against a newer engine
  version is flagged.
- **Auditable.** Every step
  of the calculation is
  recorded in the audit trail
  (the input + the contract
  version + the rule applied
  + the output + the engine
  version).
- **Decimal-safe.** The engine
  uses `BigDecimal`
  exclusively. A `Double` /
  `Float` / `f64` is a
  contract violation.
- **Idempotent.** The same
  `eventId` + the same engine
  version produces the same
  settlement. A duplicate
  event is a no-op.
- **Reproducible.** A
  calculation can be replayed
  from the audit trail.
- **Capable of reversal and
  adjustment events.** A
  reversal is a typed
  `Reversal` event; an
  adjustment is a typed
  `Adjustment` event; the
  engine processes them as
  such.
- **Capable of multiple
  beneficiaries.** A single
  revenue event can produce
  N settlements (per
  contributor).
- **Capable of tiered rates.**
  A contract may define N
  tiers (e.g. "0–1M units at
  5%, 1–5M units at 3%,
  5M+ at 1%").
- **Capable of minimum
  guarantees.** A contract
  may define a minimum
  guarantee (e.g. "the
  licensor receives ≥ 100k
  per year, regardless of
  sales").
- **Capable of territory-
  specific rules.** A
  contract may define per-
  territory rates.
- **Capable of related-party-
  sale adjustments.** When
  contractually defined, a
  sale to a related party is
  adjusted to fair market
  value.

**AI may explain a result. AI
MUST NOT perform the authoritative
calculation.** The model produces
a draft; the deterministic engine
+ a human review apply. Per
`.ai/AGENTS.md` section 8 +
`.ai/STANDARDS.md` section 5.

A calculation that does not have
all 10 properties is a contract
violation; the verifier rejects
the engine.

## 8. Ledger

The platform uses **double-entry
accounting concepts** for
financial state. Every entry has
a debit + a credit + a balance.

**Do not** mutate an old payment
or royalty record. A signed
settlement is immutable.

The platform creates:

- **`Adjustment`.** A correction
  to a previous settlement
  (e.g. a sales-volume
  recalculation).
- **`Reversal`.** A full
  reversal of a previous
  settlement (e.g. a returned
  sale).
- **`Correction`.** A typo /
  data fix on a previous
  settlement.
- **`Settlement`.** A new
  settlement.

**Maintain full history.** The
audit trail is append-only. A
mutated settlement is a contract
violation; the security skill
(skill 12) escalates the incident
as P0.

## 9. Anti-circumvention

The platform models **contract
clauses for**:

- **Affiliates.** A sale to an
  affiliate is governed per
  the clause.
- **Successor models.** A
  successor model (a
  redesigned successor to
  the work product) is
  governed per the clause.
- **Sublicenses.** A
  sublicense is governed per
  the clause.
- **Bundled products.** A
  bundle (the work product +
  another product) is
  governed per the clause.
- **Related-party transfers.**
  A transfer to a related
  party is governed per the
  clause.
- **Mergers.** A merger
  involving a party is
  governed per the clause.
- **Assignments.** An
  assignment of the contract
  is governed per the clause.
- **Territory changes.** A
  change of territory (a
  re-publication from the EU
  to the US) is governed per
  the clause.
- **Product renaming.** A
  rename of the work product
  is governed per the clause
  (the rename does NOT
  reset the royalty
  obligation).

**The application evaluates only
clauses explicitly present in the
signed contract.** A clause
invented by the application (a
"we'll also apply anti-
circumvention" rule without a
contract clause) is a contract
violation; the security skill
(skill 12) escalates as P0.

## 10. Definition of done

The IP/provenance/royalty engine
is accepted only when **every**
test below proves.

- **A contract is signed**
  with all 14 rights fields
  (per section 3). A
  contract without a field
  is rejected.
- **A signed contract is
  immutable.** A test asserts
  the audit trail rejects a
  modification attempt on
  a signed contract version.
- **An amendment creates a
  new version.** A test
  asserts the amendment
  produces a new version +
  the supersession +
  the migration policy.
- **Historical sales are
  calculated under the
  version effective at the
  event time.** A test asserts
  a sale that occurred
  between v1 and v2 is
  calculated under v1 (per
  the migration policy).
- **A deduction not in the
  contract is rejected.** A
  test asserts the engine
  rejects a deduction that
  is not in the contract.
- **The engine is
  deterministic.** A test
  asserts the same inputs
  produce the same outputs
  byte-for-byte.
- **The engine uses
  `BigDecimal` exclusively.**
  A test asserts a `Double`
  in the calculation path is
  a CI failure.
- **The engine is reversible.**
  A test asserts a
  `Reversal` event produces
  a settlement that
  nullifies the original
  without mutating the
  original.
- **The engine supports
  tiered rates + minimum
  guarantees + territory-
  specific rules.** A test
  asserts each property with
  a fixture.
- **AI does not perform the
  authoritative calculation.**
  A test asserts the model
  cannot write to the
  settlement table; the
  deterministic engine +
  the human review are the
  only path.
- **The audit trail is
  append-only.** A test
  asserts no UPDATE or
  DELETE in the standard
  API.
- **Anti-circumvention
  clauses are evaluated
  only when present in the
  contract.** A test asserts
  the engine rejects an
  anti-circumvention
  evaluation when the
  clause is not in the
  contract.

A failing test is a P0 incident;
the orchestrator blocks the
release.

### 10.1. Top-level guarantees (8 proofs)

The IP/provenance/royalty engine
MUST also satisfy the following
**8 top-level guarantees**. A
guarantee that fails is a P0
incident.

- **No contract means no
  royalty.** A test asserts
  no settlement is generated
  when there is no signed
  agreement.
- **An inactive contract
  cannot generate charges.**
  A test asserts a
  `RoyaltyContract` whose
  status is not `ACTIVE`
  produces no settlement.
- **Historical events use
  historical contract
  versions.** A test asserts
  a sale that occurred
  between v1 and v2 is
  calculated under v1 (per
  the migration policy).
- **Duplicate sale events do
  not duplicate royalties.** A
  test asserts the same
  `eventId` produces the same
  settlement; the state is
  unchanged.
- **Reversals produce
  balanced ledger entries.**
  A test asserts a `Reversal`
  event produces a
  `Settlement` that nullifies
  the original without
  mutating it; the ledger
  remains balanced.
- **Unauthorized users cannot
  alter agreements.** A
  test asserts a user without
  the role receives a typed
  `UnauthorizedProjectAccess`
  error.
- **Floating-point arithmetic
  is absent.** A test asserts
  a `Double` / `Float` / `f64`
  in the calculation path is
  a CI failure.
- **Every statement can be
  reproduced from immutable
  inputs.** A test asserts a
  statement can be replayed
  from the audit trail (the
  revenue events + the
  contract versions + the
  engine version).

## 11. Quality gates

- Every contract has all 14
  rights fields.
- Every contract version is
  signed + immutable.
- Every amendment creates a
  new version + a migration
  policy.
- Every contribution has all
  12 record fields.
- Every revenue event has
  all required fields.
- Every settlement uses
  `BigDecimal`.
- Every settlement has a
  per-step audit trail.
- Every reversal is a
  separate event; the
  original is unchanged.
- AI is excluded from the
  authoritative calculation
  path.

## 12. Failure modes

- **A contract is missing a
  field.** The contract is
  rejected at the validation
  step.
- **A deduction is not in the
  contract.** The deduction
  is rejected with a typed
  `RoyaltyCalculationRejected`
  error.
- **A calculation uses
  `Double`.** The
  calculation is rejected;
  the field is flagged.
- **A signed contract is
  mutated.** The audit
  trail records the
  mutation attempt; the
  security skill (skill 12)
  escalates as P0.
- **AI tries to write to
  the settlement table.** The
  write is rejected; the
  AI-authority gate trips;
  the orchestrator blocks
  the release.

## 13. Coordination contract

- **Input from**: skill 04
  (the spec), the user
  (the contributor), the
  marketplace (skill 10) (the
  sales events).
- **Output to**: the user (the
  statement), the marketplace
  (skill 10), the audit trail
  (per skill 09 itself), the
  security skill (skill 12) on
  anomalies.
- **Triggered by**: every
  contract change, every
  contribution, every
  revenue event, every
  settlement.
- **Frequency**: continuous.

## 14. Forbidden patterns

- **A hardcoded royalty.** A
  5% royalty that is not in
  the contract is a contract
  violation.
- **Inferred ownership.** A
  "5 commits = 5% ownership"
  rule is a contract
  violation. The ownership
  is in the contract.
- **A deduction that is not
  in the contract.** A
  deduction silently applied
  is a contract violation.
- **A mutable signed
  contract.** A contract
  version that is mutated is
  a contract violation.
- **AI performing the
  authoritative
  calculation.** A model
  that writes to the
  settlement table is a
  contract violation.
- **`Double` for money.** A
  `Double` in the calculation
  path is a contract
  violation.
- **A `Float` / `f64` for
  money.** Same.
- **An anti-circumvention
  clause invented by the
  application.** A clause
  not in the contract is a
  contract violation.
- **A payment or royalty
  record that is mutated.**
  A `Reversal` / `Adjustment`
  / `Correction` /
  `Settlement` is a separate
  event; the original is
  unchanged.

## 15. Working with this skill

When invoked, this skill:

1. Receives the contract
   change / the contribution /
   the revenue event.
2. Validates the input (per
   the schema).
3. Applies the rule engine
   (per section 7).
4. Computes the settlement
   (per the contract version
   effective at the event
   time).
5. Files the audit trail.
6. Returns the statement.

The skill does **not** draft
contracts. The skill does **not**
provide legal advice. The skill
**records evidence** + **executes
agreements**.

## 16. Cross-references

- **Required error model:**
  `.ai/AGENTS.md` section 10 +
  `.ai/STANDARDS.md` section 7.
- **AI authority boundary:**
  `.ai/AGENTS.md` section 8 +
  `.ai/STANDARDS.md` section 5.
- **Money type (BigDecimal):**
  `.ai/STANDARDS.md` section 2.2.
- **Truth and confidence
  model:** `.ai/STANDARDS.md`
  section 3.
- **Vehicle representation
  levels:** `.ai/STANDARDS.md`
  section 4.
- **Artifact contract:**
  `.ai/AGENTS.md` section 12.
- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Ontology (skill 03):**
  `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.
- **DSL compiler (skill 04):**
  `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`.
- **Backend event platform
  (skill 08):**
  `.ai/skills/08-backend-event-platform/SKILL.md`.
- **Marketplace (skill 10):**
  `.ai/skills/10-marketplace-manufacturing/SKILL.md`.
- **Security (skill 12):**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **Regulatory (skill 13):**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
