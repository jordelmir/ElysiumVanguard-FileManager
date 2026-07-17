---
title: Dependency Map
status: stub — to be filled in by the orchestrator in Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Dependency Map — Elysium Automotive Foundry

> **Status:** This is the **mandatory sixth
> output** of the program orchestrator (skill
> 00) before any production feature is
> implemented. Reference: `.ai/AGENTS.md`
> section 22 (G0 — Repository understood) +
> skill 00 section 10 (coordination
> contract).

This document is the **map of every
cross-skill dependency edge** in the
Elysium Automotive Foundry platform. Every
edge has a data shape, a schema version,
and an auth requirement.

## What this document must include

Per `.ai/skills/00-program-orchestrator/SKILL.md`
section 3 (Mandatory first outputs), the
dependency map covers:

- **Every cross-skill edge** (the producer
  skill + the consumer skill + the data
  shape + the schema version + the auth
  requirement + the error envelope + the
  retry classification + the correlation
  ID propagation).
- **Every external dependency** (the third-
  party SaaS + the protocol + the auth +
  the SLA).
- **The cross-cutting concerns** (per
  `.ai/AGENTS.md` section 24) propagated
  through every edge.

## Cross-skill edges

The cross-skill edges are the ones defined
in `.ai/skills/00-program-orchestrator/SKILL.md`
section 10 (coordination contract). For
every edge, the document records:

- **The data shape.** The typed value
  that crosses the edge.
- **The schema version.** The version of
  the shape, per the artifact contract in
  `.ai/AGENTS.md` section 12.
- **The auth requirement.** The
  authentication + authorization needed
  to consume the edge.
- **The error envelope.** The typed
  `FoundryError` the edge may return, per
  `.ai/STANDARDS.md` section 7.
- **The retry classification.** Per
  `.ai/AGENTS.md` section 24.4, every
  error is one of `retryable_immediate` /
  `retryable_backoff` /
  `retryable_idempotent_only` /
  `non_retryable`.
- **The correlation ID propagation.** Per
  `.ai/AGENTS.md` section 24.3, the
  correlation ID is propagated through
  every downstream call.

### Edges from skill 00 (orchestrator)

| Edge | Counterpart | Contract |
|---|---|---|
| out | skill 01 (repo) | dependency map + ADR |
| in | skill 01 (repo) | code-base facts |
| out | skill 02 (PRD) | ADR |
| in | skill 02 (PRD) | PRD + acceptance criteria |
| out | skill 03 (ontology) | data shape |
| in | skill 03 (ontology) | entity types |
| ... | ... | ... |

(The full table is in
`.ai/skills/00-program-orchestrator/SKILL.md`
section 10.)

### Edges from skill 01 (repo archaeology)

| Edge | Counterpart | Contract |
|---|---|---|
| out | skill 00 (orchestrator) | code-base facts |
| in | skill 00 (orchestrator) | dependency map + ADR |

### Edges from skill 02 (PRD)

| Edge | Counterpart | Contract |
|---|---|---|
| out | skill 00 (orchestrator) | PRD + acceptance criteria |
| in | skill 00 (orchestrator) | ADR |
| out | skill 03 (ontology) | new types + acceptance criteria |
| in | skill 03 (ontology) | new types + invariants |

### Edges from skill 03 (ontology)

| Edge | Counterpart | Contract |
|---|---|---|
| out | every other skill | data shape + invariants |
| in | every other skill | new type request + ADR |

### Edges from skill 04 (DSL compiler)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 03 (ontology) | entity types |
| out | skill 06 (3D) | typed spec artifact |
| out | skill 07 (twin) | typed spec artifact |
| out | skill 09 (catalog) | typed spec artifact |
| out | skill 10 (marketplace) | typed spec artifact |

### Edges from skill 05 (AI council)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 04 (DSL) | DSL grammar |
| out | skill 04 (DSL) | typed proposal (DSL surface) |
| out | skill 06 (3D) | typed proposal (3D surface) |
| out | skill 09 (IP) | typed proposal (authorship) |
| in | skill 09 (IP) | signed counter-signature |

### Edges from skill 06 (3D pipeline)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 04 (DSL) | typed spec artifact |
| out | skill 07 (twin) | canonical 3D artifact |
| out | skill 09 (catalog) | metadata + provenance |
| out | skill 10 (marketplace) | asset reference |
| out | skill 11 (mobile) | LODs + manifest |

### Edges from skill 07 (digital twin)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 04 (DSL) | typed spec artifact |
| in | skill 06 (3D) | canonical 3D artifact |
| out | skill 09 (catalog) | diagnostic bindings |
| out | skill 11 (mobile) | validated telemetry |

### Edges from skill 08 (backend event platform)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 03 (ontology) | entity types |
| out | every other skill | event bus topology + schema |

### Edges from skill 09 (IP / provenance / royalties)

| Edge | Counterpart | Contract |
|---|---|---|
| in | every other skill | authorship claims + royalty contracts + sales events + GDPR requests |
| out | every other skill | catalog queries + audit trail + settlement results |

### Edges from skill 10 (marketplace / manufacturing)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 09 (IP) | catalog + authorship + royalty contracts |
| in | skill 06 (3D) | asset reference |
| out | skill 09 (IP) | sales events + escrow |
| out | skill 11 (mobile) | listing |

### Edges from skill 11 (mobile / forge UX)

| Edge | Counterpart | Contract |
|---|---|---|
| in | skill 04 (DSL) | DSL grammar + spec |
| in | skill 06 (3D) | 3D LODs + manifest |
| in | skill 07 (twin) | validated telemetry |
| in | skill 10 (marketplace) | listing |
| out | skill 08 (events) | events |
| out | skill 09 (catalog) | catalog queries |
| out | skill 12 (auth) | auth events |

### Edges from skill 12 (security)

| Edge | Counterpart | Contract |
|---|---|---|
| in | every other skill | PR + release + CVE + incident |
| out | every other skill | threat model + sign-off |
| in/out | skill 09 (catalog) | auth audit log |
| in/out | skill 13 (regulatory) | compliance report |

### Edges from skill 13 (regulatory)

| Edge | Counterpart | Contract |
|---|---|---|
| in | every other skill | PR + release + regulatory change + audit |
| out | every other skill | RIA + compliance report + homologation |
| in/out | skill 09 (catalog) | homologation package |
| in/out | skill 12 (security) | compliance report |

### Edges from skill 14 (quality)

| Edge | Counterpart | Contract |
|---|---|---|
| in | every other skill | PR + release + SKILL.md + PRD + ADR |
| out | skill 00 (orchestrator) | verification report |
| out | skill 15 (devops) | gate status |

### Edges from skill 15 (devops)

| Edge | Counterpart | Contract |
|---|---|---|
| in | every other skill | PR + release + ADR |
| out | every other skill | CI + SLO data + on-call + runbook |
| in | skill 14 (quality) | gate status |
| in/out | skill 00 (orchestrator) | release |

## External dependencies

The platform's external dependencies are:

- **Identity provider** (OIDC, Keycloak
  or equivalent). **Protocol:** OIDC +
  OAuth 2.1 + WebAuthn. **Auth:** OIDC.
  **SLA:** 99.9% uptime.
- **Object storage** (S3-compatible,
  Supabase Storage, or equivalent).
  **Protocol:** S3. **Auth:** IAM +
  bucket policy. **SLA:** 99.9%
  durability + 99.9% uptime.
- **Payment provider** (third-party SaaS).
  **Protocol:** REST. **Auth:** OAuth
  2.1. **SLA:** per the provider's
  contract.
- **OpenTelemetry collector.** **Protocol:**
  OTLP. **Auth:** mTLS. **SLA:** 99.9%
  uptime.
- **AI council** (the multi-agent
  deliberation service). **Protocol:**
  gRPC. **Auth:** mTLS. **SLA:** 99.5%
  uptime.

A new external dependency is added to
the map only when an ADR approves it.
A dependency without an ADR is a contract
violation; the orchestrator blocks the
release.

## Output

When Phase 0 is complete, this document
contains:

- The full cross-skill edge table (per
  the section above).
- The full external dependency table.
- A cross-reference to the domain
  ownership (per `domain-ownership.md`).
- A cross-reference to the implementation
  roadmap (per `implementation-roadmap.md`).

The orchestrator files the dependency
map under
`docs/foundry/gates/g0-dependency-map.md`
when G0 is green.
