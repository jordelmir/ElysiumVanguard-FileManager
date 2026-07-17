---
title: Domain Ownership — Elysium Automotive Foundry
status: Phase 0 deliverable, signed 2026-07-17
owner: skill 00 (program-orchestrator)
audited_by: skill 01 (repository-archaeology)
git_head: c9028dc
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Domain Ownership — Elysium Automotive Foundry

> **Status:** Phase 0 deliverable. The
> **ownership map** of every domain aggregate
> in the Foundry platform. Every domain
> entity has **exactly one** owning skill;
> two skills editing the same entity is a
> contract violation (per skill 00 section
> 11 failure mode "two skills attempt to own
> the same aggregate"). The map is the
> authoritative input to the dependency map
> (`dependency-map.md`), the implementation
> roadmap (`implementation-roadmap.md`), and
> the risk register (`risk-register.md`).
>
> **Source documents:** the ontology (skill
> 03), the forensic audit (`current-state-audit.md`),
> the target architecture (`target-architecture.md`),
> the standards (`.ai/STANDARDS.md`), and the
> meta-contract (`.ai/AGENTS.md` sections 6,
> 7, 8, 9, 22, 23, 24).

---

## 0. How to read this document

The document is split into four parts:

1. **The aggregate inventory** (sections
   1–6) — every domain aggregate, its
   owner, the reading skills, and the
   migration owner.
2. **The cross-skill contracts** (section
   7) — for every cross-skill edge, the
   data shape, the schema version, the
   auth requirement, the error envelope,
   the retry classification, and the
   correlation ID propagation.
3. **The arbitration rules** (section 8)
   — what happens when two skills want
   the same field, the same aggregate, or
   the same edge.
4. **The ADR registry** (section 9) —
   every arbitration decision + every
   migration ADR.

The skill numbers are stable (00–15). A
skill name like "skill 03 (ontology)" is
shorthand for `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.

A "contract violation" is anything that
breaks the rules in this document. The
verifier (skill 14) is the gate.

---

## 1. The domain primitives (skill 03 owns)

The domain primitives are the
**immutable building blocks** every
aggregate composes. They are owned by
skill 03 (ontology) because every other
skill reads them. A primitive that is
not in this list is a smell.

| Primitive | Owner | Reading skills | Schema version |
|---|---|---|---|
| `StronglyTypedId<T>` (UUID value class wrapper) | skill 03 | every skill | v1 |
| `Money` (BigDecimal-backed, currency-tagged) | skill 03 | skill 09, skill 10 | v1 |
| `Unit` (SI base + derived; locale-aware display) | skill 03 | skill 03, skill 04, skill 06, skill 07 | v1 |
| `CoordinateSystem` (right-handed Y-up, right-handed Z-up, right-handed X-up, custom-with-ADR) | skill 03 | skill 06, skill 07 | v1 |
| `EngineeringFact<T>` (typed fact with provenance + verification status + source + jurisdiction) | skill 03 | every skill | v1 |
| `VerificationStatus` (enum: `OEM_VERIFIED`, `REGULATORY_VERIFIED`, `LAB_VERIFIED`, `ENGINEER_REVIEWED`, `COMMUNITY_CORROBORATED`, `AI_INFERRED`, `UNKNOWN`) | skill 03 | every skill | v1 |
| `Source` (typed reference: OEM doc, reg filing, lab report, signed review, public corpus, AI inference) | skill 03 | every skill | v1 |
| `Jurisdiction` (UNECE, FMVSS, GB, JIS, regional EU, regional US, regional CN — never free-form) | skill 03 | skill 13, skill 04, skill 11 | v1 |
| `Timestamp` (HLC-aware, monotonic) | skill 03 | every skill | v1 |
| `Locale` (BCP-47, with display fall-back chain) | skill 03 | skill 11 | v1 |
| `ContentHash` (SHA-256 content address) | skill 03 | every skill | v1 |
| `Signature` (asymmetric, post-quantum-ready) | skill 03 + skill 12 | skill 09, skill 13, skill 12 | v1 |
| `FoundryError` (typed error envelope, per `.ai/STANDARDS.md` section 7) | skill 00 + skill 14 | every skill | v1 |
| `VehicleRepresentationLevel` (enum: `OEM_EXACT`, `OEM_PARTIAL`, `PARAMETRIC_FUNCTIONAL`, `CONCEPTUAL`, `VISUAL_ONLY`) | skill 03 | every skill that touches a vehicle | v1 |
| `InterfacePort` (28 kinds, per skill 03 section 15) | skill 03 | skill 04, skill 06, skill 07, skill 10 | v1 |
| `PowerTrainCategory` (8 categories, per skill 03 section 13) | skill 03 | skill 04, skill 07, skill 10 | v1 |

A primitive change is a **migration**.
The migration owner is skill 03. The
migration is **content-addressed** (the
new primitive is a new content hash) +
**signed** (a signature on the migration
ADR) + **versioned** (the new schema
version is incremented) + **backward-
compatible** (the old primitive is
deprecated, not deleted) per
`.ai/AGENTS.md` section 12.

---

## 2. The product aggregates (skill 02 + skill 03 own)

The product aggregates are the
**user-facing domain entities** the
Foundry is built around. Each is owned
by exactly one skill; the orchestrator
(skill 00) arbitrates when two skills
want to add fields.

### 2.1 `Project` (skill 03 owns)

The top-level container. A `Project`
has a `Brand`, a name, a description,
a list of `VehicleProgram` references,
a list of `Contributor` references, a
default `Locale`, a default
`Jurisdiction`, an audit-trail
reference, and a status (`DRAFT` /
`ACTIVE` / `ARCHIVED`).

| Field | Type | Owner | Notes |
|---|---|---|---|
| `id` | `ProjectId` (UUID) | skill 03 | value class |
| `name` | `NonEmptyString` | skill 03 | locale-aware |
| `brand` | `BrandId?` | skill 03 | nullable when no brand yet |
| `programs` | `Set<VehicleProgramId>` | skill 03 | append-only |
| `contributors` | `Set<ContributorId>` | skill 03 | append-only |
| `status` | `ProjectStatus` | skill 03 | enum |
| `createdAt` | `Timestamp` | skill 03 | HLC |
| `auditTrail` | `AuditTrailId` | skill 09 | reference, not embedded |

**Reading skills:** every skill that
touches a project (skill 02, 04, 05,
06, 07, 09, 10, 11, 13, 14).
**Migration owner:** skill 03.

### 2.2 `VehicleProgram` (skill 03 owns)

A vehicle family under a `Project`. A
`VehicleProgram` is "the Ford Mustang
line" or "the Hyundai Accent line"; a
`VehicleRevision` (section 2.3) is one
specific year + trim + region of that
line.

### 2.3 `VehicleRevision` (skill 03 owns)

One specific vehicle configuration.
The `VehicleRevision` is the unit of
**immutability**: a revision, once
signed, cannot mutate. A change is a
new revision that points to its
predecessor. The chain is content-
addressed + signed.

### 2.4 `Variant` (skill 03 owns)

A derived configuration from a
`VehicleRevision`. A "2024 Mustang GT
Premium with the Performance Package"
is a `Variant` of the "2024 Mustang"
`VehicleRevision`. The `Variant` is
fully derived; the storage is
content-addressed; the resolution is
deterministic.

### 2.5 `Compatibility` (skill 03 owns)

The constraint graph between parts.
A "this engine fits this transmission"
relationship is a `Compatibility`
record. The graph is typed (per skill
03 section 11); the `Compatibility`
engine (skill 04) is the only
component that may validate the
graph; the `Compatibility` is the
read-side cache for the engine.

### 2.6 `Subsystem` (skill 03 owns)

A logical grouping of parts inside a
`VehicleDefinition`. Examples: the
`Powertrain.Subsystem`, the
`BodyInWhite.Subsystem`, the
`ThermalManagement.Subsystem`. A
`Subsystem` is owned by skill 03; the
subsystem's parts are owned by skill
03; the subsystem's compatibility
graph is owned by skill 04.

### 2.7 `Assembly` (skill 03 owns)

A physical relationship between parts
in a `VehicleDefinition`. An
"engine is bolted to the subframe via
these four bolts" is an `Assembly`.
The `Assembly` is the read-side cache
for the 3D pipeline (skill 06) and the
digital twin (skill 07).

### 2.8 `Part` (skill 03 owns)

The atomic unit of engineering. A
`Part` has a `PartDefinitionId`, a
`name`, an `EngineeringFact<Geometry>`
field, an `EngineeringFact<Material>`
field, an `EngineeringFact<Mass>`
field, an `EngineeringFact<Cost>`
field, an `InterfacePort` set, a
`PartCategory`, a `Locale`-aware
`DisplayName`, and a content hash.

### 2.9 `Brand` (skill 03 owns)

The trademark identity. A `Brand` is
owned by a `Project`; the `Brand`
does not outlive the `Project` (a
"spin-off" is a new `Project` +
`Brand` with an ADR-recorded
provenance pointer). The `Brand`
**never** holds engineering data; the
brand holds the trademark + the
visual identity + the voice.

### 2.10 `Contributor` (skill 09 owns, with skill 03 co-read)

A human or an organization that has
contributed to a project. The
`Contributor` is owned by skill 09
because the contributor's identity
is bound to the provenance ledger
+ the royalty contracts. Skill 03
reads the contributor's display
name + locale + public handle, but
not the contributor's private
data (the private data is in skill
09's encrypted store).

### 2.11 `EngineeringArtifact` (skill 03 owns)

A typed reference to a content-
addressed engineering artifact. A
glTF, a STEP, a USD, a PDF datasheet,
an OCR'd image, a CAD drawing, a
simulated stress report — all are
`EngineeringArtifact` references.
The artifact's bytes are in the
content-addressed store (skill 08);
the reference is the typed pointer.
The `EngineeringArtifact` is the
read-side cache for skill 06 (3D) +
skill 07 (twin) + skill 13
(regulatory).

### 2.12 `ProvenanceRecord` (skill 09 owns)

A signed event in the audit trail
that records "this fact came from
this source at this time and was
verified by this reviewer". Every
`EngineeringFact<T>` has a
`ProvenanceRecord` reference; the
`ProvenanceRecord` is append-only +
content-addressed + signed. The
owner is skill 09 because the
ledger is the audit trail's source
of truth.

---

## 3. The vehicle-engineering aggregates (skill 04 owns)

The vehicle-engineering aggregates are
the typed outputs of the deterministic
vehicle compiler. They are owned by
skill 04 (DSL compiler) because the
compiler is the only component that may
produce them.

### 3.1 `CompilationRequest` (skill 04 owns)

A user's request to compile a `VehicleRevision`.
The request is idempotent (per
`.ai/AGENTS.md` section 24); the
request's `CompilationRequestId` is
the idempotency key.

### 3.2 `CompilationResult` (skill 04 owns)

The output of the compiler. A
`CompilationResult` is
**content-addressed** + **signed** +
**versioned**; the same input produces
the same `CompilationResult` (the
compiler is deterministic per skill
04). A `CompilationResult` is the
contract between skill 04 and skill
06 + skill 07 + skill 09 + skill 10
— every cross-skill edge reads the
`CompilationResult`, never the raw
DSL source.

### 3.3 `CompilationReport` (skill 04 owns)

The user-facing report. A
`CompilationReport` is a
`CompilationResult` plus a
localized summary, a list of
`CompilationDiagnostic` (warnings,
errors, hints), a list of
`CompilationSuggestion`, and a
"go to definition" map for the IDE.

### 3.4 `CompilationDiagnostic` (skill 04 owns)

A typed diagnostic. The diagnostic
has a `DiagnosticCode`
(`VEHICLE_DEFINITION_INVALID`,
`COMPATIBILITY_CONSTRAINT_VIOLATION`,
`ARTIFACT_INTEGRITY_FAILURE`,
`PROVENANCE_INCOMPLETE`, etc.), a
`Severity` (`ERROR` / `WARNING` /
`HINT`), a localized
`UserMessage`, a
`MachineDetails` payload (per
`.ai/STANDARDS.md` section 7), and
a `Span` (file, line, column).

### 3.5 `CompiledVehicleSpec` (skill 04 owns)

The typed output of the compiler. The
spec is the single source of truth for
"what is this vehicle" at the
engineering level. The spec is
consumed by skill 06 (3D) + skill
07 (twin) + skill 09 (IP/provenance)
+ skill 10 (marketplace). The
spec is content-addressed; the
spec's content hash is the
canonical id of the spec.

### 3.6 `ConstraintGraph` (skill 04 owns)

The compatibility graph between
parts. The graph is a typed DAG
with typed edges (`MECHANICAL`,
`ELECTRICAL`, `FLUID`, `THERMAL`,
`DATA`, `SAFETY`). The graph is the
input to the compatibility
validator (skill 04); the graph's
validation report is the
`CompilationReport`.

### 3.7 `CompiledPart` (skill 04 owns)

The compiler's view of a `Part`. A
`CompiledPart` is a `Part` +
its resolved `InterfacePort`
set + its `ConstraintGraph`
edges + its `EngineeringArtifact`
references.

### 3.8 `CompiledAssembly` (skill 04 owns)

The compiler's view of an
`Assembly`. A `CompiledAssembly`
is an `Assembly` + its resolved
transforms (per the
`CoordinateSystem`) + its
resolved `InterfacePort`
connections + its
`EngineeringArtifact`
references.

### 3.9 `VehicleClassManifest` (skill 04 owns)

The compiler's view of a
`VehicleDefinition`'s metadata:
its `PowerTrainCategory`, its
`VehicleRepresentationLevel`,
its applicable `Jurisdiction`
set, its `Variant` set, its
`Compatibility` summary.

---

## 4. The 3D / digital-twin aggregates (skill 06 + skill 07 own)

The 3D / digital-twin aggregates are
the typed outputs of the 3D pipeline
and the digital twin. They are owned by
skill 06 (3D pipeline) for the asset
side and skill 07 (digital twin) for
the runtime side; the digital twin
reads the 3D pipeline's outputs.

### 4.1 `Canonical3DAsset` (skill 06 owns)

A signed, content-addressed 3D
asset. The asset has a `ContentHash`
(SHA-256 of the bytes), a
`Signature` (per skill 12), a
`Format` (`GLB` / `GLTF` /
`USD` / `USDZ` / `STEP` /
`IGES` / `FBX`), a `LOD`
distribution (LOD0..LODn + a
collision proxy), a
`CoordinateSystem` reference, a
`Unit` reference, a set of
`EngineeringArtifact` references,
a `ProvenanceRecord` reference, and
a `VehicleRepresentationLevel`
declaration.

### 4.2 `AssetValidationReport` (skill 06 owns)

The signed report from the asset
validator. The report covers
manifold, units, coordinate
system, file size, embedded
scripts, provenance coverage,
and `VehicleRepresentationLevel`
correctness. A positive report is
a prerequisite for the asset to
enter the canonical store; a
negative report is a hard
rejection.

### 4.3 `SceneManifest` (skill 06 owns)

The typed manifest of a 3D scene.
The manifest is a list of
`Canonical3DAsset` references +
their `LOD` selection + their
`Transform` + their
`CoordinateSystem` + their
parent-child relationship.
The manifest is signed; the
manifest's content hash is the
canonical id.

### 4.4 `PartInstanceGraph` (skill 07 owns)

The runtime graph of part instances
in the digital twin. The graph is
the live representation of the
vehicle: the user can select a
part, isolate it, view its
diagnostics, see its
`EngineeringArtifact` references,
and trigger a `RepairAction`.

### 4.5 `Diagnostic` (skill 07 owns)

A typed diagnostic event in the
twin. The `Diagnostic` is a
typed `DTC` reference (per
skill 07 section 11), a
`Symptom` reference, a
`Hypothesis` list, a
`TestProcedure` list, a
`RepairAction` list, a
`TelemetrySnapshot` reference,
and a `VerificationStatus`.

### 4.6 `Fault` (skill 07 owns)

A typed fault model. The
`Fault` is a `DTC` +
`AffectedComponent` +
`TriggerCondition` +
`ObservableSymptom` +
`Severity` + `SafetyImpact`
+ `RepetitionRate`.

### 4.7 `RepairAction` (skill 07 owns)

A typed repair procedure. The
`RepairAction` is a
`ProcedureStep` list
(13 elements per skill 07),
a `Tool` list, a
`TimeEstimate`, a
`PartReplacement` list, a
`SafetyPrecaution` list, a
`VerificationStep` list,
and a `PostRepairTest`.

### 4.8 `TelemetryStream` (skill 07 owns)

A live stream of vehicle telemetry.
The stream is the input to the
diagnostic engine. The stream
is signed + timestamped (HLC)
+ filtered (PII redaction per
`.ai/AGENTS.md` section 14).

### 4.9 `SelectionState` (skill 07 owns)

The user's selection in the
twin. The `SelectionState` is
the read-side state for the UI
+ the input to the
`Diagnostic` engine.

---

## 5. The commercial aggregates (skill 09 + skill 10 own)

The commercial aggregates are the
typed outputs of the IP/provenance
engine and the marketplace. They are
owned by skill 09 for the IP side
and skill 10 for the marketplace
side; the marketplace reads the IP
side.

### 5.1 `Authorship` (skill 09 owns)

A typed authorship claim. The
`Authorship` is a `ContributorId`
+ a `Contribution` reference +
a `VerificationStatus` (the
authorship was either signed by
the contributor or rebutted by
the contributor or has expired)
+ a `Witness` list (other
contributors who signed).

### 5.2 `Contribution` (skill 09 owns)

A typed contribution event. The
`Contribution` is a
`ContributorId` + a
`ProjectId` (or
`VehicleProgramId` or
`VehicleRevisionId` or
`PartId` — the target of the
contribution) + a
`ContributionType` (one of
`CREATION`, `MODIFICATION`,
`REVIEW`, `DERIVATION`,
`PORT`, `VALIDATION`,
`DOCUMENTATION`, `TESTING`,
`MANUFACTURING_INPUT`) + a
`Timestamp` + a `Signature` +
a `ProvenanceRecord` reference.

### 5.3 `RoyaltyContract` (skill 09 owns)

A typed royalty contract. The
`RoyaltyContract` is a
`ProjectId` (the project the
contract covers) + a
`ContributorId` set (the
parties) + a
`RoyaltyRule` set (the rules
that govern the calculation) +
a `Territory` set (the
jurisdictions the contract
covers) + a `Term`
(start, end, renewal) + a
`Status` (`DRAFT` / `ACTIVE` /
`SUSPENDED` / `TERMINATED`) +
a `Signature` set + a
`CounterSignature` set.

### 5.4 `License` (skill 09 owns)

A per-artifact license. The
`License` is a `LicenseType`
(one of `CC0`, `CC-BY`,
`CC-BY-SA`, `CC-BY-NC`,
`CC-BY-NC-SA`, `ELV-PERSONAL`,
`ELV-COMMERCIAL`,
`ELV-INCUBATED`, `PROPRIETARY`)
+ a `LicenseTerms` (the typed
terms) + a `Licensee` reference
+ a `Term` + a `Territory` set
+ a `Signature` set.

### 5.5 `Listing` (skill 10 owns)

A marketplace listing. The
`Listing` is a `ProjectId` or
`VehicleRevisionId` or
`PartId` (the listing target)
+ a `Price` (Money + currency)
+ a `License` reference + a
`Visibility` (`PUBLIC` /
`PRIVATE` / `INVITE_ONLY`) +
a `Status` (`DRAFT` /
`LISTED` / `SOLD` /
`DELISTED`).

### 5.6 `Order` (skill 10 owns)

A purchase order. The `Order`
is a `ListingId` + a `BuyerId`
+ a `Price` + a `License`
reference + a `PaymentReference`
+ a `Status` (`PENDING` /
`PAID` / `SHIPPED` /
`DELIVERED` / `CANCELLED` /
`REFUNDED`).

### 5.7 `Escrow` (skill 10 owns)

A typed escrow. The `Escrow`
is an `OrderId` + a `Price` +
a `Status` (`HELD` /
`RELEASED` / `REFUNDED`) +
a `Trigger` (the
condition that releases the
escrow).

### 5.8 `Settlement` (skill 09 + skill 10 co-own)

A typed settlement event. The
`Settlement` is an `OrderId` +
a `RoyaltyContractId` + a
`RoyaltyRule` set + a
`Distribution` list (the
typed breakdown of who gets
what) + a `Money` field
(BigDecimal) + a `Signature`
+ a `Timestamp` + a
`ProvenanceRecord` reference.
Co-ownership is recorded here:
the settlement's distribution
is owned by skill 09 (the
royalty engine is the only
component that may compute
the distribution); the
settlement's order + escrow
fields are owned by skill 10
(the marketplace is the only
component that may produce
the order + the escrow).

### 5.9 `RFQ` (skill 10 owns)

A request for quote. The `RFQ`
is a `PartId` (the part the
buyer needs) + a `Quantity` +
a `DeliveryDate` + a
`Qualification` set (the
qualifications the supplier
must have) + a
`Visibility` +
a `Status`.

### 5.10 `Offer` (skill 10 owns)

A supplier's offer. The `Offer`
is an `RFQId` + a
`SupplierId` + a `Price` +
a `LeadTime` + a
`QualificationEvidence` set +
a `Status`.

### 5.11 `SupplierQualification` (skill 10 owns, with skill 13 co-read)

A typed supplier qualification.
The qualification is a
`SupplierId` + a
`QualificationType` (one of
`ISO_9001`, `IATF_16949`,
`AS9100`, `NADCAP`,
`OEM_APPROVED`, `REGULATORY_APPROVED`)
+ an `Evidence` set +
a `VerifierId` set +
a `Term` + a `Status`.

### 5.12 `Disclosure` (skill 10 owns, with skill 12 co-read)

A controlled disclosure of
proprietary data. The
disclosure is a `SupplierId`
+ a `BuyerId` + a
`NDARef` + a `DataSet` (the
data being disclosed) +
a `Term` + a `Visibility` +
a `RevocationPolicy` +
a `Signature` set. The
disclosure is encrypted at
rest + in transit; the
disclosure is revocable.

---

## 6. The platform aggregates (skill 08 + skill 12 + skill 13 + skill 15 own)

The platform aggregates are the
infrastructure-level entities that
every other skill relies on.

### 6.1 `EventStream` (skill 08 owns)

The platform's event bus. The
`EventStream` is a typed topic
+ a schema version + an auth
requirement + a retention
policy + a dead-letter policy.

### 6.2 `OutboxRecord` (skill 08 owns)

The transactional outbox's
record. The `OutboxRecord` is
an `AggregateId` + a
`MutationType` + a `Payload`
+ a `CorrelationId` + a
`Status` + a `RetryCount` +
a `LastAttemptAt`.

### 6.3 `Migration` (skill 08 owns, with skill 03 co-read)

A schema migration. The
`Migration` is a `FromVersion`
+ a `ToVersion` + a
`MigrationScript` (forward)
+ a `RollbackScript`
(reverse) + a `ContentHash`
+ a `Signature` + a
`TestEvidence` (the
re-runnable test that proves
the migration is idempotent).

### 6.4 `AuditTrail` (skill 09 owns, with skill 12 co-read)

The signed audit trail. The
`AuditTrail` is a
`ProjectId` (or a
`VehicleRevisionId` or a
`RoyaltyContractId` — the
scope) + a `SignedEvent` list
(append-only) + a
`RetentionPolicy`.

### 6.5 `ComplianceReport` (skill 13 owns, with skill 12 co-read)

A regulatory compliance report.
The `ComplianceReport` is a
`Jurisdiction` + a
`Regulation` reference (per
skill 13 section 4) + an
`Evidence` set + a
`VerifierId` set + a
`Status`.

### 6.6 `HomologationPackage` (skill 13 owns)

A regulatory homologation
package. The package is a
`VehicleRevisionId` (or a
`VehicleProgramId`) + a
`Jurisdiction` set + a
`ComplianceReport` set +
a `TestProtocol` set +
a `Signature` set.

### 6.7 `ThreatModel` (skill 12 owns)

The platform's threat model.
The `ThreatModel` is a
`Surface` set (each with a
STRIDE analysis) + a
`TrustBoundary` set +
a `Mitigation` set + a
`ResidualRisk` set +
a `ReviewDate`.

### 6.8 `SecurityFinding` (skill 12 owns, with skill 14 co-read)

A security finding. The
finding is a `Surface` +
a `Severity` +
a `CVSS` (or equivalent) +
an `Evidence` + a
`Mitigation` + a
`Status` + a
`DiscoveredBy` (the
verifier + the date).

### 6.9 `Incident` (skill 15 owns, with skill 12 co-read)

A production incident. The
`Incident` is a
`Severity` + a
`Surface` + a `Timeline`
+ a `RootCause` +
a `Mitigation` + a
`Postmortem` + a
`Status`.

### 6.10 `BackupRecord` (skill 15 owns)

A backup. The `BackupRecord`
is a `Scope` (the data
covered) + a `Timestamp` +
a `ContentHash` +
a `RestorationTest` (the
test that proves the
backup is restorable) +
a `RetentionPolicy`.

### 6.11 `Deployment` (skill 15 owns, with skill 12 co-read)

A production deployment. The
`Deployment` is a
`Version` + a
`Strategy` (`CANARY` /
`STAGED` / `BLUE_GREEN` /
`FORWARD_RECOVERY`) +
a `HealthCheck` set +
a `RollbackPlan` +
a `Status`.

---

## 7. Cross-skill contracts

Every cross-skill edge is documented
here. The table format is fixed (per
skill 00 section 10): **data shape**,
**schema version**, **auth**,
**error envelope**, **retry**,
**correlation**.

### 7.1 Reading the table

- **Edge** — the producer → consumer
  edge.
- **Data shape** — the typed value
  that crosses the edge.
- **Schema version** — the version of
  the shape, per `.ai/AGENTS.md`
  section 12.
- **Auth** — the authentication +
  authorization needed to consume
  the edge.
- **Error envelope** — the typed
  `FoundryError` (per `.ai/STANDARDS.md`
  section 7) the edge may return.
- **Retry** — the retry classification
  (per `.ai/AGENTS.md` section 24.4).
- **Correlation** — whether the
  correlation ID (per `.ai/AGENTS.md`
  section 24.3) is propagated.

### 7.2 The cross-skill edge table

| Edge (producer → consumer) | Data shape | Schema version | Auth | Error envelope | Retry | Correlation |
|---|---|---|---|---|---|---|
| skill 03 → skill 04 | `OntologySnapshot` | v1 | internal | `VehicleDefinitionInvalid`, `SchemaVersionIncompatible` | `non_retryable` | yes |
| skill 03 → skill 06 | `PartDefinition` + `InterfacePortSet` + `CoordinateSystem` | v1 | internal | `VehicleDefinitionInvalid` | `non_retryable` | yes |
| skill 03 → skill 07 | `PartDefinition` + `Subsystem` + `CompatibilityGraph` | v1 | internal | `VehicleDefinitionInvalid` | `non_retryable` | yes |
| skill 03 → skill 11 | `OntologySnapshot` (mobile-shaped) | v1 | internal | `SchemaVersionIncompatible` | `retryable_backoff` | yes |
| skill 04 → skill 06 | `CompiledVehicleSpec` (content-addressed) | v1 | internal | `ArtifactIntegrityFailure`, `ProvenanceIncomplete` | `non_retryable` | yes |
| skill 04 → skill 07 | `CompiledVehicleSpec` + `ConstraintGraph` | v1 | internal | `ArtifactIntegrityFailure` | `non_retryable` | yes |
| skill 04 → skill 09 | `CompiledVehicleSpec` + `AuthorshipMarker` | v1 | signed | `ArtifactIntegrityFailure`, `SignatureInvalid` | `non_retryable` | yes |
| skill 04 → skill 10 | `CompiledVehicleSpec` + `VehicleRepresentationLevel` | v1 | signed | `ArtifactIntegrityFailure`, `VehicleRepresentationLevelIneligible` | `non_retryable` | yes |
| skill 05 → skill 04 | `AIProposal<DslMutation>` (typed) | v1 | signed (council) | `SchemaVersionIncompatible`, `ProposalRejected` | `non_retryable` | yes |
| skill 05 → skill 06 | `AIProposal<AssetMetadata>` (typed) | v1 | signed (council) | `SchemaVersionIncompatible` | `non_retryable` | yes |
| skill 05 → skill 09 | `AIProposal<AuthorshipClaim>` (typed) | v1 | signed (council) + counter-signed by contributor | `AuthorshipRejected` | `non_retryable` | yes |
| skill 06 → skill 07 | `Canonical3DAsset` + `SceneManifest` + `AssetValidationReport` | v1 | internal | `ArtifactIntegrityFailure`, `AssetValidationFailed` | `non_retryable` | yes |
| skill 06 → skill 09 | `Canonical3DAsset` + `AssetValidationReport` + `ProvenanceRecord` | v1 | internal | `ArtifactIntegrityFailure`, `ProvenanceIncomplete` | `non_retryable` | yes |
| skill 06 → skill 10 | `Canonical3DAsset` reference (the listing) | v1 | internal | `ArtifactIntegrityFailure` | `non_retryable` | yes |
| skill 06 → skill 11 | `LOD` set + `SceneManifest` + `TextureSet` | v1 | internal | `ArtifactIntegrityFailure`, `AssetLimitExceeded` | `retryable_backoff` | yes |
| skill 07 → skill 09 | `Diagnostic` + `Fault` + `RepairAction` | v1 | internal | `ProvenanceIncomplete` | `non_retryable` | yes |
| skill 07 → skill 11 | `TelemetryStream` (filtered, PII-redacted) | v1 | internal | `PiiRedactionFailed`, `UnauthorizedTelemetryAccess` | `retryable_backoff` | yes |
| skill 08 → every skill | `EventStream` (typed topic) | v1 | mTLS | `SchemaVersionIncompatible`, `OutboxLag` | `retryable_idempotent_only` | yes |
| skill 09 → skill 04 | `RoyaltyContract` reference (during compilation) | v1 | internal | `ContractNotActive` | `non_retryable` | yes |
| skill 09 → skill 10 | `RoyaltyContract` + `License` + `Settlement` | v1 | internal | `ContractNotActive`, `RoyaltyCalculationRejected`, `LicenseIncompatible` | `non_retryable` | yes |
| skill 10 → skill 11 | `Listing` + `Order` + `Escrow` | v1 | internal | `UnauthorizedMarketplaceAccess`, `OrderRejected` | `retryable_idempotent_only` | yes |
| skill 11 → skill 08 | `UserEvent` (typed) | v1 | internal | `OutboxLag` | `retryable_backoff` | yes |
| skill 11 → skill 09 | `CatalogQuery` (read) | v1 | OIDC | `UnauthorizedProjectAccess` | `retryable_backoff` | yes |
| skill 11 → skill 12 | `AuthEvent` (typed) | v1 | OIDC | `AuthEventInvalid` | `retryable_backoff` | yes |
| skill 12 → every skill | `SecurityFinding` + `ThreatModel` (read) | v1 | internal | n/a (read) | n/a | yes |
| skill 13 → every skill | `ComplianceReport` + `HomologationPackage` (read) | v1 | signed | `ComplianceReportInvalid` | `non_retryable` | yes |
| skill 14 → skill 00 | `VerificationReport` (the gate) | v1 | internal | n/a (read) | n/a | yes |
| skill 14 → skill 15 | `GateStatus` (the green / red status) | v1 | internal | `GateStatusInvalid` | `retryable_backoff` | yes |
| skill 15 → every skill | `Deployment` + `SloSnapshot` + `Incident` (read) | v1 | internal | n/a (read) | n/a | yes |

### 7.3 The schema-versioning rule

A shape's schema version is
**incremented** on every backward-
incompatible change. A backward-
compatible change (adding a field
with a default, deprecating a
field) does **not** increment the
version. The version is part of
the typed envelope.

A consumer that sees a shape
with a version it does not
recognize returns a
`SchemaVersionIncompatible`
error; the error is
`non_retryable` (per section
24.4); the orchestrator (skill
00) coordinates the upgrade.

A producer that produces a
shape with a version the
consumer does not recognize is
a **P0 contract violation**;
the verifier (skill 14) blocks
the release.

### 7.4 The auth-by-edge rule

The auth column is the
**minimum** auth required.
A stricter auth is permitted
(e.g. a read edge that requires
`OIDC` may be served over
`mTLS` in production); a
looser auth is not.

| Auth level | When |
|---|---|
| `internal` (in-process) | both producer + consumer run in the same module |
| `mTLS` (mutual TLS) | producer + consumer are different services in the same trust zone |
| `OIDC` (OpenID Connect + OAuth 2.1) | consumer is the mobile app or a third party |
| `signed` (asymmetric signature) | the consumer needs to verify the producer's authorship (e.g. an `AIProposal`) |

A read edge from a public actor
(e.g. a public `Listing`) uses
`OIDC` + a `Visibility`
filter; the filter is part of
the auth contract.

### 7.5 The error-envelope contract

Every error envelope is a
typed `FoundryError` (per
`.ai/STANDARDS.md` section 7).
The envelope is:

```json
{
  "code": "VEHICLE_DEFINITION_INVALID",
  "userMessage": { "en": "...", "es": "...", "ja": "..." },
  "machineDetails": {
    "field": "powertrain.battery.capacity",
    "reason": "capacity must be positive",
    "provenance": { ... },
    "correlationId": "..."
  },
  "retryClassification": "non_retryable",
  "schemaVersion": "v1"
}
```

A free-form string is never
the value. A `Map<String, Any>`
is never the value. A `null`
is never the value where a
typed value is required.

---

## 8. Arbitration rules

### 8.1 Two skills want to add a field to the same aggregate

The aggregate's owner decides.
The decision is filed as an ADR
(per `.ai/AGENTS.md` section
17). A skill that disagrees
files a counter-ADR; the
orchestrator (skill 00) hosts a
council review; the council's
decision is binding.

A field that is added to an
aggregate without the owner's
approval is a **P0 contract
violation**; the verifier
(skill 14) rejects the PR.

### 8.2 Two skills want to add a new aggregate

The orchestrator decides which
skill owns the new aggregate.
The decision is filed as an
ADR. The new aggregate's owner
writes the migration; the
migration is signed + versioned
+ content-addressed.

A new aggregate without an
owner is a **contract violation**;
the orchestrator blocks the
release.

### 8.3 Two skills want to consume the same cross-skill edge

The producer decides the
contract. The producer is
bound by the rule in section
7.3 (the schema-versioning
rule) + section 7.4 (the
auth-by-edge rule) + section
7.5 (the error-envelope
contract). A consumer that
cannot honor the contract is
out of compliance; the
orchestrator arbitrates.

### 8.4 Two skills produce the same cross-skill edge

The aggregate's owner produces
the edge. The other skill is a
reader; the other skill's
contribution is mediated
through the owner.

A skill that produces an edge
without being the aggregate's
owner is a **P0 contract
violation**; the verifier
rejects the PR.

### 8.5 A skill wants to consume an aggregate without an explicit reader relationship

The aggregate's owner arbitrates.
A blanket "every skill reads
everything" policy is
forbidden; reading access is
explicit + narrow.

### 8.6 A skill wants to mutate an aggregate it does not own

**Forbidden.** A skill that
needs to mutate an aggregate
files a request with the
aggregate's owner; the
request is a typed
`MutationRequest`; the owner
reviews; the owner decides.
A direct mutation is a
**P0 contract violation**.

---

## 9. ADR registry

The ADR registry is the
log of every arbitration
decision + every migration
ADR. The registry is append-
only; an ADR is never edited
after the decision is signed;
a change is a new ADR.

| ADR | Title | Status | Owner | Date |
|---|---|---|---|---|
| ADR-0001 | `Money` is `BigDecimal`-backed, currency-tagged, never `Double` / `Float` | Active | skill 03 | 2026-07-17 |
| ADR-0002 | `VehicleRepresentationLevel` is `OEM_EXACT` / `OEM_PARTIAL` / `PARAMETRIC_FUNCTIONAL` / `CONCEPTUAL` / `VISUAL_ONLY`; transitions are append-only + signed | Active | skill 03 | 2026-07-17 |
| ADR-0003 | `VerificationStatus` cannot transition `AI_INFERRED → OEM_VERIFIED` / `REGULATORY_VERIFIED` / `LAB_VERIFIED` without a signed counter-signature | Active | skill 03 + skill 09 | 2026-07-17 |
| ADR-0004 | `RoyaltyContract` must be `ACTIVE` before a `Settlement` is computed; the engine rejects with `ContractNotActive` otherwise | Active | skill 09 | 2026-07-17 |
| ADR-0005 | The Elysium 5% royalty is a **configurable** `RoyaltyRule`; not a hardcoded constant; the rule applies only to projects that have accepted an `ELV-INCUBATED` license | Active | skill 09 | 2026-07-17 |
| ADR-0006 | `VehicleDefinition` and its derivatives are `append-only`; a mutation is a new `VehicleRevision` that points to its predecessor | Active | skill 03 | 2026-07-17 |
| ADR-0007 | `OutboxRecord` is the only path to a `Mutation`; the outbox is reliable; the consumer is idempotent | Active | skill 08 | 2026-07-17 |
| ADR-0008 | `FoundryError` is the typed error envelope; the `userMessage` is localized; the `machineDetails` is structured; the `retryClassification` is part of the envelope | Active | skill 00 + skill 14 | 2026-07-17 |
| ADR-0009 | `CorrelationId` is generated at the entry point; propagated through every downstream call; included in every audit-trail event; returned in the response headers | Active | skill 00 + skill 15 | 2026-07-17 |
| ADR-0010 | The `AI council` is a multi-agent deliberation; the model produces a typed `AIProposal`; the deterministic engine + a human review apply the proposal; the model has no path to the database / catalog / audit trail / royalty engine / regulatory submission / safety gate | Active | skill 05 + skill 14 | 2026-07-17 |
| ADR-0011 | `VISUAL_ONLY` and `CONCEPTUAL` vehicles are not eligible for a `Settlement`; the royalty engine rejects with `VehicleRepresentationLevelIneligible` | Active | skill 09 + skill 10 | 2026-07-17 |
| ADR-0012 | `Canonical3DAsset` validation is a hard prerequisite for the asset to enter the canonical store; the validator checks manifold + units + coordinate system + file size + no-embedded-scripts + provenance coverage | Active | skill 06 | 2026-07-17 |
| ADR-0013 | `EngineeringArtifact` references are content-addressed + signed; the bytes are in the content-addressed store; the reference is the typed pointer | Active | skill 03 + skill 08 | 2026-07-17 |
| ADR-0014 | The cross-skill edge auth-by-edge rule (per section 7.4) is the minimum auth; a stricter auth is permitted; a looser auth is forbidden | Active | skill 00 + skill 12 | 2026-07-17 |
| ADR-0015 | The cross-skill edge schema-versioning rule (per section 7.3) increments the version on every backward-incompatible change; a backward-compatible change does not | Active | skill 00 + skill 14 | 2026-07-17 |
| ADR-0016 | A `Migration` is forward + rollback + content-addressed + signed + tested (the test re-runs the migration on a fixture and asserts idempotence) | Active | skill 08 + skill 03 | 2026-07-17 |
| ADR-0017 | A `Settlement` is co-owned by skill 09 (the distribution) + skill 10 (the order + escrow); the co-ownership is recorded in section 5.8; the verifier (skill 14) blocks a PR that violates the co-ownership | Active | skill 09 + skill 10 | 2026-07-17 |
| ADR-0018 | A `SupplierQualification` is co-owned by skill 10 (the qualification state) + skill 13 (the regulatory evidence); a qualification without regulatory evidence is a P1 finding | Active | skill 10 + skill 13 | 2026-07-17 |
| ADR-0019 | A `Disclosure` is co-owned by skill 10 (the disclosure state) + skill 12 (the encryption + revocation); a disclosure without encryption is a P0 incident | Active | skill 10 + skill 12 | 2026-07-17 |
| ADR-0020 | The platform begins as a modular monolith (per `.ai/AGENTS.md` section 3); microservices require an ADR + a measurable scaling boundary | Active | skill 00 | 2026-07-17 |
| ADR-0021 | The Android runtime (the Elysium Vanguard universal runtime) is **not** a vehicle domain concept; new vehicle code lives in `:foundry:core:*` namespaces; the existing `core/runtime/` namespaces are preserved | Active | skill 00 + skill 11 | 2026-07-17 |
| ADR-0022 | The backend (skill 08's service) is **deferred to Phase 2**; the Android app is the first surface; the backend is designed from the proven domain shape | Active | skill 00 + skill 08 | 2026-07-17 |
| ADR-0023 | The multi-module split is **deferred to Phase 7**; the modular monolith shape is the production shape | Active | skill 00 | 2026-07-17 |
| ADR-0024 | The 1,380 unit tests in the existing codebase are preserved; the existing 19 `core/` packages and 26 `features/` packages are preserved; the existing 20 ADRs (ADR-001 through ADR-019 in the EV series) are preserved | Active | skill 00 | 2026-07-17 |

The ADR series for the
Foundry begins at
`ADR-0001` (above) and is
filed under
`docs/adr/foundry/`. The
Elysium Vanguard runtime's
ADR series (the EV series)
is preserved under
`docs/adr/elysium-vanguard/`.

---

## 10. Migration owner rules

When an aggregate's schema
changes, the **migration
owner** is the skill that
owns the aggregate (per the
inventory in sections 1–6).
The migration owner:

1. **Writes the migration.**
   The migration is a forward
   script + a rollback script
   + a content hash + a
   signature (per `.ai/AGENTS.md`
   section 12 + skill 08
   section 7).
2. **Tests the migration.**
   The test re-runs the
   migration on a fixture and
   asserts idempotence +
   asserts the rollback
   produces the original state.
3. **Files the migration ADR.**
   The ADR is in the registry
   (section 9).
4. **Coordinates the rollout.**
   The orchestrator (skill 00)
   coordinates the rollout with
   the verifier (skill 14) +
   the devops (skill 15).

A migration without an owner
is a **contract violation**;
the orchestrator blocks the
release.

A migration that is not
tested + signed + versioned
+ content-addressed is a
**P0 contract violation**;
the verifier blocks the PR.

A migration that mutates
historical data in a
non-append-only way is a
**P0 incident** (per
`.ai/STANDARDS.md` section
2.2 + skill 09 + skill 15).

---

## 11. The "one owner, many readers" rule

The fundamental rule:

> **Every aggregate has exactly one
> owner. Many skills may read. No
> skill may mutate without the
> owner's approval.**

A skill that holds a read-
only cache of an aggregate
is a "reader"; the cache is
**invalidated** when the
owner's notification fires
(via the `EventStream`,
section 6.1).

A skill that holds a stale
cache is a **P1 contract
violation**; the cache
invalidation is part of the
verifier's test suite.

A skill that writes a
mutation without the owner's
approval is a **P0 contract
violation**; the verifier
rejects the PR.

---

## 12. Output

This document is the
**authoritative ownership
map** of the Foundry
platform. The document is
current as of 2026-07-17.
A change to the inventory
(adding / removing an
aggregate) is an ADR. A
change to the cross-skill
edge table (adding /
removing an edge, changing
the auth, changing the
schema version) is an
ADR. A change to the
arbitration rules is an
ADR.

The document is the input
to:

- `docs/foundry/dependency-map.md`
  (every edge is a row in
  the dependency map).
- `docs/foundry/implementation-roadmap.md`
  (every increment is owned
  by a skill).
- `docs/foundry/risk-register.md`
  (every cross-skill edge
  has a risk; the risks are
  in the register).

The orchestrator files
this document under
`docs/foundry/gates/g0-domain-ownership.md`
when G0 is green.
