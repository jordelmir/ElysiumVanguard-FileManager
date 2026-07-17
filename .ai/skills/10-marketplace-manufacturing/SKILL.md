---
name: marketplace-manufacturing
description: Implements project licensing, supplier discovery, RFQs, manufacturing qualification and controlled collaboration.
---

# Skill 10 — Marketplace and Manufacturing Network

## 1. Mission

**Connect qualified projects with engineers,
suppliers, laboratories, manufacturers and
licensees without exposing confidential
engineering data unnecessarily.**

The marketplace is the **controlled
disclosure** surface. A `VISUAL_ONLY` or
`CONCEPTUAL` vehicle (per `.ai/STANDARDS.md`
section 4) is **NOT** eligible for listing.
A `PARAMETRIC_FUNCTIONAL` vehicle is
eligible for design-stage engagement (a
design partner, a tooling supplier). An
`OEM_PARTIAL` or `OEM_EXACT` vehicle is
eligible for full commercial engagement.

The marketplace is **not** a free-for-all
bazaar. The platform enforces the
manufacturing readiness gates (per
section 6) before a project is marketed
as manufacturing-ready. The platform is
the **gate** between "the engineering works"
and "the engineering ships".

## 2. Marketplace categories

The marketplace supports **11
categories**. A category that is not in
this list is a contract violation.

- **Vehicle projects.** A
  `VehicleDefinition` + the
  supporting `Spec.Artifact`s.
- **Component designs.** A
  `PartDefinition` + the
  supporting `GeometryAsset`s.
- **Technology licenses.** A
  patent, a trademark, a
  trade secret, a piece of
  software.
- **Engineering services.** A
  service (a "design review",
  a "thermal simulation", a
  "FEA study").
- **Simulation services.** A
  simulation service (a
  crash simulation, a CFD
  run, an EMC test).
- **Prototyping.** A
  prototyping service (a
  3D print, a CNC part, a
  hand-built prototype).
- **Testing laboratories.** A
  test service (a dyno test,
  a salt-spray test, an EMC
  chamber).
- **Tooling.** A tooling
  service (a mold, a die, a
  jig, a fixture).
- **Manufacturing.** A
  manufacturing service (a
  small-batch production,
  a large-batch production,
  a specialized process).
- **Software modules.** A
  software module (a
  diagnostic plugin, a
  simulation plugin, an
  analysis tool).
- **Diagnostic content.** A
  diagnostic content (a
  `Procedure`, a `DiagnosticTarget`,
  a `FaultCode` set, a
  `RepairAction`).

A new category is added via an ADR + a
vote in the AI council (skill 05).

## 3. Commercial workflow

The marketplace implements a **14-step
commercial workflow**. Every step is
recorded in the audit trail (per skill
09) + the outbox (per skill 08).

1. **Project eligibility review.**
   The platform checks the project's
   `VehicleRepresentationLevel`
   (per `.ai/STANDARDS.md` section 4)
   + the manufacturing readiness
   gates (per section 6). A project
   that fails the gates is rejected
   at this step.
2. **Public teaser.** A public
   teaser (the public
   `representationLevel`, the
   key features, the public
   preview images) is published.
   The teaser does NOT include
   confidential engineering
   data.
3. **Confidentiality agreement
   (CDA / NDA).** The buyer /
   supplier signs a CDA / NDA
   before any confidential data
   is shared. The CDA / NDA is
   a typed `RoyaltyContract` (per
   skill 09) with a `TERRITORY` +
   a `FIELD OF USE` + an
   `EXPIRATION`.
4. **Controlled data-room access.**
   The buyer / supplier gets
   access to the confidential
   data room (per section 4).
   The access is per-buyer +
   per-artifact + time-bounded.
5. **Buyer or supplier
   qualification.** The buyer /
   supplier is qualified (per
   section 5). An unqualified
   party is rejected at this
   step.
6. **Request for information
   (RFI).** The buyer / supplier
   may submit an RFI. The RFI is
   a typed artifact; the
   response is a typed artifact.
7. **Request for Quote (RFQ).**
   The buyer / supplier may
   submit an RFQ. The RFQ is a
   typed artifact; the offer is
   a typed artifact; both are
   content-addressed + signed.
8. **Offer.** The supplier
   responds to the RFQ with an
   offer. The offer is bound to
   the RFQ; the offer is
   content-addressed + signed;
   the offer's money is
   `BigDecimal`.
9. **Negotiation.** The
   parties negotiate the offer.
   Each negotiation round is a
   typed artifact. The accepted
   offer is signed.
10. **Contract.** The accepted
    offer becomes a
    `RoyaltyContract` (per skill
    09). The contract is signed
    + content-addressed. The
    contract is immutable.
11. **Milestones.** The
    contract has N milestones.
    Each milestone is a typed
    event; the milestone payment
    is in `BigDecimal`.
12. **Delivery acceptance.** The
    buyer accepts the delivery.
    The acceptance is a typed
    event + a signed artifact.
13. **Dispute process.** A
    dispute is filed + a
    resolution is attempted.
    The dispute is in the audit
    trail. A dispute that is
    not resolved in the SLA
    (default: 30 days) is
    escalated.
14. **Settlement.** The
    settlement is computed by
    the royalty engine (per
    skill 09). The settlement is
    in `BigDecimal`. The
    settlement is filed in the
    audit trail.

## 4. Controlled disclosure

The platform enforces **9 controlled
disclosure practices**. A disclosure
that bypasses a practice is a P0
incident.

- **Project-level access.** A
  party may access only the
  projects they are authorized
  for. A party without
  authorization receives a
  typed `UnauthorizedProjectAccess`
  error.
- **Artifact-level access.** A
  party may access only the
  artifacts they are authorized
  for. A confidential
  `GeometryAsset` is hidden
  from an unauthorized party
  even if the party is
  authorized for the project.
- **Expiring access.** A
  party's access expires on
  the contract's
  `EFFECTIVE PERIOD` end. An
  access after the expiration
  is a P0 incident.
- **Watermarking.** Every
  artifact the party downloads
  is watermarked with the
  party's ID + the download
  timestamp. The watermark
  is per-download.
- **Download restrictions.** A
  confidential artifact has a
  download cap (default: 5
  downloads per party). A
  download that exceeds the
  cap is rejected.
- **Export audit.** Every
  download + every access +
  every disclosure is logged
  in the audit trail. The log
  includes the party ID + the
  artifact ID + the timestamp.
- **Region restrictions.** A
  party in a restricted
  jurisdiction (per the
  export controls + the
  sanctions list) is rejected.
- **Revocation.** A party that
  is revoked (the CDA is
  revoked, the contract is
  terminated, the party is
  sanctioned) loses access
  **immediately**. The
  revocation is recorded in the
  audit trail.
- **Least privilege.** A party
  sees only the data the party
  needs to do the work. A
  supplier that is assigned to
  the powertrain does NOT see
  the chassis details. A
  supplier that is assigned
  to the chassis does NOT see
  the powertrain details.

**A supplier should see only what is
required to quote or manufacture its
assigned subsystem.** A supplier that
sees more is a contract violation; the
verifier (skill 14) rejects the
disclosure.

## 5. Supplier qualification

The platform stores **13 fields** per
supplier. A supplier without all 13
fields is a contract violation.

- **Legal entity.** The legal
  name + the registration
  number + the tax ID.
- **Jurisdictions.** The
  countries the supplier is
  registered in.
- **Certifications.** The
  certifications (ISO 9001,
  IATF 16949, AS9100, ISO
  14001).
- **Capabilities.** The
  capabilities (the
  manufacturing processes the
  supplier supports, the
  materials, the tolerances).
- **Processes.** The
  per-process documentation
  (the process flow, the
  control plan, the FMEA).
- **Materials.** The materials
  the supplier can source
  (steel, aluminum, composites,
  plastics).
- **Capacity.** The per-month
  production capacity.
- **Quality history.** The
  historical quality metrics
  (the defect rate, the
  on-time delivery rate, the
  customer satisfaction).
- **Cybersecurity posture.**
  The cybersecurity posture
  (per the security model +
  the regulatory requirements
  for the supply chain).
- **Insurance.** The
  insurance coverage
  (general liability,
  product liability, cyber
  liability).
- **Audit status.** The
  most recent audit (the
  audit date, the auditor, the
  findings, the remediation).
- **Sanctions screening
  status.** The OFAC / EU /
  UN sanctions screening
  status.
- **Expiration dates.** The
  per-field expiration date
  (a certification expires on
  2027-01-01, an insurance
  policy expires on 2027-06-01,
  etc.).

**Do not** mark a supplier as verified
solely from self-declared information.
A "the supplier self-declared ISO
9001" without an audit record is a
`HYPOTHESIS`, not a fact. A supplier
verification requires an external
audit + a regulatory record + a
documented evidence.

## 6. Manufacturing readiness gates

A project **cannot be marketed as
manufacturing-ready** unless it has
**11 items**. A project missing an
item is a contract violation; the
verifier (skill 14) rejects the
marketing.

- **Frozen revision.** The
  `VehicleRevision` is in
  `ENGINEERING_FREEZE` (per
  skill 03). A draft revision
  is NOT manufacturing-ready.
- **Valid BOM.** A `BOM` is
  generated by skill 04's
  compiler. The BOM lists every
  `PartInstance` + the
  quantity + the cost (in
  `BigDecimal`).
- **Material definitions.**
  Every `PartInstance` has a
  material declaration (the
  alloy + the grade + the
  heat-treatment).
- **Tolerances.** Every
  dimension has a tolerance
  (the ISO 2768 medium, the
  custom ±0.05 mm, etc.).
- **Process assumptions.**
  The manufacturing process
  assumptions (the machining
  + the joining + the surface
  treatment + the assembly).
- **Interface control
  documents.** The
  mechanical + electrical +
  thermal + data interfaces
  are documented.
- **Inspection plan.** The
  per-`PartInstance` inspection
  plan (the CMM, the visual,
  the functional test).
- **Known critical
  characteristics.** The
  KCCs (key product
  characteristics + key
  process characteristics)
  are identified.
- **Change-control policy.**
  A documented change-control
  policy (who may change what,
  when, how; per skill 04
  section 7).
- **Evidence status.** The
  evidence status per
  `EngineeringFact<T>` (per
  `.ai/STANDARDS.md` section 3).
- **Responsible engineering
  sign-off.** A human
  engineer has signed off
  the manufacturing
  readiness. The sign-off is
  `ENGINEER_REVIEWED` +
  `LAB_VERIFIED` (where
  applicable) +
  `REGULATORY_VERIFIED` (where
  applicable).

## 7. Workflow

1. **Receive a project.** The
   marketplace receives a
   project from the design
   stage.
2. **Run the 11 manufacturing
   readiness gates.** Per
   section 6. A gate that fails
   is reported to the project
   owner.
3. **Apply the 9 controlled
   disclosure practices.** Per
   section 4.
4. **Run the 14-step commercial
   workflow.** Per section 3.

## 8. Definition of done

The marketplace is accepted only
when **every** proof below passes.

- **Confidential artifacts
  cannot be accessed before
  authorization.** A test
  asserts a party without a
  CDA receives a typed
  `UnauthorizedProjectAccess`
  error.
- **Revoked users lose access
  immediately.** A test
  asserts a revoked party
  cannot access the data room
  on the next request.
- **RFQ revisions remain
  traceable.** A test asserts
  every RFQ revision is in
  the audit trail + is bound
  to the original RFQ.
- **Offers cannot silently
  change after acceptance.**
  A test asserts an accepted
  offer is immutable; a
  modification attempt is
  rejected.
- **Supplier claims have
  evidence and expiry.** A
  test asserts a supplier's
  certification claim has
  an audit record + an
  expiration date; an
  expired claim is flagged.
- **Commercial status never
  implies regulatory
  approval.** A test asserts
  the marketplace listing
  does NOT carry a
  `REGULATORY_VERIFIED` flag
  (the regulatory posture is
  per skill 13, NOT per the
  marketplace).

## 9. Quality gates

- Every project that is
  marketed as manufacturing-
  ready passes the 11
  manufacturing readiness
  gates.
- Every party in the
  marketplace has a CDA / NDA
  + a 13-field supplier
  qualification.
- Every disclosure is
  governed by the 9
  controlled disclosure
  practices.
- Every RFQ + every offer is
  content-addressed + signed.
- Every accepted offer is
  immutable.
- Every settlement is in
  `BigDecimal`.
- Every party revocation
  takes effect immediately.
- `VISUAL_ONLY` and
  `CONCEPTUAL` vehicles are
  NOT listed (per
  `.ai/STANDARDS.md` section
  4).

## 10. Failure modes

- **A project fails the 11
  manufacturing readiness
  gates.** The project is
  not marketed. The owner
  is informed.
- **A supplier is unqualified.**
  The supplier is rejected
  with a typed
  `UnauthorizedProjectAccess`
  error.
- **A party is revoked.** The
  revocation takes effect
  immediately; a party that
  tries to access the data
  room receives a typed
  error.
- **A deduction is not in the
  contract.** The settlement
  is rejected (per skill 09).
- **A `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle is
  listed.** The listing is
  rejected with a typed
  `ContractNotActive` error
  (per skill 09).

## 11. Coordination contract

- **Input from**: the project
  (the design stage), the
  supplier (the quote), the
  buyer (the order), the
  catalog (skill 09) for the
  royalty contract.
- **Output to**: the project
  owner (the listing), the
  catalog (skill 09) for the
  royalty engine, the
  security skill (skill 12)
  for the controlled
  disclosure.
- **Triggered by**: every
  project + every supplier +
  every buyer + every RFQ +
  every offer.
- **Frequency**: continuous.

## 12. Forbidden patterns

- **A `VISUAL_ONLY` /
  `CONCEPTUAL` vehicle
  listed.** Per
  `.ai/STANDARDS.md` section
  4. The listing is rejected.
- **A supplier verified solely
  from self-declared
  information.** A supplier
  without an external audit
  is a `HYPOTHESIS`, not a
  fact.
- **A deduction not in the
  contract.** A deduction
  silently applied is a
  contract violation.
- **A disclosure that bypasses
  the 9 controlled disclosure
  practices.** A leak is a P0
  incident.
- **A commercial status that
  implies regulatory
  approval.** The marketplace
  does NOT carry a
  `REGULATORY_VERIFIED` flag.
- **A mutation of an accepted
  offer.** An accepted offer
  is immutable.
- **A "we'll fix the supplier
  qualification later"
  pattern.** A supplier
  without all 13 fields is
  not eligible.
- **A "we'll just give the
  party everything" pattern.**
  A party sees only what the
  party needs.

## 13. Working with this skill

When invoked, this skill:

1. Receives the project + the
   supplier + the buyer.
2. Runs the 11 manufacturing
   readiness gates.
3. Qualifies the supplier + the
   buyer.
4. Runs the 14-step commercial
   workflow.
5. Applies the 9 controlled
   disclosure practices.
6. Settles the order (per skill
   09).
7. Files the audit trail.

The skill does **not** list a
project that is not
manufacturing-ready. The skill
does **not** disclose a
confidential artifact without
authorization. The skill does
**not** settle a `VISUAL_ONLY`
or `CONCEPTUAL` project.

## 14. Cross-references

- **Vehicle representation
  levels:** `.ai/STANDARDS.md`
  section 4.
- **Required error model:**
  `.ai/AGENTS.md` section 10 +
  `.ai/STANDARDS.md` section 7.
- **Money type (BigDecimal):**
  `.ai/STANDARDS.md` section 2.2.
- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **DSL compiler (skill 04):**
  `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`.
- **IP / provenance / royalties
  (skill 09):**
  `.ai/skills/09-ip-provenance-royalties/SKILL.md`.
- **Mobile UX (skill 11):**
  `.ai/skills/11-mobile-forge-ux/SKILL.md`.
- **Security (skill 12):**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **Regulatory (skill 13):**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
- **Backend event platform
  (skill 08):**
  `.ai/skills/08-backend-event-platform/SKILL.md`.
- **Digital twin (skill 07):**
  `.ai/skills/07-digital-twin-diagnostics/SKILL.md`.
