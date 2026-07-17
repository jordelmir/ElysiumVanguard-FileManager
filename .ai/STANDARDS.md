# Elysium Automotive Foundry — Technical Standards

> **Status:** Living document. Every skill in `.ai/skills/`
> inherits the standards here. Any change to a standard
> that weakens a non-negotiable is a regression and must
> be escalated to skill 00 (program-orchestrator) via an
> ADR.
>
> **Scope:** This document is the canonical reference for
> the technical specifics — the tech stack, the
> non-negotiables, the truth model, the AI authority
> boundary, the delivery rules, and the error model. The
> meta-document is `.ai/AGENTS.md`; this document is the
> meta-document's companion.

---

## 1. Tech Stack (Canonical)

The platform's tech stack is **canonical**. A skill
that introduces a new dependency in one of these
slots without an ADR is a contract violation.

| Slot | Default |
|---|---|
| Android | Kotlin, Jetpack Compose, coroutines, Flow |
| Web | React + TypeScript |
| Mobile 3D | Filament (Android / iOS) with glTF / GLB |
| Backend application services | Kotlin + Spring Boot **or** Ktor, selected per repository convention |
| Geometry / compiler workers | Rust, when deterministic computation, memory control, or concurrency justifies it |
| Database (OLTP) | PostgreSQL |
| Object storage | S3-compatible storage, or Supabase Storage |
| Cache and transient coordination | Redis only when required |
| API contracts | OpenAPI + generated clients |
| Internal events | Typed domain events (per skill 08) |
| Authentication | OIDC / OAuth2-compatible identity |
| Authorization | RBAC + ABAC |
| Observability | OpenTelemetry |
| Infrastructure | Docker + reproducible CI/CD |

**Any deviation** from the canonical stack requires an
ADR (per `.ai/AGENTS.md` section 3) that:

- Names a measurable constraint the default cannot
  satisfy.
- Compares 2+ alternatives with their trade-offs.
- Identifies the migration path away from the
  alternative if it fails.

---

## 2. Non-negotiable Engineering Constraints

The following are **non-negotiable**. A skill, an
agent, a PR, or a release that violates any of these
is a contract violation. The orchestrator blocks the
release; the security skill escalates the incident;
the regulatory skill files the RIA.

### 2.1 Data integrity

- **Never invent OEM data.** Every OEM figure
  (specification, tolerance, torque) MUST be
  sourced from a verifiable OEM document. An
  AI-inferred value is a `AI_INFERRED` fact, never
  an `OEM_VERIFIED` one (see section 3).
- **Never invent dimensions, torques, pinouts, or
  homologation requirements.** Every value MUST
  carry a `Source`, a `Source type`, a
  `Jurisdiction or market when applicable`, a
  `Vehicle applicability`, a `Revision`, a
  `Confidence`, a `Verification status`, a
  `Reviewer`, and a `Timestamp` (per section 3).
- **Never present AI-generated geometry as
  validated production CAD.** A generated mesh
  is `PARAMETRIC_FUNCTIONAL` or `CONCEPTUAL`
  (per section 4). Promoting it to `OEM_EXACT`
  or `OEM_PARTIAL` is a contract violation.
- **Never treat visual mesh compatibility as
  mechanical compatibility.** A mesh that
  visually matches a part is not mechanically
  compatible. The compatibility check is the
  fault model + the engineering review, not the
  3D viewer.

### 2.2 Commercial integrity

- **Never calculate royalties without an active
  contract version.** A royalty calculation is
  computed only against a `RoyaltyContract` whose
  status is `ACTIVE` (per skill 09).
- **Never allow mutable historical commercial
  releases.** A signed release is append-only. A
  row that is updated is a violation. A row that is
  deleted is a violation. A version that is rolled
  back is a new release, not an edit.
- **Never store money using floating-point types.**
  Money is `BigDecimal` (JVM), `decimal.Decimal`
  (Python), or `rust_decimal::Decimal` (Rust). A
  `Double` / `Float` / `f64` for money is a
  contract violation.

### 2.3 Code hygiene

- **Never use generic catch blocks that hide
  failures.** Every catch block MUST either
  re-throw, log with a typed error, or return a
  typed `Result` / `Either`. A `catch (e: Exception)
  { /* ignore */ }` is a contract violation.
- **Never use unchecked null assertions.** A
  `!!` in Kotlin, an `as` without a check in
  TypeScript, a `unwrap()` in production Rust, a
  `Object!` cast in C# is a contract violation.
  Every null-handling path is typed.
- **Never use `unwrap`, `expect`, or panic-driven
  control flow in production Rust.** An `unwrap`
  in a non-test file is a contract violation. The
  error path is typed; the panic is the failure
  of the error path, not the success path.

### 2.4 Concurrency

- **Never block the Android main thread with model
  loading, decoding, or network work.** Every
  heavy operation is on `Dispatchers.IO`. The
  Compose render path is non-blocking. A network
  call on the main thread is a contract violation.

### 2.5 Trust

- **Never trust imported 3D assets.** Every asset
  is validated (manifold, units, coordinate
  system, file size) before it enters the
  canonical store (per skill 06). An unvalidated
  asset is a contract violation.
- **Never execute scripts embedded in uploaded
  assets.** A glTF with a script, a STEP with
  macros, a USD with a custom schema that runs
  code: rejected at the parse step. The
  pipeline never executes user-supplied code.
- **Never place secrets in the application
  package.** Secrets live in the vault (per
  skill 12). A secret in the code, the
  config, the assets, or the build artifacts is
  a P0 incident.

### 2.6 AI authority

- **Never let an LLM directly mutate authoritative
  financial or engineering state.** A model
  proposes; a deterministic engine + a human
  review applies. The model is a draft; the
  rule engine is the law; the human is the
  witness.
- **Never mark a vehicle as road legal based
  solely on AI output.** A `RoadLegal` flag
  requires an `ENGINEER_REVIEWED` verification
  AND a `REGULATORY_VERIFIED` verification AND
  a human counter-signature. An AI-inferred
  "this is road legal" is a violation.

---

## 3. Truth and Confidence Model

Every engineering fact in the platform MUST carry
the following metadata. The metadata is the
provenance; the fact without provenance is a
hypothesis.

### 3.1 Required metadata

| Field | Type | Notes |
|---|---|---|
| `Source` | `String` | Where the fact came from (URL, doc id, sensor id, etc.) |
| `Source type` | `String` | `OEM_DOC`, `REGULATORY_FILING`, `LAB_REPORT`, `ENGINEER_MEMO`, `TELEMETRY`, `AI_INFERENCE`, `USER_INPUT`, `COMMUNITY` |
| `Jurisdiction or market` | `String?` | EU / US / CN / BR / etc. — when applicable |
| `Vehicle applicability` | `String` | The `VehicleId` (or family) the fact applies to |
| `Revision` | `String` | The `RevisionId` of the spec the fact is associated with |
| `Confidence` | `Float` in [0.0, 1.0] | The confidence in the fact, per the verifier (skill 14) |
| `Verification status` | `VerificationStatus` (enum) | One of section 3.2 below |
| `Reviewer` | `String?` | The human who reviewed the fact, if any |
| `Timestamp` | `ISO-8601` | When the fact was recorded |

A fact without all required fields is a
contract violation. The quality skill (skill 14)
rejects the PR.

### 3.2 Verification levels

The verification status is one of:

| Level | Meaning |
|---|---|
| `OEM_VERIFIED` | The fact was sourced from an OEM document and the OEM signed off on the platform's use of it. The OEM relationship is in the catalog (skill 09). |
| `REGULATORY_VERIFIED` | The fact was sourced from a regulatory filing (UN, EU, ISO, etc.) and the regulatory body is in the catalog. |
| `LAB_VERIFIED` | The fact was produced by a lab test (an independent test, a dyno, an EMC chamber, etc.). The lab is in the catalog. |
| `ENGINEER_REVIEWED` | A human engineer reviewed the fact and signed it. The reviewer is in the catalog. |
| `COMMUNITY_CORROBORATED` | The fact is sourced from community knowledge (a forum, a wiki, a contributor's documentation). At least 3 independent contributors corroborate. |
| `AI_INFERRED` | The fact is produced by an AI model. The model, the version, the prompt, and the temperature are in the audit trail (skill 09). |
| `UNKNOWN` | The fact has no source. The platform refuses to use an `UNKNOWN` fact in a production path; the fact is only allowed in dev / preview. |

**AI_INFERRED** data MUST NOT silently become
**VERIFIED**. The transition is a human review
+ a signed counter-signature, recorded in the
audit trail. An AI-inferred fact that is treated
as verified without the transition is a
contract violation.

### 3.3 Storage shape

In the codebase, the metadata is a value class
or a data class:

```kotlin
data class EngineeringFact<T>(
    val value: T,
    val source: String,
    val sourceType: SourceType,
    val jurisdiction: String? = null,
    val vehicleApplicability: String,
    val revision: String,
    val confidence: Float,
    val verificationStatus: VerificationStatus,
    val reviewer: String? = null,
    val timestamp: String  // ISO-8601
)
```

The shape is owned by skill 03 (ontology) and
mirrored in every language the platform uses.

---

## 4. Vehicle Representation Levels

Every generated vehicle MUST declare one
representation level. The level is a typed enum
on the `Vehicle` aggregate (per skill 03). The
UI MUST display the level prominently. A
`VISUAL_ONLY` or `CONCEPTUAL` vehicle MUST NOT
present itself as production-ready or
homologated.

### 4.1 Levels

| Level | Meaning |
|---|---|
| `OEM_EXACT` | The vehicle spec was produced from an OEM's authoritative document. The OEM is in the catalog. Every engineering fact is `OEM_VERIFIED` or higher. |
| `OEM_PARTIAL` | The vehicle spec was produced from an OEM document for a subset of the parts. The remaining parts are `PARAMETRIC_FUNCTIONAL` or `CONCEPTUAL`. The UI must show which parts are OEM and which are not. |
| `PARAMETRIC_FUNCTIONAL` | The vehicle spec was produced by the platform's parametric model, validated by a human engineer. Every fact is `ENGINEER_REVIEWED` or higher. |
| `CONCEPTUAL` | The vehicle spec is a concept; no engineer has signed it. The UI must show "CONCEPTUAL — not validated". |
| `VISUAL_ONLY` | The vehicle spec is a visual mock; the platform has not validated the engineering. The UI must show "VISUAL_ONLY — not for production". |

A `VISUAL_ONLY` or `CONCEPTUAL` vehicle is NOT
eligible for:
- Marketplace listing (skill 10)
- Royalty settlement (skill 09)
- Regulatory submission (skill 13)
- Field diagnostic reference (skill 07)

A `VISUAL_ONLY` or `CONCEPTUAL` vehicle IS
eligible for:
- Dev / preview paths
- Educational content
- Research prototypes

### 4.2 UI requirement

The UI (skill 11) MUST display the level
prominently on every vehicle card. A
`VISUAL_ONLY` or `CONCEPTUAL` vehicle's UI MUST
include a "this is not validated" warning. A
`OEM_PARTIAL` vehicle's UI MUST show which parts
are OEM-sourced and which are not.

The UI MUST NOT allow a `VISUAL_ONLY` or
`CONCEPTUAL` vehicle to be listed in the
marketplace, settled for royalties, or submitted
for regulatory approval. The orchestrator's
verifier (skill 14) checks the UI for this
constraint.

### 4.3 Transition

A vehicle's level is **append-only**. The
transition is:

```
VISUAL_ONLY → CONCEPTUAL → PARAMETRIC_FUNCTIONAL → OEM_PARTIAL → OEM_EXACT
```

A transition is a signed revision. A regression
is forbidden. The new revision's facts are
`VERIFIED` per section 3; the new revision's
level is the new level; the catalog records
both.

---

## 5. AI Authority Boundary

The AI in the platform is a **drafting tool**,
not an **authority**. The platform's authoritative
workflow is:

```
natural language
  → structured proposal (the AI)
  → schema validation (the deterministic engine)
  → simulation or evidence (the deterministic engine + skill 07)
  → human review (the reviewer in the catalog)
  → signed revision (the catalog)
```

A model is **not** an authority. A model is
**a draft** the deterministic engine + the
human review **apply**.

### 5.1 What AI may do

The AI may:

- **Interpret requirements.** The user says "I
  want a small EV for city use". The AI proposes
  a 2-seat, 80 kWh battery, single-motor layout.
  The AI's output is a structured proposal.
- **Propose architectures.** The user has a
  battery + a motor + a chassis. The AI proposes
  the wiring harness, the ECU, the software stack.
  The AI's output is a structured proposal.
- **Generate candidate configurations.** The
  user has 3 motors, 2 batteries, 4 chassis.
  The AI generates the candidate pairings. The
  AI's output is a structured proposal.
- **Explain trade-offs.** The user asks "should
  I use NMC or LFP for the battery?". The AI
  explains. The AI's output is a draft, not a
  decision.
- **Resolve terminology.** The user says
  "fastback". The AI resolves to a `BodyStyle`
  enum value.
- **Suggest validation plans.** The user has a
  new design. The AI suggests a homologation
  plan. The AI's output is a draft.
- **Generate drafts.** The user has a PRD. The
  AI generates a draft. The draft is reviewed.
- **Identify inconsistencies.** The user has a
  spec. The AI points out the inconsistencies.
- **Produce structured commands for
  deterministic engines.** The user has a
  request. The AI produces a typed command. The
  deterministic engine executes the command.

### 5.2 What AI may NOT directly do

The AI may NOT directly:

- **Approve safety-critical requirements.** A
  `SafetyGoal` requires `ENGINEER_REVIEWED` +
  `REGULATORY_VERIFIED` + a human counter-signature.
  An AI's "this is safe" is a draft, not an
  approval.
- **Certify regulatory compliance.** A
  `RegulatorySubmission` requires
  `REGULATORY_VERIFIED` + a human counter-signature.
  An AI's "this is compliant" is a draft, not a
  certification.
- **Declare mechanical compatibility.** A
  `Compatibility` fact requires `LAB_VERIFIED`
  or `OEM_VERIFIED` + a human counter-signature.
  An AI's "these parts fit" is a draft, not a
  declaration.
- **Finalize financial settlements.** A
  `Settlement` requires `ENGINEER_REVIEWED`
  + an audit trail + a human counter-signature.
  An AI's "the royalty is X" is a draft, not a
  finalization.
- **Determine legal ownership.** An
  `AuthorshipClaim` requires a human
  counter-signature. An AI's "this was written
  by X" is a draft, not a determination.
- **Modify signed releases.** A signed release
  is append-only. An AI's "let me fix this in
  v1.2" is a violation.
- **Create verified technical facts without
  evidence.** A fact is `VERIFIED` only when the
  evidence exists. An AI's "this is true" is
  `AI_INFERRED` (or `UNKNOWN`), not `VERIFIED`.

### 5.3 The authoritative workflow

The authoritative workflow for any non-trivial
decision is:

```
natural language
  → AI's structured proposal
  → deterministic rule engine (the schema
     validator, the fault model, the royalty
     engine, the compat check, etc.)
  → simulation or evidence (the digital twin,
     the lab, the regulatory filing, etc.)
  → human review (the reviewer in the catalog)
  → signed revision (the catalog, the audit
     trail, the content-addressed store)
```

A decision that bypasses the workflow is a
contract violation.

---

## 6. Delivery Rules

Work in **vertical, reviewable increments**. An
increment is a slice that ships end-to-end (domain
→ DB → use case → API → UI → auth → errors → tests
→ observability → docs → migration).

### 6.1 Required contents of an increment

Every increment MUST contain:

1. **Domain model** — the new / changed types
   (per skill 03).
2. **Database migration** — when the domain
   model changed (per skill 08).
3. **Application use case** — the new / changed
   command (per skill 08).
4. **API contract** — the OpenAPI delta (per
   skill 08).
5. **UI integration** — when applicable (per
   skill 11).
6. **Authorization checks** — every new
   action has an auth check (per skill 12).
7. **Structured errors** — every new failure
   path is a typed error (per section 7).
8. **Unit tests** — per skill 14.
9. **Integration tests** — per skill 14.
10. **Observability** — the traces + metrics
    + logs for the new code path (per skill 15).
11. **Documentation** — the PRD + the ADR +
    the user-facing docs.
12. **Migration or rollback instructions** —
    every increment that changes the schema or
    the API has a migration + a rollback.

A "placeholder production logic disguised as
complete implementation" is a contract
violation. The verifier (skill 14) rejects the
PR.

### 6.2 Increment size

An increment is **vertical** (a slice of value,
not a layer of tech) and **reviewable** (small
enough that a human can review it in 30
minutes, but big enough to ship value). The
target is 1-3 days of focused work per
increment.

### 6.3 The verification report

Every increment is verified by skill 14 before
merge. The verification report is the
orchestrator's primary input.

---

## 7. Required Error Model

Use **explicit domain and application errors**. An
error is not a string; an error is a typed value
with a code, a message, a cause, a recovery hint,
and the provenance metadata (per section 3).

### 7.1 Canonical errors

The platform uses the following canonical
errors. A skill that introduces a new error type
without an ADR is a contract violation; the new
error MUST be added to this list.

| Error | When | Recovery |
|---|---|---|
| `VehicleDefinitionInvalid` | The vehicle spec fails validation (the DSL compiler rejected it, the ontology rejected it, etc.) | The user fixes the spec. |
| `CompatibilityConstraintViolation` | Two parts are not compatible (the engine has the constraint, the spec violates it) | The user picks compatible parts. |
| `ArtifactIntegrityFailure` | An artifact's content hash does not match the manifest, or the signature does not verify | The user re-uploads the artifact. |
| `ContractNotActive` | A royalty contract is not in the `ACTIVE` state | The user activates the contract, or picks an active one. |
| `RoyaltyCalculationRejected` | The royalty engine rejected the calculation (e.g. the contract is malformed, the sum exceeds 100%, the jurisdiction caps the royalty) | The user fixes the contract. |
| `UnauthorizedProjectAccess` | The user does not have access to the project the artifact is in | The user requests access. |
| `RevisionConflict` | A revision was modified by another user + the current user since the current user started | The user refreshes + retries. |
| `ProvenanceIncomplete` | An engineering fact is missing required metadata (section 3) | The user provides the metadata. |
| `SafetyGateNotSatisfied` | A `SafetyGoal` is not `REGULATORY_VERIFIED` + `ENGINEER_REVIEWED` + counter-signed | The user obtains the missing verifications. |
| `AssetLimitExceeded` | A per-asset limit (file size, polygon count, etc.) is exceeded | The user reduces the asset or requests a limit exception (an ADR). |

### 7.2 Error shape

In the codebase, an error is a sealed class or a
value class:

```kotlin
sealed class FoundryError(message: String) : RuntimeException(message) {
    abstract val code: String
    abstract val recovery: String
    abstract val provenance: EngineeringFact<Unit>  // per section 3

    data class VehicleDefinitionInvalid(
        val field: String,
        val reason: String,
        override val provenance: EngineeringFact<Unit>
    ) : FoundryError("vehicle definition invalid at $field: $reason") {
        override val code = "VEHICLE_DEFINITION_INVALID"
        override val recovery = "Fix the spec at the indicated field."
    }

    // ... other canonical errors
}
```

The error is **typed**; the error is **never** a
free-form string. A `throw Exception("oops")` is
a contract violation.

### 7.3 Error transport

Errors flow through the platform as typed values:

- Domain → Application: the use case returns a
  `Result<DomainValue, FoundryError>` (Kotlin)
  or an `Either<FoundryError, DomainValue>`
  (Haskell-style) or a tagged union (TypeScript).
- Application → Infrastructure: the error is
  serialised to a typed JSON envelope:
  ```json
  {
    "code": "VEHICLE_DEFINITION_INVALID",
    "message": "...",
    "field": "powertrain.battery.capacity",
    "reason": "capacity must be positive",
    "provenance": { ... }
  }
  ```
- Application → UI: the UI receives the typed
  error and renders a typed message (not a
  free-form string).

A string-only error path is a contract violation.

### 7.4 Error logging

Every error is logged with the typed
`FoundryError` + the full provenance (per
section 3). The log is in the structured log
stream (skill 15). The error is also in the
audit trail (skill 09) when the error affects
a financial or engineering decision.

---

## 8. Standards Compliance

A skill that violates a standard is a contract
violation. The standards are enforced by:

- **Skill 14 (quality)** — the verifier checks
  the standards as part of the global quality
  gates.
- **Skill 12 (security)** — the security review
  checks the security-relevant standards (no
  secrets, no main-thread blocking, etc.).
- **Skill 13 (regulatory)** — the regulatory
  review checks the regulatory-relevant
  standards (no AI-claimed road legality, etc.).
- **Skill 00 (orchestrator)** — the
  orchestrator's ADR is required for any
  deviation.

A standard is a hard contract. A deviation is
an ADR + a vote in the AI council (skill 05).

---

## 9. Working with this document

When a skill needs to deviate from a standard:

1. Open an ADR under `docs/adr/`.
2. Identify the standard + the cost of NOT
   deviating.
3. Compare 2+ alternatives.
4. Submit the ADR to the AI council (skill 05).
5. The council votes.
6. The orchestrator files the deviation in
   `docs/adr/active-deviations.md`.

A deviation without an ADR is a bug.
