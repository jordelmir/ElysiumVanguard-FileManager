---
title: Domain Ownership
status: stub — to be filled in by the orchestrator in Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Domain Ownership — Elysium Automotive Foundry

> **Status:** This is the **mandatory third
> output** of the program orchestrator (skill
> 00) before any production feature is
> implemented. Reference: `.ai/AGENTS.md`
> section 22 (G0 — Repository understood) +
> skill 00 section 11 (failure mode:
> "two skills attempt to own the same
> aggregate").

This document is the **ownership map** of
every domain aggregate in the Elysium
Automotive Foundry platform. Every domain
entity has **exactly one** owner. Two skills
editing the same entity is a contract
violation.

## What this document must include

Per `.ai/skills/00-program-orchestrator/SKILL.md`
section 3 (Mandatory first outputs), the
domain ownership covers:

- **Every domain aggregate** (per the
  ontology, skill 03).
- **The owning skill** (the skill that
  has read-write access to the
  aggregate).
- **The reading skills** (the skills that
  have read-only access to the aggregate).
- **The cross-skill contracts** (the data
  shape, the schema version, the auth
  requirement).
- **The migration owner** (the skill that
  owns the schema migration when the
  aggregate changes).

## Domain aggregate inventory

The platform's domain aggregates (per
`.ai/skills/03-vehicle-domain-ontology/SKILL.md`
section 2) are:

| Aggregate | Owner | Reading skills |
|---|---|---|
| `Brand` | skill 03 (ontology) | every skill that touches a brand |
| `Project` | skill 03 (ontology) | every skill that touches a project |
| `Vehicle` | skill 03 (ontology) | every skill that touches a vehicle |
| `Subsystem` | skill 03 (ontology) | skill 04 (DSL), skill 06 (3D), skill 07 (twin) |
| `Part` | skill 03 (ontology) | skill 04, skill 06, skill 07, skill 10 (marketplace) |
| `Assembly` | skill 03 (ontology) | skill 04, skill 06, skill 07 |
| `Revision` | skill 03 (ontology) | every skill that touches a revision |
| `Variant` | skill 03 (ontology) | skill 04, skill 10 |
| `Compatibility` | skill 03 (ontology) | skill 04, skill 07, skill 10 |
| `Diagnostic` | skill 03 (ontology) | skill 07, skill 11 (mobile) |
| `Fault` | skill 03 (ontology) | skill 07, skill 11 |
| `RepairAction` | skill 03 (ontology) | skill 07, skill 11 |
| `Authorship` | skill 09 (IP/provenance) | skill 04, skill 10 |
| `Contribution` | skill 09 | skill 04, skill 10 |
| `RoyaltyContract` | skill 09 | skill 04, skill 10 |
| `License` | skill 09 | skill 04, skill 10 |
| `Listing` | skill 10 (marketplace) | skill 09, skill 11 |
| `Order` | skill 10 | skill 09, skill 11 |
| `Escrow` | skill 10 | skill 09 |
| `Settlement` | skill 10 | skill 09, skill 13 (regulatory) |
| `VehicleRepresentationLevel` | skill 03 (ontology) | every skill that touches a vehicle |
| `EngineeringFact<T>` | skill 03 (ontology) | every skill that produces or consumes an engineering fact |
| `VerificationStatus` | skill 03 (ontology) | every skill that produces or consumes a verification status |

## Cross-skill contracts

For every cross-skill edge, the document
records:

- **The data shape** (the typed value that
  crosses the edge).
- **The schema version** (the version of
  the shape, per the artifact contract in
  `.ai/AGENTS.md` section 12).
- **The auth requirement** (the
  authentication + authorization needed
  to consume the edge).
- **The error envelope** (the typed
  `FoundryError` the edge may return, per
  `.ai/STANDARDS.md` section 7).
- **The retry classification** (per
  `.ai/AGENTS.md` section 24.4).
- **The correlation ID propagation** (per
  `.ai/AGENTS.md` section 24.3).

## Two skills editing the same aggregate

When two skills attempt to edit the same
aggregate, the orchestrator arbitrates
(per `.ai/skills/00-program-orchestrator/SKILL.md`
section 11). The arbitration is filed as
an ADR. A skill that ignores the
arbitration is escalated.

A typical case: skill 04 (DSL) wants to
add a new field to `Part`; skill 07
(twin) wants to add a different field to
`Part`. The orchestrator arbitrates:
either the new fields are merged into
the ontology (skill 03 owns), or one
field is renamed, or one is moved to a
different aggregate. The decision is in
an ADR; the ADR is the contract.

## Migration owner

When an aggregate's schema changes, the
**migration owner** is the skill that
owns the aggregate (per the table
above). The migration owner:

- Writes the migration (per `.ai/AGENTS.md`
  section 9 — delivery rules + per
  `.ai/skills/00-program-orchestrator/SKILL.md`
  section 11 — failure mode "data is
  migrated safely").
- Tests the migration (the test re-runs
  the migration on a fixture and asserts
  idempotence).
- Files the migration ADR.
- Coordinates the rollout with the
  orchestrator (skill 00).

A migration without an owner is a
contract violation; the orchestrator
blocks the release.

## Output

When Phase 0 is complete, this document
contains:

- The domain aggregate inventory (per
  the table above).
- The cross-skill contracts table (per
  the section above).
- The ADR registry (every arbitration
  decision + every migration ADR).
- A cross-reference to the dependency
  map (per `dependency-map.md`).

The orchestrator files the domain
ownership under
`docs/foundry/gates/g0-domain-ownership.md`
when G0 is green.
