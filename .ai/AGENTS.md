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

When no equivalent implementation exists, prefer
(before reaching for third-party SaaS):

| Concern | Default choice |
|---|---|
| Language (backend) | Kotlin (JVM 17+) or Go (1.22+) |
| Language (frontend) | TypeScript (strict mode) |
| Language (mobile) | Kotlin (Android) / Swift (iOS) |
| UI (web) | React + Vite or SolidJS |
| UI (mobile) | Jetpack Compose / SwiftUI |
| Database (OLTP) | PostgreSQL 16+ |
| Database (analytics) | ClickHouse or DuckDB |
| Cache | Redis 7+ |
| Object storage | S3-compatible (MinIO for local) |
| Search | OpenSearch (or PostgreSQL FTS for < 1M docs) |
| Message bus | NATS or PostgreSQL LISTEN/NOTIFY |
| Identity | OIDC (Keycloak or self-hosted) |
| 3D viewer (web) | Three.js + glTF |
| 3D engine (native) | Filament (Android/iOS) |
| CAD kernel | OpenCascade (OCCT) — vendored |
| Telemetry | OpenTelemetry |
| CI | GitHub Actions or self-hosted Woodpecker |
| IaC | Terraform (Pulumi acceptable) |

Every choice in this table is a default. A skill MAY
propose a different choice with an ADR that:

- Names a measurable constraint the default cannot
  satisfy.
- Compares 2+ alternatives with their trade-offs.
- Identifies the migration path away from the
  alternative if it fails.

---

## 5. Skills Architecture

The platform is built by a **team of specialized
agents**, each one mapped to a skill under
`.ai/skills/`. The orchestrator (skill 00) is the
only skill that has the full picture; every other
skill owns a bounded context.

### 5.1 The 16 Skills

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

### 5.2 Skill Contract

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

### 5.3 Skill Topology

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

## 6. Artifact Contract

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

## 7. Quality Gates (Global)

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

## 8. Security Posture

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

## 9. Regulatory Posture

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

## 10. Working with This Contract

When an agent (human or AI) needs to deviate from
this contract, the deviation:

1. MUST be documented in an ADR under
   `docs/adr/NNNN-title.md`.
2. MUST identify the cost of NOT deviating.
3. MUST be reviewed by skill 00 (program-orchestrator).
4. MUST be tracked in `docs/adr/active-deviations.md`.

A deviation without an ADR is a bug.

---

## 11. Coordination Protocol

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

## 12. Local Development Contract

Every developer (human or AI agent) MUST:

- Run `./scripts/check.sh` before pushing.
- The script runs: lint, type check, unit tests,
  artifact contract check, secrets scan, license
  check.
- A green run is a prerequisite for merge. A
  failing run is a blocked push.
- The script is owned by skill 14 (quality-verification).

---

## 13. How to Use This Document

- **New agent onboarding** — read this document
  first. Then read the SKILL.md of the skill
  you are operating.
- **Adding a new skill** — add a new `SKILL.md`
  under `.ai/skills/`. Update section 5.1 of
  this document. Update section 5.3 (the topology)
  if the new skill has cross-skill calls.
- **Changing an existing skill** — update the
  `SKILL.md`. If the change violates a global
  rule, open an ADR.
- **Resolving a cross-skill conflict** — the
  orchestrator (skill 00) decides. The decision
  is filed under `docs/adr/`.

---

> "No skill is an island. The platform is the
> composition."
