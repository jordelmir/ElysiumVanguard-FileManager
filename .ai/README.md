# .ai/ — Elysium Automotive Foundry Skills Package

This directory contains the engineering contract for
**Elysium Automotive Foundry** — the production-grade
platform for vehicle design, digital twins,
diagnostics, IP / royalty, marketplace, and
manufacturing integration.

> **Read `.ai/AGENTS.md` first.** It is the global
> contract every skill inherits. Every `SKILL.md`
> implements a slice of that contract.
>
> For the **technical specifics** (tech stack,
> non-negotiables, truth model, vehicle
> representation levels, AI authority boundary,
> delivery rules, error model, project gates,
> completion standard, cross-cutting concerns),
> read [`.ai/STANDARDS.md`](./STANDARDS.md).
> The two documents are companions: AGENTS.md
> is the meta-contract; STANDARDS.md is the
> canonical detail.

## Layout

```
.ai/
├── AGENTS.md                ← global engineering contract
└── skills/
    ├── 00-program-orchestrator/        ← the only skill with the full picture
    ├── 01-repository-archaeology/      ← read a code base before touching it
    ├── 02-product-requirements/        ← PRDs, user stories, acceptance criteria
    ├── 03-vehicle-domain-ontology/      ← the single source of truth for domain types
    ├── 04-vehicle-dsl-compiler/         ← the DSL for assembling architectures
    ├── 05-ai-engineering-council/       ← multi-agent deliberation + voting
    ├── 06-3d-cad-asset-pipeline/        ← glTF / STEP / USD import + canonicalize
    ├── 07-digital-twin-diagnostics/     ← telemetry, faults, repair flow
    ├── 08-backend-event-platform/       ← event bus, projections, sagas, outbox
    ├── 09-ip-provenance-royalties/      ← authorship, contributions, royalty
    ├── 10-marketplace-manufacturing/    ← listings, escrow, supplier integration
    ├── 11-mobile-forge-ux/              ← the field UX, the on-device forge
    ├── 12-security-zero-trust/          ← identity, secrets, threat model
    ├── 13-functional-safety-regulatory/ ← UN R155/R156, ISO 26262, GDPR
    ├── 14-quality-verification/         ← tests, gates, mutation
    └── 15-devops-observability/          ← CI, SLOs, tracing, on-call
```

## Topology

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
   ┌────▼──────────▼───────────▼──────────────────────▼────┐
   │  06 3d  │  07 twin  │  08 events │  09 ip  │  10 mkt │
   └─────────┴───────────┴────────────┴─────────┴─────────┘
        │          │           │           │          │
   ┌────▼──────────▼───────────▼──────────────────────▼────┐
   │  11 mobile  │  12 sec  │  13 safety │  14 qa  │  15 ops│
   └────────────┴──────────┴────────────┴─────────┴─────────┘
```

Arrows = "calls" or "depends on". Every arrow has a
documented contract in the corresponding
`SKILL.md` (section "Coordination contract").

## Adding a new skill

1. Create the directory
   `.ai/skills/<NN>-<slug>/SKILL.md`.
2. Use a 2-digit number prefix (`16-...`, `17-...`).
3. Implement the 9-section template
   (Mission / In-scope / Out-of-scope / Inputs /
   Outputs / Workflow / Quality gates / Failure
   modes / Coordination contract / Forbidden
   patterns).
4. Update `AGENTS.md` section 11.1 (the table) and
   section 11.3 (the topology).
5. If the new skill has cross-skill calls, document
   the contract in the consuming skill's
   "Coordination contract" section.

## Working with the standards

The technical specifics (tech stack,
non-negotiables, truth model, vehicle
representation levels, AI authority boundary,
delivery rules, error model, project gates,
completion standard, cross-cutting concerns) live
in [`.ai/STANDARDS.md`](./STANDARDS.md).

When a skill needs to deviate from a standard,
the protocol is:

1. Open an ADR under `docs/adr/`.
2. Identify the standard + the cost of NOT
   deviating.
3. Compare 2+ alternatives.
4. Submit the ADR to the AI council (skill 05).
5. The council votes.
6. The orchestrator files the deviation in
   `docs/adr/active-deviations.md`.

A deviation without an ADR is a bug.

## Project gates (G0–G10)

The Foundry has 11 project gates that map to
the execution phases defined in skill 00
(program-orchestrator). **No gate is skipped.**
A gate that cannot be green is an ADR + a
risk-register entry, not a bypass.

The full gate definitions + the evidence
required per gate are in `.ai/AGENTS.md`
section 22.

## Completion standard

A feature is complete only when **every** item
on the completion standard (`.ai/AGENTS.md`
section 21) is true. The 8 items are the
platform's definition of "done".

## Cross-cutting concerns

Every code path honors the four cross-cutting
concerns (`.ai/AGENTS.md` section 24):

- **Stable machine-readable code** — typed
  values at every boundary.
- **Safe user-facing message** — short,
  actionable, jargon-free, secret-free,
  localized.
- **Correlation ID** — propagated through every
  downstream call.
- **Retry classification** — every error is one
  of `retryable_immediate` / `retryable_backoff`
  / `retryable_idempotent_only` /
  `non_retryable`.

## Removing a skill

1. Deprecate the skill first: add a "Deprecated" note
   at the top of the `SKILL.md`. The note MUST name
   the replacement skill (or the orchestrator) and
   the deprecation timeline.
2. Migrate every consumer to the replacement.
3. Delete the skill only after the migration is
   complete + the orchestrator has signed off.

## Status

This skills package is the **foundation commit** for
the Elysium Automotive Foundry. The next step is
skill 01 (repository archaeology) on the actual
codebase. Every subsequent engineering decision is
filed as an ADR under `docs/adr/`.
