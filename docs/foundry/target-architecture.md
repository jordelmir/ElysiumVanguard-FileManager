---
title: Target Architecture
status: stub — to be filled in by the orchestrator in Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Target Architecture — Elysium Automotive Foundry

> **Status:** This is the **mandatory second
> output** of the program orchestrator (skill
> 00) before any production feature is
> implemented. Reference: `.ai/AGENTS.md`
> section 22 (G0 — Repository understood).

This document is the **target-state map** of
the Elysium Automotive Foundry platform. It
is the destination the implementation roadmap
(per `implementation-roadmap.md`) is working
toward.

The target architecture is the answer to
"what does the platform look like when every
project gate (G0–G10) is green?".

## What this document must include

Per `.ai/skills/00-program-orchestrator/SKILL.md`
section 3 (Mandatory first outputs), the
target architecture covers:

- **The system context** (C4 level 1) — the
  platform, its actors, and its external
  systems.
- **The container view** (C4 level 2) — the
  platform's deployable units + their
  responsibilities + their technology
  choices.
- **The component view** (C4 level 3) — the
  major components within each container +
  their responsibilities + their interfaces.
- **The cross-cutting concerns** — the
  identity model, the auth model, the
  observability model, the data model, the
  AI authority boundary, the security
  posture.
- **The bridges from the current state** —
  for every significant gap between the
  current state (per `current-state-audit.md`)
  and the target state, the document names
  the bridge + the cut-over + the
  rollback.
- **The non-negotiables** — every section
  of `.ai/STANDARDS.md` that constrains the
  architecture (tech stack, truth model,
  vehicle representation levels, AI
  authority boundary, error model, etc.).

## Architecture principles

The target architecture honors every
architectural principle in `.ai/AGENTS.md`
section 3:

- **Domain-Driven Design** for core
  automotive and commercial domains.
- **Clean Architecture** boundaries
  (`domain/`, `application/`,
  `infrastructure/`, `interfaces/`).
- **SOLID** and **GRASP**.
- **Explicit use cases** (no god services).
- **Dependency inversion** (domain code
  MUST NOT import infrastructure code).
- **Immutable domain events.**
- **Strongly typed identifiers.**
- **Transactional outbox** for reliable
  event publication.
- **Optimistic concurrency control** on
  every aggregate.
- **Idempotent commands.**
- **Append-only audit trails** for critical
  records.
- **Content-addressed storage** for
  immutable engineering artifacts.
- **Versioned schemas and deterministic
  migrations.**
- **Zero Trust security.**
- **Least privilege.**
- **Default deny.**
- **Evidence-backed engineering data.**

## System context (C4 level 1)

The platform is a **modular monolith** (per
`.ai/AGENTS.md` section 3 — microservices
require an ADR + a measurable scaling
boundary). The platform's external actors
and systems are:

- **Designer** — the user who designs a
  vehicle.
- **Engineer** — the user who reviews +
  signs off a design.
- **Mechanic** — the user who diagnoses +
  repairs a vehicle in the field.
- **Buyer** — the user who browses +
  purchases a project.
- **Supplier** — the user who supplies
  parts.
- **Reviewer (regulator)** — the user
  who approves a regulatory submission.
- **OEM relationship manager** — the
  background process that maintains the
  OEM catalog.
- **AI council** — the background process
  that runs the AI council's structured
  tools.
- **External identity provider** — OIDC /
  OAuth 2.1 (Keycloak or equivalent).
- **External object storage** — S3-
  compatible (or Supabase Storage).
- **External payment provider** — third-
  party SaaS.
- **External regulatory filings** — UN,
  EU, ISO, etc.

A C4 level 1 diagram is added here when
Phase 0 produces it.

## Container view (C4 level 2)

The platform's deployable units are:

- **Web app** — React + TypeScript.
- **Mobile app** — Kotlin + Jetpack
  Compose + Filament.
- **Backend application services** —
  Kotlin + Spring Boot or Ktor.
- **Geometry / compiler workers** — Rust.
- **PostgreSQL** (OLTP).
- **S3-compatible object storage.**
- **Redis** (when required).
- **OpenTelemetry collector.**
- **AI council** (a separate service for
  multi-agent deliberation).

A C4 level 2 diagram is added here when
Phase 0 produces it.

## Component view (C4 level 3)

The major components are the 16 skills in
`.ai/skills/`. The orchestrator (skill 00)
defines the cross-skill contracts (per
`AGENTS.md` section 17). The component view
maps each skill to the bounded context it
owns.

A C4 level 3 diagram is added here when
Phase 0 produces it.

## Cross-cutting concerns

The target architecture honors every
cross-cutting concern in `.ai/AGENTS.md`
section 24:

- **Stable machine-readable code** — typed
  values at every boundary.
- **Safe user-facing message** — short,
  actionable, jargon-free, secret-free,
  localized.
- **Correlation ID** — propagated through
  every downstream call.
- **Retry classification** — every error
  is one of `retryable_immediate` /
  `retryable_backoff` /
  `retryable_idempotent_only` /
  `non_retryable`.
- **No leak** — internal stack traces +
  secrets are stripped at every API
  boundary.
- **Relevant structured metadata** — every
  event carries the relevant metadata
  (`correlationId`, `tenantId`, `userId`,
  `vehicleId`, `vehicleRepresentationLevel`,
  `verificationStatus`, `source`,
  `sourceType`).

## Bridges from the current state

The orchestrator identifies the bridges
from the current state (per
`current-state-audit.md`) to the target
state. For every significant gap:

- The **bridge** is the work that moves the
  current state to the target state.
- The **cut-over** is the moment the bridge
  is in production + the old code is
  retired.
- The **rollback** is the path back to the
  current state if the bridge fails.

A gap that does not have a bridge + a
cut-over + a rollback is a risk-register
entry (per `risk-register.md`).

## Non-negotiables

The target architecture honors every
non-negotiable in `.ai/STANDARDS.md` section
2 (the 6 categories: data integrity,
commercial integrity, code hygiene,
concurrency, trust, AI authority). A
violation of a non-negotiable is a contract
violation; the orchestrator blocks the
release.

## Output

When Phase 0 is complete, this document
contains:

- A C4 level 1 + level 2 + level 3 diagram
  set.
- A bridges + cut-overs + rollbacks table
  for every significant gap.
- A cross-reference to the current-state
  audit (per `current-state-audit.md`).
- A cross-reference to the implementation
  roadmap (per `implementation-roadmap.md`).
- A cross-reference to the dependency map
  (per `dependency-map.md`).

The orchestrator files the target
architecture under
`docs/foundry/gates/g0-target-architecture.md`
when G0 is green.
