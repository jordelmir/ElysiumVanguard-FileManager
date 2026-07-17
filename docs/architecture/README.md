# docs/architecture/ — Elysium Automotive Foundry Architecture

> **Status:** Stub index. The full content
> is produced during Phase 0 (Discovery)
> and updated as the platform grows.
> Reference: `.ai/AGENTS.md` section 23
> (Required Documentation).

This directory contains the **C4 model**
+ the **security model** + the **data
classification** + the **AI authority
boundaries** for the Elysium Automotive
Foundry platform. Every document is a
**living document**: a stale document is a
contract violation.

## The five required documents

| Document | What it covers |
|---|---|
| [`system-context.md`](./system-context.md) | The C4 level 1 system context: the platform, the actors, the external systems, the trust boundaries, the data flows. |
| [`domain-map.md`](./domain-map.md) | The bounded contexts + the relationships between them + the shared kernel + the anti-corruption layer. |
| [`security-model.md`](./security-model.md) | The trust boundaries + the auth model + the authz model + the secret management + the encryption + the threat model summary + the CVE posture + the compliance posture. |
| [`data-classification.md`](./data-classification.md) | The data classes (public, internal, confidential, regulated) + the handling rules per class + the data inventory + the cross-border rules. |
| [`ai-authority-boundaries.md`](./ai-authority-boundaries.md) | What the AI may do, what the AI may NOT do, the recovery patterns, the verification. |

## Cross-references

- **Global engineering contract:**
  `.ai/AGENTS.md`.
- **Technical standards:**
  `.ai/STANDARDS.md`.
- **Target architecture (C4 levels 1–3):**
  `docs/foundry/target-architecture.md`.
- **Domain ownership:**
  `docs/foundry/domain-ownership.md`.
- **Dependency map:**
  `docs/foundry/dependency-map.md`.
- **Threat model:**
  `docs/threat-model/`.
- **API contracts:**
  `docs/api/`.
