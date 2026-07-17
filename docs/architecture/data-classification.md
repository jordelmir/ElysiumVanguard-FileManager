---
title: Data Classification
status: stub — to be filled in during Phase 0 + G9
owner: skill 13 (functional-safety-regulatory)
last_updated: 2026-07-17
audience: orchestrator, skill 13, all 16 skills, auditors
---

# Data Classification — Elysium Automotive Foundry

> **Status:** This is a stub for the data
> classification. The full content is
> produced during Phase 0 (Discovery) +
> G9 (Safety and regulatory evidence
> model). Reference: `.ai/AGENTS.md`
> section 23 (Required Documentation) +
> `.ai/skills/13-functional-safety-regulatory/SKILL.md`.

## What this document must include

- **The data classes** (the
  classification levels).
- **The handling rules per class**
  (the encryption + the access + the
  retention + the deletion).
- **The data inventory** (which
  dataset belongs to which class).
- **The cross-border rules** (the
  data residency + the data transfer).

## Data classes

The platform's data classes are:

| Class | Description | Examples |
|---|---|---|
| **Public** | Data that is intentionally public | the marketplace browse, the public docs, the public ADRs |
| **Internal** | Data that is not public but is not sensitive | the team roster, the internal ADRs, the changelogs |
| **Confidential** | Data that is sensitive to the org | the OEM catalog, the supplier contracts, the auth audit log |
| **Regulated** | Data that is regulated by a jurisdiction | the user PII (GDPR / CCPA / LGPD), the safety data (ISO 26262), the export-controlled data (ITAR / EAR) |

A data class is a property of the
**field** + the **dataset** + the
**record**. A field's class is the
highest class of any record that
contains it.

## Handling rules per class

### Public

- **Encryption at rest:** not
  required.
- **Encryption in transit:** TLS 1.3
  (for the API).
- **Access:** any authenticated
  user.
- **Retention:** indefinite.
- **Deletion:** on user request.

### Internal

- **Encryption at rest:** AES-256-GCM.
- **Encryption in transit:** TLS 1.3.
- **Access:** any authenticated user
  in the same tenant.
- **Retention:** 7 years (per the
  audit-trail requirement).
- **Deletion:** on tenant request +
  the audit-trail entry.

### Confidential

- **Encryption at rest:** AES-256-GCM
  (per the security model, in
  `docs/architecture/security-model.md`).
- **Encryption in transit:** TLS 1.3
  + application-level encryption for
  the most-sensitive fields.
- **Access:** RBAC + ABAC (per the
  security model). The user must have
  a role that grants access to the
  field's class.
- **Retention:** 7 years.
- **Deletion:** on tenant request +
  the audit-trail entry. A deletion
  request for a Confidential field
  triggers a P2 incident.

### Regulated

- **Encryption at rest:** AES-256-GCM
  + the encryption key is in the
  KMS + the key rotation cadence
  per the security model.
- **Encryption in transit:** TLS 1.3
  + mTLS for service-to-service.
- **Access:** RBAC + ABAC + the
  regulatory posture. The user must
  have a role that grants access to
  the field's class + the
  jurisdiction must match the user's
  jurisdiction.
- **Retention:** per the regulatory
  requirement (ISO 26262: 15 years
  for safety data; GDPR: as long as
  the processing purpose is active).
- **Deletion:** on user request
  (GDPR right to erasure) + the
  audit-trail entry. A deletion
  request for a Regulated field
  triggers a P1 incident + the
  privacy impact assessment is
  filed.

## Data inventory

The platform's data inventory is
maintained by the orchestrator (skill
00) + the regulatory skill (skill 13).
The inventory is in
`docs/architecture/data-classification-inventory.md`
(a living document, not in this stub).

For every dataset, the inventory
records:

- **The dataset name.**
- **The fields in the dataset.**
- **The class of each field.**
- **The retention period.**
- **The deletion procedure.**
- **The cross-border rules.**

A dataset without an inventory entry
is a contract violation; the
orchestrator blocks the release.

## Cross-border rules

The platform's cross-border rules
are:

- **EU data** (GDPR) — the data
  MUST stay in the EU. A transfer
  to a non-EU jurisdiction requires
  a Standard Contractual Clause
  (SCC) or a Binding Corporate Rule
  (BCR).
- **US data** (CCPA) — the data MAY
  be transferred to a non-US
  jurisdiction, but the user has
  the right to opt out.
- **Brazil data** (LGPD) — the data
  MUST stay in Brazil or in a
  jurisdiction with an adequacy
  decision.
- **China data** (PIPL, when
  relevant) — the data MUST stay
  in China.
- **Export-controlled data** (ITAR /
  EAR) — the data MUST NOT be
  transferred to a non-US person
  without an export license.

A cross-border transfer that
violates the rules is a P0 incident
+ a regulatory filing (per skill 13).

## Cross-references

- **Security model:**
  `docs/architecture/security-model.md`.
- **Threat model:** `docs/threat-model/`.
- **Compliance posture:**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Privacy policy:** `docs/PRIVACY_POLICY.md`.
