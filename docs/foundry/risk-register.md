---
title: Risk Register — Elysium Automotive Foundry
status: Phase 0 deliverable, signed 2026-07-17
owner: skill 00 (program-orchestrator)
audited_by: skill 01 (repository-archaeology)
git_head: c9028dc
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Risk Register — Elysium Automotive Foundry

> **Status:** Phase 0 deliverable. The
> **single source of truth for program-
> level risks** in the Foundry platform.
> Every identified risk has an owner, a
> likelihood, an impact, and a mitigation.
> The register is the input to the
> verification gate (skill 14) + the
> devops gate (skill 15) + the on-call
> rotation (skill 15).

---

## 0. How to read this document

The document is split into six parts:

1. **The risk classification** (section
   1) — the six categories of risk
   (R-DI, R-CI, R-CH, R-CO, R-T, R-AI).
2. **The risk inventory** (sections
   2–7) — every identified risk in each
   category.
3. **The per-increment risk log**
   (section 8) — the per-increment risks
   filed by the orchestrator.
4. **The risk treatment rules** (section
   9) — how a risk is closed.
5. **The risk review schedule** (section
   10) — when the register is reviewed.
6. **The risk ADR log** (section 11) —
   every risk closed via an ADR + a
   deviation.

### The risk ID format

A risk ID is `R-<CATEGORY>-<N>` where
`<CATEGORY>` is one of:

- `DI` — Data integrity
- `CI` — Commercial integrity
- `CH` — Code hygiene
- `CO` — Concurrency
- `T` — Trust
- `AI` — AI authority

`<N>` is a 1-based index within the
category. A risk is referenced as
`R-DI-1` or `R-CI-1` etc.

### The likelihood scale

- **Low** — unlikely to occur in the
  next 12 months.
- **Medium** — likely to occur in the
  next 12 months.
- **High** — likely to occur in the
  next 30 days.

### The impact scale

- **Low** — the failure is recoverable
  with a single hotfix; no data loss;
  no user impact.
- **Medium** — the failure is recoverable
  with a coordinated effort; minor data
  loss; minor user impact.
- **High** — the failure is recoverable
  with a major effort; significant data
  loss; significant user impact.
- **Critical** — the failure is not
  recoverable without a rollback; data
  is corrupted; users are at risk (safety
  / financial / legal).

### The status

- **Open** — the risk is identified;
  the mitigation is pending.
- **Mitigating** — the mitigation is in
  progress.
- **Closed** — the mitigation is
  complete; the risk is no longer
  relevant.
- **Accepted** — the risk is accepted
  by the orchestrator; the ADR is filed.

---

## 1. The risk classification

The six risk categories are the
**archetypes** of failure in a Foundry-
class platform. A risk that does not
fit a category is a smell; the
orchestrator arbitrates.

| Category | What it covers | Why it matters |
|---|---|---|
| **R-DI** — Data integrity | The platform's facts (engineering, regulatory, financial, audit) are not corrupted, not faked, not back-dated | The platform is a source of truth for engineering decisions + commercial settlements + regulatory submissions |
| **R-CI** — Commercial integrity | The platform's commercial state (contracts, sales, royalties, settlements, listings) is not mutated, not bypassed, not front-run | A commercial state mutation is a legal liability + a trust loss |
| **R-CH** — Code hygiene | The platform's code (Kotlin, Rust, TypeScript) is readable, testable, type-safe, and free of anti-patterns | A code-hygiene failure is a vector for data integrity + commercial integrity failures |
| **R-CO** — Concurrency | The platform's concurrent operations (DB transactions, outbox publishes, asset downloads, AI council deliberations) are safe + idempotent | A concurrency failure is a vector for data integrity failures |
| **R-T** — Trust | The platform's inputs (3D assets, OBD telemetry, user uploads, supplier data) are validated, signed, and safe to consume | A trust failure is a vector for security + safety + data integrity failures |
| **R-AI** — AI authority | The platform's AI (the multi-agent council + the local LLM + the OCR / image-labeling models) does not directly mutate authoritative state | An AI authority failure is the highest-impact failure mode in the platform |

---

## 2. R-DI — Data integrity risks

### R-DI-1 — AI-inferred data masquerades as verified

**Description:** an `AI_INFERRED`
fact becomes `OEM_VERIFIED` /
`REGULATORY_VERIFIED` / `LAB_VERIFIED` /
`ENGINEER_REVIEWED` /
`COMMUNITY_CORROBORATED` without a
signed transition event in the audit
trail.

**Why it matters:** a verified
engineering fact is the basis for
safety + commercial + regulatory
decisions. A fact that is silently
upgraded from `AI_INFERRED` to
`OEM_VERIFIED` is a fabricated
engineering claim.

**Mitigation:** per `.ai/STANDARDS.md`
section 3.2 + ADR-0003, the
transition is a human review + a
signed counter-signature. The CI
asserts the audit trail's invariant:
every `VerificationStatus`
transition has a signed event.

**Owner:** skill 14 (verifier) +
skill 09 (audit trail).
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating (the
mitigation is in the audit trail +
the CI; the mitigation is verified
when the first transition event is
exercised in Phase 4).
**ADR:** ADR-0003.

### R-DI-2 — Visual-mesh compatibility declared as mechanical compatibility

**Description:** a mesh that
visually matches a part is treated
as mechanically compatible. The
user sees the meshes overlap +
the system asserts "these are
compatible".

**Why it matters:** mechanical
compatibility is a constraint on
the `ConstraintGraph` (per skill
04 section 7). A visual match is
NOT a mechanical match. A
declared compatibility that is
not grounded in the constraint
graph is an engineering lie.

**Mitigation:** per `.ai/STANDARDS.md`
section 2.1, the compatibility
check is the fault model (skill
07) + the engineering review
(skill 03), not the 3D viewer
(skill 06). The `Compatibility`
record in the `ConstraintGraph`
is the only source of truth.

**Owner:** skill 06 (3D) + skill
07 (twin) + skill 03 (ontology).
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending (to be filed in
Phase 3).

### R-DI-3 — Float / Double for money

**Description:** a royalty
calculation uses `Double` /
`Float` / `f64`.

**Why it matters:** `Double` /
`Float` are not exact. A
settlement of `0.1 + 0.2` is
`0.30000000000000004` in IEEE
754. A settlement that drifts
by a fraction of a cent is a
commercial integrity failure.

**Mitigation:** per `.ai/STANDARDS.md`
section 2.2 + ADR-0001, money
is `BigDecimal` (JVM) /
`decimal.Decimal` (Python) /
`rust_decimal::Decimal` (Rust).
The CI enforces this: a positive
match of `BigDecimal` → `Double`
in the call graph is a hard
build failure.

**Owner:** skill 14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0001.

### R-DI-4 — Mutable historical commercial release

**Description:** a signed release
is updated or deleted.

**Why it matters:** a signed
release is a contract (per skill
09). A mutation is a breach of
contract.

**Mitigation:** per `.ai/STANDARDS.md`
section 2.2 + ADR-0006, the
audit trail is append-only. A
rollback is a new release, not
an edit. The verifier (skill 14)
tests the append-only invariant:
an attempt to mutate a signed
release raises a typed
`RevisionConflict` error.

**Owner:** skill 09 + skill 14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0006.

### R-DI-5 — Provenance data loss

**Description:** a `ProvenanceRecord`
is lost (DB corruption, file system
failure, backup failure).

**Why it matters:** a provenance
record is the only evidence of
the engineering fact's source.
A loss is a trust loss.

**Mitigation:** per skill 15
section 8, the backup procedure
is tested (the test re-runs the
restore on a fixture and asserts
the records are present). The
backup retention is 7 years (per
the regulatory retention). The
backup is encrypted at rest.

**Owner:** skill 15 + skill 09.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending (to be filed in
Phase 7).

### R-DI-6 — `VehicleRepresentationLevel` downgrade

**Description:** a vehicle's level
is downgraded from `OEM_EXACT`
to `PARAMETRIC_FUNCTIONAL` (or
worse) without a signed
transition.

**Why it matters:** the level is
the basis for the safety gate
+ the marketplace eligibility.
A silent downgrade is a
regulatory + commercial
violation.

**Mitigation:** per `.ai/STANDARDS.md`
section 2.1 + ADR-0002, the
transitions are append-only +
signed. The verifier (skill 14)
asserts the append-only invariant.

**Owner:** skill 03 + skill 14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0002.

### R-DI-7 — EngineeringArtifact content-address collision

**Description:** two distinct
artifacts have the same SHA-256
content hash (a hash collision).

**Why it matters:** a content-
addressed store is keyed on
the hash. A collision is a
data loss.

**Mitigation:** per `.ai/STANDARDS.md`
section 2.5, the content hash is
SHA-256 (128-bit collision
resistance). A collision is a
2^128 brute force — not
realistic. The risk is logged
for completeness; the mitigation
is the choice of hash function.

**Owner:** skill 03 + skill 08.
**Likelihood:** Negligible.
**Impact:** Critical.
**Status:** Accepted.
**ADR:** pending.

### R-DI-8 — Telemetry data corruption

**Description:** a `TelemetryStream`
record is corrupted (a byte
flipped, a timestamp drifted).

**Why it matters:** the
diagnostic engine (skill 07)
consumes the telemetry. A
corrupted record is a misdiagnosis.

**Mitigation:** per skill 07
section 9, the telemetry is
signed + timestamped (HLC) +
checksummed. A corrupted record
is rejected at the parse step.

**Owner:** skill 07 + skill 08.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-DI-9 — Supplier data false-claim

**Description:** a supplier
falsifies a qualification (e.g.
claims ISO 9001 without being
certified).

**Why it matters:** a
qualification is the basis for
the supplier's eligibility to
supply a part. A false claim
is a regulatory + safety
violation.

**Mitigation:** per skill 13
section 4 + skill 10 section
7, a qualification is verified
by a regulator (skill 13) +
a buyer (skill 10) + an
engineer (skill 03). The
verification is a signed event
in the audit trail.

**Owner:** skill 10 + skill 13.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

### R-DI-10 — OBD / UDS protocol misinterpretation

**Description:** the diagnostic
engine misinterprets an OBD /
UDS response (e.g. a positive
response is treated as a
negative response).

**Why it matters:** a misread
DTC is a misdiagnosis. A
misdiagnosis is a safety
hazard.

**Mitigation:** per skill 07
section 7, the protocol parser
is the only component that may
parse the bytes; the parser
is total + tested against a
golden corpus. The parser is
verified by skill 14.

**Owner:** skill 07 + skill 14.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

---

## 3. R-CI — Commercial integrity risks

### R-CI-1 — Royalty without an active contract

**Description:** a royalty
calculation against a
`RoyaltyContract` whose status
is not `ACTIVE`.

**Why it matters:** a royalty
is a contractual obligation.
A royalty without a contract
is a fraud.

**Mitigation:** per
`.ai/STANDARDS.md` section 2.2
+ skill 09 section 6 + ADR-0004,
the engine rejects with a
`ContractNotActive` error. The
verifier (skill 14) tests the
invariant.

**Owner:** skill 09.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0004.

### R-CI-2 — Royalty on a `VISUAL_ONLY` or `CONCEPTUAL` artifact

**Description:** a settlement
against an ineligible
`VehicleRepresentationLevel`.

**Why it matters:** a
`VISUAL_ONLY` / `CONCEPTUAL`
artifact is not engineering
data; it is a visual
representation. A settlement
on it is a commercial lie.

**Mitigation:** per skill 09
+ skill 10 + ADR-0011, the
settlement is rejected with a
`VehicleRepresentationLevelIneligible`
error. The verifier (skill 14)
tests the invariant.

**Owner:** skill 09 + skill 10.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0011.

### R-CI-3 — Hardcoded Elysium 5% royalty

**Description:** the Elysium
5% royalty is hardcoded as a
constant.

**Why it matters:** the
master prompt explicitly
forbids this. A hardcoded
constant is a contract
violation: the 5% must be a
configurable `RoyaltyRule`
that applies only to projects
that have accepted an
`ELV-INCUBATED` license.

**Mitigation:** per ADR-0005,
the royalty is a configurable
`RoyaltyRule`. The CI asserts
the absence of the constant
in the call graph.

**Owner:** skill 09 + skill 14.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0005.

### R-CI-4 — Escrow bypass

**Description:** a marketplace
transaction completes without
an `Escrow`.

**Why it matters:** an escrow
is the buyer's protection. A
bypass is a fraud.

**Mitigation:** per skill 10
section 5, every `Order` MUST
have an `Escrow` before the
transaction is `PAID`. The
verifier (skill 14) tests the
invariant.

**Owner:** skill 10 + skill 14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

### R-CI-5 — Settlement double-pay

**Description:** a settlement
is paid twice (a duplicate
event).

**Why it matters:** a double
payment is a financial loss.

**Mitigation:** per skill 09
section 8, the settlement is
idempotent (the `SettlementId`
is the idempotency key). The
outbox (skill 08) is the only
path to a payment; the outbox
is reliable.

**Owner:** skill 09 + skill 08.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0007.

### R-CI-6 — License mismatch

**Description:** a buyer
purchases a `Listing` under a
license that is incompatible
with the project's `License`.

**Why it matters:** a license
mismatch is a contract
violation.

**Mitigation:** per skill 09
section 5, the marketplace
validates the license
compatibility before the
transaction is `PAID`. The
verifier (skill 14) tests the
invariant.

**Owner:** skill 10 + skill 09.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CI-7 — RFQ response forgery

**Description:** a supplier
forges an RFQ response (e.g.
changes the price after the
buyer has accepted the
original).

**Why it matters:** a forged
response is a fraud.

**Mitigation:** per skill 10
section 4, every RFQ response
is signed + content-addressed.
A forged response is rejected
at the parse step.

**Owner:** skill 10.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

---

## 4. R-CH — Code hygiene risks

### R-CH-1 — Generic catch blocks hiding failures

**Description:** a
`catch (e: Exception) { /* ignore */ }`
or a `try { ... } catch (_) { }`.

**Why it matters:** a hidden
failure is a data integrity
failure waiting to happen.

**Mitigation:** per
`.ai/STANDARDS.md` section 2.3,
every catch block re-throws,
logs with a typed error, or
returns a typed `Result` /
`Either`. The CI enforces this.

**Owner:** skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending (to be filed
in Phase 1).

### R-CH-2 — Unchecked null assertions

**Description:** a `!!` in
Kotlin, an unchecked `as` in
TypeScript, an `unwrap()` in
production Rust.

**Why it matters:** an
unchecked null assertion is a
crash waiting to happen.

**Mitigation:** per
`.ai/STANDARDS.md` section 2.3,
the error path is typed. The
CI enforces this: a positive
match of `!!` in production
code is a build warning; a
positive match in a critical
path is a build failure.

**Owner:** skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CH-3 — `Map<String, Any>` as a domain type

**Description:** a `Map<String,
Any>` field on a domain entity
instead of a typed data class.

**Why it matters:** a
`Map<String, Any>` is a
contract violation (per
`.ai/AGENTS.md` section 24.1).
A typed data class is the
contract.

**Mitigation:** per skill 03
section 14, the ID is a type,
not a primitive. The CI
enforces this: a positive
match of `Map<String, Any>`
in a domain entity is a
build failure.

**Owner:** skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CH-4 — Float in a money calculation

**Description:** see R-DI-3
(the same risk, from a code-
hygiene angle).

**Mitigation:** see R-DI-3.

**Owner:** skill 14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0001.

### R-CH-5 — Untyped exception in production code

**Description:** a `throw Exception("...")`
in production code.

**Why it matters:** an untyped
exception leaks internal
context to the user.

**Mitigation:** per
`.ai/STANDARDS.md` section 2.3,
the error is typed. The CI
enforces this: a positive
match of `throw Exception`
in production code is a build
failure.

**Owner:** skill 14.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CH-6 — Untyped `null` return

**Description:** a function
that returns `null` on error
instead of a typed
`Result.failure(FooError)`.

**Why it matters:** a `null`
return is a contract violation
(per `.ai/AGENTS.md` section
24.1).

**Mitigation:** per
`.ai/AGENTS.md` section 24.1,
the value is typed. The CI
enforces this.

**Owner:** skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CH-7 — Free-form error string

**Description:** a
`"Vehicle definition is invalid"`
return value instead of a
typed `VehicleDefinitionInvalid`
error.

**Why it matters:** a free-
form string is a contract
violation (per `.ai/AGENTS.md`
section 24.1).

**Mitigation:** per
`.ai/AGENTS.md` section 24.1
+ `.ai/STANDARDS.md` section
7, the value is typed. The
CI enforces this.

**Owner:** skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0008.

### R-CH-8 — No idempotency key on a write API

**Description:** a write API
that does not have an
idempotency key.

**Why it matters:** a write
API without an idempotency
key is a double-write waiting
to happen.

**Mitigation:** per
`.ai/AGENTS.md` section 9
(delivery rules), every write
API has an idempotency key.
The verifier (skill 14) tests
the invariant.

**Owner:** skill 14 + skill 08.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0007.

---

## 5. R-CO — Concurrency risks

### R-CO-1 — Main-thread blocking on Android

**Description:** a model load,
a decode, or a network call
on the main thread.

**Why it matters:** a main-
thread block is a UI freeze
+ an ANR.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.4, every heavy operation
is on `Dispatchers.IO`. The
`StrictMode` test asserts no
main-thread blocking.

**Owner:** skill 11 (mobile) +
skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CO-2 — Lost update on optimistic concurrency

**Description:** two clients
update the same aggregate
concurrently; the second
update silently overwrites
the first.

**Why it matters:** a lost
update is a data loss.

**Mitigation:** per
`.ai/AGENTS.md` section 9 +
skill 03, every aggregate has
a `version: Long` field. The
second update detects the
version mismatch + raises a
typed `RevisionConflict`
error. The verifier (skill
14) tests the invariant.

**Owner:** skill 03 + skill
08 + skill 14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CO-3 — Outbox publish failure

**Description:** an outbox
record is published, but the
consumer crashes before
processing it; the outbox
record is lost.

**Why it matters:** an outbox
loss is a data loss.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.2 + ADR-0007, the outbox
is reliable: the publish is
idempotent + the consumer
is idempotent. The verifier
(skill 14) tests the
invariant via crash-recovery
scenarios.

**Owner:** skill 08 + skill
14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0007.

### R-CO-4 — Asset download race

**Description:** two parts of
the app download the same
asset concurrently; the
second download overwrites
the first.

**Why it matters:** an
overwritten asset is a data
loss.

**Mitigation:** per skill 06
section 5, the asset
download is content-addressed
+ idempotent. The download
path uses a content-addressed
cache. The second download
is a no-op.

**Owner:** skill 06 + skill
11.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CO-5 — Council deliberation deadlock

**Description:** the AI
council (skill 05) deadlocks
on a proposal (e.g. two
agents vote in opposite
directions + the tie-breaker
crashes).

**Why it matters:** a
deadlock is a user-visible
hang.

**Mitigation:** per skill 05
section 6, the council has
a tie-breaker + a timeout.
A deadlocked proposal is
escalated to a human
reviewer.

**Owner:** skill 05.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CO-6 — Compilation race

**Description:** two users
compile the same
`VehicleRevision`
concurrently; the second
compilation overwrites the
first's `CompilationResult`.

**Why it matters:** an
overwritten result is a data
loss.

**Mitigation:** per skill 04
section 8, the compiler is
deterministic + the
`CompilationResult` is
content-addressed. The
second compilation produces
the same `CompilationResult`
(content hash) + the
write is idempotent.

**Owner:** skill 04 + skill
08.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-CO-7 — Concurrent `SupplierQualification` updates

**Description:** two
qualifiers (a regulator + a
buyer) update the same
`SupplierQualification`
concurrently; the second
update silently overwrites
the first.

**Mitigation:** per skill 10
+ skill 13, the
`SupplierQualification` is
versioned + signed. The
second update detects the
version mismatch + raises a
typed `RevisionConflict`
error.

**Owner:** skill 10 + skill
13.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0018.

---

## 6. R-T — Trust risks

### R-T-1 — Unvalidated 3D asset

**Description:** a glTF /
STEP / USD enters the
canonical store without
validation.

**Why it matters:** an
unvalidated asset is a
vector for malicious code
+ corrupted geometry +
inconsistent units.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.5 + ADR-0012, the asset
is validated (manifold +
units + coordinate system +
file size + no-embedded-
scripts + provenance
coverage) before it enters
the store. The validator
runs in a sandbox.

**Owner:** skill 06.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0012.

### R-T-2 — Scripts executed from uploaded assets

**Description:** a glTF with
a script, a STEP with
macros, a USD with a custom
schema that runs code.

**Why it matters:** a script
is a code execution vector.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.5, the pipeline rejects
at the parse step. The
pipeline never executes
user-supplied code.

**Owner:** skill 06.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending (to be filed
in Phase 3).

### R-T-3 — Secret in the application package

**Description:** a secret
in the binary, the assets,
the config, or the build
artifacts.

**Why it matters:** a secret
leak is a P0 incident.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.5 + skill 12, secrets
live in the vault + the
secure enclave + the KMS.
The CI scans every build
artifact for known secret
patterns. A positive match
is a hard build failure.

**Owner:** skill 12.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

### R-T-4 — OBD / UDS untrusted source

**Description:** a malicious
OBD / UDS adapter injects
false telemetry.

**Why it matters:** a false
telemetry is a misdiagnosis.

**Mitigation:** per skill 07
section 7, the protocol
parser is the only component
that may parse the bytes;
the parser is total +
tested against a golden
corpus. A malformed
response is rejected at
the parse step.

**Owner:** skill 07.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-T-5 — Untrusted supplier data

**Description:** a supplier
uploads proprietary data
that is poisoned (e.g.
falsified dimensions,
falsified materials).

**Mitigation:** per skill
10 section 4, the
supplier's data is
disclosed under a signed
NDA + a time-bound
disclosure. The
disclosure is encrypted
at rest + in transit;
the disclosure is
revocable.

**Owner:** skill 10 + skill
12.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0019.

### R-T-6 — Path traversal in archive extraction

**Description:** a user
uploads an archive
containing a path
traversal (e.g.
`../../../etc/passwd`).

**Why it matters:** a path
traversal is a code
execution vector.

**Mitigation:** per skill
06 + the existing EV
runtime's archive
extractor, the extraction
is sandboxed + the paths
are validated. The
verifier (skill 14) tests
the invariant with
crafted inputs.

**Owner:** skill 06 + skill
14.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating (the
existing EV extractor
already implements this
mitigation; the Foundry
inherits it).
**ADR:** pending.

### R-T-7 — Untrusted user input to the DSL parser

**Description:** a user
uploads a malicious DSL
file (e.g. one that
exploits a parser bug).

**Why it matters:** a parser
bug is a code execution
vector.

**Mitigation:** per skill
04, the parser is total
+ tested with a fuzz
corpus. The parser is
verified by skill 14.

**Owner:** skill 04 + skill
14.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-T-8 — Untrusted user input to the AI council

**Description:** a user
uploads a prompt injection
that hijacks the AI
council.

**Mitigation:** per skill
05, the council's input
is sanitized + the
council's output is a
typed `AIProposal`. The
council never receives
free-form text after the
proposal schema is
applied.

**Owner:** skill 05 + skill
12.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-T-9 — Telemetry PII leakage

**Description:** a
`TelemetryStream` record
contains PII (e.g. a
license plate, a GPS
coordinate).

**Why it matters:** a PII
leak is a GDPR / CCPA /
LGPD violation.

**Mitigation:** per
`.ai/AGENTS.md` section
14 + skill 07 section 9,
the telemetry is filtered
at the source. A positive
match of PII in the
telemetry is rejected at
the parse step.

**Owner:** skill 07 + skill
12.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-T-10 — Backup contains unencrypted PII

**Description:** a backup
contains PII that is not
encrypted.

**Why it matters:** an
unencrypted backup is a
GDPR / CCPA / LGPD
violation.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.5 + skill 12, the backup
is encrypted at rest. The
encryption key is in the
KMS.

**Owner:** skill 15 + skill
12.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

---

## 7. R-AI — AI authority risks

### R-AI-1 — LLM directly mutates authoritative state

**Description:** a model
that writes to the
database, the catalog,
the audit trail, the
royalty engine, the
regulatory submission, or
the safety gate.

**Why it matters:** the LLM
is a non-deterministic
component. A direct
mutation is a data
integrity failure.

**Mitigation:** per
`.ai/STANDARDS.md` section
5 + ADR-0010, the AI may
NOT directly mutate any
of these. The model
produces a typed
proposal; the deterministic
engine + a human review
apply it. The verifier
(skill 14) tests the
invariant: the LLM has no
path to the database /
catalog / audit trail /
royalty engine /
regulatory submission /
safety gate.

**Owner:** skill 05 + skill
14.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0010.

### R-AI-2 — Vehicle marked as road legal based solely on AI output

**Description:** a
`RoadLegal` flag whose
only verification is
`AI_INFERRED`.

**Why it matters:** a
road-legal vehicle is a
safety-critical state. An
AI-inferred flag is a
fabricated safety claim.

**Mitigation:** per
`.ai/STANDARDS.md` section
2.6, the flag requires
`ENGINEER_REVIEWED` +
`REGULATORY_VERIFIED` + a
human counter-signature.
The CI enforces this.

**Owner:** skill 13 + skill
14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

### R-AI-3 — AI proposal bypasses the human review

**Description:** a
deterministic engine
applies an `AIProposal`
without the human review.

**Why it matters:** the
human review is the
last line of defense
against an AI mistake.

**Mitigation:** per
`.ai/STANDARDS.md` section
5 + skill 05, the
proposal is in
`PENDING_REVIEW` state
until a human signs off.
The verifier (skill 14)
tests the invariant: an
attempt to apply a
`PENDING_REVIEW` proposal
raises a typed
`ProposalRejected` error.

**Owner:** skill 05 + skill
14.
**Likelihood:** Low.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** pending.

### R-AI-4 — AI proposal injected with prompt injection

**Description:** a
malicious user crafts a
prompt that hijacks the
AI council.

**Why it matters:** a
prompt injection is a
trust failure.

**Mitigation:** per skill
05 section 8, the input
is sanitized + the
council's output is a
typed `AIProposal`. The
council never receives
free-form text after the
proposal schema is
applied.

**Owner:** skill 05 + skill
12.
**Likelihood:** Medium.
**Impact:** High.
**Status:** Mitigating.
**ADR:** pending.

### R-AI-5 — AI model produces `AI_INFERRED` fact that is silently verified

**Description:** the AI
model produces a fact +
the deterministic engine
applies it + the
`VerificationStatus` is
silently upgraded from
`AI_INFERRED` to
`OEM_VERIFIED`.

**Why it matters:** a
silent verification is a
data integrity failure.

**Mitigation:** per
`.ai/STANDARDS.md` section
3.2 + ADR-0003, the
transition is a human
review + a signed
counter-signature. The
verifier (skill 14) tests
the invariant.

**Owner:** skill 14 + skill
05.
**Likelihood:** Medium.
**Impact:** Critical.
**Status:** Mitigating.
**ADR:** ADR-0003.

### R-AI-6 — AI-generated 3D asset is unvalidated

**Description:** the AI
council proposes a
`Canonical3DAsset` +
the asset is accepted
without the validation
suite.

**Why it matters:** an
unvalidated asset is a
trust failure.

**Mitigation:** per skill
06 + ADR-0012, every
`Canonical3DAsset` MUST
pass the validation suite
before it enters the
canonical store. The
verifier (skill 14) tests
the invariant.

**Owner:** skill 06 + skill
05.
**Likelihood:** Low.
**Impact:** High.
**Status:** Mitigating.
**ADR:** ADR-0012.

### R-AI-7 — AI model produces a content hash collision (impersonation)

**Description:** a
malicious actor forges
an artifact with the
same content hash as a
legitimate artifact.

**Why it matters:** a
forged artifact is a
trust failure.

**Mitigation:** per skill
03 + skill 12, the
artifact's signature is
verified at load time.
A forged signature is
rejected at the parse
step.

**Owner:** skill 12 + skill
03.
**Likelihood:** Negligible.
**Impact:** Critical.
**Status:** Accepted.
**ADR:** pending.

---

## 8. Per-increment risk log

The per-increment risk log is
initialized by the orchestrator
when each increment starts. The
log has one row per increment.

### The per-increment log format

| Column | Description |
|---|---|
| Increment ID | the I-N.M identifier |
| Description | the increment's objective |
| Risk | the specific risk (e.g. "the migration may be lossy") |
| Mitigation | the action that reduces the risk |
| Status | Open / Mitigating / Closed |
| Owner | the skill or human accountable |
| Date opened | when the risk was identified |
| Date closed | when the risk was closed |

### The Phase 1 per-increment risks (initialized)

| Increment | Risk | Mitigation | Status | Owner |
|---|---|---|---|---|
| I-1.1 | A raw `String` is used as an ID | Boundary validation rejects invalid `UUID` strings with a typed `FoundryError` | Mitigating | skill 14 |
| I-1.2 | A primitive has a hidden invariant | Each primitive is documented + the invariant is in the type signature | Mitigating | skill 03 |
| I-1.3 | The `Project` migration is lossy | The migration is tested on a fixture with a rollback path | Open | skill 08 + skill 03 |
| I-1.4 | The `VehicleProgram` migration is lossy | Same as I-1.3 | Open | skill 08 + skill 03 |
| I-1.5 | The `VehicleRevision` append-only invariant is bypassed | The verifier tests the invariant | Open | skill 14 |
| I-1.6 | The `Contributor` PII is unencrypted | The PII is encrypted at rest + the encryption key is in the KMS | Mitigating | skill 12 |
| I-1.7 | The `EngineeringArtifact` content hash is not verified | The hash is verified at load time | Mitigating | skill 03 |
| I-1.8 | The `ProvenanceRecord` is not append-only | The append-only invariant is tested by the verifier | Open | skill 14 |
| I-1.9 | The optimistic concurrency is not versioned | Every aggregate has a `version: Long` field | Mitigating | skill 14 |

The log is appended to by the
orchestrator when each
increment starts. A new row is
added; an old row is updated;
no row is deleted.

---

## 9. Risk treatment rules

### How a risk is closed

A risk is closed when one of
the following is true:

1. **The mitigation is complete.**
   The mitigation is verified by
   the verifier (skill 14). The
   verifier's test suite covers
   the mitigation. The status is
   `Closed`.
2. **The risk is accepted.** The
   orchestrator (skill 00) files
   an ADR. The ADR explains the
   rationale. The status is
   `Accepted`.
3. **The risk is no longer
   relevant.** The risk's premise
   no longer holds (e.g. a
   feature is removed). The
   status is `Closed`.

A risk that is not `Closed` or
`Accepted` is `Open` or
`Mitigating`. A risk that is
`Open` for more than 90 days
is escalated to the on-call
rotation.

### How a risk is escalated

A risk is escalated when:

1. **The mitigation is overdue.**
   The mitigation's target date
   has passed.
2. **The likelihood increases.**
   A new event has raised the
   likelihood.
3. **The impact increases.** A
   new event has raised the
   impact.
4. **A new finding emerges.** A
   security audit + a regulator
   + a red team has surfaced a
   new finding.

The escalation is filed as an
ADR. The escalation is in the
register.

---

## 10. Risk review schedule

| Review | Frequency | Owner | Audience |
|---|---|---|---|
| **Per-increment review** | when an increment's gate is green | the increment's owner | skill 00 + skill 14 |
| **Per-phase review** | when a phase's gate is green | the phase's owner skill | skill 00 + all 16 skills |
| **Quarterly review** | every 90 days | skill 00 | all 16 skills + the user |
| **Incident-triggered review** | when an incident is filed | the incident's owner | skill 00 + skill 15 + skill 12 |
| **Regulator-triggered review** | when a regulator files a finding | skill 13 | skill 00 + skill 13 + the user |

A review that is overdue is a
P1 finding; the orchestrator
blocks the release.

---

## 11. Risk ADR log

| ADR | Risk closed | Status | Owner | Date |
|---|---|---|---|---|
| ADR-0001 | R-DI-3, R-CH-4 (Float → BigDecimal for money) | Active | skill 03 | 2026-07-17 |
| ADR-0002 | R-DI-6 (VehicleRepresentationLevel downgrade) | Active | skill 03 | 2026-07-17 |
| ADR-0003 | R-DI-1, R-AI-5 (AI_INFERRED → VERIFIED transition) | Active | skill 03 + skill 09 | 2026-07-17 |
| ADR-0004 | R-CI-1 (Royalty without active contract) | Active | skill 09 | 2026-07-17 |
| ADR-0005 | R-CI-3 (Hardcoded Elysium 5% royalty) | Active | skill 09 | 2026-07-17 |
| ADR-0006 | R-DI-4 (Mutable historical commercial release) | Active | skill 03 | 2026-07-17 |
| ADR-0007 | R-CO-3, R-CI-5, R-CH-8 (Outbox reliability + idempotency) | Active | skill 08 | 2026-07-17 |
| ADR-0008 | R-CH-7 (Free-form error string) | Active | skill 00 + skill 14 | 2026-07-17 |
| ADR-0010 | R-AI-1 (LLM directly mutates authoritative state) | Active | skill 05 + skill 14 | 2026-07-17 |
| ADR-0011 | R-CI-2 (Royalty on ineligible VehicleRepresentationLevel) | Active | skill 09 + skill 10 | 2026-07-17 |
| ADR-0012 | R-T-1, R-AI-6 (Unvalidated 3D asset) | Active | skill 06 | 2026-07-17 |
| ADR-0018 | R-CO-7 (Concurrent SupplierQualification updates) | Active | skill 10 + skill 13 | 2026-07-17 |
| ADR-0019 | R-T-5 (Untrusted supplier data) | Active | skill 10 + skill 12 | 2026-07-17 |

The ADR series is in
`docs/adr/foundry/`. A new
ADR is filed when a new
risk is closed via a
deviation.

---

## 12. Output

This document is the
**authoritative risk register**
of the Foundry platform.
The document is current as
of 2026-07-17. A change to a
risk (new risk, closed risk,
escalated risk) is an ADR. A
change to a risk treatment
rule is an ADR. A change to
the review schedule is an
ADR.

The document is the input
to:

- `docs/foundry/implementation-roadmap.md`
  (every increment has a
  per-increment risk).
- `docs/foundry/dependency-map.md`
  (every cross-skill edge has
  a risk).
- The verifier's gate (skill
  14) — the verifier blocks
  the release if a risk is
  unmitigated.

The orchestrator files
this document under
`docs/foundry/gates/g0-risk-register.md`
when G0 is green.
