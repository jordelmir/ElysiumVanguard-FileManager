---
name: automotive-product-requirements
description: Converts an automotive idea into measurable, traceable and conflict-aware product requirements.
---

# Skill 02 — Product and Requirements Engineering

## 1. Mission

Transform vague vehicle ideas into
**explicit, measurable, versioned
requirements**. A requirement is testable;
the test is the requirement; the
requirement is the test.

Statements such as "make it futuristic",
"make it safe" or "make it cheap" are
**not** requirements. They are aspirations.
A requirement is a value with a unit + a
threshold + a verification method.

This skill is the bridge between the user
and the engineering skills. The
orchestrator hands a user request to this
skill; this skill produces a PRD the
engineering skills can implement.

## 2. In-scope

- Conducting the discovery interview
  (when the user request is ambiguous).
- Decomposing the vision into a
  hierarchical requirement set.
- Naming the stakeholder needs.
- Writing the product requirements
  (the user-facing "what").
- Deriving the system + subsystem +
  component requirements (the
  engineering-facing "how").
- Naming the verification requirements
  (the test).
- Naming the regulatory constraints
  (per skill 13).
- Naming the commercial constraints
  (per skill 09 + skill 10).
- Running the conflict engine (per
  section 6).
- Running the change-control process
  (per section 7).

## 3. Out-of-scope

- Writing the architecture (skill 00 +
  skill 03 + skill 04).
- Writing the implementation (every
  other skill).
- Writing the tests (skill 14).
- Deciding the regulatory posture
  (skill 13).
- Deciding the commercial posture
  (skill 09 + skill 10).

## 4. Requirement hierarchy

The platform uses a 9-level hierarchy:

| Level | Audience | Example |
|---|---|---|
| **Vision** | User + executive | "An affordable, road-legal, repairable urban EV for emerging markets." |
| **Stakeholder need** | User | "A 2-seat, 200 km range EV under $15k." |
| **Product requirement** | Product | "REQ-RANGE-001: The vehicle shall achieve at least 320 km of validated combined-cycle range." |
| **System requirement** | System architect | "REQ-SYS-BATT-001: The battery pack shall deliver 75 kWh at 400V nominal." |
| **Subsystem requirement** | Subsystem lead | "REQ-SUB-CELL-001: Each cell shall be a 21700 cylindrical LiFePO4 cell." |
| **Component requirement** | Component lead | "REQ-CMP-CELL-001: Each cell shall weigh ≤ 70g and have a capacity of ≥ 5 Ah at 1C." |
| **Verification requirement** | QA | "REQ-VER-RANGE-001: Range shall be verified by a combined-cycle simulation (WLTP) and a physical test on a chassis dyno." |
| **Regulatory constraint** | Compliance | "REQ-REG-R155-001: The vehicle shall comply with UN R155 (cybersecurity management system)." |
| **Commercial constraint** | Commercial | "REQ-COM-COST-001: The vehicle shall have a bill-of-materials cost under $8,000 USD at 10k units/year." |

Every requirement has:

- **Unique stable ID.** The ID is
  immutable. A renumbering is a new
  requirement, not a rename.
- **Description.** The "what".
- **Rationale.** The "why".
- **Owner.** The human who owns the
  requirement.
- **Priority.** `MUST` / `SHOULD` /
  `MAY` (per RFC 2119).
- **Source.** The stakeholder need
  that derived the requirement.
- **Acceptance metric.** The value +
  the unit + the threshold.
- **Verification method.** The test
  that proves the requirement is met.
- **Dependencies.** The other
  requirements that this one depends
  on.
- **Conflicts.** The other requirements
  that this one conflicts with.
- **Revision.** The revision history.
- **Status.** `PROPOSED` / `ACCEPTED` /
  `REJECTED` / `SUPERSEDED`.

## 5. Example

```yaml
id: REQ-RANGE-001
type: PRODUCT
statement: |
  The vehicle shall achieve at least 320 km
  of validated combined-cycle range.
rationale: |
  Target-market urban and interurban use.
  The 320 km figure is the WLTP combined-
  cycle number; the EPA equivalent is
  ~280 mi.
priority: MUST
source: STAKEHOLDER-NEED-CITY-001
verification:
  method: SIMULATION_AND_PHYSICAL_TEST
  threshold: 320
  unit: km
  cycle: WLTP
  confidence: HIGH
  evidence: WLTP-Regulation-2017
status: PROPOSED
```

## 6. Conflict engine

The conflict engine detects conflicts
between requirements. Examples:

- **Range versus battery cost.** A
  higher range requires a bigger
  battery, which raises the cost.
- **Performance versus energy
  consumption.** A higher performance
  requires more energy per kilometer.
- **Mass versus crash structure.** A
  safer structure is heavier, which
  reduces range.
- **Repairability versus sealing.** A
  better sealing is harder to repair.
- **Low price versus premium
  material.** A premium material is
  more expensive.
- **Interior space versus external
  dimensions.** A larger interior
  requires a larger body.
- **Manufacturing volume versus
  tooling cost.** A higher volume
  amortizes the tooling cost.
- **Aerodynamics versus styling.** A
  more aerodynamic shape is less
  expressive.
- **Modularity versus structural
  efficiency.** A modular structure
  is less efficient.

**Never hide conflicts.** A conflict is
filed in the PRD + the risk register +
the AI council (skill 05).

The conflict engine returns:

- **Constraint involved.** The
  requirement ID + the conflicting
  requirement ID.
- **Why the conflict exists.** The
  physical / economic / regulatory
  reason.
- **Quantitative impact where
  evidence exists.** The trade-off
  curve (e.g. "+10% range requires
  +5% mass, which costs +$200/unit").
- **Candidate trade-offs.** The
  possible resolutions.
- **Required human decision.** A
  stakeholder must pick a resolution
  (per skill 05 + the orchestrator).

A conflict without a resolution is a
contract violation; the verifier
(skill 14) rejects the PRD.

## 7. Change control

Once a vehicle revision enters
**engineering freeze** (per skill 03 +
skill 04):

- **Every requirement change creates
  a change request.** A change request
  is a structured artifact under
  `docs/foundry/change-requests/`.
- **Impact analysis is mandatory.**
  The change request analyzes the
  impact on the architecture, the
  test plan, the regulatory posture,
  the commercial posture, the
  schedule, and the cost.
- **Affected artifacts are
  identified.** Every artifact that
  references the changed requirement
  is listed.
- **Cost, schedule, safety and
  compliance impact are recorded.**
  The change request records the
  per-dimension impact.
- **Approval is explicit.** A
  stakeholder + the orchestrator +
  the affected skills approve the
  change.
- **Previous requirements remain
  immutable.** A "change" is a new
  requirement, not an edit. The
  original is preserved in the
  history.

A change without a change request is
a contract violation; the orchestrator
rejects the change.

## 8. Workflow

1. **Receive the user request.** From
   the orchestrator (skill 00) or
   directly from the user.
2. **Conduct the discovery interview.**
   If the request is ambiguous, ask
   the clarifying questions. The
   interview is documented in
   `docs/foundry/discovery/<id>.md`.
3. **Decompose the vision into the 9
   levels.** Use the requirement
   hierarchy (per section 4).
4. **Run the conflict engine** (per
   section 6). File every conflict.
5. **Submit the PRD** to the
   orchestrator. The PRD is a
   structured artifact under
   `docs/foundry/prd/<id>.md`.
6. **Receive the orchestrator's
   review.** The orchestrator +
   skill 03 + skill 04 + skill 13 +
   skill 09 + skill 10 review the
   PRD.
7. **Iterate** until the PRD is
   accepted.
8. **File the change requests**
   during the engineering freeze
   (per section 7).

## 9. Definition of done

A vehicle project **cannot** enter
detailed engineering unless **every**
item below is true:

- **All `MUST` requirements have
  measurable acceptance criteria.**
  A `MUST` requirement without a
  threshold + a unit + a verification
  method is a violation.
- **Contradictory requirements are
  resolved or explicitly accepted.**
  A conflict in the conflict engine
  without a resolution is a violation.
- **Verification methods exist.** A
  requirement without a test is a
  violation.
- **Regulatory market assumptions
  are declared.** The platform's
  jurisdiction (EU / US / CN / BR)
  is named; the regulation set
  (UN R155 / UN R156 / ISO 26262 /
  GDPR / etc.) is named.
- **Cost and manufacturability
  targets are present.** A
  requirement without a cost or a
  manufacturability target is a
  violation for any commercial
  project.

## 10. Quality gates

- Every requirement has a unique
  stable ID.
- Every requirement has a description
  + a rationale + an owner.
- Every requirement has an
  acceptance metric (value + unit +
  threshold).
- Every requirement has a
  verification method.
- Every conflict has a resolution
  (or an explicit acceptance).
- Every change has a change request
  + an impact analysis + an
  approval.
- The PRD is versioned + signed.
- The discovery interview is
  documented.

## 11. Failure modes

- **The user request is ambiguous.**
  This skill conducts the discovery
  interview; the orchestrator does
  not implement on an ambiguous
  request.
- **A conflict cannot be resolved.**
  The conflict is escalated to the
  AI council (skill 05) + the
  orchestrator. The PRD is not
  advanced until the conflict is
  resolved.
- **A verification method does not
  exist.** The requirement is
  flagged. A `MUST` requirement
  without a verification method is
  a blocker on engineering.
- **A change is filed after the
  engineering freeze without a
  change request.** The change is
  rejected. The original requirement
  is preserved.

## 12. Coordination contract

- **Input from**: skill 00
  (orchestrator), the user.
- **Output to**: skill 00
  (orchestrator), every engineering
  skill that consumes the PRD
  (skills 03, 04, 06, 07, 09, 10,
  13).
- **Triggered by**: every vehicle
  project + every major spec change.
- **Frequency**: per project + per
  change request.

## 13. Forbidden patterns

- **Aspirations as requirements.**
  "Make it futuristic" is not a
  requirement. A requirement has a
  value + a unit + a threshold +
  a verification method.
- **Hidden conflicts.** A conflict
  in the conflict engine is filed,
  not hidden. A hidden conflict is a
  contract violation.
- **Implicit verification.** A
  requirement without a named
  verification method is a contract
  violation. "We'll test it" is not
  a verification method.
- **Mutable engineering-freeze
  requirements.** A requirement
  after the engineering freeze is
  changed via a change request, not
  via an edit. An edit is a
  violation.
- **Unsourced requirements.** A
  requirement without a stakeholder
  need source is a violation. A
  "the user wants it" is not a
  source.
- **Unowned requirements.** A
  requirement without a human owner
  is a violation. The owner is the
  person who is accountable for the
  requirement.
- **Unmeasurable `MUST`
  requirements.** A `MUST`
  requirement without a measurable
  acceptance criterion is a
  violation. A "MUST be fast" is
  not measurable; a "MUST respond
  in < 100ms P99" is measurable.

## 14. Working with this skill

When invoked, this skill:

1. Reads the user request.
2. Conducts the discovery interview
   (if needed).
3. Decomposes the vision into the 9
   levels.
4. Runs the conflict engine.
5. Files the PRD.
6. Submits the PRD to the
   orchestrator.
7. Iterates until the PRD is
   accepted.
8. Files the change requests during
   the engineering freeze.

This skill does **not** implement
features. The PRD is the input to the
engineering skills.

## 15. Cross-references

- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Repository archaeology (skill 01):**
  `.ai/skills/01-repository-archaeology/SKILL.md`.
- **Vehicle domain ontology (skill 03):**
  `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.
- **DSL compiler (skill 04):**
  `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`.
- **IP / provenance / royalties (skill 09):**
  `.ai/skills/09-ip-provenance-royalties/SKILL.md`.
- **Marketplace (skill 10):**
  `.ai/skills/10-marketplace-manufacturing/SKILL.md`.
- **Regulatory (skill 13):**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
