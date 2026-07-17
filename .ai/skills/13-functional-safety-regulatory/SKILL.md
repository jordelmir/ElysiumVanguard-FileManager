---
name: functional-safety-regulatory
description: Models safety lifecycle, hazards, regulatory evidence and explicit human approval boundaries.
---

# Skill 13 — Functional Safety and Regulatory Engineering

## 1. Mission

**Prevent the platform from presenting
conceptual or AI-generated output as
certified automotive engineering.**

The platform **organizes evidence and
workflows**. The platform does **not**
certify. The platform does **not** sign
a safety case. The platform does **not**
accept residual risk. The platform does
**not** declare a design safe.

**Authorized engineers, laboratories,
manufacturers, and regulators** perform
the required approvals. The platform
records the approvals + the evidence +
the audit trail. The platform does not
replace the human in the safety loop.

## 2. Safety lifecycle model

The platform supports a **12-step
safety lifecycle model**. Every step
produces a typed artifact; every typed
artifact is in the audit trail.

- **Item definition.** The
  scope of the safety analysis
  (the function, the system,
  the boundary).
- **Hazard identification.**
  The HAZOP / the FMEA / the
  brainstorming that
  identifies the hazards.
- **Risk assessment.** The
  severity + the probability
  + the controllability per
  hazard.
- **Safety goals.** The
  top-level safety
  requirements (per ISO
  26262-3). A `SafetyGoal` is
  `REGULATORY_VERIFIED` +
  `ENGINEER_REVIEWED` + a
  human counter-signature
  (per `.ai/AGENTS.md`
  section 8).
- **Functional safety
  requirements.** The
  derived functional
  requirements that satisfy
  the safety goals.
- **Technical safety
  requirements.** The
  derived technical
  requirements that satisfy
  the functional safety
  requirements.
- **Architecture.** The
  hardware + software
  architecture that
  implements the technical
  safety requirements.
- **Verification.** The
  tests that prove the
  architecture satisfies the
  technical safety
  requirements.
- **Validation.** The
  tests that prove the system
  satisfies the safety goals
  in the field.
- **Change impact.** The
  analysis of the impact of a
  change on the safety
  (per ISO 26262-6).
- **Safety case.** The
  structured argument that
  the system is safe. The
  safety case is signed by a
  qualified engineer.
- **Release approval.** The
  external sign-off that
  permits the system to be
  released. A release without
  the external sign-off is
  NOT regulatory-ready.

## 3. Analysis artifacts

The platform supports **11 structured
analysis artifacts**. A safety analysis
without the artifacts is incomplete; the
regulator rejects the safety case.

- **HARA** (Hazard Analysis and Risk
  Assessment). The
  hazard-and-risk assessment
  per ISO 26262-3.
- **FMEA** (Failure Mode and
  Effects Analysis). The
  failure-mode analysis per
  the part.
- **FTA** (Fault Tree Analysis).
  The fault tree per the
  top-level hazard.
- **STPA** (Systems-Theoretic
  Process Analysis). The
  STPA is used where
  appropriate (per ISO
  21448 SOTIF).
- **Cybersecurity threat
  analysis.** The TARA per
  ISO 21434.
- **Safety requirements.** The
  derived safety requirements
  (per section 2).
- **Assumptions.** The
  assumptions the safety
  case makes (the use case,
  the environment, the
  driver, the operating
  envelope). An assumption
  that is not validated is a
  contract violation.
- **Evidence.** The
  per-requirement evidence
  (the test result, the
  review, the field data).
- **Residual risk.** The
  residual risk after the
  safety mechanisms are
  applied. A residual risk
  that is not accepted is a
  contract violation.
- **Reviewer sign-off.** The
  sign-off per the role
  (the HARA reviewer, the
  FMEA reviewer, the safety
  case reviewer).
- **Conformity of production.**
  The per-batch / per-vehicle
  verification that the
  production units match
  the type-approved units.

## 4. Safety finding shape

A `SafetyFinding` is a typed value. The
shape is:

```json
{
  "findingId": "SAFE-001",
  "severity": "CRITICAL",
  "affectedRevision": "REV-42",
  "hazard": "Loss of braking assistance",
  "operationalSituation": "High-speed deceleration",
  "currentControls": [],
  "status": "OPEN",
  "releaseBlocking": true
}
```

- **`severity`.** `CRITICAL` /
  `MAJOR` / `MINOR` / `OBSERVATION`.
- **`status`.** `OPEN` /
  `INVESTIGATING` / `MITIGATED` /
  `CLOSED` / `ACCEPTED`.
- **`releaseBlocking`.** A
  boolean. A `releaseBlocking`
  finding blocks the release
  until it is `CLOSED` or
  `ACCEPTED`.

A `releaseBlocking` finding with
`status = OPEN` is a P0 incident.
The release is blocked.

## 5. Regulatory model

Regulatory requirements depend on
**8 factors**. The platform
does **not** create one universal
"approved" flag; the platform uses
**jurisdiction-specific compliance
profiles**.

- **Vehicle category.** M1
  (passenger), M2 / M3 (bus),
  N1 / N2 / N3 (commercial),
  L1 / L2 / L3 / L4 / L5 / L6 / L7
  (motorcycle), etc.
- **Jurisdiction.** The
  market (the EU, the US, the
  CN, the BR, the JP, the KR,
  the IN, etc.).
- **Market.** The
  customer-facing market
  (consumer, commercial,
  fleet, racing).
- **Production volume.** The
  annual production volume
  (small, medium, large).
- **Propulsion type.** ICE,
  BEV, HEV, PHEV, FCEV.
- **Model year.** The
  production year.
- **Software functionality.**
  The presence + the level of
  ADAS features, the
  infotainment features, the
  over-the-air update
  capability.
- **Connectivity.** The
  presence + the type of
  network connectivity
  (V2X, C-V2X, telematics).

A `VehicleDefinition` declares the
8 factors. The platform evaluates
the per-jurisdiction requirements
against the factors. A
`COMPLIANCE_PROFILE` is a typed
artifact that captures the
per-jurisdiction rules.

**Do not** create one universal
"approved" flag. A `REGULATORY_VERIFIED`
flag is per-jurisdiction; a
`REGULATORY_VERIFIED` in the EU is
NOT a `REGULATORY_VERIFIED` in the US.

## 6. Release gates

A road-vehicle release **cannot be
labeled regulatory-ready** unless
**every** item below is true.

- **Target markets are
  explicit.** The
  `VehicleDefinition`
  declares the target markets.
- **Applicable requirements
  are identified.** The
  `COMPLIANCE_PROFILE` per
  market identifies the
  applicable requirements.
- **Evidence exists.** The
  per-requirement evidence
  is in the audit trail.
- **Open blocking findings
  are zero.** The number of
  `releaseBlocking` findings
  with `status = OPEN` is
  zero.
- **Responsible organizations
  are identified.** The
  organization that is
  responsible for the
  safety case + the
  organization that is
  responsible for the
  regulatory submission are
  named.
- **Approval status is
  externally verifiable
  where required.** Some
  jurisdictions require an
  external sign-off (a
  homologation authority);
  the sign-off is verifiable
  (a signed document + a
  public registry).
- **Software and hardware
  revisions match the
  evidence.** The
  `SoftwareRevision` +
  the `HardwareRevision`
  that were tested are the
  ones that are released.

## 7. AI restrictions

The AI in the regulatory skill may
**5 things**. The AI may NOT do
**5 things**.

The AI **may**:

- **Classify candidate
  requirements.** The AI
  reads a regulation +
  proposes which requirements
  apply to the
  `VehicleDefinition`.
- **Detect missing evidence.**
  The AI checks the audit
  trail + identifies the
  requirements whose
  evidence is missing.
- **Draft hazard-analysis
  entries.** The AI drafts
  a HARA entry; the human
  reviews + signs.
- **Summarize standards
  supplied to it.** The AI
  summarizes a standard for
  the human to read; the
  human reads the original
  standard for the
  authoritative text.
- **Identify requirement
  conflicts.** The AI runs
  the conflict engine (per
  skill 02 section 6).

The AI **may NOT**:

- **Sign a safety case.** A
  safety case is signed by a
  qualified engineer; the
  AI does not sign.
- **Certify compliance.** A
  compliance certification is
  the homologation
  authority's decision; the
  AI does not certify.
- **Accept residual risk.** A
  residual risk is accepted
  by the responsible
  engineer; the AI does
  not accept.
- **Declare a design safe.**
  A design is declared safe
  by the human engineer +
  the homologation
  authority; the AI does
  not declare.
- **Replace licensed or
  authorized professionals.**
  The AI is a tool; a
  licensed engineer is the
  authority. A regulatory
  submission that replaces
  a licensed professional is
  a contract violation.

## 8. Workflow

1. **Receive the project.** The
   regulatory skill receives a
   project + its
   `VehicleDefinition`.
2. **Identify the applicable
   requirements.** Per
   section 5.
3. **Collect the evidence.** The
   per-requirement evidence
   is in the audit trail.
4. **Detect the open findings.**
   The number of
   `releaseBlocking` findings
   is computed.
5. **Apply the release gates.**
   Per section 6.
6. **File the safety case.** The
   safety case is in the
   audit trail; the safety
   case is signed by a
   qualified engineer.

## 9. Definition of done

The regulatory skill is accepted
only when **every** test below
proves.

- **Conceptual projects
  cannot be marked road
  legal.** A test asserts a
  `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle cannot
  be labeled regulatory-ready.
- **A blocking safety
  finding prevents release.**
  A test asserts a
  `releaseBlocking` finding
  with `status = OPEN` blocks
  the release pipeline.
- **Evidence cannot be reused
  across incompatible
  revisions.** A test asserts
  an evidence from `v1` is
  not valid for `v2` when
  the `v2` changed.
- **Market-specific
  compliance remains
  separate.** A test asserts
  a `COMPLIANCE_PROFILE` for
  the EU is not transferable
  to the US.
- **Approval actions require
  privileged human roles.** A
  test asserts a
  `REGULATORY_VERIFIED`
  flag can only be set by a
  user with the
  `homologation-authority`
  role; the AI cannot set
  the flag.

A failing test is a P0 incident;
the verifier (skill 14) blocks the
release.

## 10. Quality gates

- The safety lifecycle is
  per-`VehicleRevision`.
- The `SafetyFinding` is
  typed + signed.
- The `COMPLIANCE_PROFILE`
  is per-jurisdiction.
- The release gates are
  enforced at the CI.
- The AI restrictions are
  enforced at the
  application layer.
- The safety case is signed
  by a qualified engineer.

## 11. Failure modes

- **A `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle is
  marked regulatory-ready.**
  The label is rejected; a
  P0 incident is filed.
- **A `releaseBlocking` finding
  is `OPEN` and the release
  is shipped.** The release
  is rolled back.
- **The AI signs a safety
  case.** The signature is
  rejected; the AI-authority
  gate trips; a P0 incident
  is filed.
- **Evidence is reused across
  incompatible revisions.**
  The reuse is rejected.
- **A `COMPLIANCE_PROFILE` is
  transferred across
  jurisdictions.** The
  transfer is rejected.

## 12. Coordination contract

- **Input from**: the
  ontology (skill 03), the
  DSL compiler (skill 04),
  the digital twin (skill 07),
  the security skill (skill
  12).
- **Output to**: the
  marketplace (skill 10)
  (the regulatory status
  per listing), the
  orchestrator (skill 00)
  (the release gates), the
  quality skill (skill 14)
  (the test report).
- **Triggered by**: every
  project + every change
  + every release.
- **Frequency**: continuous.

## 13. Forbidden patterns

- **A universal "approved"
  flag.** A `REGULATORY_VERIFIED`
  flag is per-jurisdiction.
- **A `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle
  marked road-legal.** A
  `CONCEPTUAL` vehicle is
  not road-legal.
- **An AI signature on a
  safety case.** The safety
  case is signed by a
  qualified engineer; the AI
  does not sign.
- **An AI that certifies
  compliance.** The AI
  may not certify.
- **An AI that accepts
  residual risk.** The AI
  may not accept.
- **A "we'll do the regulatory
  later" release.** A
  release without a
  per-jurisdiction
  `COMPLIANCE_PROFILE` is
  blocked.
- **A "we'll fix the open
  safety findings after
  release" release.** A
  release without zero
  `OPEN` blocking findings
  is blocked.

## 14. Working with this skill

When invoked, this skill:

1. Receives the project + the
   `VehicleDefinition`.
2. Identifies the applicable
   requirements.
3. Collects the evidence.
4. Detects the open findings.
5. Applies the release gates.
6. Files the safety case.

The skill does **not** sign a
safety case. The skill does
**not** certify compliance. The
skill does **not** accept
residual risk. The skill
**organizes evidence and
workflows**.

## 15. Cross-references

- **Truth and confidence
  model:** `.ai/AGENTS.md`
  section 6 + `.ai/STANDARDS.md`
  section 3.
- **Vehicle representation
  levels:** `.ai/STANDARDS.md`
  section 4.
- **AI authority boundary:**
  `.ai/AGENTS.md` section 8 +
  `.ai/STANDARDS.md` section 5.
- **Required error model:**
  `.ai/AGENTS.md` section 10 +
  `.ai/STANDARDS.md` section 7.
- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Ontology (skill 03):**
  `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.
- **DSL compiler (skill 04):**
  `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`.
- **Digital twin (skill 07):**
  `.ai/skills/07-digital-twin-diagnostics/SKILL.md`.
- **Marketplace (skill 10):**
  `.ai/skills/10-marketplace-manufacturing/SKILL.md`.
- **Security (skill 12):**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
