---
title: Dependency Map — Elysium Automotive Foundry
status: Phase 0 deliverable, signed 2026-07-17
owner: skill 00 (program-orchestrator)
audited_by: skill 01 (repository-archaeology)
git_head: c9028dc
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Dependency Map — Elysium Automotive Foundry

> **Status:** Phase 0 deliverable. The
> **map of every cross-skill dependency
> edge** in the Foundry platform. Every
> edge has a data shape, a schema
> version, an auth requirement, an error
> envelope, a retry classification, and
> a correlation ID propagation rule. The
> map is the authoritative input to the
> verifier's edge-tests (skill 14), the
> devops' SLO model (skill 15), and the
> security review (skill 12).

---

## 0. How to read this document

The document is split into six parts:

1. **The cross-cutting concerns**
   (section 1) — the four concerns
   that apply to **every** edge.
2. **The cross-skill edge table**
   (section 2) — every edge with
   its full contract.
3. **The external dependencies**
   (section 3) — every third-party
   system the platform depends on.
4. **The internal services**
   (section 4) — the in-process
   services that are not skills but
   are still dependencies (e.g. the
   outbox publisher, the asset
   cache).
5. **The dependency lifecycle**
   (section 5) — how a new
   dependency is added, how an
   existing dependency is upgraded,
   and how a dependency is retired.
6. **The dependency ADR log**
   (section 6) — every dependency
   decision.

The skill numbers are stable
(00–15). A skill name like
"skill 03 (ontology)" is shorthand
for `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.

A "contract violation" is
anything that breaks the rules in
this document. The verifier
(skill 14) is the gate.

---

## 1. The cross-cutting concerns (per `.ai/AGENTS.md` section 24)

These four concerns apply to
**every** code path, **every**
skill, **every** release. A
path that does not honor them
is a contract violation.

### 1.1 Stable machine-readable code (`.ai/AGENTS.md` 24.1)

Every code path returns a
**typed** machine-readable
value. The value is:

- A typed domain object
  (Kotlin data class, Rust
  struct, TypeScript
  interface, etc.).
- A typed `Result` / `Either`
  (success payload + typed
  error) at the application
  boundary.
- A typed JSON envelope
  (code, message, field,
  reason, provenance) at the
  infrastructure boundary.
- A typed `FoundryError`
  envelope (per
  `.ai/STANDARDS.md` section
  7) at the error boundary.

A free-form string is never
the value. A `Map<String,
Any>` is never the value. A
`null` is never the value
where a typed value is
required.

### 1.2 Safe user-facing message (`.ai/AGENTS.md` 24.2)

Every error path produces a
**safe user-facing message**.
The message is:

- **Short** (one sentence, ≤
  140 characters).
- **Actionable** (the user
  knows what to do next).
- **Free of internal jargon**
  (no stack traces, no
  internal class names, no
  internal URLs).
- **Free of secrets** (no API
  keys, no tokens, no
  credentials, no internal
  host names).
- **Localized** (every
  supported locale has a
  translation).

### 1.3 Correlation ID (`.ai/AGENTS.md` 24.3)

Every request carries a
**correlation ID**. The ID
is:

- Generated at the entry
  point (the API gateway / the
  UI / the CLI).
- Propagated through every
  downstream call (via the
  `X-Correlation-Id` header in
  HTTP, via a context in
  coroutines, via a thread-
  local in synchronous code,
  via a span attribute in
  OpenTelemetry).
- Logged at every hop.
- Included in every audit-
  trail event.
- Returned to the user (in
  the response headers + in
  the error envelope).

A request that cannot be
correlated is a contract
violation. The platform
cannot debug, audit, or
support a request that has
no correlation ID.

### 1.4 Retry classification (`.ai/AGENTS.md` 24.4)

Every error has a **retry
classification**. The
classification is one of:

- `retryable_immediate` —
  the client MAY retry the
  same request immediately.
- `retryable_backoff` — the
  client MAY retry the same
  request after an exponential
  backoff.
- `retryable_idempotent_only`
  — the client MAY retry the
  same request only if the
  request is idempotent.
- `non_retryable` — the
  client MUST NOT retry.

The classification is part
of the typed error envelope.
A retry that does not honor
the classification is a
contract violation.

### 1.5 No leak of internal stack traces or secrets (`.ai/AGENTS.md` 24.5)

A response that includes a
raw stack trace, an internal
class name, an internal URL,
or a secret is a **P0
incident**. The platform
strips these from every
user-facing response. The
full stack trace + the
internal context are in the
server-side log (correlated
by the correlation ID); the
user sees only the safe
user-facing message.

### 1.6 Relevant structured metadata (`.ai/AGENTS.md` 24.6)

Every event in the audit
trail + every log entry +
every metric + every trace
carries **relevant
structured metadata**:

- `correlationId`
- `tenantId`
- `userId`
- `vehicleId` / `revisionId`
  (when vehicle-scoped)
- `vehicleRepresentationLevel`
- `verificationStatus`
- `source` / `sourceType`

A log entry without the
relevant metadata is a
contract violation.

---

## 2. The cross-skill edge table

### 2.1 Reading the table

- **Edge** — the producer →
  consumer edge.
- **Data shape** — the typed
  value that crosses the
  edge.
- **Schema version** — the
  version of the shape, per
  `.ai/AGENTS.md` section 12.
- **Auth** — the minimum
  authentication + authorization
  needed to consume the edge
  (per `domain-ownership.md`
  section 7.4).
- **Error envelope** — the
  typed `FoundryError` the edge
  may return, per
  `.ai/STANDARDS.md` section 7.
- **Retry** — the retry
  classification, per
  `.ai/AGENTS.md` section 24.4.
- **Correlation** — whether
  the correlation ID is
  propagated.

### 2.2 The full cross-skill edge table

| # | Edge (producer → consumer) | Data shape | Schema version | Auth | Error envelope | Retry | Correlation |
|---|---|---|---|---|---|---|---|
| E-001 | skill 03 → skill 04 | `OntologySnapshot` (the typed domain primitives + the 16 ID types + the enum sets) | v1 | `internal` | `VehicleDefinitionInvalid`, `SchemaVersionIncompatible` | `non_retryable` | yes |
| E-002 | skill 03 → skill 06 | `PartDefinition` + `InterfacePortSet` (28 kinds) + `CoordinateSystem` (4 enum values) | v1 | `internal` | `VehicleDefinitionInvalid` | `non_retryable` | yes |
| E-003 | skill 03 → skill 07 | `PartDefinition` + `Subsystem` + `CompatibilityGraph` (typed DAG) | v1 | `internal` | `VehicleDefinitionInvalid` | `non_retryable` | yes |
| E-004 | skill 03 → skill 11 | `OntologySnapshot` (mobile-shaped, smaller surface) | v1 | `internal` | `SchemaVersionIncompatible` | `retryable_backoff` | yes |
| E-005 | skill 03 → skill 13 | `EngineeringFact<T>` + `VerificationStatus` (regulatory-shape) | v1 | `internal` | `VerificationStatusIncompatible` | `non_retryable` | yes |
| E-006 | skill 04 → skill 06 | `CompiledVehicleSpec` (content-addressed + signed) + `CompiledPart` set | v1 | `internal` | `ArtifactIntegrityFailure`, `ProvenanceIncomplete` | `non_retryable` | yes |
| E-007 | skill 04 → skill 07 | `CompiledVehicleSpec` + `ConstraintGraph` + `DiagnosticBindingHint` | v1 | `internal` | `ArtifactIntegrityFailure` | `non_retryable` | yes |
| E-008 | skill 04 → skill 09 | `CompiledVehicleSpec` + `AuthorshipMarker` (the per-spec authorship claim) | v1 | `signed` | `ArtifactIntegrityFailure`, `SignatureInvalid` | `non_retryable` | yes |
| E-009 | skill 04 → skill 10 | `CompiledVehicleSpec` + `VehicleRepresentationLevel` + `CompiledAssembly` | v1 | `signed` | `ArtifactIntegrityFailure`, `VehicleRepresentationLevelIneligible` | `non_retryable` | yes |
| E-010 | skill 04 → skill 11 | `CompilationReport` (localized) + `CompilationDiagnostic` list | v1 | `internal` | `CompilationReportInvalid` | `retryable_backoff` | yes |
| E-011 | skill 05 → skill 04 | `AIProposal<DslMutation>` (typed proposal) + `CouncilSignature` | v1 | `signed` (council) | `SchemaVersionIncompatible`, `ProposalRejected` | `non_retryable` | yes |
| E-012 | skill 05 → skill 06 | `AIProposal<AssetMetadata>` (typed proposal) + `CouncilSignature` | v1 | `signed` (council) | `SchemaVersionIncompatible` | `non_retryable` | yes |
| E-013 | skill 05 → skill 09 | `AIProposal<AuthorshipClaim>` (typed proposal) + `CouncilSignature` + `ContributorCounterSignature` | v1 | `signed` (council + counter-signed by contributor) | `AuthorshipRejected` | `non_retryable` | yes |
| E-014 | skill 05 → skill 13 | `AIProposal<ComplianceSuggestion>` (typed proposal) + `CouncilSignature` | v1 | `signed` (council) | `ComplianceReportInvalid` | `non_retryable` | yes |
| E-015 | skill 06 → skill 07 | `Canonical3DAsset` + `SceneManifest` + `AssetValidationReport` | v1 | `internal` | `ArtifactIntegrityFailure`, `AssetValidationFailed` | `non_retryable` | yes |
| E-016 | skill 06 → skill 09 | `Canonical3DAsset` + `AssetValidationReport` + `ProvenanceRecord` | v1 | `internal` | `ArtifactIntegrityFailure`, `ProvenanceIncomplete` | `non_retryable` | yes |
| E-017 | skill 06 → skill 10 | `Canonical3DAsset` reference (the listing's asset) | v1 | `internal` | `ArtifactIntegrityFailure` | `non_retryable` | yes |
| E-018 | skill 06 → skill 11 | `LOD` set (LOD0..LODn) + `SceneManifest` + `TextureSet` + `StreamingManifest` | v1 | `internal` | `ArtifactIntegrityFailure`, `AssetLimitExceeded` | `retryable_backoff` | yes |
| E-019 | skill 07 → skill 09 | `Diagnostic` + `Fault` + `RepairAction` + `TelemetrySnapshot` | v1 | `internal` | `ProvenanceIncomplete` | `non_retryable` | yes |
| E-020 | skill 07 → skill 11 | `TelemetryStream` (filtered, PII-redacted) + `SelectionState` | v1 | `internal` | `PiiRedactionFailed`, `UnauthorizedTelemetryAccess` | `retryable_backoff` | yes |
| E-021 | skill 07 → skill 13 | `DiagnosticReport` (the fault model + the regulatory-implication hint) | v1 | `internal` | `ProvenanceIncomplete` | `non_retryable` | yes |
| E-022 | skill 08 → every skill | `EventStream` (typed topic) + `OutboxRecord` | v1 | `mTLS` | `SchemaVersionIncompatible`, `OutboxLag` | `retryable_idempotent_only` | yes |
| E-023 | skill 08 → skill 14 | `Migration` (forward + rollback + content hash + signature) | v1 | `internal` | `MigrationInvalid`, `MigrationNotIdempotent` | `non_retryable` | yes |
| E-024 | skill 09 → skill 04 | `RoyaltyContract` reference (during compilation, for the spec's licensing) | v1 | `internal` | `ContractNotActive` | `non_retryable` | yes |
| E-025 | skill 09 → skill 10 | `RoyaltyContract` + `License` + `Settlement` + `Distribution` | v1 | `internal` | `ContractNotActive`, `RoyaltyCalculationRejected`, `LicenseIncompatible` | `non_retryable` | yes |
| E-026 | skill 09 → skill 13 | `ProvenanceRecord` (regulatory-shape) + `AuditTrail` reference | v1 | `internal` | `ProvenanceIncomplete` | `non_retryable` | yes |
| E-027 | skill 10 → skill 11 | `Listing` + `Order` + `Escrow` + `RFQ` + `Offer` | v1 | `internal` | `UnauthorizedMarketplaceAccess`, `OrderRejected` | `retryable_idempotent_only` | yes |
| E-028 | skill 10 → skill 13 | `SupplierQualification` + `Disclosure` (regulatory-shape) | v1 | `internal` | `SupplierQualificationInsufficient` | `non_retryable` | yes |
| E-029 | skill 10 → skill 12 | `SupplierQualification` + `Disclosure` (security-shape, encrypted) | v1 | `internal` | `DisclosureEncryptionFailed` | `non_retryable` | yes |
| E-030 | skill 11 → skill 08 | `UserEvent` (typed) + `Telemetry` (filtered) | v1 | `internal` | `OutboxLag` | `retryable_backoff` | yes |
| E-031 | skill 11 → skill 09 | `CatalogQuery` (read) + `Project` reference | v1 | `OIDC` | `UnauthorizedProjectAccess` | `retryable_backoff` | yes |
| E-032 | skill 11 → skill 12 | `AuthEvent` (typed) + `ThreatReport` (user-reported) | v1 | `OIDC` | `AuthEventInvalid` | `retryable_backoff` | yes |
| E-033 | skill 11 → skill 13 | `RegulatoryReport` (user-facing) + `ComplianceReport` reference | v1 | `OIDC` | `UnauthorizedRegulatoryAccess` | `retryable_backoff` | yes |
| E-034 | skill 12 → every skill | `SecurityFinding` + `ThreatModel` (read) + `CveFeed` (read) | v1 | `internal` | n/a (read) | n/a | yes |
| E-035 | skill 12 → skill 15 | `SecurityFinding` (incident-triggered) + `PatchSlaStatus` | v1 | `internal` | `SecurityFindingInvalid` | `retryable_backoff` | yes |
| E-036 | skill 13 → every skill | `ComplianceReport` + `HomologationPackage` (read) | v1 | `signed` | `ComplianceReportInvalid` | `non_retryable` | yes |
| E-037 | skill 13 → skill 15 | `ComplianceReport` (regulatory-triggered) + `SafetyGateNotSatisfied` | v1 | `internal` | `ComplianceReportInvalid` | `retryable_backoff` | yes |
| E-038 | skill 14 → skill 00 | `VerificationReport` (the gate) + `GateStatus` | v1 | `internal` | n/a (read) | n/a | yes |
| E-039 | skill 14 → skill 15 | `GateStatus` (the green / red status per phase) | v1 | `internal` | `GateStatusInvalid` | `retryable_backoff` | yes |
| E-040 | skill 15 → every skill | `Deployment` + `SloSnapshot` + `Incident` (read) + `BackupRecord` (read) | v1 | `internal` | n/a (read) | n/a | yes |
| E-041 | skill 00 → every skill | `Phase` (the current phase + the next phase) + `Ar` (the arbitration decision) | v1 | `internal` | `PhaseInvalid` | `non_retryable` | yes |
| E-042 | skill 01 → skill 00 | `ArchaeologyReport` (the 7 questions + the 15 analyses + the 11 outputs) | v1 | `internal` | n/a (read) | n/a | yes |
| E-043 | skill 02 → skill 00 | `Prd` (the 9-level hierarchy + the conflict engine + the change control) | v1 | `internal` | `PrdInvalid` | `non_retryable` | yes |
| E-044 | skill 02 → skill 03 | `Prd` (the new types + the acceptance criteria) | v1 | `internal` | `PrdInvalid` | `non_retryable` | yes |

### 2.3 The auth-by-edge rule (per `domain-ownership.md` section 7.4)

The auth column is the
**minimum** auth required. A
stricter auth is permitted; a
looser auth is forbidden.

| Auth level | When |
|---|---|
| `internal` (in-process) | both producer + consumer run in the same module |
| `mTLS` (mutual TLS) | producer + consumer are different services in the same trust zone |
| `OIDC` (OpenID Connect + OAuth 2.1) | consumer is the mobile app or a third party |
| `signed` (asymmetric signature) | the consumer needs to verify the producer's authorship (e.g. an `AIProposal`) |

A read edge from a public
actor (e.g. a public
`Listing`) uses `OIDC` + a
`Visibility` filter; the
filter is part of the auth
contract.

### 2.4 The schema-versioning rule (per `domain-ownership.md` section 7.3)

A shape's schema version is
**incremented** on every
backward-incompatible change.
A backward-compatible change
(adding a field with a
default, deprecating a field)
does **not** increment the
version.

A consumer that sees a shape
with a version it does not
recognize returns a
`SchemaVersionIncompatible`
error; the error is
`non_retryable`; the
orchestrator coordinates the
upgrade.

A producer that produces a
shape with a version the
consumer does not recognize
is a **P0 contract violation**;
the verifier blocks the
release.

### 2.5 The error-envelope contract (per `domain-ownership.md` section 7.5)

Every error envelope is a
typed `FoundryError` (per
`.ai/STANDARDS.md` section
7). The envelope is:

```json
{
  "code": "VEHICLE_DEFINITION_INVALID",
  "userMessage": {
    "en": "Fix the spec at the indicated field.",
    "es": "Corrige la especificación en el campo indicado.",
    "ja": "指定されたフィールドで仕様を修正してください。"
  },
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

### 2.6 The correlation propagation rule

Every edge in the table
propagates the correlation
ID, **with one exception**:
read edges from the verifier
(skill 14) and read edges
from the devops (skill 15)
may run on a separate
correlation context (the
context is the
`VerificationReport`'s ID +
the `Deployment`'s ID
respectively). The
exception is documented per
edge.

The propagation is:

- **HTTP edges:** via the
  `X-Correlation-Id` header.
- **gRPC edges:** via the
  `x-correlation-id` metadata.
- **In-process edges:** via
  a coroutine context (Kotlin)
  / a `tokio` task local
  (Rust) / a `Context` (Go) /
  a `ThreadLocal` (Java).
- **Event-bus edges:** via
  the `correlationId` field
  in the event envelope.

---

## 3. The external dependencies

The platform's external
dependencies are the
third-party systems the
platform depends on. A new
external dependency is added
to the map only when an ADR
approves it.

### 3.1 The external dependency table

| ID | System | Protocol | Auth | SLA | Failure mode | Owner | ADR |
|---|---|---|---|---|---|---|---|
| X-001 | **Identity provider** (OIDC, Keycloak or equivalent) | OIDC + OAuth 2.1 + WebAuthn | OIDC | 99.9% uptime | `UnauthorizedMarketplaceAccess` if the IdP is down | skill 12 | pending |
| X-002 | **Object storage** (S3-compatible, Supabase Storage, or equivalent) | S3 | IAM + bucket policy | 99.9% durability + 99.9% uptime | `ArtifactIntegrityFailure` if the storage is unreachable | skill 08 | pending |
| X-003 | **Payment provider** (third-party SaaS — Stripe, Adyen, or equivalent) | REST | OAuth 2.1 | per the provider's contract | `OrderRejected` if the payment is declined | skill 10 | pending |
| X-004 | **OpenTelemetry collector** (the observability backend) | OTLP | mTLS | 99.9% uptime | `ObservabilityLag` if the collector is unreachable (the platform continues to operate; the events are buffered) | skill 15 | pending |
| X-005 | **AI council deliberation service** (the multi-agent service — could be in-house or third-party) | gRPC | mTLS | 99.5% uptime | `ProposalRejected` if the council is unreachable (the user can fall back to a direct human review) | skill 05 | pending |
| X-006 | **Email / SMS provider** (SendGrid, Twilio, or equivalent) | REST | API key | 99.5% uptime | `NotificationFailed` if the provider is down (the user is notified in-app instead) | skill 11 | pending |
| X-007 | **Push notification provider** (FCM, APNs) | proprietary + REST | API key | 99.9% uptime | `NotificationFailed` | skill 11 | pending |
| X-008 | **OCR provider** (ML Kit on-device, or cloud OCR as a fallback) | proprietary + REST | API key | 99.5% uptime | `OcrFailed` (the user is asked to re-take the photo) | skill 11 | pending |
| X-009 | **Map provider** (Mapbox, Google Maps, or equivalent) | proprietary + REST | API key | 99.9% uptime | `MapLoadFailed` (the user is shown a static map) | skill 11 | pending |
| X-010 | **CAD / STEP parser library** (OpenCascade, or equivalent) | in-process | n/a (in-process) | n/a | `CadParserFailed` | skill 06 | pending |
| X-011 | **glTF / USD library** (Khronos glTF SDK, USD SDK) | in-process | n/a (in-process) | n/a | `AssetLoadFailed` | skill 06 | pending |
| X-012 | **Regulatory feed** (the per-jurisdiction regulatory change feed) | RSS / webhook | API key | best-effort | `RegulatoryFeedLag` (the platform flags affected projects) | skill 13 | pending |
| X-013 | **CVE feed** (NVD, GHSA, or equivalent) | REST | API key | best-effort | `CveFeedLag` (the platform continues to operate; the alert is delayed) | skill 12 | pending |
| X-014 | **Backup storage** (S3-compatible with versioning + cross-region replication) | S3 | IAM + bucket policy | 99.999999999% durability | `BackupRestoreFailed` (the on-call is paged) | skill 15 | pending |
| X-015 | **Secrets manager** (HashiCorp Vault, AWS Secrets Manager, or equivalent) | REST | IAM + mTLS | 99.99% uptime | `SecretAccessFailed` (the platform fails closed) | skill 12 | pending |

### 3.2 The external-dependency ADR rule

A new external dependency is
added to the map only when
an ADR approves it. The ADR
must include:

1. **The system's purpose.**
   What the system does + why
   the platform needs it.
2. **The alternative.** What
   the platform would do
   without the system.
3. **The cost.** The
   financial cost + the
   operational cost.
4. **The failure mode.** What
   happens when the system is
   down.
5. **The exit strategy.** How
   the platform would
   replace the system.
6. **The data classification.**
   Per `.ai/AGENTS.md` section
   14.

A dependency without an ADR
is a **contract violation**;
the orchestrator blocks the
release.

### 3.3 The external-dependency upgrade rule

A dependency is upgraded
when:

1. **The new version fixes a
   P0 / P1 security finding.**
2. **The new version adds a
   required feature.**
3. **The old version reaches
   end-of-life.**

The upgrade is tested in a
staging environment. The
upgrade is rolled out via
a canary deployment (per
`.ai/AGENTS.md` section 9.1).

A dependency upgrade is
filed as an ADR.

### 3.4 The external-dependency retirement rule

A dependency is retired when:

1. **The platform no longer
   needs the system.**
2. **The system is replaced
   by another system (the
   replacement is the new
   dependency).**

The retirement is filed as
an ADR. The retirement is
rolled out in a backward-
compatible way (the platform
continues to support the
old system for a grace
period).

---

## 4. The internal services

The internal services are the
in-process services that are
not skills but are still
dependencies.

### 4.1 The internal service table

| ID | Service | Owner | Interface | Auth | Failure mode |
|---|---|---|---|---|---|
| I-001 | **Outbox publisher** | skill 08 | `OutboxPublisher.publish(OutboxRecord): Result<Unit, OutboxError>` | `internal` | `OutboxLag` if the publisher is slow; the outbox is reliable (the events are retried) |
| I-002 | **Asset cache** (the in-process content-addressed cache) | skill 06 | `AssetCache.get(ContentHash): Result<Canonical3DAsset, AssetCacheMiss>` | `internal` | `AssetCacheMiss` (the asset is downloaded from the canonical store) |
| I-003 | **Localization service** | skill 11 | `LocalizationService.localize(UserMessage, Locale): Result<String, LocalizationFailed>` | `internal` | `LocalizationFailed` (the platform falls back to the canonical English) |
| I-004 | **Vault** (Tink AES-256-GCM) | skill 12 | `Vault.encrypt(Plaintext, KeyId): Result<Ciphertext, VaultError>` | `internal` | `VaultError` (the platform fails closed) |
| I-005 | **Signature service** | skill 12 | `SignatureService.sign(Plaintext, KeyId): Result<Signature, SignatureError>` | `internal` | `SignatureError` (the platform fails closed) |
| I-006 | **AI gateway** (the local LLM + the multi-agent council) | skill 05 | `AiGateway.deliberate(ProposalRequest): Result<AiProposal, AiError>` | `mTLS` | `AiError` (the user is asked to retry or to escalate to a human review) |
| I-007 | **OCR service** (ML Kit on-device) | skill 11 | `OcrService.recognize(Image): Result<Text, OcrError>` | `internal` | `OcrError` (the user is asked to re-take the photo) |
| I-008 | **Image labeling service** (ML Kit on-device) | skill 11 | `ImageLabelingService.label(Image): Result<LabelSet, LabelingError>` | `internal` | `LabelingError` (the user is shown a manual input field instead) |
| I-009 | **CRDT sync engine** (the HLC + NodeId engine — inherited from the Elysium Vanguard runtime) | skill 08 | `CrdtSyncEngine.merge<T>(local: T, remote: T): T` (in-place mutation) | `internal` | `CrdtMergeConflict` (the conflict is logged; the loser is dropped) |
| I-010 | **HLC clock** (the hybrid logical clock) | skill 08 | `HlcClock.now(): Hlc` | `internal` | n/a (the clock is monotonic) |
| I-011 | **Event bus** (the in-process bus — SynchronizedEventBus from the Elysium Vanguard runtime) | skill 08 | `EventBus.publish<T>(Topic, T): Result<Unit, EventBusError>` | `internal` | `EventBusError` (the event is retried) |
| I-012 | **Content-addressed store** (the in-process + on-disk store) | skill 08 | `ContentStore.put(Bytes): ContentHash` | `internal` | `ContentStoreError` (the platform fails closed) |
| I-013 | **ID generator** (UUID v7) | skill 03 | `IdGenerator.next(): Uuid` | `internal` | n/a (the generator is reliable) |
| I-014 | **Migration runner** (the forward + rollback runner) | skill 08 | `MigrationRunner.run(Migration): Result<Unit, MigrationError>` | `internal` | `MigrationError` (the migration is rolled back; the orchestrator is notified) |
| I-015 | **Audit trail** (the signed + append-only trail) | skill 09 | `AuditTrail.append(SignedEvent): Result<Unit, AuditTrailError>` | `signed` (per event) | `AuditTrailError` (the platform fails closed) |

### 4.2 The internal-service ADR rule

A new internal service is
added to the map only when
an ADR approves it. The ADR
follows the same shape as
the external-dependency ADR
(per section 3.2).

---

## 5. The dependency lifecycle

### 5.1 Adding a dependency

To add a dependency:

1. **File an ADR.** Per
   section 3.2 (external) or
   section 4.2 (internal).
2. **Update the map.** Add
   a row to the appropriate
   table.
3. **Implement the edge.**
   The producer implements
   the edge; the consumer
   implements the
   consumption; the contract
   is enforced.
4. **Write the test.** The
   verifier (skill 14) writes
   the edge-test.
5. **Update the docs.** The
   PRD + the user-facing
   docs are updated.

A dependency added without
an ADR is a **contract
violation**; the verifier
blocks the PR.

### 5.2 Upgrading a dependency

To upgrade a dependency:

1. **File an ADR.** Per
   section 3.3.
2. **Test in staging.** The
   upgrade is tested in a
   staging environment.
3. **Roll out via canary.**
   Per `.ai/AGENTS.md`
   section 9.1.
4. **Update the map.** The
   row in the table is
   updated.

A dependency upgraded
without an ADR is a
**contract violation**; the
verifier blocks the PR.

### 5.3 Retiring a dependency

To retire a dependency:

1. **File an ADR.** Per
   section 3.4.
2. **Roll out in backward-
   compatible way.** The
   platform continues to
   support the old system
   for a grace period.
3. **Update the map.** The
   row is removed.
4. **Update the docs.** The
   PRD + the user-facing
   docs are updated.

A dependency retired
without an ADR is a
**contract violation**.

---

## 6. The dependency ADR log

| ADR | Title | Status | Owner | Date |
|---|---|---|---|---|
| ADR-0001 | Money is `BigDecimal` (not `Double` / `Float`) | Active | skill 03 | 2026-07-17 |
| ADR-0002 | `VehicleRepresentationLevel` transitions are append-only + signed | Active | skill 03 | 2026-07-17 |
| ADR-0003 | `VerificationStatus` cannot transition `AI_INFERRED → VERIFIED` without a signed counter-signature | Active | skill 03 + skill 09 | 2026-07-17 |
| ADR-0004 | `RoyaltyContract` must be `ACTIVE` before a `Settlement` is computed | Active | skill 09 | 2026-07-17 |
| ADR-0005 | Elysium 5% royalty is a configurable `RoyaltyRule` (not hardcoded) | Active | skill 09 | 2026-07-17 |
| ADR-0006 | Vehicle definitions are append-only | Active | skill 03 | 2026-07-17 |
| ADR-0007 | Outbox is reliable; mutations are idempotent | Active | skill 08 | 2026-07-17 |
| ADR-0008 | `FoundryError` is the typed error envelope | Active | skill 00 + skill 14 | 2026-07-17 |
| ADR-0009 | `CorrelationId` is generated at the entry point + propagated + logged + returned | Active | skill 00 + skill 15 | 2026-07-17 |
| ADR-0010 | AI council produces typed proposals; the LLM has no path to authoritative state | Active | skill 05 + skill 14 | 2026-07-17 |
| ADR-0011 | `VISUAL_ONLY` / `CONCEPTUAL` vehicles are not eligible for a `Settlement` | Active | skill 09 + skill 10 | 2026-07-17 |
| ADR-0012 | `Canonical3DAsset` validation is a hard prerequisite | Active | skill 06 | 2026-07-17 |
| ADR-0013 | `EngineeringArtifact` references are content-addressed + signed | Active | skill 03 + skill 08 | 2026-07-17 |
| ADR-0014 | Cross-skill edge auth-by-edge rule | Active | skill 00 + skill 12 | 2026-07-17 |
| ADR-0015 | Cross-skill edge schema-versioning rule | Active | skill 00 + skill 14 | 2026-07-17 |
| ADR-0016 | A `Migration` is forward + rollback + content-addressed + signed + tested | Active | skill 08 + skill 03 | 2026-07-17 |
| ADR-0017 | `Settlement` is co-owned by skill 09 + skill 10 | Active | skill 09 + skill 10 | 2026-07-17 |
| ADR-0018 | `SupplierQualification` is co-owned by skill 10 + skill 13 | Active | skill 10 + skill 13 | 2026-07-17 |
| ADR-0019 | `Disclosure` is co-owned by skill 10 + skill 12 | Active | skill 10 + skill 12 | 2026-07-17 |
| ADR-0020 | The platform begins as a modular monolith; microservices require an ADR | Active | skill 00 | 2026-07-17 |
| ADR-0021 | The Android runtime is not a vehicle domain concept; new code lives in `:foundry:core:*` | Active | skill 00 + skill 11 | 2026-07-17 |
| ADR-0022 | The backend is deferred to Phase 2 | Active | skill 00 + skill 08 | 2026-07-17 |
| ADR-0023 | The multi-module split is deferred to Phase 7 | Active | skill 00 | 2026-07-17 |
| ADR-0024 | The existing 1,380 unit tests + 19 `core/` packages + 26 `features/` packages + 20 EV-series ADRs are preserved | Active | skill 00 | 2026-07-17 |
| ADR-0025 | The internal service list (section 4) is the initial set; new internal services are ADRs | Active | skill 00 + skill 14 | 2026-07-17 |
| ADR-0026 | The external dependency list (section 3) is the initial set; new external dependencies are ADRs | Active | skill 00 + skill 12 | 2026-07-17 |

The ADR series is in
`docs/adr/foundry/`. A new
ADR is filed when a new
dependency is added, when
an existing dependency is
upgraded, or when an
existing dependency is
retired.

---

## 7. The cross-references

The dependency map is the
input to:

- `docs/foundry/domain-ownership.md`
  (every edge in this map is
  a row in the cross-skill
  contract table in
  `domain-ownership.md`
  section 7.2).
- `docs/foundry/implementation-roadmap.md`
  (every cross-skill edge is
  scheduled in a phase; the
  per-increment risks in
  `risk-register.md` section
  8 are the per-edge risks).
- `docs/foundry/risk-register.md`
  (every edge has a risk;
  the risks are in the
  register).

A change to this document
is an ADR. A change to an
edge (data shape, schema
version, auth, error
envelope, retry, correlation)
is an ADR.

---

## 8. Output

This document is the
**authoritative dependency
map** of the Foundry
platform. The document is
current as of 2026-07-17.
A change to a row in any
table is an ADR. A change
to a rule (the auth-by-edge
rule, the schema-versioning
rule, the error-envelope
contract, the correlation
propagation rule) is an
ADR.

The orchestrator files
this document under
`docs/foundry/gates/g0-dependency-map.md`
when G0 is green.
