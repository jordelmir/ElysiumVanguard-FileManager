---
title: AI Authority Boundaries
status: stub — to be filled in during Phase 0 + G5
owner: skill 05 (ai-engineering-council)
last_updated: 2026-07-17
audience: orchestrator, skill 05, all 16 skills, external reviewers
---

# AI Authority Boundaries — Elysium Automotive Foundry

> **Status:** This is a stub for the AI
> authority boundaries. The full content
> is produced during Phase 0 (Discovery) +
> G5 (AI constrained by structured tools).
> Reference: `.ai/AGENTS.md` section 8
> + section 23 + `.ai/STANDARDS.md`
> section 5.

## What this document must include

- **The authoritative workflow** (per
  `.ai/STANDARDS.md` section 5).
- **What the AI may do** (the
  structured tools).
- **What the AI may NOT do** (the
  prohibitions).
- **The recovery patterns** (when
  the AI proposes something it
  should not).
- **The verification** (how the
  verifier — skill 14 — enforces the
  boundary).

## The authoritative workflow

The platform's authoritative workflow
is:

```
natural language
  → structured proposal (the AI)
  → schema validation (the deterministic engine)
  → simulation or evidence (the deterministic engine + skill 07)
  → human review (the reviewer in the catalog)
  → signed revision (the catalog)
```

A model is **a draft**. A deterministic
engine + a human review **apply** the
draft. A model is **not** an authority.

## What the AI may do

The AI may:

- **Interpret requirements.** The
  user says "I want a small EV for
  city use". The AI proposes a
  2-seat, 80 kWh battery, single-
  motor layout. The AI's output is
  a structured proposal.
- **Propose architectures.** The user
  has a battery + a motor + a
  chassis. The AI proposes the
  wiring harness, the ECU, the
  software stack. The AI's output
  is a structured proposal.
- **Generate candidate configurations.**
  The user has 3 motors, 2
  batteries, 4 chassis. The AI
  generates the candidate pairings.
  The AI's output is a structured
  proposal.
- **Explain trade-offs.** The user
  asks "should I use NMC or LFP for
  the battery?". The AI explains.
  The AI's output is a draft, not
  a decision.
- **Resolve terminology.** The user
  says "fastback". The AI resolves
  to a `BodyStyle` enum value.
- **Suggest validation plans.** The
  user has a new design. The AI
  suggests a homologation plan.
  The AI's output is a draft.
- **Generate drafts.** The user has
  a PRD. The AI generates a draft.
  The draft is reviewed.
- **Identify inconsistencies.** The
  user has a spec. The AI points
  out the inconsistencies.
- **Produce structured commands for
  deterministic engines.** The user
  has a request. The AI produces a
  typed command. The deterministic
  engine executes the command.

## What the AI may NOT do

The AI may NOT directly:

- **Approve safety-critical
  requirements.** A `SafetyGoal`
  requires `ENGINEER_REVIEWED` +
  `REGULATORY_VERIFIED` + a human
  counter-signature. An AI's "this
  is safe" is a draft, not an
  approval.
- **Certify regulatory compliance.**
  A `RegulatorySubmission` requires
  `REGULATORY_VERIFIED` + a human
  counter-signature. An AI's "this
  is compliant" is a draft, not a
  certification.
- **Declare mechanical compatibility.**
  A `Compatibility` fact requires
  `LAB_VERIFIED` or `OEM_VERIFIED`
  + a human counter-signature. An
  AI's "these parts fit" is a
  draft, not a declaration.
- **Finalize financial settlements.**
  A `Settlement` requires
  `ENGINEER_REVIEWED` + an audit
  trail + a human counter-signature.
  An AI's "the royalty is X" is a
  draft, not a finalization.
- **Determine legal ownership.** An
  `AuthorshipClaim` requires a
  human counter-signature. An AI's
  "this was written by X" is a
  draft, not a determination.
- **Modify signed releases.** A
  signed release is append-only.
  An AI's "let me fix this in v1.2"
  is a violation.
- **Create verified technical facts
  without evidence.** A fact is
  `VERIFIED` only when the evidence
  exists. An AI's "this is true" is
  `AI_INFERRED` (or `UNKNOWN`),
  not `VERIFIED`.
- **Write to the database, the
  catalog, the audit trail, the
  royalty engine, the regulatory
  submission, or the safety gate.**
  The AI has **no path** to any of
  these. The model produces a typed
  proposal; the deterministic
  engine + a human review apply
  the proposal.

## Recovery patterns

When the AI proposes something it
should not, the platform's recovery
patterns are:

- **The schema validation rejects
  the proposal.** The proposal
  does not match the typed schema;
  the engine returns a typed
  `FoundryError`; the AI is
  re-prompted with the error.
- **The deterministic engine
  rejects the proposal.** The
  proposal violates an invariant;
  the engine returns a typed
  `FoundryError`; the AI is
  re-prompted with the error.
- **The simulation rejects the
  proposal.** The digital twin
  (skill 07) simulates the proposal
  + the simulation returns a
  failure; the AI is re-prompted
  with the failure.
- **The human review rejects the
  proposal.** The reviewer (in the
  catalog) signs off "no"; the
  AI is re-prompted with the
  rejection.
- **The audit trail records the
  proposal + the rejection.** The
  signed event is in the catalog
  (skill 09); the platform's
  telemetry records the rejection.

A recovery that bypasses the
workflow is a contract violation;
the orchestrator blocks the
release.

## Verification

The verifier (skill 14) enforces the
boundary. The verification is:

- **Schema-validation tests.** A
  test asserts the AI cannot
  produce a proposal that bypasses
  the schema validator.
- **Engine-rejection tests.** A
  test asserts the AI cannot
  produce a proposal that bypasses
  the deterministic engine.
- **Audit-trail tests.** A test
  asserts every AI proposal is
  recorded in the audit trail.
- **Human-review tests.** A test
  asserts every AI proposal that
  affects a regulated surface has
  a human review + a signed
  counter-signature.
- **AI-authority gate.** The
  quality gate (per skill 14
  section 7) fails the build if
  the AI has a path to the
  database, the catalog, the audit
  trail, the royalty engine, the
  regulatory submission, or the
  safety gate.

A failing verification is a
contract violation; the
orchestrator blocks the release.

## Cross-references

- **AI authority boundary (in AGENTS.md):**
  `.ai/AGENTS.md` section 8.
- **AI authority boundary (in STANDARDS.md):**
  `.ai/STANDARDS.md` section 5.
- **AI council skill:**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
- **Quality gate (AI-authority):**
  `.ai/skills/14-quality-verification/SKILL.md`
  section 7.
- **Regulatory:**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
