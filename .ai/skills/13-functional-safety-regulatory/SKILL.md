---
name: functional-safety-regulatory
description: UN R155/R156, ISO 26262, ISO 21434, GDPR/CCPA/LGPD, homologation. The "this can be sold in a regulated jurisdiction" skill. The bridge between "the platform works" and "the platform is legal".
---

# Skill 13 — Functional Safety + Regulatory

## 1. Mission

Make the platform **compliant** with the
regulations of every jurisdiction it operates in.
The bridge between "the platform works" and
"the platform is legal".

The skill owns the regulatory contract. Every
feature that touches a regulated surface has a
regulatory impact assessment (RIA) before
merge. Every release has a compliance report.
Every audit is filed in the catalog.

## 2. In-scope

- UN R155 (cybersecurity management system).
- UN R156 (software update management system).
- ISO 26262 (functional safety, automotive).
- ISO 21434 (cybersecurity engineering,
  automotive).
- ISO/SAE 21434 (SOTIF — Safety Of The Intended
  Functionality).
- GDPR (EU data protection).
- CCPA (California).
- LGPD (Brazil).
- PIPL (China, when relevant).
- Type approval (homologation) per jurisdiction.
- The regulatory impact assessment (RIA) per
  feature.
- The compliance report per release.
- The regulatory change log (which regulation
  was updated, when, what the platform's
  response was).
- **The verification-level + safety-gate
  contract.** A `SafetyGoal` requires
  `REGULATORY_VERIFIED` + `ENGINEER_REVIEWED`
  + a human counter-signature (per
  `.ai/AGENTS.md` section 8 and `.ai/STANDARDS.md`
  section 5). A `RoadLegal` flag requires
  `ENGINEER_REVIEWED` + `REGULATORY_VERIFIED`
  + a human counter-signature. An AI-inferred
  "this is safe" / "this is compliant" / "this
  fits" is a draft, not an approval.
- **The `VehicleRepresentationLevel`
  regulatory gates.** A `VISUAL_ONLY` or
  `CONCEPTUAL` vehicle is NOT eligible for
  regulatory submission. A `PARAMETRIC_FUNCTIONAL`
  vehicle is eligible only after the
  verification-level requirements are met
  (per `.ai/STANDARDS.md` section 4).

## 3. Out-of-scope

- The product code (the regulated surface is
  the input; the code is the implementation).
- The 3D pipeline (skill 06).
- The diagnostic (skill 07).
- The marketplace (skill 10).
- The royalty (skill 09).
- The mobile UX (skill 11).

The regulatory skill is a **veto** on every
release. A release without a compliance
report is a blocked push.

## 4. Inputs

- Every PR (the regulatory impact assessment).
- Every release (the compliance report).
- The regulatory change log (the world).
- The audit (the regulator's findings).
- The user's jurisdiction (the buyer's, the
  supplier's, the platform operator's).

## 5. Outputs

- The regulatory impact assessment (RIA) per
  feature.
- The compliance report per release.
- The regulatory change log.
- The audit response (the platform's response
  to a regulator's finding).
- The homologation package (the per-
  jurisdiction type approval submission).
- The Software Bill of Materials (SBOM) per
  release (UN R156).
- The vulnerability management process (UN R155).
- The update management process (UN R156).

The regulatory skill is the platform's "we
know what regulations we comply with, and we
can prove it" answer.

## 6. Workflow

1. **Receive a PR.** The RIA is triggered
   automatically.
2. **Identify the regulations.** The reviewer
   checks: does this PR touch a regulated
   surface? Which regulations apply? Does
   the PR touch a `SafetyGoal`, a `RoadLegal`
   flag, a `Compatibility` fact, or a
   `Settlement`? Each of these is a
   regulated surface.
3. **Assess the impact.** The reviewer
   documents:
   - The regulation(s) the PR affects.
   - The current compliance state.
   - The change the PR introduces.
   - The gap (if any) between the current
     state and the required state.
   - The remediation (if needed).
   - **The verification-level impact.** If
     the PR introduces or changes a
     `SafetyGoal`, a `RoadLegal` flag, a
     `Compatibility` fact, or a `Settlement`,
     the reviewer checks that the
     verification level is consistent with
     the regulatory requirement. A
     `SafetyGoal` MUST be
     `REGULATORY_VERIFIED` +
     `ENGINEER_REVIEWED` + a human
     counter-signature (per `.ai/AGENTS.md`
     section 8 and `.ai/STANDARDS.md`
     section 5).
   - **The `VehicleRepresentationLevel`
     impact.** If the PR introduces a
     vehicle, a `SafetyGateNotSatisfied`
     error is raised for a `VISUAL_ONLY` or
     `CONCEPTUAL` vehicle that is being
     submitted for regulatory approval.
4. **Sign off.** The reviewer approves or
   requests changes. A "request changes"
   blocks the PR. A "request changes" is
   the only way to reject a PR that tries
   to bypass the verification-level or
   safety-gate contract.
5. **Receive a release.** The compliance
   report is required. The SBOM is required.
   The verification-level + safety-gate
   report is required (a list of every
   `SafetyGoal` + its verification level
   + its counter-signature).
6. **Receive a regulatory change.** The
   reviewer updates the regulatory change
   log. The change is filed in the next
   release's compliance report.
7. **Receive an audit.** The reviewer
   prepares the audit response. The audit
   is filed in the catalog. The audit
   response includes the verification
   levels + the safety gates + the human
   counter-signatures.
8. **Annual review.** The reviewer updates
   the regulatory change log. The platform
   is re-assessed per jurisdiction. The
   verification-level + safety-gate
   contract is re-confirmed.

## 7. Quality gates

- Every PR has an RIA.
- Every release has a compliance report.
- Every release has an SBOM.
- The vulnerability management process is
  documented.
- The update management process is documented.
- The audit response is in the catalog.
- The homologation package is in the catalog.
- The regulatory change log is up to date.
- **Every `SafetyGoal` carries a verification
  level + a human counter-signature.** A
  `SafetyGoal` with `verificationStatus =
  AI_INFERRED` is rejected with a
  `SafetyGateNotSatisfied` error.
- **Every `RoadLegal` flag carries
  `ENGINEER_REVIEWED` + `REGULATORY_VERIFIED`
  + a human counter-signature.** A
  `RoadLegal` flag with `AI_INFERRED` is
  rejected with a `SafetyGateNotSatisfied`
  error.
- **Every `Compatibility` fact carries
  `LAB_VERIFIED` or `OEM_VERIFIED` + a human
  counter-signature.** An `AI_INFERRED`
  compatibility fact is rejected with a
  `SafetyGateNotSatisfied` error.
- **Every `Settlement` carries
  `ENGINEER_REVIEWED` + an audit trail + a
  human counter-signature.** An `AI_INFERRED`
  settlement is rejected with a
  `SafetyGateNotSatisfied` error.
- **No `VISUAL_ONLY` or `CONCEPTUAL` vehicle
  is in a regulatory submission.** A
  submission that includes an ineligible
  level is rejected with a
  `SafetyGateNotSatisfied` error.

## 8. Failure modes

- **A PR is released without an RIA.** The
  release is rolled back. The PR is re-
  reviewed.
- **A release is shipped without a compliance
  report.** The release is rolled back. The
  report is produced.
- **A regulatory change is missed.** The
  auditor is informed. The platform is
  updated.
- **An audit fails.** The audit response is
  prepared. The platform is updated.
- **A homologation package is rejected.** The
  package is re-prepared. The platform is
  updated.

## 9. Coordination contract

- **Input from**: every PR, every release, every
  regulatory change, every audit.
- **Output to**: every PR, every release, the
  catalog (skill 09), the orchestrator (skill
  00).
- **Triggered by**: every PR, every release, every
  regulatory change, every audit.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **"We'll do the RIA later".** A PR that ships
  without an RIA is a contract violation.
- **"We'll add the SBOM later".** A release
  that ships without an SBOM is a contract
  violation. UN R156 is mandatory.
- **"The regulation is for the other team".** A
  feature that touches a regulated surface
  has this skill as a co-owner.
- **Homologation by side-channel.** A vehicle
  that is type-approved outside the platform
  is a contract violation. The homologation
  is in the catalog.
- **"We'll localize later".** A release that
  ships without i18n is a contract violation
  in any non-English jurisdiction.
- **AI-claimed road legality.** A `RoadLegal`
  flag whose only verification is
  `AI_INFERRED` is a contract violation (per
  `.ai/AGENTS.md` section 5.6 and section 8,
  and `.ai/STANDARDS.md` section 2.6 and
  section 5). The flag requires
  `ENGINEER_REVIEWED` + `REGULATORY_VERIFIED`
  + a human counter-signature.
- **AI-claimed safety approval.** A `SafetyGoal`
  whose only verification is `AI_INFERRED`
  is a contract violation. The goal requires
  `REGULATORY_VERIFIED` + `ENGINEER_REVIEWED`
  + a human counter-signature.
- **AI-claimed mechanical compatibility.** A
  `Compatibility` fact whose only verification
  is `AI_INFERRED` is a contract violation.
  The fact requires `LAB_VERIFIED` or
  `OEM_VERIFIED` + a human counter-signature.
- **AI-claimed financial settlement.** A
  `Settlement` whose only review is
  `AI_INFERRED` is a contract violation. The
  settlement requires `ENGINEER_REVIEWED` +
  an audit trail + a human counter-signature.
- **Regulatory submission of an ineligible
  vehicle.** A submission that includes a
  `VISUAL_ONLY` or `CONCEPTUAL` vehicle is
  a contract violation. The submission is
  rejected with a `SafetyGateNotSatisfied`
  error.
- **Silent `AI_INFERRED → VERIFIED`
  transitions.** A fact that flips from
  `AI_INFERRED` to `OEM_VERIFIED` /
  `REGULATORY_VERIFIED` / `LAB_VERIFIED` /
  `ENGINEER_REVIEWED` / `COMMUNITY_CORROBORATED`
  without a signed transition event in the
  audit trail is a contract violation.

## 11. The regulatory contract in the Elysium
Automotive Foundry

The platform is a regulated product in several
jurisdictions. The regulatory contract is:

- **UN R155** — the platform's cybersecurity
  management system. The vulnerability
  management process is in the catalog.
- **UN R156** — the platform's software update
  management system. The update process is
  in the catalog. The SBOM is per release.
- **ISO 26262** — the platform's functional
  safety. The hazard analysis is per feature.
  The ASIL classification is per feature.
- **ISO 21434** — the platform's cybersecurity
  engineering. The TARA (Threat Analysis and
  Risk Assessment) is per feature.
- **ISO/SAE 21434** (SOTIF) — the platform's
  SOTIF. The validation is per feature.
- **GDPR** — the EU data protection. The
  privacy impact assessment is per feature.
  The user's right to erasure is per request.
- **CCPA** — the California equivalent.
- **LGPD** — the Brazilian equivalent.

The regulatory contract is the platform's "we
can sell this in every jurisdiction we operate
in" answer.

## 12. Working with this skill

When invoked, this skill:

1. Receives the input (PR, release, regulatory
   change, audit).
2. Performs the assessment / response.
3. Files the output (RIA, compliance report,
   audit response).
4. Returns the result to the orchestrator.

The skill does not implement product code. The
skill does not implement the audit. The skill
is the **veto** that gates every regulated
release.
