---
name: quality-verification
description: Test strategy, unit / integration / contract / fuzz / property / mutation tests, coverage thresholds, gate enforcement, the verification report. The "the platform actually works as specified" skill. The verifier the orchestrator runs on every release.
---

# Skill 14 — Quality + Verification

## 1. Mission

Verify that the platform actually works as
specified. The orchestrator runs this skill on
every release. A failing quality gate is a
**blocker**; the orchestrator does not merge a
release with a failing gate.

The quality skill is the platform's "we know
the platform works" answer. Without it, the
orchestrator has no signal; without signal, the
platform is a fiction.

## 2. In-scope

- The test strategy (which tests, which level,
  which tool, which threshold).
- The unit tests (per module, per function).
- The integration tests (per skill, per
  contract).
- The contract tests (per cross-skill
  contract).
- The fuzz tests (per input surface).
- The property tests (per invariant).
- The mutation tests (per critical module).
- The coverage thresholds (per module, per
  release).
- The gate enforcement (the CI gates that
  block a failing release).
- The verification report (per release, per
  PR).
- **The error model as a quality gate.** The
  verifier checks that every new code path
  uses the typed `FoundryError` model (per
  `.ai/AGENTS.md` section 10 and `.ai/STANDARDS.md`
  section 7). A `throw Exception("oops")` is
  a CI failure. A free-form string error is a
  CI failure. A `Double` / `Float` / `f64`
  for money is a CI failure. A generic
  catch-block that hides a failure is a CI
  failure. An unchecked null assertion is a
  CI failure.
- **The standards-compliance gate.** The
  verifier checks the technical standards
  (per `.ai/STANDARDS.md`) as part of the
  global quality gates. A standard violation
  is a CI failure. The security skill (12)
  enforces the security-relevant standards;
  the regulatory skill (13) enforces the
  regulatory-relevant standards.

## 3. Out-of-scope

- The product code (this skill verifies; the
  other skills implement).
- The CI infrastructure (skill 15).
- The audit (skill 09).
- The security (skill 12).
- The regulatory (skill 13).

The quality skill is the **verifier** the
orchestrator runs. The quality skill does not
implement; the quality skill gates.

## 4. Inputs

- Every PR.
- Every release.
- Every skill's `SKILL.md` (the contract).
- Every PRD (the acceptance criteria).
- Every ADR (the architecture decision).

## 5. Outputs

- The test report (per PR, per release).
- The coverage report (per module, per release).
- The mutation score (per critical module, per
  release).
- The gate status (per gate, per release).
- The verification report (per release; the
  orchestrator's primary input).

The verification report is **the** signal the
orchestrator uses to approve a release.

## 6. Workflow

1. **Receive a PR.** The CI runs the unit
   tests + the integration tests + the
   coverage + the lint + the type check.
2. **Receive a release.** The CI runs the full
   suite (unit + integration + contract +
   fuzz + property + mutation) + the gates.
3. **Run the unit tests.** Per module. The
   coverage threshold is per module.
4. **Run the integration tests.** Per skill.
   The integration tests cross module
   boundaries.
5. **Run the contract tests.** Per cross-skill
   contract. The contract is the API; the
   contract test pins the API.
6. **Run the fuzz tests.** Per input surface.
   The fuzz tests are continuous; the CI runs
   them for a fixed time budget.
7. **Run the property tests.** Per invariant.
   The property tests are slow; they run on
   the merge queue, not on every PR.
8. **Run the mutation tests.** Per critical
   module. The mutation tests verify that the
   unit tests are actually testing the
   behaviour, not the implementation.
9. **Compute the coverage.** Per module. The
   coverage threshold is 80% on changed lines.
10. **Compute the gate status.** Every gate
    is pass / fail. A fail blocks the release.
    The gates include the error-model gate,
    the money-type gate, the null-safety gate,
    the main-thread gate, the asset-trust
    gate, the AI-authority gate, the
    vehicle-representation-level gate, and the
    truth-model gate (per section 7).
11. **Emit the verification report.** The
    report is the orchestrator's input. The
    report includes the standards-compliance
    status: which standards were checked,
    which ones passed, which ones failed,
    and the diff against the previous
    release.

## 7. Quality gates

- Lint (language-specific) — pass.
- Type check (strict) — pass.
- Unit tests — pass.
- Integration tests — pass.
- Contract tests — pass.
- Coverage ≥ 80% on changed lines — pass.
- Coverage ≥ 70% on the overall codebase —
  pass.
- No new dependency without ADR — pass.
- Artifact contract honored — pass.
- No secrets in repo, in the config, in the
  assets, in the build artifacts, or in the
  application package — pass. The CI scans
  every PR + every build for known secret
  patterns. A positive match is a hard build
  failure.
- No license-incompatible deps — pass.
- Mutation score ≥ 70% on critical modules —
  pass (critical modules are: skill 03
  ontology, skill 04 DSL, skill 06 3D, skill
  07 diagnostic, skill 08 event platform,
  skill 09 catalog, skill 12 auth, skill 13
  regulatory).
- **Error model gate** — every catch block
  re-throws, logs with a typed error, or
  returns a typed `Result` / `Either`. A
  `catch (e: Exception) { /* ignore */ }`
  is a CI failure. A `throw Exception("oops")`
  is a CI failure. A free-form string error
  is a CI failure (per `.ai/AGENTS.md`
  section 5.3 and section 10, and
  `.ai/STANDARDS.md` section 2.3 and
  section 7).
- **Money type gate** — no `Double` /
  `Float` / `f64` for money. Money is
  `BigDecimal` (or equivalent). A money
  type that is not `BigDecimal` /
  `decimal.Decimal` / `rust_decimal::Decimal`
  is a CI failure (per `.ai/AGENTS.md`
  section 5.2 and `.ai/STANDARDS.md`
  section 2.2).
- **Null-safety gate** — no `!!` in Kotlin,
  no unchecked `as` in TypeScript, no
  `unwrap()` / `expect` in production Rust,
  no `Object!` cast in C#. A null-safety
  violation is a CI failure.
- **Main-thread gate (Android)** — no model
  loading, decoding, or network work on the
  Android main thread. A `StrictMode`
  violation is a CI failure (per
  `.ai/AGENTS.md` section 5.4).
- **Asset-trust gate** — every imported 3D
  asset is validated (manifold + units +
  coordinate system + file size + no-
  embedded-scripts + provenance coverage)
  before it enters the canonical store. An
  unvalidated asset is a CI failure.
- **AI-authority gate** — no LLM directly
  mutates authoritative financial or
  engineering state. A model that writes to
  the database, the catalog, the audit
  trail, the royalty engine, the regulatory
  submission, the settlement service, or the
  safety gate is a CI failure (per
  `.ai/AGENTS.md` section 5.6 and section 8,
  and `.ai/STANDARDS.md` section 2.6 and
  section 5).
- **Vehicle-representation-level gate** —
  every `Vehicle` carries a
  `VehicleRepresentationLevel`. A vehicle
  without a level is a CI failure. A
  level-regression is a CI failure. An
  `OEM_EXACT` or `OEM_PARTIAL` spec with an
  `AI_INFERRED` fact is a CI failure (per
  `.ai/AGENTS.md` section 7 and
  `.ai/STANDARDS.md` section 4).
- **Truth-model gate** — every engineering
  fact carries a full `EngineeringFact<T>`
  with all required metadata (per
  `.ai/AGENTS.md` section 6 and
  `.ai/STANDARDS.md` section 3). A fact
  missing any required field is a CI
  failure. An `AI_INFERRED → VERIFIED`
  transition without a signed event in the
  audit trail is a CI failure.

A failed gate is a blocker. The release is
blocked until the gate is green.

## 8. Failure modes

- **A test fails.** The PR is blocked. The
  developer fixes the test or the code.
- **Coverage drops below the threshold.** The
  PR is blocked. The developer adds the
  missing tests.
- **A fuzz test finds a crash.** The PR is
  blocked. The developer fixes the crash.
- **A mutation test reveals an untested
  branch.** The PR is blocked. The developer
  adds the test.
- **A gate is broken by a hotfix.** The
  release is blocked. The developer fixes
  the gate.

## 9. Coordination contract

- **Input from**: every PR, every release, every
  skill's `SKILL.md`, every PRD, every ADR.
- **Output to**: the orchestrator (skill 00),
  the CI (skill 15).
- **Triggered by**: every PR, every release.
- **Frequency**: continuous.

## 10. Forbidden patterns

- **Coverage theatre.** A 100% line-coverage
  score that is achieved by testing trivial
  getters is a contract violation. The
  coverage is on behaviour, not on lines.
- **Mutation test theatre.** A mutation score
  achieved by mutating trivial branches is a
  contract violation. The mutation tests are
  on critical modules.
- **Slow tests in the inner loop.** A test
  suite that takes 30 minutes to run is a
  contract violation. The inner loop is <
  5 minutes.
- **Flaky tests.** A test that fails 1% of the
  time is a contract violation. The test is
  fixed or deleted.
- **"We'll add tests later".** A PR that
  ships without tests is a contract violation.
- **Tests that don't run.** A test that is
  `@Ignore`d without a follow-up ticket is a
  contract violation.
- **Tests that test the implementation.** A
  test that breaks on every refactor is a
  contract violation. The test tests the
  behaviour, not the implementation.
- **Bypassing the gates.** A release that
  bypasses the gates is a contract violation.
  The orchestrator does not merge a release
  with a failing gate.
- **`throw Exception("oops")` in production
  code.** Every error path is a typed
  `FoundryError` (per `.ai/AGENTS.md`
  section 10 and `.ai/STANDARDS.md` section
  7). A free-form string throw is a CI
  failure.
- **Free-form string errors at the API
  boundary.** Every error is a typed JSON
  envelope. A string-only error is a CI
  failure.
- **Generic catch blocks that hide failures.**
  Every catch block re-throws, logs with a
  typed error, or returns a typed `Result` /
  `Either`. A `catch (e: Exception) { /* ignore */ }`
  is a CI failure.
- **Float / Double for money.** A
  `Double` / `Float` / `f64` for a royalty
  amount, a settlement, a price, or a
  balance is a CI failure. Money is
  `BigDecimal` (or equivalent) — see
  `.ai/AGENTS.md` section 5.2 and
  `.ai/STANDARDS.md` section 2.2.
- **Unchecked null assertions.** A `!!` in
  Kotlin, an unchecked `as` in TypeScript,
  an `unwrap()` / `expect` in production
  Rust, a hard `Object!` cast in C# is a
  CI failure.
- **Main-thread blocking on Android.** A
  model load, a decode, or a network call on
  the Android main thread is a CI failure
  (per `.ai/AGENTS.md` section 5.4).
- **Trusting imported 3D assets without
  validation.** An unvalidated asset is a CI
  failure (per `.ai/AGENTS.md` section 5.5
  and skill 06).
- **Executing scripts embedded in uploaded
  assets.** A glTF with a script, a STEP
  with macros, a USD with a custom schema
  that runs code: rejected at the parse
  step. The pipeline never executes user-
  supplied code.
- **AI-claimed road legality, safety
  approval, mechanical compatibility, or
  financial settlement.** A model that
  directly mutates any of these is a CI
  failure. The model is a draft; the
  deterministic engine + a human review
  apply the draft (per `.ai/AGENTS.md`
  section 5.6 and section 8, and
  `.ai/STANDARDS.md` section 2.6 and
  section 5).
- **A `Vehicle` without a
  `VehicleRepresentationLevel`.** A vehicle
  card, vehicle detail page, or spec view
  without the level is a UI test failure
  (per `.ai/AGENTS.md` section 7 and
  `.ai/STANDARDS.md` section 4).
- **A `VehicleRepresentationLevel`
  regression.** A vehicle that moves from
  `OEM_EXACT` to `VISUAL_ONLY` is a CI
  failure. The transition is append-only.
- **An `AI_INFERRED → VERIFIED` transition
  without a signed event in the audit
  trail.** A silent transition is a CI
  failure.

## 11. Test strategy in the Elysium Automotive
Foundry

The test strategy is layered:

- **Unit tests** — per function, per module.
  Fast (< 1ms per test). The inner loop.
- **Integration tests** — per skill. The skill's
  public API is exercised against the production
  collaborators (or test doubles if the
  collaborator is external).
- **Contract tests** — per cross-skill contract.
  The contract is the API; the contract test
  pins the API.
- **Fuzz tests** — per input surface. The CI
  runs them for a fixed time budget (1 hour
  per release for the critical surfaces).
- **Property tests** — per invariant. The
  property tests are slow; they run on the
  merge queue, not on every PR.
- **Mutation tests** — per critical module.
  The critical modules are: skill 03, skill
  04, skill 06, skill 07, skill 08, skill 09,
  skill 12, skill 13.

The test strategy is the platform's "we know
the platform works" answer.

## 12. Working with this skill

When invoked, this skill:

1. Receives the input (PR, release).
2. Runs the suite.
3. Computes the gates.
4. Files the verification report.
5. Returns the gate status to the orchestrator.

The skill does not implement product code. The
skill does not implement the CI. The skill is
the **verifier** the orchestrator runs.
