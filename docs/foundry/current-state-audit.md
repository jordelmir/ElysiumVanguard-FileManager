---
title: Current State Audit
status: stub — to be filled in by the orchestrator in Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Current State Audit — Elysium Automotive Foundry

> **Status:** This is the **mandatory first output**
> of the program orchestrator (skill 00) before
> any production feature is implemented.
> Reference: `.ai/AGENTS.md` section 22 (G0 —
> Repository understood).

This document is the **current-state map** of
the repository. It is the baseline against
which the target architecture (per
`target-architecture.md`) is defined.

The audit is a **living document**: every
significant repository change updates the
relevant section. A stale audit is a contract
violation (per `.ai/AGENTS.md` section 22).

## What this document must include

Per `.ai/skills/00-program-orchestrator/SKILL.md`
section 3 (Mandatory first outputs), the audit
covers:

- **Modules.**
- **Build system.**
- **Data stores.**
- **Authentication.**
- **Existing vehicle entities.**
- **Existing 3D renderer.**
- **Existing diagnostic bindings.**
- **Existing AI providers.**
- **Network boundaries.**
- **Test coverage.**
- **Security findings.**
- **Performance bottlenecks.**
- **Duplicate concepts.**
- **Abandoned or dead code.**

Each section is filled in by the orchestrator
during Phase 0. The orchestrator reads the
repository, runs the test suite, and produces
a signed report. The audit is a prerequisite
for advancing past G0.

## Phase 0 — discovery checklist

When the orchestrator runs Phase 0, it MUST
produce a report that answers every question
below. A "we don't know" is itself a finding
that goes into the risk register.

### Modules

- What is the language breakdown (lines of
  code per language)?
- What is the module structure (top-level
  directories + their purpose)?
- Which modules are dead (no commits in the
  last 90 days)?

### Build system

- What is the build tool (Gradle, Maven,
  Cargo, npm, etc.)?
- What is the build configuration
  (Kotlin DSL, Groovy, YAML, TOML)?
- What is the CI provider (GitHub Actions,
  Woodpecker, Jenkins, etc.)?
- What is the test command (`./scripts/check.sh`,
  `make test`, `gradle test`, etc.)?

### Data stores

- What databases are in use (PostgreSQL,
  ClickHouse, DuckDB, Redis, etc.)?
- What object stores are in use (S3,
  Supabase Storage, MinIO, etc.)?
- What is the migration story (Flyway,
  Liquibase, raw SQL, etc.)?
- What is the schema-versioning strategy?

### Authentication

- What is the identity provider (OIDC, OAuth,
  custom)?
- What is the auth library (Keycloak, Spring
  Security, custom)?
- Where are the secrets (HashiCorp Vault,
  AWS Secrets Manager, env vars)?

### Vehicle entities

- What vehicle / brand / project / part
  entities exist?
- Where are they defined (the ontology skill
  is the answer)?
- What is the persistence shape (database
  tables, file formats)?

### 3D renderer

- What 3D engine is in use (Filament,
  Three.js, Babylon.js, custom)?
- What is the asset pipeline (glTF, STEP,
  USD, custom)?
- Where is the asset store?

### Diagnostic bindings

- What fault models exist (OBD-II, OEM
  proprietary, custom)?
- What diagnostic protocols are supported
  (UDS, KWP2000, DoIP)?
- Where is the diagnostic pipeline (mobile,
  cloud, edge)?

### AI providers

- What AI providers are integrated (OpenAI,
  Anthropic, on-device models, custom)?
- What is the AI authority boundary (what
  the AI may / may not do)?
- Where is the audit trail for AI outputs?

### Network boundaries

- What are the public endpoints (the
  marketplace browse, the public docs)?
- What are the authenticated endpoints (the
  forge, the AI council, the catalog)?
- What are the internal endpoints (the event
  bus, the storage layer)?
- What is the encryption posture (TLS 1.3,
  application-level encryption, mTLS)?

### Test coverage

- What is the unit-test coverage (per
  module, per release)?
- What is the integration-test coverage
  (per skill, per contract)?
- What is the mutation-test score (per
  critical module)?
- What is the fuzz-test coverage (per input
  surface)?

### Security findings

- What is the CVE history (open CVEs by
  severity, by surface)?
- What is the secret-scan result (any
  secrets in the repository)?
- What is the dependency-audit result (any
  license-incompatible dependencies)?
- What is the threat-model summary (top-3
  risks, top-3 mitigations)?

### Performance bottlenecks

- What is the slowest code path (P99
  latency, per surface)?
- What is the highest-throughput code path
  (requests per second, per surface)?
- What are the resource hot spots (CPU,
  memory, disk, network)?

### Duplicate concepts

- Are there two or more types for the same
  concept (e.g. `Vehicle` and `Car` and
  `Model`)?
- Are there two or more services for the
  same concern (e.g. two catalog services)?
- Are there two or more pipelines for the
  same artifact (e.g. two 3D importers)?

### Abandoned or dead code

- What code has not been touched in 180+
  days?
- What dependencies are unmaintained (no
  releases in 365+ days)?
- What features are documented but not
  implemented?

## Output

When Phase 0 is complete, this document
contains:

- A signed report covering every section
  above.
- A risk-register entry for every "we don't
  know" + every "this is a problem".
- A cross-reference to the target
  architecture (per `target-architecture.md`).
- A cross-reference to the implementation
  roadmap (per `implementation-roadmap.md`).

The orchestrator files the audit under
`docs/foundry/gates/g0-current-state-audit.md`
when G0 is green.
