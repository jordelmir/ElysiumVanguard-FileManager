---
name: elysium-program-orchestrator
description: Coordinates the complete implementation of Elysium Automotive Foundry through controlled phases, specialized skills and measurable quality gates.
---

# Skill 00 — Program Orchestrator

## 1. Mission

Coordinate all engineering activity without
allowing parallel agents to produce incompatible
domain models, duplicated services or conflicting
migrations.

This skill owns planning, sequencing, dependency
resolution and gate enforcement. It does **not**
implement every subsystem directly. It is the
**only** skill with the full picture; every other
skill owns a bounded context and reports into
this one.

The orchestrator writes ADRs, release plans,
dependency maps, the cross-skill contracts, and
the foundry audit outputs that other skills
consume. The orchestrator is the single source
of truth for the program's state.

## 2. Responsibilities

1. **Audit the repository** before planning.
2. **Create a current-state architecture map.**
3. **Identify reusable modules** that the
   Foundry should inherit, not re-implement.
4. **Detect technical debt** that blocks the
   Foundry.
5. **Build a dependency-ordered implementation
   plan.**
6. **Assign work to specialized skills.** A
   work-item has exactly one primary skill;
   secondary skills are pulled in via the
   cross-skill protocol.
7. **Prevent multiple skills from owning the
   same aggregate.** Two skills editing the
   same domain entity is a contract violation.
8. **Define acceptance criteria** before
   implementation. Every increment has
   testable acceptance criteria filed in the
   PRD (skill 02).
9. **Require evidence** before advancing a
   gate. A "looks done" is not a "is done";
   the verifier (skill 14) emits the
   evidence.
10. **Maintain a risk register and a decision
    log.** Every risk has an owner; every
    decision has an ADR.

The orchestrator does **not** bypass any of
these responsibilities. A request to the
orchestrator that turns out to belong to a
specific skill is **handed off**, not absorbed.

## 3. Mandatory first outputs

Before any production feature is implemented,
the orchestrator creates the following
artifacts under `docs/foundry/`:

- `docs/foundry/current-state-audit.md` — the
  current state of the repository. Includes:
  - Modules.
  - Build system.
  - Data stores.
  - Authentication.
  - Existing vehicle entities.
  - Existing 3D renderer.
  - Existing diagnostic bindings.
  - Existing AI providers.
  - Network boundaries.
  - Test coverage.
  - Security findings.
  - Performance bottlenecks.
  - Duplicate concepts.
  - Abandoned or dead code.
- `docs/foundry/target-architecture.md` — the
  target state. The map from "today" to
  "tomorrow", with the bridges and the
  cut-overs.
- `docs/foundry/domain-ownership.md` — which
  skill owns which aggregate. Every domain
  entity has exactly one owner.
- `docs/foundry/implementation-roadmap.md` —
  the dependency-ordered sequence of
  increments. Every increment is a vertical
  slice that ships end-to-end (domain → DB →
  use case → API → UI → auth → errors → tests
  → observability → docs → migration).
- `docs/foundry/risk-register.md` — every
  identified risk, with an owner, a
  likelihood, an impact, and a mitigation.
- `docs/foundry/dependency-map.md` — every
  cross-skill edge, with the data shape, the
  schema version, and the auth requirement.

A release is **not allowed** to start production
work until these six artifacts exist and are
current. A stale artifact is a contract
violation.

## 4. Execution phases

The Foundry is built in 6 controlled phases.
A phase is **not** declared done until its
gate (per `.ai/AGENTS.md` section 22, the
**Project Gates**) is green. A phase is
**not** started until the previous phase's
gate is green. A skipped phase is a contract
violation.

### Phase 0 — Discovery

No production feature implementation.

The orchestrator performs repository
archaeology, runs the existing tests,
documents the current architecture, and
establishes the six baseline artifacts
under `docs/foundry/`. The output is a
signed-off `current-state-audit.md` + the
five companion documents.

Gate: **G0** (Repository understood). All
six baseline artifacts exist; the audit is
reviewed and signed; the existing test
suite is green.

### Phase 1 — Foundational domain

The orchestrator dispatches skill 03
(ontology) to implement:

- `Project`.
- `VehicleProgram`.
- `VehicleRevision`.
- `Contributor`.
- `EngineeringArtifact`.
- `ProvenanceRecord`.
- Strongly-typed identifiers.
- Revision and concurrency strategy.

Gate: **G1** (Domain model approved). The
ontology is documented, versioned, signed,
and the invariant tests pass.

### Phase 2 — Vehicle definition

The orchestrator dispatches skill 04 (DSL
compiler) to implement:

- Vehicle Definition Language.
- Parser.
- Schema.
- Validator.
- Compatibility rules.
- Deterministic compiler.
- Compilation report.

Gate: **G3** (Vehicle compiler
deterministic). The parser + resolver +
type-checker are total; the spec output is
content-addressed + signed; the golden
tests pass.

### Phase 3 — Digital twin

The orchestrator dispatches skill 06 (3D
pipeline) + skill 07 (digital twin) to
implement:

- Scene manifest.
- Part instance graph.
- Asset streaming.
- Selection and isolation.
- Representation confidence.
- Diagnostic bindings.

Gate: **G4** (3D digital twin integrated).
The 3D pipeline + the digital twin consume
the same canonical artifact; the manifest
is signed; the LODs are present.

### Phase 4 — AI council

The orchestrator dispatches skill 05 (AI
council) to implement structured AI tools.
**The LLM MUST produce typed proposals
rather than direct database mutations.**
The orchestrator enforces the AI authority
boundary (per `.ai/AGENTS.md` section 8 +
`.ai/STANDARDS.md` section 5).

Gate: **G5** (AI constrained by structured
tools). The AI cannot write to the
database, the catalog, the audit trail, the
royalty engine, the regulatory submission,
or the safety gate. The model produces
typed proposals; the deterministic engine
+ a human review apply them.

### Phase 5 — Commercial foundation

The orchestrator dispatches skill 09
(IP/provenance/royalties) + skill 10
(marketplace/manufacturing) + skill 08
(backend event platform) to implement:

- Contracts.
- Rights.
- Licenses.
- Revenue events.
- Royalty rules.
- Statements.
- Audit trail.

Gate: **G6** (IP and provenance ledger
operational), **G7** (Royalty engine
contract-driven), **G8** (Marketplace and
supplier workflow).

## 5. Project gates

The Foundry has 11 project gates (G0–G10)
that map directly to the execution phases.
**No gate is skipped.** A gate that cannot
be green is an ADR + a risk-register entry,
not a bypass.

| Gate | Owner | What it proves |
|---|---|---|
| G0 — Repository understood | skill 00 | The six `docs/foundry/` artifacts exist + signed |
| G1 — Domain model approved | skill 03 | Ontology is documented + versioned + invariant tests pass |
| G2 — Persistence and versioning proven | skill 08 | Schema migrations are deterministic + idempotent + content-addressed |
| G3 — Vehicle compiler deterministic | skill 04 | Parser + resolver + type-checker are total; golden tests pass |
| G4 — 3D digital twin integrated | skill 06 + 07 | 3D pipeline + digital twin share the canonical artifact |
| G5 — AI constrained by structured tools | skill 05 | LLM produces typed proposals; cannot mutate authoritative state |
| G6 — IP and provenance ledger operational | skill 09 | Authorship + provenance + audit trail are signed + content-addressed |
| G7 — Royalty engine contract-driven | skill 09 | Royalty engine is deterministic; money is `BigDecimal`; settlements are auditable |
| G8 — Marketplace and supplier workflow | skill 10 | Listings + escrow + supplier integration are end-to-end |
| G9 — Safety and regulatory evidence model | skill 13 | ISO 26262 + UN R155/R156 + ISO 21434 + GDPR/CCPA/LGPD are documented + evidenced |
| G10 — Production hardening | skill 12 + 15 | Threat model + SLOs + on-call + runbooks + red team + CVE SLA + observability |

The full gate definitions, the evidence
required, and the recovery patterns are in
`.ai/AGENTS.md` section 22.

## 6. Inputs

- User requests (natural language, sometimes
  with attached files: PRDs, sketches,
  recordings).
- Skill requests via
  `.ai/requests/<skill>/<id>.md` (the
  cross-skill coordination protocol — see
  `.ai/AGENTS.md` section 17).
- CI results (skill 15).
- Security alerts (skill 12).
- Regulatory findings (skill 13).
- Quality reports (skill 14).
- Foundry audit updates (the
  `docs/foundry/` artifacts are living
  documents).

## 7. Outputs

- **ADRs** under `docs/adr/NNNN-title.md`
  (one per architectural decision).
- **Release plans** under
  `docs/releases/<version>.md`.
- **Changelogs** under `CHANGELOG.md`.
- **Updated `AGENTS.md`** (when the global
  contract changes).
- **Updated `docs/adr/active-deviations.md`**
  (when a deviation is approved).
- **Foundry audit artifacts** under
  `docs/foundry/` (the six mandatory first
  outputs + their updates).
- **Cross-skill contracts** (when a new
  dependency edge is added — see section 10).
- **Risk register** under
  `docs/foundry/risk-register.md` (the
  single source of truth for program-level
  risks).
- **Implementation roadmap** under
  `docs/foundry/implementation-roadmap.md`
  (the dependency-ordered sequence of
  increments).

## 8. Workflow

1. **Receive a request.** Either from the
   user directly, or from a skill via the
   `.ai/requests/` protocol.
2. **Triage.** Decide which skill owns the
   work. If the request is cross-cutting
   (e.g. a new feature that touches the
   ontology, the DSL, the AI council, the
   marketplace, and the mobile UX), the
   orchestrator opens a *coordination
   thread* and identifies the primary skill
   + the secondary skills.
3. **Open an ADR** (or update an existing
   one) if the work is architectural. The
   ADR includes:
   - The context (what is happening now).
   - The decision (what we are going to do).
   - The alternatives (what we considered).
   - The consequences (what this commits us
     to).
4. **Dispatch.** Hand the request to the
   primary skill. The primary skill MAY
   pull secondary skills in via the
   cross-skill protocol.
5. **Monitor.** Track the request's
   progress. Skills report status via
   `.ai/responses/`.
6. **Quality gate.** When the primary skill
   declares done, the orchestrator runs
   the **project gate** that corresponds
   to the current phase (per section 5) +
   the **global quality gates** (per
   `.ai/AGENTS.md` section 13). A failing
   gate blocks release.
7. **Advance the phase.** When a project's
   gate is green, the orchestrator
   advances the phase. The next phase's
   work is dispatched.
8. **Release.** Cut the version, sign the
   artifacts, write the changelog, publish.
9. **Archive.** Move the request + response
   under `.ai/archive/`.

## 9. Quality gates

The orchestrator's quality gates are the
**project gates** (G0–G10, per section 5)
plus the **global gates** (per
`.ai/AGENTS.md` section 13) plus:

- Every release has an ADR.
- Every cross-skill contract change has an
  ADR.
- Every dependency edge has a documented
  contract (data shape, version, auth).
- Every release is signed.
- Every changelog is reviewable (one entry
  per ADR + per request).
- Every project gate has a sign-off
  artifact in `docs/foundry/gates/`.

## 10. Coordination contract (the "shape" of
this skill)

The orchestrator has the highest coupling in
the system: it talks to every other skill.
The contracts are:

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

The orchestrator MUST NOT bypass any of
these contracts. A skill that refuses to
honor a contract is escalated to the user.

## 11. Failure modes

- **A skill produces an artifact that
  violates the global contract.** The
  orchestrator quarantines the artifact,
  files a quality incident, and blocks
  release until the skill fixes the
  violation.
- **Two skills interpret a request
  differently.** The orchestrator picks
  one interpretation (typically: the more
  conservative), files an ADR explaining
  the choice, and re-issues the request.
- **A project gate fails on an emergency
  hotfix.** The orchestrator MAY allow a
  hotfix to ship with a failing gate IF
  AND ONLY IF the gate's failure is
  documented as a known issue in the
  release notes and the failing test is
  fixed in the same release. The
  exception is logged in
  `docs/adr/active-deviations.md`.
- **The user asks for something the
  contract forbids.** The orchestrator
  escalates. The user can override the
  contract via an ADR.
- **A phase's gate cannot be green.** The
  orchestrator does **not** advance the
  phase. The gap is filed as a risk in
  `docs/foundry/risk-register.md` and the
  orchestrator dispatches remediation
  work.
- **Two skills attempt to own the same
  aggregate.** The orchestrator arbitrates
  via the `docs/foundry/domain-ownership.md`
  document. A skill that ignores the
  arbitration is escalated.

## 12. Forbidden patterns

- **Absorbing work that belongs to a
  specialized skill.** The orchestrator
  coordinates; it does not implement.
- **Bypassing the project gates for
  "speed".** A hotfix is allowed (see
  section 11), but the bypass is
  documented.
- **Skipping ADRs for "small" decisions.**
  There is no decision small enough to
  skip an ADR. Even a one-line change that
  affects a global contract needs an ADR.
- **Holding unreleased work in the
  orchestrator's local state.** Every
  artifact is in the repository, signed,
  versioned. The orchestrator does not
  have a "scratch space".
- **Silent deviations.** Every deviation
  from the global contract is documented
  in `docs/adr/active-deviations.md`.
- **Trusting an agent's claim without
  verification.** The orchestrator runs
  the verifier (skill 14) on every
  release. A skill that says "this works"
  without a passing test suite is a
  violation.
- **Skipping a phase.** A phase is not
  declared done until its gate is green.
  A skipped phase is a contract violation.
- **Letting an LLM mutate authoritative
  state directly.** The model produces
  typed proposals; the deterministic
  engine + a human review apply them
  (per `.ai/AGENTS.md` section 8 +
  `.ai/STANDARDS.md` section 5).
- **Leaking internal stack traces or
  secrets to clients.** The orchestrator
  enforces the typed error model at every
  API boundary (per `.ai/AGENTS.md`
  section 10 + `.ai/STANDARDS.md` section
  7). A free-form string error or a raw
  stack trace at the API boundary is a
  contract violation.

## 13. Escalation matrix

| Situation | Escalate to |
|---|---|
| User request is ambiguous in a way that
  affects a global contract | User (human) |
| A regulatory question (UN R155/R156, ISO
  26262, GDPR, etc.) | Skill 13 + User |
| A security question (auth, secrets,
  threat model) | Skill 12 + User |
| A budget question (license, infra cost,
  manpower) | User |
| A cross-skill conflict the orchestrator
  cannot resolve in 30 minutes | User (with
  a summary of the conflict) |
| A quality gate the user explicitly wants
  to bypass | User (the user IS the bypass —
  but the bypass is documented) |
| A phase's gate cannot be green | User +
  risk register entry |
| A skill refuses to honor a contract | User
  (with the contract + the skill's
  objection) |

## 14. Working with this skill

When the orchestrator is invoked, it:

1. Reads the user's request.
2. Identifies the primary skill (or, for a
   new program, runs Phase 0 first).
3. Writes a structured request to
   `.ai/requests/<skill>/<id>.md`.
4. Monitors `.ai/responses/<skill>/<id>.md`.
5. Runs the project gate that corresponds
   to the current phase + the global
   quality gates.
6. Reports back to the user with a
   summary + a link to the diff / artifact
   + the next concrete step.

The orchestrator does **not** start the
work itself unless the request is
explicitly for "cross-skill coordination"
or "ADR filing" or "Phase 0 audit". A user
who wants a single skill to act should
call the skill directly, not the
orchestrator.

## 15. Cross-references

- **Project gates (G0–G10):**
  `.ai/AGENTS.md` section 22.
- **Completion standard:** `.ai/AGENTS.md`
  section 21.
- **Required documentation:**
  `.ai/AGENTS.md` section 23.
- **Cross-cutting concerns (correlation ID,
  retry, no-leak):** `.ai/AGENTS.md`
  section 24.
- **Global quality gates:** `.ai/AGENTS.md`
  section 13.
- **Cross-skill coordination protocol:**
  `.ai/AGENTS.md` section 17.
- **Artifact contract:** `.ai/AGENTS.md`
  section 12.
- **AI authority boundary:**
  `.ai/AGENTS.md` section 8 +
  `.ai/STANDARDS.md` section 5.
- **Required error model:** `.ai/AGENTS.md`
  section 10 + `.ai/STANDARDS.md` section
  7.
