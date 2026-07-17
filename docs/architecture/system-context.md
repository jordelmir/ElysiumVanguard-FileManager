---
title: System Context (C4 Level 1)
status: stub — to be filled in during Phase 0
owner: skill 00 (program-orchestrator)
last_updated: 2026-07-17
audience: orchestrator, all 16 skills, external reviewers
---

# System Context — Elysium Automotive Foundry

> **Status:** This is a stub for the C4
> level 1 system-context document. The full
> content is produced during Phase 0
> (Discovery) and updated as the platform
> grows. Reference: `.ai/AGENTS.md` section
> 23 (Required Documentation).

## What this document must include

- **The system context diagram (C4 level 1).**
  The platform is the central box; the
  actors are on the left; the external
  systems are on the right.
- **The actors.** Every person or system
  that interacts with the platform.
- **The external systems.** Every third-
  party SaaS + every regulator + every
  partner.
- **The trust boundaries.** Where the
  trust boundary is drawn between the
  platform and the external world.
- **The data flows.** The high-level data
  flows across the trust boundary.

## Actors

The platform's actors are:

- **Designer** — the user who designs a
  vehicle via the DSL.
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

## External systems

The platform's external systems are:

- **External identity provider** (OIDC /
  OAuth 2.1).
- **External object storage** (S3-
  compatible).
- **External payment provider** (third-
  party SaaS).
- **External regulatory filings** (UN,
  EU, ISO, etc.).
- **External AI providers** (when the
  AI council needs a remote model).

## Trust boundary

The trust boundary is drawn at the
platform's edge. Everything inside the
boundary is trusted; everything outside
is untrusted.

The boundary is enforced by:

- The auth layer (skill 12) — every
  request is authenticated.
- The authz layer (skill 12) — every
  action is authorized.
- The audit layer (skill 09) — every
  state-changing action is audited.
- The encryption layer (skill 12) —
  encryption at rest + in transit.
- The threat model (skill 12) — the
  documented attack surface + the
  mitigations.

## Cross-references

- **Container view (C4 level 2):**
  `docs/foundry/target-architecture.md`.
- **Component view (C4 level 3):**
  `docs/foundry/target-architecture.md`.
- **Domain map:** `docs/architecture/domain-map.md`.
- **Security model:**
  `docs/architecture/security-model.md`.
- **Data classification:**
  `docs/architecture/data-classification.md`.
- **AI authority boundaries:**
  `docs/architecture/ai-authority-boundaries.md`.
