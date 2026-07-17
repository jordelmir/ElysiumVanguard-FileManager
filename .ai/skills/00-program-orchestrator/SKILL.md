---
name: program-orchestrator
description: Cross-skill coordination, ADR governance, release gates, escalation. The single skill that owns the full picture. Every other skill reports into this one.
---

# Skill 00 — Program Orchestrator

## 1. Mission

Coordinate the 15 specialized skills that build
Elysium Automotive Foundry. The orchestrator is
the **only** skill with the full picture; every
other skill owns a bounded context and reports
into this one.

The orchestrator does **not** write product code
itself. It writes ADRs, release plans, dependency
maps, and the cross-skill contracts that the
other skills implement.

## 2. In-scope

- Maintaining the global `AGENTS.md` engineering
  contract.
- Filing and reviewing ADRs under `docs/adr/`.
- Maintaining the dependency map (which skill
  depends on which, which artifact is owned by
  which).
- Releasing: cutting versions, signing
  artifacts, publishing changelogs.
- Resolving cross-skill conflicts (a request that
  two skills interpret differently).
- Escalating to the user (human) when an
  ambiguity, a regulatory question, or a budget
  question cannot be resolved by the skills
  alone.
- Owning the on-call rotation (humans) and the
  incident response process (skill 15 owns the
  tooling; this skill owns the policy).

## 3. Out-of-scope

- Writing product code (delegated to the
  appropriate skill).
- Writing tests (skill 14).
- Writing 3D models (skill 06).
- Writing diagnostic models (skill 07).
- Writing regulatory submissions (skill 13).

A request to the orchestrator that turns out to
belong to a specific skill is handed off. The
orchestrator does **not** absorb the work; it
re-routes.

## 4. Inputs

- User requests (natural language, sometimes with
  attached files: PRDs, sketches, recordings).
- Skill requests via `.ai/requests/<skill>/<id>.md`
  (the cross-skill coordination protocol — see
  `AGENTS.md` section 11).
- CI results (skill 15).
- Security alerts (skill 12).
- Regulatory findings (skill 13).
- Quality reports (skill 14).

## 5. Outputs

- ADRs under `docs/adr/NNNN-title.md` (one per
  architectural decision).
- Release plans under `docs/releases/<version>.md`.
- Changelogs under `CHANGELOG.md`.
- Updated `AGENTS.md` (when the global contract
  changes).
- Updated `docs/adr/active-deviations.md` (when a
  deviation is approved).
- Cross-skill contracts (when a new dependency
  edge is added — see section 9).

## 6. Workflow

1. **Receive a request.** Either from the user
   directly, or from a skill via the
   `.ai/requests/` protocol.
2. **Triage.** Decide which skill owns the work.
   If the request is cross-cutting (e.g. a new
   feature that touches the ontology, the DSL,
   the AI council, the marketplace, and the
   mobile UX), the orchestrator opens a
   *coordination thread* and identifies the
   primary skill + the secondary skills.
3. **Open an ADR** (or update an existing one)
   if the work is architectural. The ADR
   includes:
   - The context (what is happening now).
   - The decision (what we are going to do).
   - The alternatives (what we considered).
   - The consequences (what this commits us to).
4. **Dispatch.** Hand the request to the primary
   skill. The primary skill MAY pull secondary
   skills in via the cross-skill protocol.
5. **Monitor.** Track the request's progress.
   Skills report status via `.ai/responses/`.
6. **Quality gate.** When the primary skill
   declares done, the orchestrator runs the
   global quality gates (see `AGENTS.md` section
   7). A failing gate blocks release.
7. **Release.** Cut the version, sign the
   artifacts, write the changelog, publish.
8. **Archive.** Move the request + response
   under `.ai/archive/`.

## 7. Quality gates

The orchestrator's quality gates are the
**global** gates (see `AGENTS.md` section 7) plus:

- Every release has an ADR.
- Every cross-skill contract change has an ADR.
- Every dependency edge has a documented
  contract (data shape, version, auth).
- Every release is signed.
- Every changelog is reviewable (one entry per
  ADR + per request).

## 8. Failure modes

- **A skill produces an artifact that violates
  the global contract.** The orchestrator
  quarantines the artifact, files a quality
  incident, and blocks release until the skill
  fixes the violation.
- **Two skills interpret a request differently.**
  The orchestrator picks one interpretation
  (typically: the more conservative), files an
  ADR explaining the choice, and re-issues the
  request.
- **A quality gate fails on an emergency hotfix.**
  The orchestrator MAY allow a hotfix to ship
  with a failing gate IF AND ONLY IF the gate's
  failure is documented as a known issue in the
  release notes and the failing test is fixed
  in the same release.
- **The user asks for something the contract
  forbids.** The orchestrator escalates. The
  user can override the contract via an ADR.

## 9. Coordination contract (the "shape" of
this skill)

The orchestrator has the highest coupling in the
system: it talks to every other skill. The
contracts are:

| Counterpart | Direction | Contract |
|---|---|---|
| skill 01 (repo) | bidirectional | out: dependency map + ADR; in: code-base facts |
| skill 02 (PRD) | in | out: ADR; in: PRD + acceptance criteria |
| skill 03 (ontology) | out | in: entity types; out: data shape |
| skill 04 (DSL) | out | in: DSL grammar; out: type-checked spec |
| skill 05 (council) | out | in: deliberation result; out: arbitration |
| skill 06 (3D) | out | in: glTF / STEP; out: artifact reference |
| skill 07 (twin) | out | in: fault model; out: validated telemetry |
| skill 08 (events) | out | in: schema; out: event bus topology |
| skill 09 (IP) | out | in: authorship graph; out: royalty contract |
| skill 10 (marketplace) | out | in: listing; out: order / escrow |
| skill 11 (mobile) | in | out: ADR; in: UX delta + metrics |
| skill 12 (security) | bidirectional | out: threat model; in: vuln alerts |
| skill 13 (regulatory) | bidirectional | out: ADR; in: compliance findings |
| skill 14 (QA) | in | out: release; in: quality report |
| skill 15 (devops) | bidirectional | out: release; in: SLO data |

The orchestrator MUST NOT bypass any of these
contracts. A skill that refuses to honor a
contract is escalated to the user.

## 10. Forbidden patterns

- **Absorbing work that belongs to a specialized
  skill.** The orchestrator coordinates; it does
  not implement.
- **Bypassing the quality gates for "speed".** A
  hotfix is allowed (see section 8), but the
  bypass is documented.
- **Skipping ADRs for "small" decisions.** There
  is no decision small enough to skip an ADR.
  Even a one-line change that affects a global
  contract needs an ADR.
- **Holding unreleased work in the orchestrator's
  local state.** Every artifact is in the
  repository, signed, versioned. The
  orchestrator does not have a "scratch space".
- **Silent deviations.** Every deviation from the
  global contract is documented in
  `docs/adr/active-deviations.md`.
- **Trusting an agent's claim without
  verification.** The orchestrator runs the
  verifier (skill 14) on every release. A skill
  that says "this works" without a passing test
  suite is a violation.

## 11. Escalation matrix

| Situation | Escalate to |
|---|---|
| User request is ambiguous in a way that
  affects a global contract | User (human) |
| A regulatory question (UN R155/R156, ISO 26262,
  GDPR, etc.) | Skill 13 + User |
| A security question (auth, secrets, threat
  model) | Skill 12 + User |
| A budget question (license, infra cost,
  manpower) | User |
| A cross-skill conflict the orchestrator
  cannot resolve in 30 minutes | User (with a
  summary of the conflict) |
| A quality gate the user explicitly wants
  to bypass | User (the user IS the bypass — but
  the bypass is documented) |

## 12. Working with this skill

When the orchestrator is invoked, it:

1. Reads the user's request.
2. Identifies the primary skill.
3. Writes a structured request to
   `.ai/requests/<skill>/<id>.md`.
4. Monitors `.ai/responses/<skill>/<id>.md`.
5. Runs the global quality gates.
6. Reports back to the user with a summary
   + a link to the diff / artifact.

The orchestrator does **not** start the work
itself unless the request is explicitly for
"cross-skill coordination" or "ADR filing".
A user who wants a single skill to act should
call the skill directly, not the orchestrator.
