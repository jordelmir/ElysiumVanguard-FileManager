---
title: Implementation Roadmap
status: stub — to be filled in by the orchestrator in Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Implementation Roadmap — Elysium Automotive Foundry

> **Status:** This is the **mandatory fourth
> output** of the program orchestrator (skill
> 00) before any production feature is
> implemented. Reference: `.ai/AGENTS.md`
> section 22 (G0 — Repository understood) +
> skill 00 section 4 (execution phases).

This document is the **dependency-ordered
sequence of increments** that move the
platform from the current state (per
`current-state-audit.md`) to the target
architecture (per `target-architecture.md`).
Every increment is a vertical slice that
ships end-to-end (per `.ai/AGENTS.md` section
9 — delivery rules).

## What this document must include

Per `.ai/skills/00-program-orchestrator/SKILL.md`
section 3 (Mandatory first outputs), the
implementation roadmap covers:

- **The execution phases** (Phase 0–5 per
  skill 00 section 4).
- **The project gates** (G0–G10 per
  `.ai/AGENTS.md` section 22) that each
  phase must pass before the next phase
  starts.
- **The increments within each phase.**
- **The dependencies between increments.**
- **The acceptance criteria for each
  increment.**
- **The risk-register entries per
  increment.**

## Execution phases

The execution phases are the ones defined
in `.ai/skills/00-program-orchestrator/SKILL.md`
section 4. A phase is not declared done
until its gate is green.

### Phase 0 — Discovery

Gate: **G0** (Repository understood).

Increments:

1. **Repository audit.** The orchestrator
   runs the audit (per
   `current-state-audit.md`) and produces
   a signed report. **Acceptance criteria:**
   the report is filed under
   `docs/foundry/gates/g0-current-state-audit.md`
   + every "we don't know" is a
   risk-register entry.
2. **Target architecture.** The
   orchestrator drafts the target
   architecture (per `target-architecture.md`).
   **Acceptance criteria:** the document
   is filed + the C4 diagrams are present
   + the bridges are documented.
3. **Domain ownership.** The orchestrator
   drafts the domain ownership (per
   `domain-ownership.md`). **Acceptance
   criteria:** every aggregate has an
   owner + every cross-skill contract is
   documented.
4. **Dependency map.** The orchestrator
   drafts the dependency map (per
   `dependency-map.md`). **Acceptance
   criteria:** every cross-skill edge is
   documented + the data shape + the
   schema version + the auth requirement
   are recorded.
5. **Risk register.** The orchestrator
   drafts the risk register (per
   `risk-register.md`). **Acceptance
   criteria:** every identified risk has
   an owner + a likelihood + an impact +
   a mitigation.
6. **Implementation roadmap.** The
   orchestrator drafts this document.
   **Acceptance criteria:** every phase
   has a gate + every increment has
   acceptance criteria.

### Phase 1 — Foundational domain

Gate: **G1** (Domain model approved).

Increments (each is a vertical slice):

1. **`Project` aggregate.** Domain type +
   DB migration + use case + API + UI
   scaffolding + auth + typed errors +
   unit tests + integration tests +
   observability + docs + migration +
   rollback.
2. **`VehicleProgram` aggregate.** Same
   shape.
3. **`VehicleRevision` aggregate.** Same
   shape.
4. **`Contributor` aggregate.** Same
   shape.
5. **`EngineeringArtifact` aggregate.**
   Same shape.
6. **`ProvenanceRecord` aggregate.** Same
   shape.
7. **Strongly-typed IDs.** Cross-cutting
   infrastructure.
8. **Revision + concurrency strategy.**
   Optimistic concurrency control on
   every aggregate.

### Phase 2 — Vehicle definition

Gate: **G3** (Vehicle compiler
deterministic).

Increments:

1. **DSL grammar.** The text surface +
   the visual AST.
2. **Parser.** Lex + parse.
3. **Schema.** The typed spec shape.
4. **Validator.** The invariant checker.
5. **Compatibility rules.** The constraint
   engine.
6. **Deterministic compiler.** The
   parser + resolver + type-checker.
7. **Compilation report.** The
   user-facing report.
8. **Editor support.** Syntax highlighting
   + autocomplete + go-to-definition +
   hover docs.

### Phase 3 — Digital twin

Gate: **G4** (3D digital twin integrated).

Increments:

1. **Scene manifest.** The typed manifest
   shape.
2. **Part instance graph.** The runtime
   graph.
3. **Asset streaming.** The LOD streaming
   pipeline.
4. **Selection + isolation.** The
   user-facing selection.
5. **Representation confidence.** The
   `VehicleRepresentationLevel` integration.
6. **Diagnostic bindings.** The fault
   model integration.

### Phase 4 — AI council

Gate: **G5** (AI constrained by structured
tools).

Increments:

1. **Typed proposal schema.** The schema
   the AI produces.
2. **Deterministic engine.** The engine
   that applies the proposal.
3. **Human review UI.** The review
   surface.
4. **Audit trail.** The signed events.
5. **Council deliberation.** The
   multi-agent voting.

### Phase 5 — Commercial foundation

Gate: **G6**, **G7**, **G8**.

Increments:

1. **Contracts.** The `RoyaltyContract`
   schema + the validation.
2. **Rights.** The rights registry.
3. **Licenses.** The per-artifact license.
4. **Revenue events.** The sale event.
5. **Royalty rules.** The rule engine.
6. **Statements.** The per-user statement.
7. **Audit trail.** The signed ledger.
8. **Listings.** The marketplace surface.
9. **Escrow.** The escrow flow.
10. **Supplier integration.** The
    supplier catalog.

## Dependencies

The phases are dependency-ordered:

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5
```

Within each phase, the increments are
dependency-ordered (the orchestrator
documents the per-increment dependencies
when the phase starts).

A phase is **not** started until the
previous phase's gate is green. A skipped
phase is a contract violation; the
orchestrator blocks the release.

## Acceptance criteria

Every increment has acceptance criteria.
The criteria are:

1. **Domain model** (per skill 03).
2. **DB migration** (per skill 08).
3. **Use case** (per skill 08).
4. **API contract** (per skill 08).
5. **UI integration** (per skill 11).
6. **Auth check** (per skill 12).
7. **Typed errors** (per
   `.ai/STANDARDS.md` section 7).
8. **Unit tests** (per skill 14).
9. **Integration tests** (per skill 14).
10. **Observability** (per skill 15).
11. **Docs** (PRD + ADR + user-facing).
12. **Migration + rollback** (per
    `.ai/AGENTS.md` section 9.1).

A "placeholder production logic disguised
as complete implementation" is a contract
violation; the verifier (skill 14) rejects
the PR.

## Output

When Phase 0 is complete, this document
contains:

- The full phase + increment + dependency
  + acceptance-criteria table.
- A cross-reference to the current-state
  audit (per `current-state-audit.md`).
- A cross-reference to the target
  architecture (per `target-architecture.md`).
- A cross-reference to the risk register
  (per `risk-register.md`).
- A cross-reference to the dependency map
  (per `dependency-map.md`).

The orchestrator files the implementation
roadmap under
`docs/foundry/gates/g0-implementation-roadmap.md`
when G0 is green.
