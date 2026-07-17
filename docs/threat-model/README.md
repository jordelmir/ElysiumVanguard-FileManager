# Threat Model — Elysium Automotive Foundry

> **Status:** Stub. The full content is
> produced during Phase 0 (Discovery) +
> G10 (Production hardening). Reference:
> `.ai/AGENTS.md` section 23 (Required
> Documentation) +
> `.ai/skills/12-security-zero-trust/SKILL.md`.

## What this directory must include

- **The threat model summary** (the
  STRIDE per surface).
- **The trust boundaries** (per
  `docs/architecture/system-context.md`).
- **The mitigations** (per skill).
- **The residual-risk register** (the
  risks that are not fully mitigated
  + the acceptance).
- **The red-team report** (per
  quarter; per the security model
  in `docs/architecture/security-model.md`).
- **The CVE feed** (per day; the
  feed + the patch SLA + the
  response).

## STRIDE per surface

The platform's surfaces are:

- **The web app.** Spoofing +
  tampering + information
  disclosure + denial of service.
- **The mobile app.** Spoofing +
  tampering + information
  disclosure + denial of service +
  elevation of privilege.
- **The backend API.** Spoofing +
  tampering + repudiation +
  information disclosure + denial
  of service.
- **The event bus.** Tampering +
  information disclosure + denial
  of service.
- **The object storage.** Tampering
  + information disclosure + denial
  of service.
- **The 3D pipeline.** Tampering
  (a malicious asset) + denial of
  service (a malformed asset).
- **The AI council.** Tampering +
  information disclosure +
  elevation of privilege.

## Trust boundaries

The trust boundaries are defined in
`docs/architecture/system-context.md`.

## Mitigations

The mitigations are owned by skill 12
(security). The major mitigations are:

- **Auth + authz** on every request.
- **Audit trail** on every state-
  changing action.
- **Encryption** at rest + in transit.
- **Rate limiting** + DDoS protection.
- **Input validation** on every input
  surface.
- **Secret management** (the vault +
  the KMS).
- **CVE monitoring** + the patch SLA.
- **Quarterly red team** on the auth
  + marketplace surfaces.

## Residual-risk register

The residual-risk register is the
list of risks that are **not** fully
mitigated. For every risk, the
register records:

- The risk description.
- The mitigation in place.
- The residual risk (the risk that
  remains after the mitigation).
- The acceptance (who accepted the
  residual risk + when).

A residual risk without an
acceptance is a contract violation;
the orchestrator blocks the release.

## Red-team report

The red-team report is filed per
quarter. The report covers:

- The surfaces exercised.
- The findings (per severity).
- The mitigations applied.
- The follow-up.

A red-team report without findings
is a "no findings" — not a "pass". A
"no findings" red team is a P1
incident (the red team did not look
hard enough).

## CVE feed

The CVE feed is monitored daily. A
HIGH CVE with a patch available
triggers a P1 incident. The patch
SLA is per the security model in
`docs/architecture/security-model.md`.

## Cross-references

- **Security model:**
  `docs/architecture/security-model.md`.
- **Security skill:**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **System context:**
  `docs/architecture/system-context.md`.
- **Risk register:**
  `docs/foundry/risk-register.md`.
