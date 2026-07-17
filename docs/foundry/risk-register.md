---
title: Risk Register
status: stub — to be filled in by the orchestrator in Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills
---

# Risk Register — Elysium Automotive Foundry

> **Status:** This is the **mandatory fifth
> output** of the program orchestrator (skill
> 00) before any production feature is
> implemented. Reference: `.ai/AGENTS.md`
> section 22 (G0 — Repository understood) +
> skill 00 section 11 (failure mode: "a
> phase's gate cannot be green").

This document is the **single source of
truth for program-level risks** in the
Elysium Automotive Foundry platform. Every
identified risk has an owner, a likelihood,
an impact, and a mitigation.

## What this document must include

Per `.ai/skills/00-program-orchestrator/SKILL.md`
section 3 (Mandatory first outputs), the
risk register covers:

- **Every identified risk** (the "we don't
  know" from the current-state audit +
  the gap between the current state and
  the target architecture + the
  per-increment risks).
- **The owner** (the skill or the human
  accountable for the risk).
- **The likelihood** (Low / Medium / High).
- **The impact** (Low / Medium / High /
  Critical).
- **The mitigation** (the action that
  reduces the likelihood or the impact).
- **The status** (Open / Mitigating /
  Closed).
- **The ADR** (when the risk is closed via
  an ADR + a deviation).

## Risk classification

### Data integrity

- **R-DI-1 — AI-inferred data masquerades
  as verified.** An `AI_INFERRED` fact
  becomes `OEM_VERIFIED` / `REGULATORY_VERIFIED`
  / `LAB_VERIFIED` / `ENGINEER_REVIEWED`
  / `COMMUNITY_CORROBORATED` without a
  signed transition event in the audit
  trail. **Mitigation:** per
  `.ai/STANDARDS.md` section 3.2, the
  transition is a human review + a signed
  counter-signature. **Owner:** skill 14
  (verifier). **Likelihood:** Medium.
  **Impact:** Critical.
- **R-DI-2 — Visual-mesh compatibility
  declared as mechanical compatibility.**
  A mesh that visually matches a part is
  treated as mechanically compatible.
  **Mitigation:** per
  `.ai/STANDARDS.md` section 2.1, the
  compatibility check is the fault model
  + the engineering review, not the 3D
  viewer. **Owner:** skill 06 (3D) +
  skill 07 (twin). **Likelihood:**
  Medium. **Impact:** Critical.
- **R-DI-3 — Float / Double for money.**
  A royalty calculation uses `Double` /
  `Float` / `f64`. **Mitigation:** per
  `.ai/STANDARDS.md` section 2.2, money is
  `BigDecimal` (JVM) / `decimal.Decimal`
  (Python) / `rust_decimal::Decimal`
  (Rust). The CI enforces this.
  **Owner:** skill 14. **Likelihood:**
  Low. **Impact:** Critical.

### Commercial integrity

- **R-CI-1 — Mutable historical commercial
  release.** A signed release is updated
  or deleted. **Mitigation:** per
  `.ai/STANDARDS.md` section 2.2, the
  audit trail is append-only. A rollback
  is a new release, not an edit.
  **Owner:** skill 09. **Likelihood:**
  Low. **Impact:** Critical.
- **R-CI-2 — Royalty without an active
  contract.** A royalty calculation
  against a `RoyaltyContract` whose
  status is not `ACTIVE`. **Mitigation:**
  per `.ai/STANDARDS.md` section 2.2 +
  skill 09, the engine rejects with a
  `ContractNotActive` error. **Owner:**
  skill 09. **Likelihood:** Medium.
  **Impact:** Critical.
- **R-CI-3 — Royalty on a `VISUAL_ONLY` or
  `CONCEPTUAL` artifact.** A settlement
  against an ineligible level.
  **Mitigation:** per skill 09, the
  settlement is rejected with a
  `ContractNotActive` error. **Owner:**
  skill 09. **Likelihood:** Medium.
  **Impact:** High.

### Code hygiene

- **R-CH-1 — Generic catch blocks hiding
  failures.** A `catch (e: Exception) { /* ignore */ }`.
  **Mitigation:** per
  `.ai/STANDARDS.md` section 2.3, every
  catch block re-throws, logs with a
  typed error, or returns a typed
  `Result` / `Either`. The CI enforces
  this. **Owner:** skill 14.
  **Likelihood:** Medium. **Impact:**
  High.
- **R-CH-2 — Unchecked null assertions.** A
  `!!` in Kotlin, an unchecked `as` in
  TypeScript, an `unwrap()` in
  production Rust. **Mitigation:** per
  `.ai/STANDARDS.md` section 2.3, the
  error path is typed. The CI enforces
  this. **Owner:** skill 14.
  **Likelihood:** Medium. **Impact:**
  High.

### Concurrency

- **R-CO-1 — Main-thread blocking on
  Android.** A model load, a decode, or
  a network call on the main thread.
  **Mitigation:** per
  `.ai/STANDARDS.md` section 2.4, every
  heavy operation is on
  `Dispatchers.IO`. The `StrictMode`
  test asserts no main-thread blocking.
  **Owner:** skill 11 (mobile) + skill
  14. **Likelihood:** Medium. **Impact:**
  High.

### Trust

- **R-T-1 — Unvalidated 3D asset.** A
  glTF / STEP / USD enters the canonical
  store without validation. **Mitigation:**
  per `.ai/STANDARDS.md` section 2.5,
  the asset is validated (manifold +
  units + coordinate system + file size
  + no-embedded-scripts + provenance
  coverage) before it enters the store.
  **Owner:** skill 06. **Likelihood:**
  Medium. **Impact:** Critical.
- **R-T-2 — Scripts executed from
  uploaded assets.** A glTF with a
  script, a STEP with macros, a USD
  with a custom schema that runs code.
  **Mitigation:** per
  `.ai/STANDARDS.md` section 2.5, the
  pipeline rejects at the parse step.
  The pipeline never executes user-
  supplied code. **Owner:** skill 06.
  **Likelihood:** Low. **Impact:**
  Critical.
- **R-T-3 — Secret in the application
  package.** A secret in the binary, the
  assets, the config, or the build
  artifacts. **Mitigation:** per
  `.ai/STANDARDS.md` section 2.5 +
  skill 12, secrets live in the vault +
  the secure enclave + the KMS. The CI
  scans every build artifact for known
  secret patterns. A positive match is
  a hard build failure. **Owner:**
  skill 12. **Likelihood:** Low.
  **Impact:** Critical.

### AI authority

- **R-AI-1 — LLM directly mutates
  authoritative state.** A model that
  writes to the database, the catalog,
  the audit trail, the royalty engine,
  the regulatory submission, or the
  safety gate. **Mitigation:** per
  `.ai/STANDARDS.md` section 5, the AI
  may NOT directly mutate any of these.
  The model produces a typed proposal;
  the deterministic engine + a human
  review apply it. **Owner:** skill 05
  + skill 14. **Likelihood:** Medium.
  **Impact:** Critical.
- **R-AI-2 — Vehicle marked as road
  legal based solely on AI output.**
  A `RoadLegal` flag whose only
  verification is `AI_INFERRED`.
  **Mitigation:** per
  `.ai/STANDARDS.md` section 2.6, the
  flag requires `ENGINEER_REVIEWED` +
  `REGULATORY_VERIFIED` + a human
  counter-signature. The CI enforces
  this. **Owner:** skill 13 + skill
  14. **Likelihood:** Low. **Impact:**
  Critical.

## Per-increment risks

When a new increment starts, the
orchestrator adds a per-increment risk
entry to the register. The entry has:

- The increment ID.
- The increment description.
- The specific risk (e.g. "the migration
  may be lossy").
- The mitigation (e.g. "the migration
  is tested on a fixture with a rollback
  path").
- The status (Open / Mitigating / Closed).

A per-increment risk is closed when the
increment's gate is green.

## Output

When Phase 0 is complete, this document
contains:

- The full risk inventory (per the
  section above).
- The per-increment risk log (when
  increments start).
- A cross-reference to the implementation
  roadmap (per `implementation-roadmap.md`).
- A cross-reference to the ADR registry
  (the `docs/adr/active-deviations.md`
  + the `docs/adr/closed-deviations.md`).

The orchestrator files the risk register
under
`docs/foundry/gates/g0-risk-register.md`
when G0 is green.
