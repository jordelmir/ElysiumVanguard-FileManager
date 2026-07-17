# docs/foundry/ — Elysium Automotive Foundry Program Artifacts

> **Status:** Stub index. The full content
> is produced during Phase 0 (Discovery).
> Reference: `.ai/AGENTS.md` section 22
> (G0 — Repository understood) +
> `.ai/skills/00-program-orchestrator/SKILL.md`
> section 3 (Mandatory first outputs).

This directory contains the **six mandatory
first outputs** the program orchestrator
(skill 00) produces before any production
feature is implemented. Every output is a
**living document**: a stale output is a
contract violation.

## The six mandatory outputs

| Output | What it covers |
|---|---|
| [`current-state-audit.md`](./current-state-audit.md) | The current state of the repository: modules, build, data stores, auth, vehicle entities, 3D renderer, diagnostic bindings, AI providers, network boundaries, test coverage, security findings, performance bottlenecks, duplicate concepts, abandoned code. |
| [`target-architecture.md`](./target-architecture.md) | The target state: C4 level 1 + level 2 + level 3 diagrams, the cross-cutting concerns, the bridges from the current state, the non-negotiables. |
| [`domain-ownership.md`](./domain-ownership.md) | Which skill owns which aggregate. Every domain entity has exactly one owner. Two skills editing the same entity is a contract violation. |
| [`implementation-roadmap.md`](./implementation-roadmap.md) | The dependency-ordered sequence of increments. Every increment is a vertical slice that ships end-to-end. |
| [`risk-register.md`](./risk-register.md) | Every identified risk: the owner, the likelihood, the impact, the mitigation, the status, the ADR. |
| [`dependency-map.md`](./dependency-map.md) | Every cross-skill edge: the data shape, the schema version, the auth requirement, the error envelope, the retry classification, the correlation ID propagation. |

## Gates (sub-directory)

The `gates/` sub-directory contains the
per-gate sign-off artifacts. When a gate
is green, the orchestrator files the
sign-off under `gates/`.

| Gate | Sign-off artifact |
|---|---|
| G0 | `gates/g0-current-state-audit.md`, `gates/g0-target-architecture.md`, `gates/g0-domain-ownership.md`, `gates/g0-implementation-roadmap.md`, `gates/g0-risk-register.md`, `gates/g0-dependency-map.md` |
| G1 | `gates/g1-domain-model-approved.md` |
| G2 | `gates/g2-persistence-versioning-proven.md` |
| G3 | `gates/g3-vehicle-compiler-deterministic.md` |
| G4 | `gates/g4-3d-digital-twin-integrated.md` |
| G5 | `gates/g5-ai-constrained-by-structured-tools.md` |
| G6 | `gates/g6-ip-provenance-ledger-operational.md` |
| G7 | `gates/g7-royalty-engine-contract-driven.md` |
| G8 | `gates/g8-marketplace-supplier-workflow.md` |
| G9 | `gates/g9-safety-regulatory-evidence-model.md` |
| G10 | `gates/g10-production-hardening.md` |

## Cross-references

- **Global engineering contract:**
  `.ai/AGENTS.md`.
- **Technical standards:**
  `.ai/STANDARDS.md`.
- **Orchestrator skill:**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Project gates (G0–G10):**
  `.ai/AGENTS.md` section 22.
- **Completion standard:**
  `.ai/AGENTS.md` section 21.
- **Cross-cutting concerns:**
  `.ai/AGENTS.md` section 24.
