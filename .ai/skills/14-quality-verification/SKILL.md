---
name: quality-verification
description: Defines the testing strategy, quality gates, deterministic verification and adversarial validation for the complete platform.
---

# Skill 14 — Quality and Verification

## 1. Mission

**Prove domain invariants, security,
determinism, performance, and recovery
behavior.**

**Code coverage alone is not
sufficient.** A 100% line-coverage
score that is achieved by testing
trivial getters is a contract
violation (per the existing forbidden
patterns in this skill). The
platform verifies **what the code
does**, not how many lines the tests
touch.

The verifier is the **only** signal the
orchestrator uses to approve a
release. A failing verification is a
blocker; the orchestrator does not
merge a release with a failing
verification.

## 2. Testing pyramid

The platform implements a **14-layer
testing pyramid**. Every layer is
testable + every layer is fast enough
to run on every PR (the inner loop).

- **Domain unit tests.** Per
  function + per module. Fast
  (< 1ms per test). The inner
  loop.
- **Property-based tests.**
  Per invariant (per section
  4). The property tests
  generate random inputs +
  check the invariant. The
  property tests are slow;
  they run on the merge queue,
  not on every PR.
- **Parser fuzzing.** Per
  input surface (per skill 04
  section 26). The CI runs
  them for a fixed time budget
  (1 hour per release for the
  critical surfaces).
- **Repository integration
  tests.** Per skill (per
  `.ai/AGENTS.md` section 23
  — integration tests cross
  module boundaries).
- **Database migration
  tests.** Every migration is
  re-runnable + idempotent;
  the test asserts a
  migration + a rollback
  preserve the data.
- **API contract tests.** Per
  cross-skill contract (per
  skill 00 + `.ai/AGENTS.md`
  section 13). The contract
  test pins the API.
- **Authorization tests.**
  Every action is tested
  with the authorized role
  (the action succeeds) +
  the unauthorized role (the
  action is rejected with a
  typed `UnauthorizedProjectAccess`
  error).
- **Android UI tests.** Per
  screen (per skill 11). The
  Compose UI test asserts the
  render + the click + the
  keyboard input.
- **Renderer lifecycle
  tests.** Per `Activity` /
  `Composable` (per skill
  11). The test asserts the
  lifecycle owner is respected
  + the GPU resources are
  disposed on close.
- **End-to-end workflows.**
  Per user journey (the
  "designer creates a
  vehicle" workflow, the
  "mechanic diagnoses a
  fault" workflow). The
  end-to-end test exercises
  every skill the workflow
  touches.
- **Performance tests.**
  Per surface (P99 latency +
  requests-per-second +
  resource hot spots). The
  performance baseline is
  documented; a regression
  beyond the approved limit
  is a P1 incident.
- **Resilience tests.** Per
  failure mode (the database
  is down, the network is
  partition, the asset
  pipeline is overloaded).
  The resilience test
  asserts the platform
  degrades gracefully + the
  user is informed.
- **Security tests.** Per
  the 11 pen-test categories
  (per skill 12 section 7.6).
  The security test is
  continuous; the CI runs
  the security tests on
  every PR.
- **Disaster-recovery
  exercises.** Per the DR
  plan. The DR exercise is
  per quarter; a failed DR
  exercise is a P1 incident.

## 3. Required invariant tests

The platform proves the **domain
invariants** with property-based
tests. An invariant without a test
is a contract violation; the
verifier rejects the build.

The required invariants are:

- **The CRDT merge mutates
  `this`.** A test asserts
  `crdt.merge(other)` mutates
  `this` (per the engineering
  rule + the past 3
  confirmed bugs).
- **The CRDT test HLCs are
  greater than wall-clock.** A
  test asserts the test HLCs
  are `Long.MAX_VALUE - N` or
  use an explicit `nowMs`
  parameter (per the
  engineering rule + the past
  reliability issues).
- **The regionMatches does
  not silently fail.** A test
  asserts `regionMatches(i,
  tag, 0, N)` returns `false`
  when `N > tag.length`
  (per the engineering rule).
- **The magic-byte rules are
  first-match.** A test
  asserts the broad-before-
  specific ordering (per the
  engineering rule).
- **The byte-boundary probe
  is correct.** A test
  asserts the ISO 9660
  probe at 32768 reads bytes
  32768..32771 (per the
  engineering rule).
- **Backtick test names
  reject colons.** A test
  asserts `fun \`name: foo\``
  is a compile error.
- **The Typed error envelope
  is consistent.** A test
  asserts every API error
  matches the `FoundryError`
  shape (per
  `.ai/STANDARDS.md` section
  7).
- **The money type is
  BigDecimal.** A test asserts
  no `Double` / `Float` /
  `f64` for money.
- **The vehicle
  representation level is
  append-only.** A test
  asserts a level regression
  is rejected.
- **The AI → VERIFIED
  transition is signed.** A
  test asserts an
  `AI_INFERRED` claim
  without a human-review
  transition event is
  rejected.
- **The contract version
  governs the event time.**
  A test asserts a sale
  between v1 and v2 is
  calculated under v1.
- **The supply chain has
  signed builds.** A test
  asserts every release
  artifact has a verified
  signature.
- **The cross-cutting
  concerns are honored.** A
  test asserts every response
  has a correlation ID + a
  retry classification + a
  safe user-facing message.
- **The marketplace
  enforces the 11
  manufacturing gates.** A
  test asserts a project that
  fails a gate is rejected.
- **The mobile UX shows the
  representation level
  prominently.** A test
  asserts every vehicle
  card has the badge.
- **The 3D pipeline rejects
  embedded scripts.** A test
  asserts a glTF with a
  script is rejected at the
  sandbox step.

## 4. Quality gates (the 30+)

The quality gates are enforced on
**every release**. A failing gate
is a blocker; the release is
blocked until the gate is green.

The platform's quality gates
include:

- Lint (language-specific) — pass.
- Type check (strict) — pass.
- Unit tests — pass.
- Integration tests — pass.
- Contract tests — pass.
- Property-based tests — pass.
- Coverage ≥ 80% on changed
  lines — pass.
- Coverage ≥ 70% on the overall
  codebase — pass.
- No new dependency without
  ADR — pass.
- Artifact contract honored —
  pass.
- No secrets in repo / in
  config / in assets / in build
  artifacts / in app package —
  pass.
- No license-incompatible
  dependencies — pass.
- Telemetry emitted for new
  code path — pass for non-
  trivial work.
- ADR for any architectural
  change — pass.
- Security review (light) for
  any new input surface — pass.
- **Error model gate** — every
  catch block re-throws, logs
  with a typed error, or
  returns a typed `Result` /
  `Either`.
- **Money type gate** — no
  `Double` / `Float` / `f64` for
  money.
- **Null-safety gate** — no
  `!!` in Kotlin, no unchecked
  `as` in TypeScript, no
  `unwrap()` / `expect` in
  production Rust.
- **Main-thread gate (Android)**
  — no model loading, decoding,
  or network work on the
  Android main thread.
- **Asset-trust gate** — every
  imported 3D asset is
  validated before it enters
  the canonical store.
- **AI-authority gate** — no
  LLM directly mutates
  authoritative financial or
  engineering state.
- **Vehicle-representation-
  level gate** — every
  `Vehicle` carries a
  `VehicleRepresentationLevel`.
- **Truth-model gate** — every
  engineering fact carries
  full `EngineeringFact<T>`
  metadata.
- **Pen-test gate** — every
  pen-test category is exercised.
- **Pen-test gate (12
  categories)** — IDOR +
  tenant escape + malicious
  GLB + zip bomb + path
  traversal + prompt injection
  + contract tampering +
  duplicate financial events +
  privilege escalation + signed
  URL reuse + offline-cache
  extraction (per skill 12
  section 7.6).
- **Critical findings gate** —
  no release with critical
  findings (per skill 12
  section 7.6).
- **Supply-chain gate** —
  signed builds + provenance
  attestations + dependency
  lockfiles (per skill 12
  section 7.5).
- **Regulatory gate** — every
  release passes the
  per-jurisdiction
  `COMPLIANCE_PROFILE`
  (per skill 13 section 6).
- **Performance gate** — no
  regression beyond the
  approved P99 latency limit.
- **Mutation gate** — mutation
  score ≥ 70% on critical
  modules.
- **Concurrent gate** —
  concurrent compilation +
  concurrent editing do not
  lose data.
- **Determinism gate** — the
  compiler output is
  byte-identical across runs.
- **Fuzz gate** — no panics
  on arbitrary input.

A failing gate is a blocker; the
release is blocked until the gate
is green. The verifier (skill 14)
emits the gate status.

## 5. Workflow

1. **Receive a PR.** The CI
   runs the inner-loop tests
   (the unit + the integration
   + the lint + the type
   check + the contract + the
   coverage).
2. **Receive a release.** The
   CI runs the full suite
   (the unit + the integration
   + the contract + the
   property + the mutation +
   the fuzz + the security +
   the performance +
   the DR + the compliance).
3. **Run the unit tests.**
   Per module. The coverage
   threshold is per module.
4. **Run the integration
   tests.** Per skill. The
   integration tests cross
   module boundaries.
5. **Run the contract tests.**
   Per cross-skill contract.
   The contract is the API;
   the contract test pins
   the API.
6. **Run the property tests.**
   Per invariant. The
   property tests are slow;
   they run on the merge
   queue, not on every PR.
7. **Run the mutation tests.**
   Per critical module. The
   mutation tests verify that
   the unit tests are actually
   testing the behaviour, not
   the implementation.
8. **Run the fuzz tests.** Per
   input surface. The CI runs
   them for a fixed time
   budget (1 hour per release
   for the critical surfaces).
9. **Run the security tests.**
   Per the 11 pen-test
   categories. The security
   test is continuous.
10. **Run the performance
    tests.** Per surface. The
    performance regression
    is a P1 incident.
11. **Run the compliance
    tests.** Per the
    per-jurisdiction
    `COMPLIANCE_PROFILE`.
12. **Compute the gate
    status.** Every gate is
    pass / fail. A fail blocks
    the release.
13. **Emit the verification
    report.** The report is
    the orchestrator's input.

## 6. Failure modes

- **A test fails.** The PR is
  blocked. The developer
  fixes the test or the code.
- **Coverage drops below the
  threshold.** The PR is
  blocked. The developer
  adds the missing tests.
- **A fuzz test finds a
  crash.** The PR is blocked.
  The developer fixes the
  crash.
- **A mutation test reveals
  an untested branch.** The
  PR is blocked. The
  developer adds the test.
- **A gate is broken by a
  hotfix.** The release is
  blocked. The developer
  fixes the gate.
- **A pen test finds a
  critical.** The release is
  blocked. The developer
  fixes the critical.

## 7. Coordination contract

- **Input from**: every
  other skill (the PR +
  the release + the
  `SKILL.md` + the PRD +
  the ADR).
- **Output to**: skill 00
  (the orchestrator, the
  release), skill 15 (the
  devops, the CI gate
  status).
- **Triggered by**: every
  PR + every release.
- **Frequency**: continuous.

## 8. Forbidden patterns

- **Coverage theatre.** A
  100% line-coverage score
  that is achieved by testing
  trivial getters is a
  contract violation. The
  coverage is on behaviour,
  not on lines.
- **Mutation test theatre.**
  A mutation score achieved
  by mutating trivial
  branches is a contract
  violation.
- **Slow tests in the inner
  loop.** A test suite that
  takes 30 minutes to run is
  a contract violation. The
  inner loop is < 5 minutes.
- **Flaky tests.** A test
  that fails 1% of the time
  is a contract violation.
  The test is fixed or
  deleted.
- **"We'll add tests later".**
  A PR that ships without
  tests is a contract
  violation.
- **Tests that don't run.** A
  test that is `@Ignore`d
  without a follow-up ticket
  is a contract violation.
- **Tests that test the
  implementation.** A test
  that breaks on every
  refactor is a contract
  violation. The test tests
  the behaviour, not the
  implementation.
- **Bypassing the gates.** A
  release that bypasses the
  gates is a contract
  violation. The
  orchestrator does not merge
  a release with a failing
  gate.
- **A free-form string
  error.** Every error is a
  typed `FoundryError`.
- **A `Double` for money.** A
  money value in `Double`
  is a contract violation.
- **A "the unit test passes
  so the code works"
  assumption.** A unit test
  that does not test the
  invariant is theatre.

## 9. Working with this skill

When invoked, this skill:

1. Receives the input (PR,
   release).
2. Runs the suite (per
   section 5).
3. Computes the gates (per
   section 4).
4. Files the verification
   report.
5. Returns the gate status
   to the orchestrator.

The skill does **not** implement
product code. The skill is the
**verifier** the orchestrator runs.

## 10. Cross-references

- **Orchestrator (skill 00):**
  `.ai/skills/00-program-orchestrator/SKILL.md`.
- **Project gates (G0–G10):**
  `.ai/AGENTS.md` section 22.
- **Completion standard:**
  `.ai/AGENTS.md` section 21.
- **Standards (tech stack +
  non-negotiables + truth
  model + levels + AI
  authority + error model):**
  `.ai/STANDARDS.md`.
- **AI council (skill 05):**
  `.ai/skills/05-ai-engineering-council/SKILL.md`.
- **DSL compiler (skill 04):**
  `.ai/skills/04-vehicle-dsl-compiler/SKILL.md`.
- **3D pipeline (skill 06):**
  `.ai/skills/06-3d-cad-asset-pipeline/SKILL.md`.
- **Digital twin (skill 07):**
  `.ai/skills/07-digital-twin-diagnostics/SKILL.md`.
- **Backend event platform
  (skill 08):**
  `.ai/skills/08-backend-event-platform/SKILL.md`.
- **IP/provenance/royalties
  (skill 09):**
  `.ai/skills/09-ip-provenance-royalties/SKILL.md`.
- **Marketplace (skill 10):**
  `.ai/skills/10-marketplace-manufacturing/SKILL.md`.
- **Mobile UX (skill 11):**
  `.ai/skills/11-mobile-forge-ux/SKILL.md`.
- **Security (skill 12):**
  `.ai/skills/12-security-zero-trust/SKILL.md`.
- **Regulatory (skill 13):**
  `.ai/skills/13-functional-safety-regulatory/SKILL.md`.
- **Devops (skill 15):**
  `.ai/skills/15-devops-observability/SKILL.md`.
