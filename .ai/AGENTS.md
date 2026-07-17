# Elysium Automotive Foundry — Global Engineering Contract

> **Status:** Living document. Every skill in `.ai/skills/`
> inherits this contract. Any change to a skill that
> weakens a global rule is a regression and must be
> escalated to the program orchestrator.

---

## 1. Mission

Build **Elysium Automotive Foundry** as a
production-grade platform where users can:

1. Create proprietary vehicle brands and projects.
2. Design vehicles through natural language and
   structured engineering tools.
3. Generate versioned digital twins.
4. Assemble mechanical, electrical, electronic, and
   software architectures.
5. Collaborate with humans and specialized AI agents.
6. Record authorship, contributions, dependencies, and
   intellectual-property provenance.
7. Validate compatibility, manufacturability,
   diagnostic coverage, and repairability.
8. Publish, license, and commercialize qualified
   projects.
9. Calculate contractual royalties and distribute
   revenue.
10. Connect vehicle designs with suppliers,
    engineers, laboratories, and manufacturers.

The system is **not** a videogame configurator. It is
an engineering, collaboration, commercialization, and
digital-twin platform.

---

## 2. Global Operating Rules

Before modifying code, every agent MUST:

1. Inspect the repository structure (`tree -L 3` or
   equivalent).
2. Read the build configuration.
3. Identify the current architecture (monolith vs.
   modular monolith vs. services).
4. Locate existing 3D, diagnostic, vehicle, AI,
   authentication, and database modules.
5. Run the current test suite and record the baseline.
6. Produce a dependency and risk map for the
   proposed change.
7. Preserve working behavior unless an approved
   migration ADR explicitly replaces it.

**Never** rewrite the application blindly.

---

## 3. Architectural Principles

Use the following as the default, and document
deviations in an ADR under `docs/adr/`:

- **Domain-Driven Design** for core automotive and
  commercial domains.
- **Clean Architecture** boundaries
  (`domain/`, `application/`, `infrastructure/`,
  `interfaces/`).
- **SOLID** and **GRASP**.
- **Explicit use cases.** No "god services" or
  "manager of managers".
- **Dependency inversion.** Domain code MUST NOT
  import infrastructure code.
- **Immutable domain events.**
- **Strongly typed identifiers**
  (`typealias ProjectId = Uuid`).
- **Transactional outbox** for reliable event
  publication across the boundary.
- **Optimistic concurrency control** on every
  aggregate.
- **Idempotent commands.** Every command MUST be
  safely re-runnable with the same ID.
- **Append-only audit trails** for critical records
  (authorship, royalties, regulatory submissions).
- **Content-addressed storage** for immutable
  engineering artifacts (3D models, telemetry
  recordings, signed approvals).
- **Versioned schemas and deterministic migrations.**
- **Zero Trust security.** Never trust a caller by
  network position.
- **Least privilege.** Default deny.
- **Evidence-backed engineering data.** A claim
  without a test, a run, or a log is a hypothesis.

Do **not** introduce distributed microservices merely
for appearance. Begin with a modular monolith unless:

1. A measurable scaling boundary exists.
2. Independent deployment is required.
3. Data ownership is explicit.
4. Failure isolation justifies the operational cost.
5. An Architecture Decision Record approves the split.

---

## 4. Default Technical Direction

Preserve the existing project stack whenever
technically sound. Before introducing a new
dependency, document:

- The license (must be compatible with the project
  license).
- The CVE history (must be clean for the last 12
  months).
- The maintenance signal (must be active within the
  last 6 months).
- The on-disk footprint (must not exceed the
  documented budget).
- The replacement risk (what happens if the project
  is abandoned tomorrow).

### 4.1 Canonical tech stack

The canonical tech stack is a contract. A skill
that introduces a new dependency in one of these
slots without an ADR is a contract violation.

| Slot | Default |
|---|---|
| Android | Kotlin, Jetpack Compose, coroutines, Flow |
| Web | React + TypeScript |
| Mobile 3D | Filament (Android / iOS) with glTF / GLB |
| Backend application services | Kotlin + Spring Boot **or** Ktor, per repository convention |
| Geometry / compiler workers | Rust (when deterministic computation, memory control, or concurrency justifies it) |
| Database (OLTP) | PostgreSQL |
| Object storage | S3-compatible, or Supabase Storage |
| Cache + transient coordination | Redis only when required |
| API contracts | OpenAPI + generated clients |
| Internal events | Typed domain events (per skill 08) |
| Authentication | OIDC / OAuth2-compatible |
| Authorization | RBAC + ABAC |
| Observability | OpenTelemetry |
| Infrastructure | Docker + reproducible CI/CD |

When no equivalent implementation exists, prefer
(before reaching for third-party SaaS):

| Concern | Default choice |
|---|---|
| Language (backend) | Kotlin (JVM 17+) or Go (1.22+) |
| Language (frontend) | TypeScript (strict mode) |
| Language (mobile) | Kotlin (Android) / Swift (iOS) |
| UI (web) | React + Vite or SolidJS |
| UI (mobile) | Jetpack Compose / SwiftUI |
| Database (analytics) | ClickHouse or DuckDB |
| Search | OpenSearch (or PostgreSQL FTS for < 1M docs) |
| Message bus | NATS or PostgreSQL LISTEN/NOTIFY |
| Identity | OIDC (Keycloak or self-hosted) |
| 3D viewer (web) | Three.js + glTF |
| CAD kernel | OpenCascade (OCCT) — vendored |
| CI | GitHub Actions or self-hosted Woodpecker |
| IaC | Terraform (Pulumi acceptable) |

Every choice in the canonical table is a **default
+ a contract**. A skill MAY propose a different
choice with an ADR that:

- Names a measurable constraint the default cannot
  satisfy.
- Compares 2+ alternatives with their trade-offs.
- Identifies the migration path away from the
  alternative if it fails.

The full canonical stack is in
[`.ai/STANDARDS.md`](./STANDARDS.md) section 1.

---

## 5. Non-negotiable Engineering Constraints

These are **non-negotiable**. A skill, an agent, a
PR, or a release that violates any of these is a
contract violation. The orchestrator blocks the
release; the security skill escalates the incident;
the regulatory skill files the RIA.

### 5.1 Data integrity

- **Never invent OEM data.** Every OEM figure
  MUST be sourced from a verifiable OEM document.
  An AI-inferred value is `AI_INFERRED`, never
  `OEM_VERIFIED` (see section 6).
- **Never invent dimensions, torques, pinouts, or
  homologation requirements.** Every value MUST
  carry the full truth-metadata (section 6).
- **Never present AI-generated geometry as
  validated production CAD.** A generated mesh is
  `PARAMETRIC_FUNCTIONAL` or `CONCEPTUAL`
  (section 7). Promoting it to `OEM_EXACT` or
  `OEM_PARTIAL` is a contract violation.
- **Never treat visual mesh compatibility as
  mechanical compatibility.** The compatibility
  check is the fault model + engineering review,
  not the 3D viewer.

### 5.2 Commercial integrity

- **Never calculate royalties without an active
  contract version.** Royalties are computed only
  against a `RoyaltyContract` whose status is
  `ACTIVE` (skill 09).
- **Never allow mutable historical commercial
  releases.** A signed release is append-only. A
  rollback is a new release, not an edit.
- **Never store money using floating-point types.**
  Money is `BigDecimal` (JVM), `decimal.Decimal`
  (Python), or `rust_decimal::Decimal` (Rust).

### 5.3 Code hygiene

- **Never use generic catch blocks that hide
  failures.** Every catch block MUST re-throw, log
  with a typed error, or return a typed `Result` /
  `Either`.
- **Never use unchecked null assertions.** A `!!`
  in Kotlin, an unchecked `as` in TypeScript, an
  `unwrap()` in production Rust, a hard `Object!`
  cast in C# is a contract violation.
- **Never use `unwrap`, `expect`, or panic-driven
  control flow in production Rust.** The error
  path is typed.

### 5.4 Concurrency

- **Never block the Android main thread with model
  loading, decoding, or network work.** Every
  heavy operation is on `Dispatchers.IO`. The
  Compose render path is non-blocking.

### 5.5 Trust

- **Never trust imported 3D assets.** Every asset
  is validated (manifold, units, coordinate system,
  file size) before it enters the canonical store
  (skill 06).
- **Never execute scripts embedded in uploaded
  assets.** A glTF with a script, a STEP with
  macros, a USD with a custom schema that runs
  code: rejected at the parse step.
- **Never place secrets in the application
  package.** Secrets live in the vault (skill 12).
  A secret in code / config / assets / build
  artifacts is a P0 incident.

### 5.6 AI authority

- **Never let an LLM directly mutate authoritative
  financial or engineering state.** A model
  proposes; a deterministic engine + human review
  applies.
- **Never mark a vehicle as road legal based
  solely on AI output.** A `RoadLegal` flag
  requires `ENGINEER_REVIEWED` AND
  `REGULATORY_VERIFIED` AND a human
  counter-signature.

The full non-negotiable list, the rationale, and
the recovery patterns are in
[`.ai/STANDARDS.md`](./STANDARDS.md) section 2.

---

## 6. Truth and Confidence Model

Every engineering fact in the platform MUST carry
the following metadata. A fact without provenance
is a hypothesis.

| Field | Type | Notes |
|---|---|---|
| `Source` | `String` | URL, doc id, sensor id, etc. |
| `Source type` | `String` | `OEM_DOC`, `REGULATORY_FILING`, `LAB_REPORT`, `ENGINEER_MEMO`, `TELEMETRY`, `AI_INFERENCE`, `USER_INPUT`, `COMMUNITY` |
| `Jurisdiction or market` | `String?` | EU / US / CN / BR / etc. — when applicable |
| `Vehicle applicability` | `String` | The `VehicleId` (or family) the fact applies to |
| `Revision` | `String` | The `RevisionId` of the spec the fact is associated with |
| `Confidence` | `Float` in [0.0, 1.0] | Per the verifier (skill 14) |
| `Verification status` | `VerificationStatus` | See below |
| `Reviewer` | `String?` | The human who reviewed the fact, if any |
| `Timestamp` | `ISO-8601` | When the fact was recorded |

**Verification status** is one of:

- `OEM_VERIFIED` — sourced from an OEM document,
  OEM signed off.
- `REGULATORY_VERIFIED` — sourced from a regulatory
  filing (UN, EU, ISO, etc.).
- `LAB_VERIFIED` — produced by a lab test.
- `ENGINEER_REVIEWED` — a human engineer reviewed
  + signed.
- `COMMUNITY_CORROBORATED` — 3+ independent
  contributors corroborate.
- `AI_INFERRED` — produced by an AI model (model,
  version, prompt, temperature in the audit trail).
- `UNKNOWN` — no source. Refused in production
  paths; allowed only in dev / preview.

**`AI_INFERRED` MUST NOT silently become
`VERIFIED`.** The transition is a human review + a
signed counter-signature, recorded in the audit
trail (skill 09).

The in-code shape is the value class
`EngineeringFact<T>` (owned by skill 03; mirrored
in every language the platform uses). The full
spec, including the storage shape and the
in-code example, is in
[`.ai/STANDARDS.md`](./STANDARDS.md) section 3.

---

## 7. Vehicle Representation Levels

Every generated vehicle MUST declare one
representation level on the `Vehicle` aggregate
(per skill 03). The level is **append-only**. The
transition is:

```
VISUAL_ONLY → CONCEPTUAL → PARAMETRIC_FUNCTIONAL → OEM_PARTIAL → OEM_EXACT
```

A regression is forbidden. A new level is a
signed revision.

| Level | Meaning |
|---|---|
| `OEM_EXACT` | Vehicle spec produced from an OEM's authoritative document. Every fact is `OEM_VERIFIED` or higher. |
| `OEM_PARTIAL` | OEM document for a subset of the parts; the rest are `PARAMETRIC_FUNCTIONAL` or `CONCEPTUAL`. UI shows which parts are OEM and which are not. |
| `PARAMETRIC_FUNCTIONAL` | Produced by the platform's parametric model, validated by a human engineer. Every fact is `ENGINEER_REVIEWED` or higher. |
| `CONCEPTUAL` | Concept; no engineer has signed it. UI must show "CONCEPTUAL — not validated". |
| `VISUAL_ONLY` | Visual mock; engineering is not validated. UI must show "VISUAL_ONLY — not for production". |

A `VISUAL_ONLY` or `CONCEPTUAL` vehicle is NOT
eligible for:

- Marketplace listing (skill 10)
- Royalty settlement (skill 09)
- Regulatory submission (skill 13)
- Field diagnostic reference (skill 07)

The mobile UI (skill 11) MUST display the level
prominently on every vehicle card. A `VISUAL_ONLY`
or `CONCEPTUAL` vehicle's UI MUST include a
"this is not validated" warning. The orchestrator's
verifier (skill 14) checks the UI for this
constraint.

The full rules, the UI requirements, and the
transition protocol are in
[`.ai/STANDARDS.md`](./STANDARDS.md) section 4.

---

## 8. AI Authority Boundary

The AI in the platform is a **drafting tool**, not
an **authority**. The platform's authoritative
workflow is:

```
natural language
  → structured proposal (the AI)
  → schema validation (the deterministic engine)
  → simulation or evidence (the deterministic engine + skill 07)
  → human review (the reviewer in the catalog)
  → signed revision (the catalog)
```

A model is a draft. A deterministic engine + a
human review apply the draft.

### 8.1 What AI may do

The AI may:

- Interpret requirements.
- Propose architectures.
- Generate candidate configurations.
- Explain trade-offs.
- Resolve terminology.
- Suggest validation plans.
- Generate drafts.
- Identify inconsistencies.
- Produce structured commands for deterministic
  engines.

### 8.2 What AI may NOT directly do

The AI may NOT directly:

- Approve safety-critical requirements.
- Certify regulatory compliance.
- Declare mechanical compatibility.
- Finalize financial settlements.
- Determine legal ownership.
- Modify signed releases.
- Create verified technical facts without
  evidence.

A decision that bypasses the workflow is a
contract violation. The full boundary, the
rationale, and the recovery patterns are in
[`.ai/STANDARDS.md`](./STANDARDS.md) section 5.

---

## 9. Delivery Rules

Work in **vertical, reviewable increments**. An
increment is a slice that ships end-to-end (domain
→ DB → use case → API → UI → auth → errors →
tests → observability → docs → migration).

### 9.1 Required contents of an increment

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
6. **Authorization checks** — every new action
   has an auth check (per skill 12).
7. **Structured errors** — every new failure
   path is a typed error (per section 10).
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
violation. The verifier (skill 14) rejects the PR.

### 9.2 Verification report

Every increment is verified by skill 14 before
merge. The verification report is the
orchestrator's primary input. The full
verification gate list is in
[`.ai/STANDARDS.md`](./STANDARDS.md) section 6.

---

## 10. Required Error Model

Use **explicit domain and application errors**. An
error is not a string; an error is a typed value
with a code, a message, a cause, a recovery hint,
and the provenance metadata (per section 6).

### 10.1 Canonical errors

The platform uses the following canonical errors.
A skill that introduces a new error type without
an ADR is a contract violation; the new error MUST
be added to the canonical list.

| Error | When | Recovery |
|---|---|---|
| `VehicleDefinitionInvalid` | The spec fails validation (DSL / ontology rejected it) | The user fixes the spec. |
| `CompatibilityConstraintViolation` | Two parts are not compatible | The user picks compatible parts. |
| `ArtifactIntegrityFailure` | Hash / signature does not verify | The user re-uploads the artifact. |
| `ContractNotActive` | A royalty contract is not `ACTIVE` | The user activates the contract, or picks an active one. |
| `RoyaltyCalculationRejected` | The royalty engine rejected the calc | The user fixes the contract. |
| `UnauthorizedProjectAccess` | The user does not have access | The user requests access. |
| `RevisionConflict` | Optimistic-concurrency collision | The user refreshes + retries. |
| `ProvenanceIncomplete` | An engineering fact is missing required metadata | The user provides the metadata. |
| `SafetyGateNotSatisfied` | A `SafetyGoal` is not `REGULATORY_VERIFIED` + `ENGINEER_REVIEWED` + counter-signed | The user obtains the missing verifications. |
| `AssetLimitExceeded` | A per-asset limit (file size, polygon count) is exceeded | The user reduces the asset or requests a limit exception (an ADR). |

### 10.2 Error shape

Errors are typed (`sealed class` / value class /
tagged union), never free-form strings. Errors
flow through the platform as typed values:

- Domain → Application: `Result<DomainValue,
  FoundryError>` (Kotlin) or `Either<FoundryError,
  DomainValue>` (Haskell-style) or tagged union
  (TypeScript).
- Application → Infrastructure: typed JSON
  envelope (code, message, field, reason,
  provenance).
- Application → UI: the UI receives the typed
  error and renders a typed message.

A `throw Exception("oops")` is a contract
violation. A string-only error path is a contract
violation. The full error transport, the
serialization envelope, and the logging contract
are in [`.ai/STANDARDS.md`](./STANDARDS.md)
section 7.

---

---

## 11. Skills Architecture

The platform is built by a **team of specialized
agents**, each one mapped to a skill under
`.ai/skills/`. The orchestrator (skill 00) is the
only skill that has the full picture; every other
skill owns a bounded context.

### 11.1 The 16 Skills

| # | Skill | Bounded context |
|---|---|---|
| 00 | `program-orchestrator` | Cross-skill coordination, ADRs, release gates |
| 01 | `repository-archaeology` | Read a code base before touching it |
| 02 | `product-requirements` | PRDs, user stories, acceptance criteria |
| 03 | `vehicle-domain-ontology` | The vehicle / brand / project / part model |
| 04 | `vehicle-dsl-compiler` | The DSL for assembling architectures |
| 05 | `ai-engineering-council` | Multi-agent deliberation, voting, escalation |
| 06 | `3d-cad-asset-pipeline` | glTF / STEP / USD import, validate, store |
| 07 | `digital-twin-diagnostics` | Telemetry ingestion, fault models, repair flows |
| 08 | `backend-event-platform` | Event bus, projections, sagas, outbox |
| 09 | `ip-provenance-royalties` | Authorship, contributions, royalty contracts |
| 10 | `marketplace-manufacturing` | Listings, escrow, supplier integration |
| 11 | `mobile-forge-ux` | Field UX for the on-device forge |
| 12 | `security-zero-trust` | Identity, secrets, threat modeling |
| 13 | `functional-safety-regulatory` | ISO 26262, UN R155/R156, homologation |
| 14 | `quality-verification` | Test strategy, fuzzing, coverage, mutation |
| 15 | `devops-observability` | CI, SLOs, tracing, on-call |

### 11.2 Skill Contract

Every skill MUST publish a `SKILL.md` with:

1. **Mission** — one paragraph: what the skill owns.
2. **In-scope / out-of-scope** — explicit list. A
   skill that tries to do everything is a smell.
3. **Inputs** — what the skill consumes (artifacts
   from other skills, environment variables, user
   requests).
4. **Outputs** — what the skill produces. Every
   output MUST be versioned + signed (when
   applicable) and stored under the artifact
   contract (section 6).
5. **Workflow** — a numbered sequence the agent
   MUST follow.
6. **Quality gates** — automated checks that MUST
   pass before the skill declares done.
7. **Failure modes** — what the skill does when a
   quality gate fails, when a downstream skill is
   missing, or when the user request is ambiguous.
8. **Coordination contract** — which other skills
   this skill calls; which other skills call this
   one; what the contract is.
9. **Forbidden patterns** — anti-patterns this
   skill MUST NOT introduce. Every forbidden
   pattern is a runtime check (lint rule, CI guard,
   or pre-commit hook).

A skill that does not list its **forbidden patterns**
is not yet production-ready.

### 11.3 Skill Topology

```
                ┌──────────────────────────┐
                │   00 program-orchestrator │
                └──────────────┬───────────┘
                               │ coordinates
        ┌──────────┬───────────┼───────────┬──────────┐
        │          │           │           │          │
   ┌────▼───┐ ┌────▼───┐ ┌─────▼─────┐ ┌───▼────┐ ┌───▼────┐
   │ 01     │ │ 02     │ │ 03        │ │ 04     │ │ 05     │
   │ repo   │ │ prd    │ │ ontology  │ │ dsl    │ │ council│
   └────┬───┘ └────┬───┘ └─────┬─────┘ └───┬────┘ └───┬────┘
        │          │           │           │          │
        │          │     ┌─────┴─────┐     │          │
        │          │     │ 04 dsl     │◄────┘          │
        │          │     └─────┬─────┘                │
        │          │           │                      │
   ┌────▼──────────▼───────────▼──────────────────────▼────┐
   │  06 3d  │  07 twin  │  08 events │  09 ip  │  10 mkt │
   └─────────┴───────────┴────────────┴─────────┴─────────┘
        │          │           │           │          │
   ┌────▼──────────▼───────────▼──────────────────────▼────┐
   │  11 mobile  │  12 sec  │  13 safety │  14 qa  │  15 ops│
   └────────────┴──────────┴────────────┴─────────┴────────┘
```

Arrows = "calls" or "depends on". Every arrow MUST
have a documented contract (data shape, version,
auth).

---

## 12. Artifact Contract

Every cross-skill artifact (a 3D model, a diagnostic
trace, a royalty settlement, a regulatory submission)
MUST be:

- **Content-addressed.** The artifact ID is the
  SHA-256 of the artifact bytes. Two artifacts with
  the same bytes have the same ID; one artifact can
  be verified by recomputing the hash.
- **Versioned.** Every artifact has a `schema_version`
  field. Consumers MUST refuse artifacts with a
  schema version they do not recognize.
- **Signed.** Every artifact carries an Ed25519
  signature from the producing agent (the
  orchestrator's key for top-level artifacts; the
  producing skill's key for skill-internal ones).
  The signature covers the artifact bytes + the
  producer ID + the timestamp.
- **Append-only.** Artifacts are never mutated. A
  "new version" is a new artifact with a new ID
  that points to its predecessor.
- **Indexed.** Every artifact is registered in the
  catalog (skill 09) with its hash, producer,
  parent (if any), and the consuming skill(s).

The artifact contract is enforced by skill 14
(quality-verification). A skill that produces
artifacts out of contract is a quality-gate
failure.

---

## 13. Quality Gates (Global)

Every change MUST pass the following gates before
merge. Skills MAY add stricter gates; no skill MAY
weaken a global gate.

| Gate | Owner | Required? |
|---|---|---|
| Lint (language-specific) | skill 14 | Required |
| Type check (strict) | skill 14 | Required |
| Unit tests pass | skill 14 | Required |
| Integration tests pass | skill 14 | Required for cross-skill work |
| Coverage ≥ 80% on changed lines | skill 14 | Required |
| No new dependency without ADR | skill 00 | Required |
| Artifact contract honored | skill 14 | Required |
| No secrets in repo | skill 12 | Required |
| No license-incompatible deps | skill 09 | Required |
| Telemetry emitted for new code path | skill 15 | Required for non-trivial work |
| ADR for any architectural change | skill 00 | Required |
| Security review (light) for any new input surface | skill 12 | Required |

A failed gate is a **blocker**, not a warning. The
orchestrator does not approve a release with a
failing gate.

---

## 14. Security Posture

Assume breach. The platform:

- Authenticates every request (no anonymous routes
  except the public marketplace listing browse).
- Authorizes every action (RBAC + ABAC, see skill
  12).
- Audits every state-changing action (immutable
  audit log, see skill 09).
- Encrypts at rest and in transit (TLS 1.3,
  application-level encryption for sensitive
  fields).
- Keeps secrets out of source (managed via the
  vault contract in skill 12).
- Patches CVEs within 14 days for HIGH, 30 days
  for MEDIUM.
- Runs a quarterly red-team exercise on the auth
  + marketplace surfaces.

---

## 15. Regulatory Posture

The platform is a regulated product in several
jurisdictions. Skill 13 (functional-safety-regulatory)
owns the regulatory contract:

- **UN R155** (cybersecurity management system).
- **UN R156** (software update management system).
- **ISO 26262** (functional safety, automotive).
- **ISO 21434** (cybersecurity engineering, automotive).
- **GDPR** (EU data protection).
- **CCPA** (California).
- **LGPD** (Brazil).

Every feature that touches a regulated surface MUST
have a regulatory impact assessment before merge.
The assessment is filed under `docs/regulatory/` and
linked from the ADR.

---

## 16. Working with This Contract

When an agent (human or AI) needs to deviate from
this contract, the deviation:

1. MUST be documented in an ADR under
   `docs/adr/NNNN-title.md`.
2. MUST identify the cost of NOT deviating.
3. MUST be reviewed by skill 00 (program-orchestrator).
4. MUST be tracked in `docs/adr/active-deviations.md`.

A deviation without an ADR is a bug.

---

## 17. Coordination Protocol

When a skill needs help from another skill, the
protocol is:

1. **The caller writes a request** — a structured
   artifact under `.ai/requests/<skill>/<id>.md`
   with the inputs the callee needs.
2. **The callee picks up the request** — a
   well-known path; the callee's automation watches
   the directory.
3. **The callee writes a response** — a structured
   artifact under `.ai/responses/<skill>/<id>.md`
   with the output.
4. **The caller verifies the response** — checks
   the schema, the signatures, the quality gates.
5. **The caller closes the request** — moves both
   files under `.ai/archive/<skill>/<id>/`.

A request without a response within the SLA
(defined per skill) is escalated to the
orchestrator.

---

## 18. Local Development Contract

Every developer (human or AI agent) MUST:

- Run `./scripts/check.sh` before pushing.
- The script runs: lint, type check, unit tests,
  artifact contract check, secrets scan, license
  check.
- A green run is a prerequisite for merge. A
  failing run is a blocked push.
- The script is owned by skill 14 (quality-verification).

---

## 19. How to Use This Document

- **New agent onboarding** — read this document
  first. Then read the SKILL.md of the skill
  you are operating.
- **Adding a new skill** — add a new `SKILL.md`
  under `.ai/skills/`. Update section 11.1 of
  this document. Update section 11.3 (the topology)
  if the new skill has cross-skill calls.
- **Changing an existing skill** — update the
  `SKILL.md`. If the change violates a global
  rule, open an ADR.
- **Resolving a cross-skill conflict** — the
  orchestrator (skill 00) decides. The decision
  is filed under `docs/adr/`.

---

## 20. Companion Document

This document is the **global contract** — the
mission, the architecture, the topology, the
artifact contract, the security posture, the
regulatory posture, the delivery rules.

The **technical specifics** — the tech stack, the
non-negotiables, the truth model, the vehicle
representation levels, the AI authority boundary,
the delivery rules, the error model — live in
[`.ai/STANDARDS.md`](./STANDARDS.md). Every section
in this document that needs canonical detail (4.1,
5, 6, 7, 8, 9, 10) cross-references STANDARDS.md
for the full spec.

When this document and STANDARDS.md disagree,
**STANDARDS.md wins for technical specifics**; this
document wins for meta-rules (mission, topology,
artifact contract, security posture, regulatory
posture).

---

> "No skill is an island. The platform is the
> composition."
