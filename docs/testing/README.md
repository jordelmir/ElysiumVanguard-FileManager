# Testing Documentation — Elysium Automotive Foundry

> **Status:** Stub. The full content is
> produced during Phase 0 (Discovery) +
> the per-test-strategy documents land
> as each test layer is built. Reference:
> `.ai/AGENTS.md` section 23 (Required
> Documentation) + `.ai/skills/14-quality-verification/SKILL.md`.

## What this directory must include

- **The test strategy** (the layered
  approach: unit / integration /
  contract / fuzz / property /
  mutation).
- **The coverage report** (per
  module + per release).
- **The mutation report** (per
  critical module + per release).
- **The fuzz report** (per input
  surface + per release).
- **The contract test report** (per
  cross-skill contract + per release).
- **The integration test report**
  (per skill + per release).
- **The verification report** (the
  orchestrator's primary input).

## Test layers

The platform's test layers (per
`.ai/skills/14-quality-verification/SKILL.md`
section 11) are:

- **Unit tests** — per function, per
  module. Fast (< 1ms per test). The
  inner loop.
- **Integration tests** — per skill.
  The skill's public API is exercised
  against the production collaborators
  (or test doubles if the collaborator
  is external).
- **Contract tests** — per cross-skill
  contract. The contract is the API;
  the contract test pins the API.
- **Fuzz tests** — per input surface.
  The CI runs them for a fixed time
  budget (1 hour per release for the
  critical surfaces).
- **Property tests** — per invariant.
  The property tests are slow; they
  run on the merge queue, not on
  every PR.
- **Mutation tests** — per critical
  module. The critical modules are:
  skill 03 (ontology), skill 04 (DSL),
  skill 06 (3D), skill 07 (twin),
  skill 08 (event platform), skill 09
  (catalog), skill 12 (auth), skill 13
  (regulatory).

## Quality gates

The quality gates (per
`.ai/skills/14-quality-verification/SKILL.md`
section 7) are the global gates +
the project gates (per
`.ai/AGENTS.md` section 22) + the
per-layer thresholds:

- **Coverage ≥ 80% on changed lines.**
- **Coverage ≥ 70% on the overall
  codebase.**
- **Mutation score ≥ 70% on critical
  modules.**
- **Fuzz time ≥ 1 hour per critical
  surface per release.**

A failing gate is a contract
violation; the orchestrator blocks
the release.

## Cross-references

- **Test strategy:**
  `.ai/skills/14-quality-verification/SKILL.md`
  section 11.
- **Quality gates:**
  `.ai/skills/14-quality-verification/SKILL.md`
  section 7.
- **Project gates (G0–G10):**
  `.ai/AGENTS.md` section 22.
- **Completion standard:**
  `.ai/AGENTS.md` section 21.
