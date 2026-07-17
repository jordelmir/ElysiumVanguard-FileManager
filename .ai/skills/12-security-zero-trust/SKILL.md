---
name: security-zero-trust
description: Identity, secrets, threat modeling, secure code review, vulnerability response. The "assume breach" discipline. Every other skill is a consumer of this one's decisions; this one is a veto on every release.
---

# Skill 12 — Security (Zero Trust)

## 1. Mission

Maintain the **security posture** of the platform.
Identity, secrets, threat modeling, secure code
review, vulnerability response. The "assume
breach" discipline.

The security skill is the platform's "we trust
no one, we verify everything" answer. Every
other skill is a consumer of this one's
decisions; this one is a **veto** on every
release. A release without a security sign-off
is a blocked push.

## 2. In-scope

- The identity layer (OIDC, OAuth 2.1, WebAuthn,
  mTLS).
- The secrets layer (a vault, a KMS, a sealed-
  secret store, a per-environment rotation).
- The threat model (the attack surface, the
  trust boundaries, the adversary archetypes,
  the mitigations).
- The secure code review (every PR is reviewed
  for security before merge).
- The vulnerability response (CVE monitoring,
  patch SLA, incident response).
- The auth audit (every action is logged; every
  log is in the audit trail).
- The rate limiting + DDoS protection.
- The encryption (at rest + in transit).
- The compliance (UN R155, UN R156, ISO 21434,
  GDPR, CCPA, LGPD).
- The red team (the quarterly exercise on the
  auth + marketplace surfaces).

## 3. Out-of-scope

- The backend (skill 08).
- The mobile UX (skill 11).
- The 3D pipeline (skill 06).
- The diagnostic (skill 07).
- The royalty (skill 09).
- The marketplace (skill 10).

The security skill is a **veto** on every other
skill's output. The security skill does not
implement product code; it reviews, signs off,
or blocks.

## 4. Inputs

- Every PR (the security review).
- Every new dependency (the license + CVE
  audit).
- Every new external integration (the threat
  model).
- Every release (the sign-off).
- Every incident (the response).
- The auth audit log (every action).
- The CVE feed (the world).

## 5. Outputs

- The threat model (a living document under
  `docs/security/threat-model.md`).
- The auth audit log (every action, signed,
  in the catalog).
- The vulnerability report (the CVE list, the
  patch status, the patch SLA).
- The incident response plan (the runbook, the
  on-call rotation, the postmortem template).
- The security sign-off (per release; required
  for merge).
- The red-team report (per quarter; required
  for compliance).
- The compliance report (per jurisdiction; per
  release).
- The auth guide (the developer's guide to
  auth + secrets; required reading for new
  contributors).

The security skill is the platform's "we know
who did what, and we can prove it" answer (the
auth audit log is the input to skill 09's audit
trail).

## 6. Workflow

1. **Receive a PR.** The security review is
   triggered automatically.
2. **Threat model.** The reviewer updates the
   threat model if the PR changes the attack
   surface.
3. **Secure code review.** The reviewer checks:
   - No new secrets in the code.
   - No new dependencies without a license +
     CVE audit.
   - No new external integrations without a
     threat model.
   - No new auth flows without a council review.
4. **Sign off.** The reviewer approves or
   requests changes. A "request changes"
   blocks the PR.
5. **Receive a release.** The security sign-off
   is required.
6. **Receive an incident.** The incident
   response plan is invoked.
7. **CVE feed.** The CVE feed is monitored
   daily. A HIGH CVE with a patch available
   triggers a P1 incident.
8. **Quarterly red team.** The red team
   exercises the auth + marketplace surfaces.
   The report is filed.
9. **Annual compliance review.** The
   compliance report is filed per
   jurisdiction.

## 7. Quality gates

- Every PR has a security review.
- Every release has a security sign-off.
- Every CVE has a patch SLA (HIGH: 14 days,
  MEDIUM: 30 days, LOW: 90 days).
- Every incident has a postmortem.
- Every quarter has a red team report.
- Every year has a compliance review.
- The auth audit log is append-only.
- The secrets are never in the repo.
- The rate limiting is in place.
- The encryption is in place (at rest + in
  transit).

## 8. Failure modes

- **A CVE is discovered.** The patch is
  applied within the SLA. A missed SLA is a
  P1 incident.
- **A secret is leaked.** The secret is
  rotated immediately. The audit log is
  reviewed for misuse. A leaked secret is a
  P0 incident.
- **An auth bypass is discovered.** The
  affected users are notified within 72 hours
  (GDPR). The fix is applied. A bypass is a
  P0 incident.
- **A rate limit is exceeded.** The
  monitoring alerts. The rate limit is
  tightened. A rate limit breach is a P1
  incident.
- **A red team finds a critical.** The fix is
  applied within 14 days. A critical finding
  is a P0 incident.

## 9. Coordination contract

- **Input from**: every PR, every release, every
  CVE, every incident.
- **Output to**: every PR, every release, the
  catalog (skill 09), the compliance reports
  (skill 13).
- **Triggered by**: every PR, every release, every
  CVE, every incident.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **"We'll add auth later".** A feature that
  ships without auth is a contract violation.
- **Hard-coded secrets.** A secret in the code
  is a contract violation. The secrets are
  in the vault.
- **Anonymous access.** An action that does
  not require auth is a contract violation.
  Every action requires auth.
- **Custom auth.** A home-grown auth flow is a
  contract violation. Use OIDC.
- **"We'll add MFA later".** A user without MFA
  is a contract violation. MFA is mandatory
  for any user with elevated privileges.
- **Open CORS.** A CORS that allows any origin
  is a contract violation. The CORS is
  allow-listed.
- **Unencrypted at rest.** A database that is
  not encrypted is a contract violation.
- **In-memory secrets.** A secret in process
  memory is a contract violation. The secrets
  are in the vault.
- **"We'll do a security review later".** A PR
  that ships without a security review is a
  contract violation.

## 11. The security model in the Elysium
Automotive Foundry

The security model is the platform's "we trust
no one, we verify everything" answer. The
platform is built on a Zero Trust architecture:

- Every request is authenticated.
- Every action is authorized.
- Every action is audited.
- Every secret is in the vault.
- Every dependency is vetted.
- Every release is signed.
- Every incident has a postmortem.
- Every CVE has a patch SLA.
- Every quarter has a red team.
- Every year has a compliance review.

The security skill is the platform's "we know
who did what, and we can prove it" answer.

## 12. Working with this skill

When invoked, this skill:

1. Receives the input (PR, release, CVE,
   incident).
2. Performs the review / response.
3. Files the output (sign-off, report, ADR).
4. Returns the result to the orchestrator.

The skill does not implement product code. The
skill does not implement the backend. The
skill is the **veto** that gates every release.
