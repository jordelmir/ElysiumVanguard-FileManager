---
name: backend-event-platform
description: Implements the domain backend, persistence, versioning, event publication, concurrency controls and artifact registry.
---

# Skill 08 — Backend and Event Platform

## 1. Mission

Provide a **trustworthy transactional
core** for engineering, collaboration,
commercialization, and audit.

The backend is the platform's
"authoritative state" layer. Every
state change goes through the
transaction model (per section 3).
Every aggregate has optimistic
concurrency (per section 4). Every
artifact is content-addressed (per
section 5). Every API has a typed
schema + an idempotency key + a
correlation ID (per section 6).
Every money value is `BigDecimal` /
arbitrary-precision (per section 7).

The backend is the implementation
of the canonical cross-cutting
concerns (per `.ai/AGENTS.md`
section 24). The backend is NOT
the user-facing surface (skill 11
+ the web app) and NOT the AI
authority (per `.ai/AGENTS.md`
section 8 + `.ai/STANDARDS.md`
section 5).

## 2. Bounded contexts

The backend is organized into
**12 explicit bounded contexts**.
A context that is not in this
list is a contract violation; an
ADR + a vote in the AI council
(skill 05) is required.

The contexts MAY initially exist
as modules in one deployable
application (per `.ai/AGENTS.md`
section 3 — modular monolith).
A context is split into its own
service when an ADR + a measurable
scaling boundary justifies the
split.

- **Identity and Access.** The
  identity provider (OIDC),
  the auth (OAuth 2.1, WebAuthn,
  mTLS), the authz (RBAC +
  ABAC), the secret management
  (Vault + KMS).
- **Project Management.** The
  `Project` + the
  `VehicleProgram` + the
  `VehicleRevision` + the
  per-project collaboration.
- **Requirements.** The PRD +
  the requirements hierarchy
  (per skill 02) + the conflict
  engine.
- **Vehicle Engineering.** The
  `Spec.Artifact` (per skill 04)
  + the scene manifest + the
  compatibility constraints.
- **Artifact Registry.** The
  content-addressed store + the
  metadata + the provenance (per
  section 5).
- **AI Orchestration.** The
  AI council (per skill 05) +
  the typed proposals + the
  per-role budgets.
- **Provenance and Rights.** The
  catalog (per skill 09) + the
  authorship claims + the
  royalty contracts + the audit
  trail.
- **Contracts and Licensing.**
  The `RoyaltyContract` + the
  per-artifact license + the
  export controls.
- **Commerce and Royalties.**
  The sales events + the
  royalty engine + the
  settlements.
- **Marketplace.** The listings
  + the escrow + the supplier
  integration (per skill 10).
- **Supplier Network.** The
  supplier discovery + the RFQs
  + the offers + the controlled
  disclosure.
- **Diagnostics.** The
  `DigitalTwinState` + the fault
  model + the repair flow (per
  skill 07).
- **Notifications.** The
  transactional outbox + the
  async notifications + the
  email + the push.

A context owns its aggregate (per
`docs/foundry/domain-ownership.md`).
Two contexts editing the same
aggregate is a contract violation;
the orchestrator arbitrates.

## 3. Transaction model

For every state change, the
backend follows a **9-step
transaction model**. A step that
fails halts the transaction + emits
a typed `FoundryError`.

1. **Validate command.** The
   command is validated against
   the typed schema (per skill
   04). A command that does
   not match the schema is
   rejected with a typed
   `VehicleDefinitionInvalid`
   error.
2. **Authorize actor.** The
   actor (the user, the system)
   is authorized against the
   action (per skill 12). An
   unauthorized actor is
   rejected with a typed
   `UnauthorizedProjectAccess`
   error.
3. **Load aggregate with
   expected version.** The
   aggregate is loaded with the
   `expectedVersion` (per section
   4). A stale version is
   rejected with a typed
   `RevisionConflict` error.
4. **Enforce invariant.** The
   aggregate's invariants (per
   skill 03) are enforced. An
   invariant violation is
   rejected with a typed
   `CompatibilityConstraintViolation`
   or `VehicleDefinitionInvalid`
   error.
5. **Persist state and domain
   event atomically.** The
   state + the domain event are
   persisted in the same
   database transaction. A
   partial persist is a
   contract violation.
6. **Write transactional
   outbox.** The event is
   written to the outbox in the
   same transaction. The outbox
   is the single source of truth
   for async publication.
7. **Commit.** The database
   transaction is committed. A
   commit failure rolls back
   the entire transaction.
8. **Publish asynchronously.** A
   separate worker reads the
   outbox + publishes the event
   to the bus. The publication
   is async; the consumer
   (section 9) is idempotent.
9. **Make consumers idempotent.**
   Every consumer is idempotent
   (per the per-consumer
   `idempotencyKey`). A
   duplicate event is a no-op
   (the consumer checks the
   `idempotencyKey` before
   applying the event).

The transaction is **atomic**:
either every step succeeds or
none of them does. A partial
state is a contract violation;
the verifier (skill 14) rejects
the implementation.

## 4. Concurrency

The backend uses **optimistic
concurrency** for collaborative
revisions. A pessimistic lock
on a `VehicleRevision` would
block the platform's collaboration
model.

Every command carries:

- **Aggregate ID.** The ID of
  the aggregate the command
  affects (the `VehicleRevisionId`,
  the `ProjectId`).
- **Expected version.** The
  version the command was
  authored against. A
  mismatch is a `RevisionConflict`
  error.
- **Idempotency key.** A
  unique key per command. A
  duplicate command (the same
  key) is a no-op.
- **Actor.** The user (or the
  system) that issued the
  command. The actor is
  recorded in the audit trail.
- **Correlation ID.** The
  correlation ID (per
  `.ai/AGENTS.md` section 24.3).
  The ID is propagated through
  every downstream event +
  every log + every audit-trail
  entry.

A command without these 5
fields is rejected; the
command is malformed.

The backend **rejects stale
writes with a `RevisionConflict`**
error. The backend does **not**
silently overwrite engineering
changes. A silent overwrite is a
contract violation; the
verifier rejects the
implementation.

## 5. Artifact registry

Artifacts are **immutable** and
**content-addressed**. A mutable
artifact is a contract violation;
the audit trail is append-only.

The registry stores 12 fields per
artifact:

- **Artifact ID.** The typed
  `ArtifactId` (per skill 03
  section 14).
- **Content hash.** The
  SHA-256 of the artifact bytes.
- **Media type.** The MIME
  type (a `model/gltf-binary`
  for a glTF, an
  `application/step` for a STEP).
- **Size.** The artifact size
  in bytes.
- **Storage locator.** The
  content-addressed store URL
  (S3 + the bucket + the key).
- **Project.** The `ProjectId`
  the artifact belongs to.
- **Revision.** The
  `RevisionId` the artifact is
  associated with.
- **Creator.** The user (or
  the system) that created the
  artifact.
- **Provenance.** The
  `EngineeringFact<T>` with the
  full metadata (per
  `.ai/STANDARDS.md` section 3).
- **Signature.** The Ed25519
  signature of the producing
  agent.
- **Malware-scan status.** The
  result of the virus scan (per
  skill 06 section 3 step 2).
- **Engineering classification.**
  The asset class
  (`VISUAL_MESH`,
  `ENGINEERING_SOLID`,
  `COLLISION_MESH`, etc., per
  skill 06 section 2).
- **Retention policy.** The
  per-artifact retention (per
  `.ai/architecture/data-classification.md`).

**Never** trust a client-provided
hash without server verification.
The server recomputes the hash
on every write; a mismatch is
rejected with a typed
`ArtifactIntegrityFailure` error.

A client that uploads a hash
disagreeing with the server's
recomputation is escalated to
the security skill (skill 12) as
a P1 incident (the client is
either buggy or malicious).

## 6. API requirements

Every backend API has **10
requirements**. An API without
all 10 is a contract violation.

- **Explicit request/response
  schemas.** The OpenAPI 3.1
  schema (per `.ai/AGENTS.md`
  section 23 + `.ai/STANDARDS.md`
  section 1). A request that
  does not match the schema is
  rejected.
- **Stable error codes.** The
  typed `FoundryError` shape
  (per `.ai/STANDARDS.md` section
  7). A free-form string error
  is rejected.
- **Pagination.** Cursor-based
  pagination for list endpoints.
  Offset-based pagination is a
  smell.
- **Filtering limits.** A
  maximum filter cardinality
  (default: 100 filters per
  request). A request that
  exceeds the limit is
  rejected.
- **Idempotency for retryable
  mutations.** Every mutating
  endpoint accepts an
  `idempotencyKey` (per section
  4). A duplicate request is a
  no-op.
- **Authorization.** Every
  endpoint has an authz check
  (per skill 12). An
  unauthenticated request is
  rejected.
- **Rate limiting.** Every
  endpoint has a rate limit
  (per skill 12). A request that
  exceeds the limit is rejected
  with a typed
  `RateLimited` error.
- **Correlation IDs.** Every
  request has a correlation ID
  (per `.ai/AGENTS.md` section
  24.3). The ID is in the
  response header + the audit
  trail.
- **Audit events.** Every
  state-changing endpoint emits
  a typed `DomainEvent` to the
  outbox (per section 3). The
  event is the source of truth
  for the audit trail.
- **Backward-compatible
  versioning.** Every endpoint
  has a major version. A
  breaking change is a major
  version bump + a deprecation
  schedule.

## 7. Money

The platform represents money
with **3 mandatory fields**:

- **Currency code.** The ISO
  4217 code (`USD`, `EUR`,
  `BRL`).
- **Arbitrary-precision decimal
  or integer minor units.** A
  `BigDecimal` (JVM), a
  `decimal.Decimal` (Python), a
  `rust_decimal::Decimal` (Rust).
  **Never** a `Double` / `Float`
  / `f64` (per
  `.ai/STANDARDS.md` section 2.2
  + `.ai/AGENTS.md` section 5.2).
- **Explicit rounding policy.**
  The rounding mode
  (`HALF_EVEN` is the default +
  the recommended IEEE 754
  default; a different mode is
  documented). A money value
  without an explicit rounding
  policy is a contract violation.

A money value that is `null` /
missing / `"N/A"` is a contract
violation; the value is required
on every commerce-relevant field.

A money value in a `Double` is a
contract violation; the CI rejects
the build.

A money value in a different
currency than the contract
specifies is a typed
`RoyaltyCalculationRejected` error
(per `.ai/STANDARDS.md` section
7).

## 8. Workflow

1. **Receive the request.** The
   backend receives the
   request (the API gateway, the
   event bus, the admin CLI).
2. **Authenticate + authorize.**
   The actor is authenticated +
   authorized (per skill 12).
3. **Validate the command.** The
   command is validated (per
   section 3 step 1).
4. **Apply the 9-step
   transaction model.** Per
   section 3.
5. **Publish the event.** The
   event is published
   asynchronously via the
   outbox.
6. **Return the response.** The
   response includes the
   correlation ID + the new
   aggregate version.

## 9. Quality gates

- The 9-step transaction model
  is enforced (per section 3).
- The optimistic concurrency is
  enforced (per section 4).
- The artifact registry is
  content-addressed (per
  section 5).
- The 10 API requirements are
  met (per section 6).
- Money is `BigDecimal` (per
  section 7).
- The audit trail is append-only.
- The correlation ID is
  propagated through every
  downstream event.
- The retry classification is
  per `.ai/AGENTS.md` section
  24.4.
- The cross-cutting concerns
  are per `.ai/AGENTS.md`
  section 24.

## 9.5. Definition of done

The backend is accepted only when
**every** proof below passes.

- **Duplicate command delivery
  is harmless.** A test
  asserts the same
  `idempotencyKey` produces
  the same result; the
  state is unchanged; the
  audit trail records the
  duplicate + the
  no-op.
- **Concurrent edits produce
  explicit conflicts.** A
  test asserts two
  concurrent commands with
  the same `AggregateId` +
  different `expectedVersion`
  produce two `RevisionConflict`
  errors; the state is
  unchanged.
- **Outbox delivery survives
  process failure.** A test
  asserts a process crash
  mid-publication does not
  lose events; the events
  are re-delivered on the
  next worker boot.
- **Artifact hashes are
  verified.** A test asserts
  the server recomputes the
  hash on every write; a
  mismatch is rejected with
  a typed `ArtifactIntegrityFailure`.
- **Unauthorized project
  access is rejected.** A
  test asserts a user
  without access to the
  project receives a typed
  `UnauthorizedProjectAccess`
  error.
- **Audit events cannot be
  modified through standard
  application APIs.** A
  test asserts the audit
  trail has no UPDATE or
  DELETE in the standard
  API; a modification
  attempt is rejected.

## 10. Failure modes

- **The database is down.** The
  request is rejected with a
  typed `ServiceUnavailable`
  error. The retry
  classification is
  `retryable_backoff`.
- **The optimistic concurrency
  check fails.** The request
  is rejected with a typed
  `RevisionConflict` error. The
  client refreshes + retries.
- **The outbox write fails.** The
  transaction is rolled back.
  The state is not persisted.
- **The artifact hash
  disagrees.** The upload is
  rejected with a typed
  `ArtifactIntegrityFailure`
  error. A P1 incident is
  filed.
- **The money is in the wrong
  currency.** The calculation
  is rejected with a typed
  `RoyaltyCalculationRejected`
  error.
- **The audit trail write
  fails.** The transaction is
  rolled back. The audit trail
  is append-only; a write
  failure is a P0 incident.

## 11. Coordination contract

- **Input from**: every other
  skill that produces a
  domain event (skills 03,
  04, 05, 06, 07, 09, 10,
  11, 13).
- **Output to**: every other
  skill that consumes a
  domain event (skills 09,
  10, 11, 13, 14, 15).
- **Triggered by**: every
  state-changing action on
  the platform.
- **Frequency**: continuous.

## 12. Forbidden patterns

- **A mutable artifact.** An
  artifact that can be updated
  or deleted is a contract
  violation. The audit trail
  is append-only.
- **A silent overwrite.** A
  command that overwrites a
  newer version is rejected
  with `RevisionConflict`.
- **A `Double` for money.** A
  money value in `Double` /
  `Float` / `f64` is a contract
  violation.
- **A client-provided hash
  without server verification.**
  The server recomputes the
  hash; a mismatch is rejected.
- **A free-form string error.**
  An error path that returns a
  string instead of a typed
  `FoundryError` is a contract
  violation.
- **A pessimistic lock on a
  collaborative aggregate.** A
  lock that blocks the
  collaboration is rejected;
  optimistic concurrency is
  the platform's contract.
- **A missing correlation ID.**
  A request without a
  correlation ID is rejected;
  the ID is required.
- **A partial transaction.** A
  state change that persists
  state without the event (or
  vice versa) is a contract
  violation. The transaction is
  atomic.

## 13. Working with this skill

When invoked, this skill:

1. Receives the request.
2. Authenticates + authorizes.
3. Validates the command.
4. Applies the 9-step
   transaction model.
5. Publishes the event.
6. Returns the response.

The skill does **not** implement
the user-facing surface (skill
11 + the web app). The skill
implements the **authoritative
state** layer; the surface
consumes the API.

The skill does **not** implement
the AI authority (per
`.ai/AGENTS.md` section 8). The
skill implements the
**transactional core**; the AI
proposes; the deterministic
engine + a human review apply.

## 14. Cross-references

- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Domain ownership:**
  `docs/foundry/domain-ownership.md`.
- **Dependency map:**
  `docs/foundry/dependency-map.md`.
- **AI authority boundary:**
  `.ai/AGENTS.md` section 8 +
  `.ai/STANDARDS.md` section 5.
- **Required error model:**
  `.ai/AGENTS.md` section 10 +
  `.ai/STANDARDS.md` section 7.
- **Cross-cutting concerns:**
  `.ai/AGENTS.md` section 24.
- **Money type (per
  STANDARDS.md section 2.2):**
  `BigDecimal` (or equivalent).
- **Ontology (skill 03):**
  `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.
- **DSL compiler (skill 04):**
  `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
- **3D pipeline (skill 06):**
  `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md`.
- **Digital twin (skill 07):**
  `.ai/skills/07-digital-twin-diagnostics/SKILL.md`.
- **Catalog (skill 09):**
  `.ai/skills/09-ip-provenance-royalties/SKILL.md`.
- **Marketplace (skill 10):**
  `.ai/skills/10-marketplace-manufacturing/SKILL.md`.
- **Mobile UX (skill 11):**
  `.ai/skills/11-mobile-forge-ux/SKILL.md`.
- **Security (skill 12):**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **Regulatory (skill 13):**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
- **Devops (skill 15):**
  `.ai/skills/15-devops-observability/SKILL.md`.
