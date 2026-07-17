---
title: Security Model
status: stub — to be filled in during Phase 0 + G10
owner: skill 12 (security-zero-trust)
last_updated: 2026-07-17
audience: orchestrator, skill 12, all 16 skills, auditors
---

# Security Model — Elysium Automotive Foundry

> **Status:** This is a stub for the
> security model. The full content is
> produced during Phase 0 (Discovery) +
> G10 (Production hardening). Reference:
> `.ai/AGENTS.md` section 23 (Required
> Documentation) + `.ai/skills/12-security-zero-trust/SKILL.md`.

## What this document must include

- **The trust boundaries** (per
  `docs/architecture/system-context.md`).
- **The auth model** (the identity
  provider + the auth flow + the token
  shape).
- **The authz model** (RBAC + ABAC).
- **The secret management** (the vault
  + the KMS + the rotation).
- **The encryption** (at rest + in
  transit + in memory).
- **The threat model summary** (per
  `docs/threat-model/`).
- **The CVE posture** (the feed + the
  SLA + the response).
- **The compliance posture** (per
  skill 13).

## Trust boundaries

The platform's trust boundaries are
defined in
`docs/architecture/system-context.md`.
The platform is inside the trust
boundary; everything outside is
untrusted.

## Auth model

The platform uses **OIDC** + **OAuth
2.1** for authentication. The token
shape is a JWT signed by the identity
provider. The platform validates the
token at every request.

The platform supports:

- **WebAuthn** (the primary factor
  for elevated privileges).
- **mTLS** (for service-to-service
  auth).
- **OIDC refresh tokens** (for
  long-lived sessions).

A custom auth flow is a contract
violation (per
`.ai/skills/12-security-zero-trust/SKILL.md`
section 10).

## Authz model

The platform uses **RBAC** + **ABAC**.
The RBAC roles are:

- `designer`
- `engineer`
- `mechanic`
- `buyer`
- `supplier`
- `reviewer` (regulator)
- `admin`

The ABAC attributes are:

- `tenantId` (the org / project the
  user belongs to).
- `vehicleRepresentationLevel` (the
  level of the vehicle the user can
  act on).
- `verificationStatus` (the
  verification level of the fact the
  user can act on).

A `VISUAL_ONLY` or `CONCEPTUAL`
vehicle cannot be acted on by a
`buyer` (per
`.ai/skills/12-security-zero-trust/SKILL.md`
section 10).

## Secret management

The platform's secrets live in:

- **HashiCorp Vault** (the canonical
  secret store).
- **AWS KMS / GCP KMS** (the
  encryption keys).
- **The platform's secure enclave**
  (for on-device secrets, e.g. the
  mobile app's biometric key).

A secret in the code, the config, the
assets, the build artifacts, or the
application package is a P0 incident
(per
`.ai/skills/12-security-zero-trust/SKILL.md`
section 10). The CI scans every build
artifact for known secret patterns. A
positive match is a hard build failure.

The secret rotation cadence is:

- **HIGH-severity secrets** (DB
  credentials, payment provider keys):
  30 days.
- **MEDIUM-severity secrets** (API
  tokens, OAuth client secrets): 90
  days.
- **LOW-severity secrets** (feature
  flags, dev credentials): 365 days.

## Encryption

The platform encrypts:

- **At rest.** AES-256-GCM (or
  equivalent) for the database, the
  object storage, and the audit log.
  The encryption keys are in the KMS.
- **In transit.** TLS 1.3 for every
  external connection. mTLS for every
  service-to-service connection.
- **In memory.** Sensitive fields
  (passwords, tokens, PII) are
  encrypted in process memory. The
  encryption keys are derived from
  the KMS.

A database that is not encrypted at
rest is a contract violation
(per
`.ai/skills/12-security-zero-trust/SKILL.md`
section 10).

## Threat model

The full threat model is in
`docs/threat-model/`. The summary is:

- **STRIDE per surface** (the
  Spoofing / Tampering / Repudiation
  / Information disclosure / Denial
  of service / Elevation of privilege
  per surface).
- **The trust boundaries** (per
  `docs/architecture/system-context.md`).
- **The mitigations** (per skill).
- **The residual-risk register**
  (the risks that are not fully
  mitigated + the acceptance).

## CVE posture

The platform monitors the CVE feed
daily. A HIGH CVE with a patch
available triggers a P1 incident.

The patch SLA is:

- **HIGH:** 14 days.
- **MEDIUM:** 30 days.
- **LOW:** 90 days.

A missed SLA is itself a P1 incident
(per
`.ai/skills/12-security-zero-trust/SKILL.md`
section 8).

## Compliance posture

The platform's compliance posture is
in `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
The platform complies with:

- **UN R155** (cybersecurity
  management system).
- **UN R156** (software update
  management system).
- **ISO 26262** (functional safety).
- **ISO 21434** (cybersecurity
  engineering).
- **GDPR / CCPA / LGPD** (data
  protection).

## Cross-references

- **Threat model:** `docs/threat-model/`.
- **Compliance posture:** `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Security skill:** `.ai/skills/12-security-zero-trust/SKILL.md`.
- **AI authority boundary:** `docs/architecture/ai-authority-boundaries.md`.
- **Data classification:** `docs/architecture/data-classification.md`.
