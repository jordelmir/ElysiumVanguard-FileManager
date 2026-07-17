---
title: Domain Map (Bounded Contexts)
status: stub — to be filled in during Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills, external reviewers
---

# Domain Map — Elysium Automotive Foundry

> **Status:** This is a stub for the
> domain map (the bounded contexts + the
> relationships between them). The full
> content is produced during Phase 0
> (Discovery) and updated as the platform
> grows. Reference: `.ai/AGENTS.md` section
> 23 (Required Documentation).

## What this document must include

- **Every bounded context.** The
  ownership boundary for a domain
  aggregate.
- **The relationships between contexts.**
  The upstream / downstream direction +
  the data shape + the contract.
- **The shared kernel.** The types the
  contexts share (the ontology).
- **The anti-corruption layer.** The
  translation between the platform's
  domain language and an external
  language (e.g. OBD-II codes).

## Bounded contexts

The platform's bounded contexts are the
16 skills in `.ai/skills/`. Each skill
owns a bounded context (per
`.ai/skills/00-program-orchestrator/SKILL.md`
section 3 + section 7).

The major bounded contexts are:

- **Ontology** (skill 03) — the single
  source of truth for the domain types.
- **DSL compiler** (skill 04) — the
  user-facing surface for "build me a
  vehicle".
- **AI council** (skill 05) — the
  multi-agent deliberation.
- **3D pipeline** (skill 06) — the
  bridge between the user's 3D data
  and the platform's typed world.
- **Digital twin** (skill 07) — the
  telemetry + fault + repair.
- **Backend event platform** (skill 08)
  — the event bus + projections +
  sagas + outbox.
- **IP / provenance / royalties**
  (skill 09) — the catalog + the
  audit trail + the royalty engine.
- **Marketplace / manufacturing**
  (skill 10) — the listings + escrow
  + supplier integration.
- **Mobile / forge UX** (skill 11) —
  the field UX.
- **Security** (skill 12) — the
  identity + secrets + threat model.
- **Regulatory** (skill 13) — the
  compliance + homologation.
- **Quality** (skill 14) — the
  verifier.
- **Devops** (skill 15) — the CI +
  SLOs + on-call.

## Relationships

The relationships between bounded
contexts are the cross-skill edges in
`.ai/skills/00-program-orchestrator/SKILL.md`
section 10 + `.ai/foundry/dependency-map.md`.

The major relationships are:

- The ontology (skill 03) is upstream
  from every other context. Every
  context consumes the ontology's
  types.
- The DSL compiler (skill 04) is
  upstream from the 3D pipeline (skill
  06) + the digital twin (skill 07) +
  the catalog (skill 09) + the
  marketplace (skill 10).
- The 3D pipeline (skill 06) is
  upstream from the digital twin (skill
  07) + the catalog (skill 09) + the
  marketplace (skill 10) + the mobile
  UX (skill 11).
- The catalog (skill 09) is upstream
  from the marketplace (skill 10) + the
  royalty engine (skill 09) + the
  regulatory (skill 13).
- The AI council (skill 05) is a
  consumer of the ontology + the DSL +
  the catalog. It is a producer of
  typed proposals (per
  `.ai/STANDARDS.md` section 5).

## Shared kernel

The shared kernel is the **ontology**
(skill 03). The types in the ontology
are the platform's lingua franca. Every
other context consumes them.

The shared kernel is versioned + signed
(per the artifact contract in
`.ai/AGENTS.md` section 12). A consumer
that does not recognize a schema version
is rejected.

## Anti-corruption layer

The platform's anti-corruption layer is
the translation between the platform's
domain language and an external language.
Examples:

- **OBD-II codes → `Fault` domain
  objects.** The mechanic plugs in; the
  platform reads the OBD-II codes; the
  platform translates the codes to
  `Fault` domain objects; the `Fault`
  is rendered to the user.
- **STEP / USD / glTF → canonical
  glTF.** The 3D pipeline translates
  the user's 3D data to the canonical
  glTF; the canonical glTF is the
  only thing the rest of the platform
  sees.
- **OEM service API → `Vehicle` domain
  objects.** The OEM relationship
  manager translates the OEM's
  proprietary API to the platform's
  `Vehicle` domain objects.

The anti-corruption layer is owned by
the consuming context. A leak of the
external language into the platform's
domain is a contract violation.

## Cross-references

- **Domain ownership:**
  `docs/foundry/domain-ownership.md`.
- **Dependency map:**
  `docs/foundry/dependency-map.md`.
- **Ontology (skill 03):**
  `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.
