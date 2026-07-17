# API Documentation — Elysium Automotive Foundry

> **Status:** Stub. The full content is
> produced during Phase 0 (Discovery) +
> the per-service API contracts land as
> each service is built. Reference:
> `.ai/AGENTS.md` section 23 (Required
> Documentation).

## What this directory must include

- **One OpenAPI document per service.**
  The contract is the API; the OpenAPI
  doc is the human-readable view.
- **The API changelog.** Every
  breaking change is documented +
  every new endpoint is documented.
- **The error envelope reference.**
  The typed `FoundryError` shape +
  the per-error-code documentation.
- **The correlation ID header
  reference.** The `X-Correlation-Id`
  header + the propagation rules.
- **The retry classification
  reference.** The four
  classifications + the per-error
  classification.
- **The rate limit reference.** The
  per-endpoint rate limit + the
  backoff.

## API surface

The platform's API surface is the
union of the 16 skills' public
contracts. The major surfaces are:

- **The vehicle DSL API** (skill 04).
- **The 3D pipeline API** (skill 06).
- **The digital twin API** (skill 07).
- **The event bus API** (skill 08).
- **The catalog API** (skill 09).
- **The marketplace API** (skill 10).
- **The mobile API** (skill 11).
- **The auth API** (skill 12).
- **The regulatory API** (skill 13).

## Cross-references

- **OpenAPI convention:**
  the standard OpenAPI 3.1 schema
  (per `.ai/STANDARDS.md` section 1).
- **Error envelope:**
  `.ai/STANDARDS.md` section 7.
- **Correlation ID:**
  `.ai/AGENTS.md` section 24.3.
- **Retry classification:**
  `.ai/AGENTS.md` section 24.4.
- **System context:**
  `docs/architecture/system-context.md`.
- **Container view:**
  `docs/foundry/target-architecture.md`.
