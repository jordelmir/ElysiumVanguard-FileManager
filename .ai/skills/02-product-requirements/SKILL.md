---
name: product-requirements
description: Write PRDs, user stories, and acceptance criteria. Turn fuzzy user intent into testable, test-driven, test-verified requirements. The skill that bridges "I want X" and the engineering skills.
---

# Skill 02 — Product Requirements

## 1. Mission

Translate fuzzy product intent ("I want a
marketplace for vehicle designs") into
**testable** requirements. Every requirement has
acceptance criteria that a test can verify. The
test is the requirement; the requirement is the
test.

This skill is the bridge between the user and the
engineering skills. The orchestrator hands a user
request to this skill; this skill produces a PRD
the engineering skills can implement.

## 2. In-scope

- Conducting the discovery interview (when the
  user request is ambiguous).
- Writing PRDs under `docs/prd/`.
- Writing user stories.
- Writing acceptance criteria.
- Writing Gherkin scenarios (Given / When / Then)
  for the acceptance criteria.
- Maintaining the requirements traceability
  matrix (requirement → story → test → ADR).
- Filing the "requirement ADR" when a requirement
  is or becomes architectural.

## 3. Out-of-scope

- Writing product code. The PRD describes the
  *what*; the engineering skills describe the
  *how*.
- Writing tests. Skill 14.
- Designing the UX. Skill 11.
- Designing the data model. Skill 03.
- Designing the AI behaviour. Skill 05.

A request to this skill that turns out to be a UX
question is handed off. The PRD does not
*design*; it *specifies*.

## 4. Inputs

- User request (natural language, sometimes with
  attached context: market research, regulatory
  notes, sketches).
- The baseline ADR + the dependency map from
  skill 01 (the existing architecture).
- Existing PRDs under `docs/prd/` (this skill
  reads what came before).
- The active-deviations list from the
  orchestrator (what the user has already
  decided is OK to break).
- Compliance scope from skill 13 (which
  regulations the PRD must respect).

## 5. Outputs

All outputs land in `docs/prd/`:

- `<slug>.md` — the PRD itself. Slugs are
  kebab-case: `vehicle-browse.md`,
  `royalty-settlement.md`.
- `stories/<slug>-<NNN>.md` — one per user story,
  where `NNN` is a 3-digit zero-padded sequence.
- `tests/<slug>-<NNN>.feature` — Gherkin
  scenarios, one file per story. The file IS
  the test (skill 14 runs it via Cucumber /
  Gauge / equivalent).
- `ADR-<NNNN>-<slug>.md` — when the requirement
  is architectural. The ADR is filed BEFORE
  the implementation skill starts.
- `traceability.csv` — every requirement → story
  → test → ADR mapping. Maintained as a
  spreadsheet + a CSV.

### PRD structure (mandatory)

```yaml
---
id: PRD-2026-0042
title: Marketplace browsing
status: draft
owner: skill 02
created_at: 2026-07-17
last_updated: 2026-07-17
related_adrs: [ADR-0042]
regulatory_scope: [GDPR, CCPA]
---

# Problem
[One paragraph: what the user is trying to do.]

# Users
[3-5 personas, each one paragraph. Personas are
sourced from the user request, the market
research, or the existing PRDs.]

# Goals
[3-7 goals, each one measurable.]

# Non-goals
[Explicit list. A PRD without non-goals is a
scope-creep risk.]

# User stories
[3-12 stories. Each one is a "As a <persona>, I
want <capability>, so that <outcome>".]

# Acceptance criteria
[One Gherkin scenario per story. The scenario
is the test.]

# Constraints
[Performance, scale, cost, regulatory. Anything
that constrains the implementation.]

# Out of scope (for this version)
[Explicit list.]

# Open questions
[List of unresolved questions. An empty list
means the PRD is ready for review.]
```

The format is non-negotiable. A PRD that is
missing the `## Non-goals` or `## Out of scope`
section is rejected by the quality gates.

## 6. Workflow

1. **Discovery interview.** The skill conducts a
   short interview with the user:
   - What problem are you solving?
   - For whom? (The persona.)
   - What does "done" look like?
   - What's the cost of NOT doing it?
   - What are the regulatory constraints?
   - What's the budget (calendar + money)?
   - Are there any PRDs / ADRs already covering
     this?
   The interview ends when the user can answer
   "yes" to "can you describe what success looks
   like in one sentence?".
2. **Draft the PRD.** Fill the template. Every
   section is required. The non-goals + out-of-scope
   sections are the most-frequently-skipped and
   the most-frequently-needed.
3. **Decompose into user stories.** One story
   per goal. Each story is a small, independent,
   testable capability.
4. **Write the Gherkin scenarios.** One scenario
   per story. The scenario is the test.
5. **File the requirement ADR** if the PRD
   commits the platform to a specific design.
6. **Update the traceability matrix.** Every
   story → every test → every ADR.
7. **Open the PR.** The orchestrator takes it
   from here. The implementation skill reads the
   PRD + the traceability + the ADRs.

## 7. Quality gates

- The PRD has all 9 sections (problem / users /
  goals / non-goals / stories / acceptance /
  constraints / out-of-scope / open questions).
- Every goal is measurable (a number, a
  percentage, a latency, a count).
- Every user story fits the "As a <persona>, I
  want <capability>, so that <outcome>" template.
- Every user story has at least one Gherkin
  scenario.
- The traceability matrix is updated.
- The PRD's `regulatory_scope` matches the
  scope skill 13 returned (if skill 13 has been
  invoked).
- The "Open questions" section is empty before
  the PRD ships.

A PRD that fails any gate is rejected. The
rejection is a learning artifact (skill 14 keeps
the gate violation log).

## 8. Failure modes

- **The user cannot describe the problem.** The
  skill escalates to the orchestrator. The
  orchestrator either schedules a deeper
  discovery session or rejects the request.
- **The PRD has untestable acceptance criteria.**
  The skill rewrites them. "It should be fast"
  is not a criterion; "p99 latency < 200ms at
  1000 RPS" is.
- **The PRD exceeds the budget.** The skill
  surfaces the trade-off and asks the user to
  cut scope.
- **The PRD overlaps with an existing PRD.** The
  skill flags the overlap and asks the
  orchestrator to consolidate.
- **The user pushes for "just build it".** The
  skill refuses. A PRD-less feature is a
  scope-creep magnet. The orchestrator
  arbitrates.

## 9. Coordination contract

- **Input from**: skill 00 (orchestrator), user.
- **Output to**: skill 03 (ontology), skill 04
  (DSL), skill 11 (UX), every other skill that
  implements the feature.
- **Triggered by**: every user request that ends
  in "I want X" or "we need Y".
- **Frequency**: per feature, per release, per
  user-visible change.

## 10. Forbidden patterns

- **"Build the platform" PRDs.** A PRD is for a
  feature, not for the whole platform. The
  platform-level document is the program charter,
  not a PRD.
- **Untestable acceptance criteria.** "Easy to
  use", "fast enough", "looks good" are not
  criteria.
- **Stories without personas.** "As a user, I
  want ..." is not a story. "As a buyer browsing
  the marketplace on a 6-inch phone, I want to
  filter by vehicle class, so that I see only the
  SUVs" is.
- **Vague non-goals.** "We won't do everything"
  is not a non-goal. "We won't support the
  bidding flow in v1" is.
- **PRDs without a regulator scope.** A PRD
  that touches user data has a GDPR / CCPA
  impact; a PRD that touches vehicle behaviour
  has an ISO 26262 impact. The PRD must name
  them.
- **PRDs that double as design docs.** The PRD
  says WHAT. The design doc says HOW. Skill 03
  and 04 own the design.
- **PRDs without open questions.** An empty
  open-questions section means the skill did
  not do the discovery interview.
- **PRD drafts that ship.** The orchestrator
  does not merge a draft. The status must be
  `ready-for-review` or `approved`.

## 11. PRDs in the Elysium Automotive Foundry

Initial PRD roadmap (this skill maintains the
queue):

- `PRD-0001` — Brand + project creation.
- `PRD-0002` — Natural-language vehicle design.
- `PRD-0003` — Digital twin versioning.
- `PRD-0004` — Mechanical / electrical / electronic
  / software architecture assembly.
- `PRD-0005` — Human + AI agent collaboration.
- `PRD-0006` — Authorship + contribution tracking.
- `PRD-0007` — Compatibility + manufacturability
  validation.
- `PRD-0008` — Publication + licensing.
- `PRD-0009` — Royalty calculation + distribution.
- `PRD-0010` — Supplier / engineer / lab / manufacturer
  integration.
- `PRD-0011` — Field diagnostic + repair flow.
- `PRD-0012` — Marketplace browsing.
- `PRD-0013` — Marketplace purchase + escrow.
- `PRD-0014` — Royalty settlement.
- `PRD-0015` — Field on-device forge (mobile).

Each PRD is the seed of a release. The
orchestrator sequences them.

## 12. Working with this skill

When invoked, this skill:

1. Asks the discovery questions.
2. Drafts the PRD.
3. Decomposes into stories.
4. Writes the Gherkin scenarios.
5. Files the requirement ADR (if needed).
6. Updates the traceability matrix.
7. Returns the PRD to the orchestrator with a
   recommended release number.

The skill is a co-pilot, not an oracle. The user
owns the product call. This skill turns the
product call into something the engineering
skills can implement.
