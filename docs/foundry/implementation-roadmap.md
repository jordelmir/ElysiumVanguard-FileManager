---
title: Implementation Roadmap — Elysium Automotive Foundry
status: Phase 0 deliverable, signed 2026-07-17
owner: skill 00 (program-orchestrator)
audited_by: skill 01 (repository-archaeology)
git_head: c9028dc
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Implementation Roadmap — Elysium Automotive Foundry

> **Status:** Phase 0 deliverable. The
> **dependency-ordered sequence of
> increments** that move the platform
> from the current state (per
> `current-state-audit.md`) to the
> target architecture (per
> `target-architecture.md`). Every
> increment is a vertical slice that
> ships end-to-end (per `.ai/AGENTS.md`
> section 9 — delivery rules). Every
> phase has a gate (per `.ai/AGENTS.md`
> section 22). **No phase is skipped.**
> A skipped phase is a contract
> violation; the orchestrator blocks
> the release.

---

## 0. How to read this document

The document is split into 8 parts
(one per execution phase, 0–7) +
one part for the dependency graph +
one part for the parallelism rules +
one part for the Definition of Done
(DoD) + one part for the critical
integration test.

Each phase has:

- **The objective** — what the
  phase proves.
- **The gate** — the G# that must
  be green before the next phase
  starts.
- **The owner skill** — the skill
  that owns the gate.
- **The increments** — the
  dependency-ordered vertical
  slices.
- **The acceptance criteria** —
  per `.ai/AGENTS.md` section 9 +
  section 21 (completion standard).
- **The risk-register entries** —
  the per-increment risks filed in
  `risk-register.md`.
- **The rollback / recovery
  procedure** — per `.ai/AGENTS.md`
  section 9.1.
- **The DoD** — per `.ai/AGENTS.md`
  section 21.

The phase numbers are stable. The
gate numbers (G0–G10) are stable.
The increment IDs (I-1.1, I-1.2, …)
are stable. A change to a phase /
gate / increment is an ADR.

---

## Phase 0 — Discovery (G0)

**Objective:** the orchestrator
understands the repository, the
target architecture, the domain
ownership, the dependency map, the
risk register, and the
implementation roadmap.

**Gate:** **G0** — Repository
understood (per `.ai/AGENTS.md`
section 22).

**Owner skill:** skill 00
(program-orchestrator) + skill 01
(repository-archaeology).

### Phase 0 increments

The increments of Phase 0 are the
six deliverables of skill 00
section 3.

#### I-0.1 — Repository audit (`current-state-audit.md`)

The orchestrator runs the audit
(per skill 01's 7 questions + 15
required analyses + 11 outputs).
**Acceptance criteria:**

- 7 DoD questions answered.
- 14 mandatory sections present.
- 15 security findings categories
  reviewed.
- Performance + duplicate-concepts
  + dead-code review complete.
- File filed under
  `docs/foundry/current-state-audit.md`
  + signed.
- Every "we don't know" is a
  risk-register entry.

**Status:** Complete (2026-07-17,
27611 bytes).

#### I-0.2 — Target architecture (`target-architecture.md`)

The orchestrator drafts the target
architecture (per skill 00 section
7 + skill 01's 17 master-prompt
surfaces). **Acceptance criteria:**

- 10 mandatory sections present.
- C4 L1 + L2 + L3 diagrams present.
- 17 master-prompt surfaces mapped
  to 16 skills.
- 10 bridges from current to target
  documented.
- 19 non-negotiables recorded.
- File filed under
  `docs/foundry/target-architecture.md`
  + signed.

**Status:** Complete (2026-07-17,
16202 bytes).

#### I-0.3 — Domain ownership (`domain-ownership.md`)

The orchestrator drafts the domain
ownership. **Acceptance criteria:**

- Every aggregate has an owner
  (sections 1–6).
- Every cross-skill contract is
  documented (section 7).
- Arbitration rules are documented
  (section 8).
- ADR registry is initialized
  (section 9).
- File filed under
  `docs/foundry/domain-ownership.md`
  + signed.

**Status:** Complete (2026-07-17,
39946 bytes).

#### I-0.4 — Dependency map (`dependency-map.md`)

The orchestrator drafts the
dependency map. **Acceptance
criteria:**

- Every cross-skill edge is
  documented (the 28 rows of the
  cross-skill edge table).
- Every external dependency is
  documented.
- Auth + error envelope + retry +
  correlation are recorded per
  edge.
- File filed under
  `docs/foundry/dependency-map.md`
  + signed.

**Status:** In progress (this
document).

#### I-0.5 — Risk register (`risk-register.md`)

The orchestrator drafts the risk
register. **Acceptance criteria:**

- Every identified risk has an
  owner + a likelihood + an impact
  + a mitigation + a status.
- The 6 categories (R-DI, R-CI,
  R-CH, R-CO, R-T, R-AI) are
  covered.
- The per-increment risk log is
  initialized.
- File filed under
  `docs/foundry/risk-register.md`
  + signed.

**Status:** Pending.

#### I-0.6 — Implementation roadmap (this document)

The orchestrator drafts the
implementation roadmap.
**Acceptance criteria:**

- 8 phases documented.
- Every phase has a gate.
- Every increment has acceptance
  criteria.
- Every phase has a rollback /
  recovery procedure.
- File filed under
  `docs/foundry/implementation-roadmap.md`
  + signed.

**Status:** In progress (this
document).

### Phase 0 rollback / recovery

Phase 0 produces documentation
only. A rollback is a
documentation edit + a new
sign-off. No code is touched;
no data is touched.

### Phase 0 DoD

- All six files are filed +
  signed.
- All six cross-references
  resolve.
- The orchestrator (skill 00)
  has read every skill (per
  master prompt's "obligación
  inicial").

---

## Phase 1 — Foundational domain (G1)

**Objective:** the platform's
domain primitives + product
aggregates are implemented,
tested, persisted, versioned, and
consumable by every other skill.

**Gate:** **G1** — Domain model
approved (per `.ai/AGENTS.md`
section 22).

**Owner skill:** skill 03
(vehicle-domain-ontology).

### Phase 1 increments

#### I-1.1 — Strongly-typed IDs

Every domain identity is a
`@JvmInline value class` over
`UUID`. **Acceptance criteria:**

- 16+ ID types defined
  (`ProjectId`, `VehicleProgramId`,
  `VehicleRevisionId`,
  `ContributorId`, `EngineeringArtifactId`,
  `ProvenanceRecordId`, `PartId`,
  `VariantId`, `CompatibilityId`,
  `SubsystemId`, `AssemblyId`,
  `BrandId`, `DiagnosticId`,
  `FaultId`, `RepairActionId`,
  `RoyaltyContractId`).
- Boundary validation rejects
  invalid `UUID` strings with a
  typed `FoundryError`.
- Unit tests cover the validation
  + the equality + the hash code.
- No raw `String` field in any
  domain entity.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.ontology.ids`.

#### I-1.2 — Domain primitives

Every domain primitive (per
`domain-ownership.md` section 1)
is implemented. **Acceptance
criteria:**

- `Money` (BigDecimal-backed,
  currency-tagged).
- `Unit` (SI base + derived;
  locale-aware display).
- `CoordinateSystem` (4 enum
  values; custom-with-ADR).
- `EngineeringFact<T>` (typed
  fact with provenance + verification
  status + source + jurisdiction).
- `VerificationStatus` (7 enum
  values).
- `Source` (typed reference).
- `Jurisdiction` (UNECE, FMVSS,
  GB, JIS, regional EU, regional
  US, regional CN).
- `Timestamp` (HLC-aware,
  monotonic).
- `Locale` (BCP-47).
- `ContentHash` (SHA-256).
- `Signature` (asymmetric, post-
  quantum-ready).
- `FoundryError` (typed error
  envelope, per `.ai/STANDARDS.md`
  section 7).
- `VehicleRepresentationLevel`
  (5 enum values, append-only
  transitions).
- `InterfacePort` (28 kinds, per
  skill 03 section 15).
- `PowerTrainCategory` (8
  categories, per skill 03
  section 13).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.ontology.primitives`.

#### I-1.3 — `Project` aggregate

The first product aggregate.
**Acceptance criteria:**

- Domain type + Room migration +
  use case + API + UI scaffolding
  + auth (RBAC) + typed errors +
  unit tests + integration tests
  + observability + docs +
  migration + rollback.
- Optimistic concurrency control
  (versioned row + conflict
  detection + typed
  `RevisionConflict` error).
- Append-only audit trail (per
  skill 09's pattern).
- 12-point acceptance checklist
  (per section 12 of this
  document).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.project.*` +
new DB migration
`V2__project_aggregate.sql` +
new Compose screen
`ProjectListScreen.kt` +
new ViewModel
`ProjectListViewModel.kt`.

#### I-1.4 — `VehicleProgram` aggregate

The vehicle family container.
**Acceptance criteria:** 12-point
acceptance checklist.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.program.*`
+ new DB migration
`V3__vehicle_program_aggregate.sql`.

#### I-1.5 — `VehicleRevision` aggregate

The unit of immutability. The
revision is **append-only**; a
mutation is a new revision that
points to its predecessor. The
chain is content-addressed +
signed. **Acceptance criteria:**

- 12-point acceptance checklist.
- Append-only enforcement
  (verified by the verifier's
  test suite: an attempt to
  mutate a signed revision
  raises a typed
  `RevisionConflict` error).
- The chain is content-addressed
  + signed (the signature is
  verified at load time).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.revision.*`
+ new DB migration
`V4__vehicle_revision_aggregate.sql`.

#### I-1.6 — `Contributor` aggregate

The human / organization
contributor. **Acceptance
criteria:**

- 12-point acceptance checklist.
- The contributor's private data
  is encrypted at rest (per
  skill 12).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.contributor.*`
+ new DB migration
`V5__contributor_aggregate.sql`.

#### I-1.7 — `EngineeringArtifact` aggregate

The typed reference to a content-
addressed engineering artifact.
**Acceptance criteria:**

- 12-point acceptance checklist.
- The reference's content hash
  is verified at load time.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.artifact.*`
+ new DB migration
`V6__engineering_artifact_aggregate.sql`.

#### I-1.8 — `ProvenanceRecord` aggregate

The signed provenance event.
**Acceptance criteria:**

- 12-point acceptance checklist.
- Append-only + signed +
  content-addressed.
- `AI_INFERRED → OEM_VERIFIED`
  transition requires a signed
  counter-signature (per
  ADR-0003).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.provenance.*`
+ new DB migration
`V7__provenance_record_aggregate.sql`.

#### I-1.9 — Revision + concurrency strategy

The cross-cutting infrastructure
for optimistic concurrency on
every aggregate. **Acceptance
criteria:**

- A `version: Long` field on
  every aggregate.
- A `RevisionConflict` typed
  error.
- A unit test per aggregate that
  asserts the conflict detection.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.concurrency.*`.

### Phase 1 rollback / recovery

Every DB migration has a
forward + a rollback (per
`.ai/AGENTS.md` section 9.1).
The rollback is tested (the
test re-runs the migration on
a fixture and asserts the
rollback produces the
original state). A failed
migration is a P0 incident;
the orchestrator blocks the
release; the rollback
procedure is the immediate
response.

### Phase 1 DoD

- 9 increments shipped + tested.
- 0 lint errors.
- All unit tests passing.
- All integration tests passing.
- The ontology is documented,
  versioned, signed.
- The invariant tests pass (the
  tests are the verifier's gate).

---

## Phase 2 — Vehicle definition (G2 + G3)

**Objective:** the deterministic
vehicle compiler is total, the
spec output is content-addressed +
signed, the golden tests pass.

**Gate:** **G3** — Vehicle compiler
deterministic (per `.ai/AGENTS.md`
section 22).

**Owner skill:** skill 04
(vehicle-dsl-compiler).

### Phase 2 increments

#### I-2.1 — DSL grammar

The text surface + the visual
AST. **Acceptance criteria:**

- The grammar is documented
  (per skill 04 section 2).
- The visual AST is documented.
- The grammar is a `grammar/`
  resource.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.grammar.*`
+ new resource
`app/src/main/grammar/foundry-vsl.g4`.

#### I-2.2 — Parser

The lexer + the parser. **Acceptance
criteria:**

- The parser is total (every
  valid input is parsed; an
  invalid input is rejected with
  a typed `CompilationDiagnostic`).
- Golden tests pass (the tests
  cover the 8 power-train
  categories + edge cases).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.parser.*`.

#### I-2.3 — Schema

The typed spec shape. **Acceptance
criteria:**

- The schema is the
  `CompiledVehicleSpec` (per
  `domain-ownership.md` section
  3.5).
- The schema is content-addressed
  + signed.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.schema.*`.

#### I-2.4 — Validator

The invariant checker. **Acceptance
criteria:**

- The validator is total (every
  spec is checked; a violation
  raises a typed
  `CompilationDiagnostic`).
- The validator's checks are
  documented (per skill 04
  section 5).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.validator.*`.

#### I-2.5 — Compatibility rules

The constraint engine. **Acceptance
criteria:**

- The engine is total (every
  `ConstraintGraph` is validated).
- The engine's output is
  deterministic.
- The engine is documented (per
  skill 04 section 7).

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.compatibility.*`.

#### I-2.6 — Deterministic compiler

The 18-step pipeline. **Acceptance
criteria:**

- The compiler is deterministic
  (the same input produces the
  same output, byte-for-byte).
- The compiler's steps are
  documented (per skill 04
  section 8).
- The compiler is the only
  component that may produce
  a `CompiledVehicleSpec`.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.compiler.*`.

#### I-2.7 — Compilation report

The user-facing report.
**Acceptance criteria:**

- The report is localized (per
  `.ai/AGENTS.md` section 24.2).
- The report includes the
  `CompilationDiagnostic` list +
  the `CompilationSuggestion`
  list + the "go to definition"
  map.

**Affected files:** new package
`com.elysium.vanguard.foundry.core.dsl.report.*`.

#### I-2.8 — Editor support

Syntax highlighting +
autocomplete + go-to-definition
+ hover docs. **Acceptance
criteria:**

- The editor is the Android
  Compose-based code editor
  (per skill 11 section 16).
- The editor consumes the
  grammar + the schema + the
  validator.

**Affected files:** new package
`com.elysium.vanguard.foundry.feature.editor.*`.

### Phase 2 rollback / recovery

The compiler is in a new
package. A failed compilation
is a typed `CompilationDiagnostic`;
the user can fix the spec and
re-compile. The compiler has no
side effects; a rollback is a
delete of the package + the
tests.

### Phase 2 DoD

- 8 increments shipped + tested.
- 0 lint errors.
- All unit tests passing.
- All integration tests passing.
- Golden tests pass (parser +
  resolver + type-checker).
- The artifact contract is
  content-addressed + signed.

---

## Phase 3 — Digital twin (G4)

**Objective:** the 3D pipeline +
the digital twin share the same
canonical artifact; the manifest
is signed; the LODs are present;
the asset-validation suite
passes.

**Gate:** **G4** — 3D digital twin
integrated (per `.ai/AGENTS.md`
section 22).

**Owner skill:** skill 06
(3D-cad-asset-pipeline) + skill
07 (digital-twin-diagnostics).

### Phase 3 increments

#### I-3.1 — Scene manifest

The typed manifest shape. The
manifest is a list of
`Canonical3DAsset` references +
their `LOD` selection + their
`Transform` + their
`CoordinateSystem` + their
parent-child relationship. The
manifest is signed; the
manifest's content hash is the
canonical id.

#### I-3.2 — Part instance graph

The runtime graph. The graph is
the live representation of the
vehicle: the user can select a
part, isolate it, view its
diagnostics, see its
`EngineeringArtifact` references,
and trigger a `RepairAction`.

#### I-3.3 — Asset streaming

The LOD streaming pipeline. The
pipeline is content-addressed;
the pipeline's source is the
canonical store; the pipeline's
sink is the runtime cache.

#### I-3.4 — Selection + isolation

The user-facing selection. The
selection is the read-side state
for the UI + the input to the
diagnostic engine.

#### I-3.5 — Representation confidence

The `VehicleRepresentationLevel`
integration. The level is
displayed prominently in the
UI (per `.ai/STANDARDS.md`
section 2.1); the level is the
gate for the marketplace (per
ADR-0011); the level is the
input to the safety gate (per
skill 13).

#### I-3.6 — Diagnostic bindings

The fault model integration. The
`Diagnostic` is a typed `DTC`
reference + a `Symptom`
reference + a `Hypothesis` list
+ a `TestProcedure` list + a
`RepairAction` list + a
`TelemetrySnapshot` reference +
a `VerificationStatus`.

### Phase 3 DoD

- 6 increments shipped + tested.
- 0 lint errors.
- The 3D pipeline's manifest is
  signed.
- The digital twin consumes the
  same canonical artifact.
- The asset-validation suite
  passes.

---

## Phase 4 — AI council (G5)

**Objective:** the LLM produces
typed proposals; the model
cannot mutate the database, the
catalog, the audit trail, the
royalty engine, the regulatory
submission, or the safety gate.

**Gate:** **G5** — AI constrained
by structured tools (per
`.ai/AGENTS.md` section 22).

**Owner skill:** skill 05
(ai-engineering-council).

### Phase 4 increments

#### I-4.1 — Typed proposal schema

The schema the AI produces. The
schema is a typed
`AIProposal<T>` where `T` is
the surface (`DslMutation`,
`AssetMetadata`, `AuthorshipClaim`,
`DiagnosticSuggestion`,
`ComplianceSuggestion`). The
schema is signed (the signature
is the council's collective
signature).

#### I-4.2 — Deterministic engine

The engine that applies the
proposal. The engine is the
only component that may apply
a proposal; the engine is
deterministic; the engine's
output is a typed `MutationRequest`.

#### I-4.3 — Human review UI

The review surface. The UI is
the Android Compose-based
review surface (per skill 11
section 17). The UI shows the
proposal + the deterministic
preview + the human's
counter-signature.

#### I-4.4 — Audit trail

The signed events. The audit
trail is append-only +
content-addressed + signed.
The audit trail is the
read-side state for the
verifier (skill 14) + the
regulator (skill 13).

#### I-4.5 — Council deliberation

The multi-agent voting. The
council is the multi-agent
deliberation (per skill 05
section 6). The council's
output is the typed
`AIProposal` + the council's
collective signature.

### Phase 4 DoD

- 5 increments shipped + tested.
- 0 lint errors.
- The LLM has no path to the
  database / catalog / audit
  trail / royalty engine /
  regulatory submission /
  safety gate.
- The audit trail is signed +
  append-only.
- The human review is recorded.

---

## Phase 5 — Commercial foundation (G6 + G7)

**Objective:** the IP / provenance
ledger is operational; the
royalty engine is contract-driven;
money is `BigDecimal`; settlements
are auditable.

**Gate:** **G6** — IP and provenance
ledger operational + **G7** —
Royalty engine contract-driven
(per `.ai/AGENTS.md` section 22).

**Owner skill:** skill 09
(ip-provenance-royalties).

### Phase 5 increments

#### I-5.1 — Contracts

The `RoyaltyContract` schema +
the validation. The contract
is typed; the contract is
signed; the contract's status
is `DRAFT` / `ACTIVE` /
`SUSPENDED` / `TERMINATED`.

#### I-5.2 — Rights

The rights registry. The
registry is the read-side
state for the royalty engine.

#### I-5.3 — Licenses

The per-artifact license. The
license is typed; the license
is signed; the license's
`LicenseType` is one of the
8 enums (per `domain-ownership.md`
section 5.4).

#### I-5.4 — Revenue events

The sale event. The event is
typed; the event is signed;
the event is the input to the
royalty engine.

#### I-5.5 — Royalty rules

The rule engine. The engine is
deterministic; the engine is
the only component that may
compute a `Distribution`; the
engine's output is signed.

#### I-5.6 — Statements

The per-user statement. The
statement is the user-facing
report; the statement is
localized; the statement is
auditable.

#### I-5.7 — Audit trail

The signed ledger. The ledger
is append-only + content-
addressed + signed; the
ledger is the read-side
state for the verifier (skill
14) + the regulator (skill
13).

### Phase 5 DoD

- 7 increments shipped + tested.
- 0 lint errors.
- The royalty engine is
  deterministic (tested with
  a fixture).
- Money is `BigDecimal` (the
  CI enforces this).
- The contract is `ACTIVE`.
- The settlement envelope is
  typed.

---

## Phase 6 — Marketplace + supplier network (G8)

**Objective:** listings, escrow,
supplier integration, and
settlement are end-to-end;
`VISUAL_ONLY` and `CONCEPTUAL`
vehicles are not eligible.

**Gate:** **G8** — Marketplace and
supplier workflow (per
`.ai/AGENTS.md` section 22).

**Owner skill:** skill 10
(marketplace-manufacturing).

### Phase 6 increments

#### I-6.1 — Supplier discovery

A supplier browses the parts
catalog; a designer browses
the supplier catalog. The match
is `EngineeringFact<T>`-driven.

#### I-6.2 — RFQs

A designer sends an RFQ to a
supplier. The RFQ is a typed
artifact; the response is a
typed artifact; both are
content-addressed + signed.

#### I-6.3 — Offers

A supplier responds to an RFQ
with an offer. The offer is
bound to the RFQ; the offer is
content-addressed + signed; the
offer's money is `BigDecimal`.

#### I-6.4 — Qualification

A supplier is qualified by a
regulator (skill 13) + a buyer
(skill 10) + an engineer (skill
03). The qualification is a
signed event in the audit
trail.

#### I-6.5 — Controlled disclosure

A supplier shares proprietary
data with a qualified buyer +
a signed NDA + a time-bound
disclosure. The disclosure is
in the audit trail; the
disclosure's data is encrypted
at rest + in transit; the
disclosure is revocable.

### Phase 6 DoD

- 5 increments shipped + tested.
- 0 lint errors.
- The marketplace end-to-end
  test (listing → order → escrow
  → settlement) passes.
- The `VISUAL_ONLY` /
  `CONCEPTUAL` ineligibility is
  tested.

---

## Phase 7 — Production hardening (G9 + G10)

**Objective:** threat model, SLOs,
on-call, runbooks, red team, CVE
SLA, observability are all in
place.

**Gate:** **G9** — Safety and
regulatory evidence model + **G10**
— Production hardening (per
`.ai/AGENTS.md` section 22).

**Owner skill:** skill 12
(security-zero-trust) + skill 13
(functional-safety-regulatory) +
skill 15 (devops-observability).

### Phase 7 increments

#### I-7.1 — Threat modeling

The threat model is current
(per `docs/threat-model/`); the
residual-risk register is
reviewed; the red team has run
at least once.

#### I-7.2 — Performance

The performance baselines are
documented (P99 latency per
surface, requests-per-second
per surface, resource hot
spots); the performance gates
are in the CI; a regression
beyond the approved limit is a
P1 incident.

#### I-7.3 — Observability

The OpenTelemetry traces are
sampled; the metrics are
emitted; the logs are
structured; the alerts are in
place; the dashboards are built.

#### I-7.4 — Disaster recovery

The DR plan is documented; the
RPO + RTO are measured; the
failover is tested; the backups
are encrypted; the backups are
restorable.

#### I-7.5 — Security review

The security sign-off is in
`docs/audits/`; the CVE feed is
monitored; the patch SLA is
met; the secrets are in the
vault; the auth + authz are
zero-trust; the encryption is
at rest + in transit.

#### I-7.6 — Regulatory review

The regulatory evidence is
documented (per skill 13
section 6); the homologation
package is in the catalog; the
`SafetyGateNotSatisfied` error
is the gate.

### Phase 7 DoD

- 6 increments shipped + tested.
- 0 lint errors.
- The threat model is current.
- The SLOs are met.
- The on-call rotation is in
  place.
- The runbooks cover the top-10
  incidents.
- The red-team report is filed.
- The CVE feed is monitored.
- The OpenTelemetry traces are
  sampled.

---

## Dependency graph

The phases are dependency-
ordered:

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7
```

Within each phase, the
increments are dependency-
ordered. The orchestrator
documents the per-increment
dependencies when the phase
starts.

A phase is **not** started
until the previous phase's
gate is green. A skipped phase
is a **contract violation**;
the orchestrator blocks the
release.

### The cross-phase dependencies

- **Phase 1 depends on Phase 0.**
  The phase 1 increments
  reference the domain primitives
  + the product aggregates
  documented in Phase 0.
- **Phase 2 depends on Phase 1.**
  The DSL grammar references the
  domain primitives (per
  `domain-ownership.md` section
  1); the compiled spec
  references the product
  aggregates.
- **Phase 3 depends on Phase 2.**
  The 3D pipeline consumes the
  `CompiledVehicleSpec` (per
  `dependency-map.md` row
  "skill 04 → skill 06").
- **Phase 4 depends on Phase 2.**
  The AI council's
  `AIProposal<DslMutation>`
  references the DSL grammar.
- **Phase 5 depends on Phase 1.**
  The royalty engine references
  the `Contributor` + the
  `Project` + the
  `VehicleRevision`.
- **Phase 6 depends on Phase 5.**
  The marketplace consumes the
  `RoyaltyContract` + the
  `License`.
- **Phase 7 depends on every
  previous phase.** The
  production hardening exercises
  every surface; the threat
  model covers every cross-skill
  edge; the SLOs cover every
  increment.

### The cross-increment dependencies

Every increment has explicit
dependencies. A dependency
without an explicit owner is
a smell; the orchestrator
files the dependency as an
ADR.

---

## Parallelism rules

Per skill 00 section 5.1, the
platform supports parallelism
under five strict conditions:

1. **The two increments are in
   different skill
   namespaces.** Two increments
   in the same skill namespace
   are serialized; the skill
   owner arbitrates.
2. **The two increments do not
   share a cross-skill edge.** A
   shared edge is a serialization
   point; the orchestrator
   arbitrates.
3. **The two increments do not
   share a DB migration.** A
   shared migration is a
   serialization point; the
   migration owner arbitrates.
4. **The two increments do not
   share a UI screen.** A
   shared screen is a
   serialization point; the UI
   owner arbitrates.
5. **The two increments do not
   share an ADR.** A shared ADR
   is a serialization point;
   the orchestrator arbitrates.

### Forbidden patterns (8)

1. **Two increments editing the
   same aggregate** in
   parallel.
2. **Two skills producing the
   same cross-skill edge** in
   parallel.
3. **Two increments sharing a
   DB migration** in parallel.
4. **Two increments editing
   the same UI screen** in
   parallel.
5. **Two skills arbitrating the
   same field** without the
   aggregate owner's approval.
6. **Two increments producing
   the same ADR** in parallel.
7. **Two increments testing
   the same invariant** with
   different assertions.
8. **Two increments sharing a
   test fixture** without the
   fixture's owner approving.

A violation of any of these
rules is a **P0 contract
violation**; the verifier
rejects the PR.

---

## Definition of Done (per `.ai/AGENTS.md` section 21 + section 9)

Per `.ai/AGENTS.md` section 21
(Completion Standard) + section
9 (Delivery rules), every
increment must ship with:

1. **Domain model.** The
   aggregate + its invariants
   + its relations.
2. **DB migration.** Forward +
   rollback + content hash +
   signature.
3. **Use case.** The
   application-level use case.
4. **API contract.** The typed
   API (the OpenAPI for backend
   surfaces, the Kotlin
   interface for in-process
   surfaces).
5. **UI integration.** The
   Compose screen + the
   navigation.
6. **Auth check.** The
   authentication + the
   authorization.
7. **Typed errors.** The typed
   `FoundryError` envelope.
8. **Unit tests.** Per skill
   14's pyramid layer 3.
9. **Integration tests.** Per
   skill 14's pyramid layer 8.
10. **Observability.** The
    OpenTelemetry traces + the
    metrics + the logs.
11. **Docs.** The PRD + the ADR
    + the user-facing doc.
12. **Migration + rollback.**
    Per `.ai/AGENTS.md` section
    9.1.

A "placeholder production
logic disguised as complete
implementation" is a **P0
contract violation**; the
verifier (skill 14) rejects
the PR.

---

## Critical integration test

Per skill 00 section 5.3, the
critical integration test
proves that the platform's
core invariants hold end-to-
end. The test has 6 assertions:

1. **A `Project` is created
   with a `VehicleProgram` +
   a `VehicleRevision` + a
   `Contributor` + an
   `EngineeringArtifact` + a
   `ProvenanceRecord`.** The
   test asserts the chain is
   content-addressed + signed
   + append-only.
2. **A `CompiledVehicleSpec`
   is produced from the
   `VehicleRevision`.** The
   test asserts the compiler
   is deterministic (the same
   input produces the same
   output, byte-for-byte).
3. **A `Canonical3DAsset`
   is produced from the
   `CompiledVehicleSpec`.**
   The test asserts the
   asset-validation suite
   passes (manifold + units +
   coordinate system + file
   size + no-embedded-scripts
   + provenance coverage).
4. **A `RoyaltyContract` is
   activated.** The test
   asserts the contract is
   signed + the contract's
   status is `ACTIVE` + the
   contract is bound to the
   `Project`.
5. **A sale event is
   produced.** The test
   asserts the settlement is
   computed against the
   contract + the settlement
   is signed + the settlement
   is in the audit trail + the
   settlement's money is
   `BigDecimal`.
6. **A `SafetyGateNotSatisfied`
   error is raised when a
   `VISUAL_ONLY` vehicle
   attempts to be sold.** The
   test asserts the marketplace
   rejects with the typed
   error.

A failure of any of the 6
assertions is a **P0
incident**; the orchestrator
blocks the release.

---

## The per-increment delivery checklist

Every increment ships with
the following 12 items:

| # | Item | Owner |
|---|---|---|
| 1 | Domain model | skill 03 |
| 2 | DB migration (forward + rollback) | skill 08 |
| 3 | Use case | skill 03 + skill 08 |
| 4 | API contract | skill 08 |
| 5 | UI integration | skill 11 |
| 6 | Auth check | skill 12 |
| 7 | Typed errors | skill 14 |
| 8 | Unit tests | skill 14 |
| 9 | Integration tests | skill 14 |
| 10 | Observability | skill 15 |
| 11 | Docs (PRD + ADR + user-facing) | skill 02 + skill 00 |
| 12 | Migration + rollback | skill 08 |

---

## Output

This document is the
**authoritative implementation
roadmap** of the Foundry
platform. The document is
current as of 2026-07-17. A
change to a phase / gate /
increment is an ADR. A change
to a parallelism rule is an
ADR. A change to the DoD is
an ADR.

The document is the input
to:

- `docs/foundry/risk-register.md`
  (every increment has a
  per-increment risk).
- `docs/foundry/dependency-map.md`
  (every cross-skill edge is
  scheduled in a phase).
- The verifier's gate (skill
  14) — the verifier blocks
  the release if the roadmap
  is violated.

The orchestrator files
this document under
`docs/foundry/gates/g0-implementation-roadmap.md`
when G0 is green.
