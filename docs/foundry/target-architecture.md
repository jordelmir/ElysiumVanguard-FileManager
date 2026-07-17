---
title: Target Architecture — Elysium Automotive Foundry
status: Phase 0 deliverable, signed 2026-07-17
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audited_by: skill 01 (repository-archaeology)
---

# Target Architecture — Elysium Automotive Foundry

> **Status:** Phase 0 deliverable. The
> target state the implementation
> roadmap (per
> `implementation-roadmap.md`) is
> working toward. **All 15 master
> prompt architectural surfaces** are
> present: Project Management,
> Requirements, Vehicle Definition
> Language, Deterministic Vehicle
> Compiler, Vehicle Domain Ontology,
> Digital Twin, 3D Asset Registry,
> AI Engineering Council, Diagnostic
> Graph, Contribution and Provenance
> Ledger, Contract and Licensing
> Engine, Royalty Calculation Engine,
> Marketplace, Supplier Network, Safety
> and Regulatory Evidence, Security
> and Audit, Observability.

---

## 1. Architectural principles

The target architecture honors every
principle in `.ai/AGENTS.md` section 3:

- Domain-Driven Design.
- Clean Architecture (`domain/` /
  `application/` /
  `infrastructure/` / `interfaces/`).
- SOLID + GRASP.
- Explicit use cases.
- Dependency inversion (domain
  code MUST NOT import
  infrastructure code).
- Immutable domain events.
- Strongly typed identifiers.
- Transactional outbox.
- Optimistic concurrency.
- Idempotent commands.
- Append-only audit trails.
- Content-addressed storage.
- Versioned schemas +
  deterministic migrations.
- Zero Trust security.
- Least privilege.
- Default deny.
- Evidence-backed engineering
  data.

The platform begins as a
**modular monolith** (per
`.ai/AGENTS.md` section 3).
Microservices require an ADR + a
measurable scaling boundary.

---

## 2. System context (C4 level 1)

### 2.1 Actors

- **Designer** — the user who
  designs a vehicle via the
  DSL.
- **Engineer** — the user who
  reviews + signs off a design.
- **Mechanic** — the user who
  diagnoses + repairs a vehicle
  in the field.
- **Buyer** — the user who
  browses + purchases a
  project.
- **Supplier** — the user who
  supplies parts.
- **Reviewer (regulator)** —
  the user who approves a
  regulatory submission.
- **OEM relationship manager**
  — a background process that
  maintains the OEM catalog.
- **AI council** — a
  background process that
  runs the AI's structured
  tools.

### 2.2 External systems

- **Identity provider** (OIDC /
  OAuth 2.1 — Keycloak or
  equivalent).
- **Object storage**
  (S3-compatible, or Supabase
  Storage).
- **Payment provider**
  (third-party SaaS).
- **Regulatory filings** (UN,
  EU, ISO, etc.).
- **External AI providers**
  (when the AI council needs a
  remote model).
- **On-device LLM**
  (MediaPipe Phi-3-mini, when
  the on-device tier is used).

### 2.3 The platform's role

The platform is the
**trustworthy transactional core**
that:

- Records evidence (per
  `.ai/STANDARDS.md` section 3).
- Executes configured
  agreements (per skill 09 +
  skill 10).
- Provides a deterministic
  compiler (per skill 04).
- Provides a 3D / CAD asset
  pipeline (per skill 06).
- Provides a digital twin +
  diagnostic graph (per skill
  07).
- Provides a marketplace +
  supplier network (per skill
  10).
- Provides a regulatory
  evidence model (per skill
  13).
- Provides a security +
  audit posture (per skill
  12).
- Provides observability
  (per skill 15).

The platform does **not** create
legal rights by code. The
platform does **not** sign a
safety case. The platform does
**not** certify compliance. The
platform does **not** declare
a design safe. The platform
**organizes evidence and
workflows** (per skill 13).

---

## 3. Container view (C4 level 2)

The target deployable units:

### 3.1 Mobile app (the Forge surface)

- **Module:** `:foundry-app`
  (new Gradle module,
  added in Phase 1).
- **Tech stack:** Kotlin +
  Jetpack Compose + Hilt +
  Room + Filament (per
  `.ai/STANDARDS.md` section
  1).
- **Reuses:** the existing
  `:app` module's Hilt
  structure, Compose
  patterns, Room DB, Tink
  vault, MINA SSHD, MediaPipe
  GenAI (the on-device AI
  tier).
- **Adds:** the 14-section
  project workspace (per
  skill 11), the 8 FORGE
  primary actions, the 11-step
  vehicle creation flow, the
  8 visual truth badges.
- **Does NOT replace:** the
  file manager or the
  universal runtime. The
  Forge is a **new surface**
  that coexists.

### 3.2 Backend event platform

- **Module:** `:foundry-backend`
  (new Gradle subproject,
  added in Phase 2).
- **Tech stack:** Kotlin +
  Spring Boot **or** Ktor
  (the discovery-time ADR
  must pick one).
- **Responsibilities:** the
  12 bounded contexts (per
  skill 08 section 2) +
  the 9-step transaction
  model + the 6 RFP-required
  properties.
- **Database:** PostgreSQL
  16+ (per `.ai/STANDARDS.md`
  section 1).
- **Cache:** Redis 7+ (when
  required).
- **Object storage:**
  S3-compatible, or Supabase
  Storage.

### 3.3 Geometry / compiler workers

- **Module:**
  `:foundry-geometry` (new
  Gradle subproject, added in
  Phase 2).
- **Tech stack:** Rust
  (per `.ai/STANDARDS.md`
  section 1) — for
  deterministic compilation,
  unit normalization, LOD
  generation, mesh
  validation, KTX2 conversion.
- **Triggered by:** the
  mobile app + the backend
  (the compiler service).
- **Stateless; horizontally
  scalable.**

### 3.4 AI council + observability

- **Module:** `:foundry-ai`
  (new Gradle subproject,
  added in Phase 2).
- **Tech stack:** Kotlin +
  Python (for the model
  integrations) + the
  on-device Phi-3-mini tier.
- **Responsibilities:** the
  16 agent roles (per skill
  05 section 2) + the
  per-role budgets + the
  evidence policy + the
  prompt-injection
  protection.
- **NOT authoritative:** the
  AI council produces
  proposals; the
  deterministic engine +
  human review apply them.

### 3.5 Web (deferred to Phase 7+)

A web app is **out of scope** for
Phases 0–6. The master prompt does
not require a web app. The web app
is a Phase 7+ deliverable (per
`.ai/AGENTS.md` section 1).

---

## 4. Component view (C4 level 3)

The 17 master-prompt architectural
surfaces map to the 16 skills in
`.ai/skills/`. The mapping is:

| Master-prompt surface | Skill | Component name |
|---|---|---|
| Project Management | skill 03 (ontology) + skill 08 (backend) | `:foundry-core:project` |
| Requirements | skill 02 (PRD) | `:foundry-core:requirements` |
| Vehicle Definition Language | skill 04 (DSL) | `:foundry-core:dsl:lang` |
| Deterministic Vehicle Compiler | skill 04 | `:foundry-core:dsl:compiler` |
| Vehicle Domain Ontology | skill 03 | `:foundry-core:ontology` |
| Digital Twin | skill 07 | `:foundry-core:twin` |
| 3D Asset Registry | skill 06 | `:foundry-core:assets:registry` |
| AI Engineering Council | skill 05 | `:foundry-ai:council` |
| Diagnostic Graph | skill 07 | `:foundry-core:diagnostic:graph` |
| Contribution + Provenance Ledger | skill 09 | `:foundry-core:provenance:ledger` |
| Contract + Licensing Engine | skill 09 | `:foundry-core:contract` |
| Royalty Calculation Engine | skill 09 | `:foundry-core:royalty:engine` |
| Marketplace | skill 10 | `:foundry-core:marketplace` |
| Supplier Network | skill 10 | `:foundry-core:supplier` |
| Safety + Regulatory Evidence | skill 13 | `:foundry-core:regulatory` |
| Security + Audit | skill 12 | `:foundry-core:security` + `:foundry-audit` |
| Observability | skill 15 | `:foundry-observability` |

### 4.1 The Foundry's "core" module split

The target is a **multi-module
Android + JVM project**:

```
:foundry:core:ontology
:foundry:core:requirements
:foundry:core:dsl:lang
:foundry:core:dsl:compiler
:foundry:core:assets:registry
:foundry:core:twin
:foundry:core:diagnostic:graph
:foundry:core:project
:foundry:core:contract
:foundry:core:royalty:engine
:foundry:core:provenance:ledger
:foundry:core:marketplace
:foundry:core:supplier
:foundry:core:regulatory
:foundry:core:security
:foundry:audit
:foundry:ai:council
:foundry:geometry
:foundry:backend
:foundry:app
```

The Android `:app` module
remains (the file manager +
universal runtime). The
`:foundry:app` module is a
**new** Compose app for the
Forge surface. The two apps
**coexist** in the same APK
for now (Phase 1) and may be
split into two APKs in
Phase 7 (per the deployment
strategy in skill 15).

---

## 5. Cross-cutting concerns

The target architecture honors
every concern in `.ai/AGENTS.md`
section 24:

- **Stable machine-readable
  code.** Typed values at
  every boundary. No
  `Map<String, Any>`. No
  free-form strings.
- **Safe user-facing message.**
  Short, actionable, jargon-
  free, secret-free,
  localized.
- **Correlation ID.**
  Propagated through every
  downstream call.
- **Retry classification.**
  Every error has a
  `retryable_immediate` /
  `retryable_backoff` /
  `retryable_idempotent_only`
  / `non_retryable`.
- **No leak.** Internal stack
  traces + secrets are
  stripped at the API
  boundary.
- **Relevant structured
  metadata.** Every event
  carries `correlationId` +
  `tenantId` + `userId` +
  `vehicleId` +
  `vehicleRepresentationLevel`
  + `verificationStatus` +
  `source` + `sourceType`.

---

## 6. Bridges (the migration path from current to target)

Per `.ai/STANDARDS.md`, the
non-negotiable constraint is
"**No reescribas el proyecto desde
cero**". The bridges are the
non-breaking path.

### Bridge 1 — Reuse the existing
Hilt DI structure

**From:** `core/runtime/RuntimeModule.kt`
**To:** `foundry/core/foundry/FoundryModule.kt`
**Action:** the
`FoundryModule` follows the
same `@Module` +
`@InstallIn(SingletonComponent::class)`
pattern. **No change** to the
existing module.

### Bridge 2 — Reuse the existing
Compose UI patterns

**From:** 26 `features/`
packages (Compose)
**To:** 14 new screens in
`foundry/app/` (Compose)
**Action:** the new screens
follow the same Hilt +
`@HiltViewModel` + `Flow`
pattern. **No change** to the
existing features.

### Bridge 3 — Reuse the existing
Tink vault

**From:** `core/vault/`
**To:** `foundry/core/security/VaultPort.kt`
**Action:** the
`VaultPort` is a small
interface that wraps the
Tink `Aead`; the security
module depends on the
VaultPort; the production
module provides the Tink
impl. **No change** to the
Tink vault.

### Bridge 4 — Reuse the existing
MINA SSHD

**From:** `core/sftp/`
**To:** `foundry/core/marketplace/ControlledDisclosure.kt`
**Action:** the
`ControlledDisclosure` uses
MINA SSHD for the SFTP-based
data room access (per skill
10 section 4). **No change**
to the SSHD server.

### Bridge 5 — Reuse the existing
ML Kit (OCR + image labeling)

**From:** `core/ocr/`,
`core/ai/`
**To:** `foundry/core/catalog/PartsCatalog.kt`
**Action:** the parts catalog
uses ML Kit text recognition
to OCR a part label + image
labeling to identify a part
image. **No change** to the
existing OCR.

### Bridge 6 — Add a new
module without changing the
existing one

**Action:** the `:foundry:`
modules are **new**
subprojects. The existing
`:app` module is untouched.
**No** existing code is
modified to add the
Foundry.

### Bridge 7 — Defer the
backend to Phase 2

**Action:** Phases 0–1 are
Android-only. The backend
events in Phase 2 are
**app-side stubs** (the
backend service does not
exist yet). The mobile app
+ the geometry workers + the
AI council (on-device tier)
suffice for Phases 0–1.

### Bridge 8 — Re-architect
the app for multi-module in
Phase 7

**Action:** the
**multi-module split** is
a Phase 7 deliverable. Until
then, the existing `:app`
module and the new
`:foundry:app` module
coexist in the same Gradle
build.

### Bridge 9 — Preserve the
1,380 unit tests

**Action:** the existing
tests are **not touched**.
The Foundry adds its own
test suite alongside. The
CI runs both. The 1,380
tests stay green throughout.

### Bridge 10 — Carry the
ADRs forward

**Action:** the existing
ADR-001 through ADR-019
(per `docs/adr/`) are valid
for the universal runtime.
The Foundry adds new
ADRs (per
`docs/foundry/implementation-roadmap.md`).

---

## 7. The cut-over plan

The cut-over is **progressive**.
There is no "big bang" migration.

- **Phase 0** — done. The
  foundation + the 6 docs
  are in place. G0 is green.
- **Phase 1** — the Foundry
  domain model + the
  Forge surface. The
  existing app is
  untouched. The 1,380 tests
  stay green.
- **Phase 2** — the 18-step
  DSL compiler + the
  geometry worker + the
  digital twin (on-device
  simulation). The
  existing app is
  untouched.
- **Phase 3** — the AI
  council (on-device tier
  with Phi-3-mini + cloud
  tier with the agent
  roles). The existing app
  is untouched.
- **Phase 4** — the IP /
  provenance / royalty
  engine (Android-side
  stub of the backend
  contract). The existing
  app is untouched.
- **Phase 5** — the
  marketplace + supplier
  network (Android-side
  stub + cloud tier for
  the matchmaking). The
  existing app is untouched.
- **Phase 6** — the
  marketplace + manufacturing
  network (the controlled
  disclosure + the
  manufacturing readiness
  gates). The existing app
  is untouched.
- **Phase 7** — production
  hardening + the
  multi-module split +
  the feature-flag rollout.
  At this point the
  existing app + the
  Foundry may be split
  into two APKs.

---

## 8. Non-negotiables (per `.ai/STANDARDS.md`)

The target architecture honors
**every** non-negotiable in
`.ai/STANDARDS.md` section 2:

- **No invented OEM data.**
  The Foundry's
  `EngineeringFact<T>` is
  the source of truth (per
  section 3).
- **No invented dimensions /
  torques / pinouts / geometry
  / homologation.** Every
  value carries a source +
  a verification status.
- **No presenting AI
  geometry as OEM geometry.**
  A generated mesh is
  `PARAMETRIC_FUNCTIONAL` or
  `CONCEPTUAL`. The
  representation level is
  displayed prominently.
- **No visual-mesh-as-
  mechanical-compatibility.**
  The 3D viewer is not an
  oracle.
- **No royalty without an
  active contract.** Per
  skill 09's 8 top-level
  guarantees.
- **No mutable historical
  releases.** Per skill 09.
- **No `Double` for money.**
  `BigDecimal` only.
- **No generic catch.** Typed
  errors only.
- **No unchecked null.**
- **No `unwrap` / `expect` in
  production Rust.**
- **No main-thread blocking
  on Android.**
- **No trusted 3D assets.**
- **No embedded scripts in
  assets.**
- **No secrets in the app
  package.**
- **No LLM directly mutates
  authoritative state.** The
  AI council is a draft; the
  engine + a human review
  apply it.
- **No "AI claimed road
  legal" flag.** The
  `RoadLegal` flag requires
  `ENGINEER_REVIEWED` +
  `REGULATORY_VERIFIED` +
  human counter-signature.
- **No hardcoded "Elysium
  always 5%".** Per the
  master prompt + per skill
  09: the 5% is a
  per-contract
  `ELYSIUM_INCUBATED` rule,
  never hardcoded.
- **No blockchain** without
  an ADR proving value over
  append-only + signatures +
  hashes + time-stamping.
- **No microservices, Kafka,
  Kubernetes, or event
  sourcing total** without a
  demonstrable need. The
  Foundry begins as a
  modular monolith.

---

## 9. The skill mapping (the skills architecture as the architecture)

The 16 skills in `.ai/skills/`
**are** the architecture. Each
skill is a bounded context with
a `SKILL.md` contract. The
contracts are enforced by the
quality skill (skill 14) +
the verifier.

The skill-to-component map is in
section 4. The orchestrator
(skill 00) owns the
**cross-skill contracts**
(per `.ai/AGENTS.md` section 17).

---

## 10. The target architecture summary

The Foundry is a **modular
monolith** with:

- A new **mobile** surface
  (the Forge).
- A new **backend** service
  (Kotlin + Spring Boot or
  Ktor).
- A new **geometry** worker
  (Rust).
- A new **AI** service (Kotlin
  + Python).
- A new **PostgreSQL** schema
  (12 bounded contexts).
- A new **S3-compatible** object
  store.
- A new **CI/CD** pipeline
  (per skill 15).
- A new **observability** stack
  (OpenTelemetry).

The existing Elysium Vanguard
File Manager + Universal Runtime
are **preserved**. The Foundry
**coexists** with them in the
same APK for Phases 0–6, then
**may be split** in Phase 7.

The architecture is **net-new
additive**, not **migratory**.
The non-negotiables are honored.
The skill contracts are the
contracts. The implementation
roadmap is the path.
