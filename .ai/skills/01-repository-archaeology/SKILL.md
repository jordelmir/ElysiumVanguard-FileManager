---
name: repository-archaeology
description: Performs forensic analysis of the existing Elysium/MEET codebase before architectural changes.
---

# Skill 01 — Repository Archaeology

## 1. Mission

Understand the **real** implementation
before proposing changes. Reuse valid
functionality and eliminate duplication
without damaging existing behavior.

This skill is the **first skill invoked** on
any new repository. Its output is the
input to every other skill's first decision.
The output is a structured inventory of
the existing architecture, the dependency
surface, the risk hotspots, the
performance bottlenecks, the security
defects, the data-model conflicts, and the
baseline test report.

## 2. Required analysis

Archaeology inspects the following. Every
item is a non-skippable section of the
output report. A "we don't know" is itself
a finding that goes into the risk register.

- **Project modules and dependency graph.**
- **Gradle, npm, Cargo and backend build
  definitions.**
- **Application entry points.**
- **Compose navigation.**
- **3D rendering layer.**
- **Asset loading and caching.**
- **Vehicle and part data models.**
- **Diagnostic and DTC data.**
- **OBD transport abstractions.**
- **AI provider integrations.**
- **Authentication and authorization.**
- **Supabase / PostgreSQL schema.**
- **Storage buckets.**
- **Ktor or Spring services.**
- **Background jobs.**
- **CI / CD.**
- **Secrets handling.**
- **Testing infrastructure.**

## 3. Required searches

Find **every** representation of:

- `Vehicle`.
- `Car`.
- `Engine`.
- `Part`.
- `Component`.
- `Node`.
- `Diagnostic`.
- `DTC`.
- `Procedure`.
- `User`.
- `Project`.
- `Revision`.
- `Asset`.
- `Contract`.
- `Payment`.

Construct a **duplication matrix**. The
matrix has:

| Concept | Existing classes | Canonical candidate | Migration needed |
|---|---|---|---|
| Vehicle | `VehicleDto`, `CarModel`, `GarageVehicle` | `VehicleProfile` | Yes |
| Component | `PartNode`, `Component3D` | `PartDefinition` / `PartInstance` | Yes |
| ... | ... | ... | ... |

A concept that has **more than one
representation** is a candidate for
consolidation. The matrix is the input to
the orchestrator's `domain-ownership.md`
decision (per
`docs/foundry/domain-ownership.md`). A
concept that has **no representation** is a
candidate for the ontology (skill 03).

## 4. Performance inspection

Identify:

- **Main-thread I/O.** File / network /
  database on the Android main thread.
- **Excessive Compose recompositions.**
  Composable functions that re-execute
  unnecessarily.
- **Unbounded coroutine scopes.** Scopes
  that outlive the lifecycle owner.
- **Bitmap or texture leaks.** `Bitmap`s
  or textures that are not recycled.
- **Repeated GLB decoding.** The same
  glTF is decoded more than once.
- **Non-cancelled flows.** Flows that
  are not cancelled when the lifecycle
  ends.
- **Unbounded caches.** Caches that
  grow without bound.
- **Database N+1 queries.** A query
  inside a loop.
- **Missing indexes.** Queries that
  scan the table.
- **Large JSON loaded eagerly.** JSON
  files that should be streamed.
- **Duplicate network requests.** The
  same request sent more than once.
- **Unsafe concurrent mutation.** Shared
  state mutated without synchronization.

## 5. Security inspection

Detect:

- **Embedded API keys.** A key in the
  code, the config, the assets, or the
  build artifacts.
- **Weak local token storage.** A
  token in `SharedPreferences` (use the
  Keystore).
- **Exported Android components.** An
  exported `Activity` / `Service` /
  `Receiver` / `Provider` without
  proper auth.
- **Unsafe deep links.** A deep link
  that allows arbitrary input.
- **WebView exposure.** A `WebView`
  with `setJavaScriptEnabled(true)` +
  untrusted content.
- **SQL injection.** A query built by
  string concatenation.
- **Missing RLS.** A Supabase table
  without row-level security.
- **Broad storage policies.** An S3
  bucket that is world-readable.
- **Unsigned asset downloads.** A 3D
  asset downloaded without signature
  verification.
- **Unvalidated file uploads.** A
  file upload without content-type +
  size + magic-byte validation.
- **Path traversal.** A path built
  from user input without sanitization.
- **Insecure deserialization.** A
  `JSON.parse` of untrusted data without
  a schema validator.
- **Log leakage.** A secret / PII /
  token in a log line.
- **Debug endpoints.** A `/debug` /
  `/admin` endpoint in production.

A security defect is a **P0 incident** if
it is in a production-bound surface. The
archaeology report's security section is
the input to skill 12's threat model.

## 6. Output

Produce findings grouped by:

- **Critical blocker.** A defect that
  blocks production deployment.
- **Architectural debt.** A pattern that
  works but does not scale + does not
  honor the global contract.
- **Security defect.** Per the security
  inspection list.
- **Performance defect.** Per the
  performance inspection list.
- **Data-model conflict.** Two or more
  representations of the same concept
  (per the duplication matrix).
- **Reusable capability.** A module +
  an interface + a contract that the
  Foundry can inherit.
- **Dead code.** Code that has not been
  touched in 180+ days + dependencies
  that are unmaintained.
- **Recommended migration.** The bridge
  from the current state to the target
  state (per `docs/foundry/target-architecture.md`).

The output is in `.ai/archaeology/<repo-id>/`:

- `STRUCTURE.md` — the file tree (depth 3
  by default), annotated with the
  language / role of each top-level
  directory.
- `DEPENDENCIES.md` — the direct
  dependency manifest (extracted from
  `package.json`, `pom.xml`,
  `build.gradle.kts`, `Cargo.toml`,
  `go.mod`, etc.) with license + version
  + CVE history per dependency.
- `TESTS.md` — the test inventory, with
  the baseline test result (last green
  run, or a fresh `cargo test` /
  `go test` / `mvn test` /
  `gradle test`).
- `DOMAIN.md` — the domain vocabulary.
  The existing names for the existing
  concepts. The modules the codebase
  calls `Customer`, `Vehicle`, `Trip`,
  etc.
- `DUPLICATION.md` — the duplication
  matrix (per section 3).
- `PERFORMANCE.md` — the performance
  inspection (per section 4).
- `SECURITY.md` — the security
  inspection (per section 5).
- `RISKS.md` — the risk map. Modules
  with high complexity (cyclomatic),
  low coverage, recent churn (git log),
  known issues (TODO / FIXME / HACK
  comments), or single-owner code.
- `MIGRATION.md` — the recommended
  migration bridges.
- `ADR-0000-baseline.md` — the first
  ADR. It describes the architecture
  as it stands. Future ADRs reference
  this one.
- `summary.json` — machine-readable
  summary the orchestrator can index.
  Schema:
  ```json
  {
    "repo_id": "...",
    "languages": ["..."],
    "module_count": 0,
    "test_count": 0,
    "coverage_pct": 0.0,
    "primary_risk": "...",
    "verified_at": "ISO-8601"
  }
  ```

## 7. Workflow

1. **Bootstrap.** Run the repo's standard
   "show me the structure" command. For a
   Git repo: `git log --oneline -1` (latest
   commit), `ls -la` (root), `find . -maxdepth 2 -type d` (depth-2 tree).
   For a container: `uname -a`, `df -h`.
2. **Identify the build system.**
   `package.json`, `build.gradle.kts`,
   `pom.xml`, `go.mod`, etc. Open it. Note
   the language, the version, the major
   plugins / dependencies.
3. **Identify the test system.** Look for
   `test/`, `*_test.go`, `src/test`,
   `androidTest`. Run the test command
   once and record the baseline (green /
   red / skipped).
4. **Map the modules.** List the top-level
   directories. For each: a one-line "what
   does this module do". A module is a
   directory that can be reasoned about
   independently.
5. **Map the dependencies.** Run the
   dependency resolver. For each direct
   dep: record name, version, license,
   last-commit-date, known-CVE-list.
6. **Build the domain vocabulary.** Open
   5–10 core files. Extract the noun
   phrases. These are the domain entities.
   Record them with the file that defines
   each.
7. **Construct the duplication matrix.**
   Per section 3, find every
   representation of every concept. The
   matrix is the input to the
   orchestrator's domain-ownership
   decision.
8. **Performance inspection.** Per section
   4, identify the bottlenecks. The
   inspection is the input to skill 15's
   performance baseline.
9. **Security inspection.** Per section 5,
   detect the defects. The inspection is
   the input to skill 12's threat model.
10. **Find the risk hotspots.** For each
    module:
    - Lines of code (`cloc` or `tokei`).
    - Cyclomatic complexity (`lizard` or
      equivalent).
    - Test coverage (`coverage.py` or
      language equivalent).
    - Recent churn (`git log --since=6.months --pretty=format: --name-only`).
    - TODO / FIXME / HACK comments.
11. **File the baseline ADR.**
    `ADR-0000-baseline.md` with: the
    language(s), the build system, the
    test system, the module map, the
    dependency map, the domain
    vocabulary, the duplication matrix,
    the risk map.
12. **Emit the summary.** `summary.json`
    for programmatic consumption.

## 8. Definition of done

The report is complete when it answers:

- **What is the architecture?** (the
  STRUCTURE + the dependency map + the
  domain vocabulary + the duplication
  matrix)
- **What is the baseline?** (the test
  count + the coverage + the build
  status + the green/red/skipped)
- **What is broken?** (the critical
  blockers + the security defects + the
  performance defects)
- **What is reusable?** (the modules +
  the interfaces + the contracts the
  Foundry can inherit)
- **What is dead?** (the abandoned code
  + the unmaintained dependencies +
  the duplicate concepts)
- **What is the migration?** (the bridges
  from the current state to the target
  state)

A report that does not answer all six
questions is **incomplete**. The
orchestrator does not advance past G0
until the report is complete.

A finding without a "what could go wrong"
+ a "how to detect early" + a "how to
mitigate" is **not a finding** — it is a
complaint. The report is rejected.

A duplicated concept without a canonical
candidate is **not analyzed** — it is
listed. The report is rejected.

A security defect without a severity +
a P-level + an owner is **not a defect**
— it is a note. The report is rejected.

## 9. Quality gates

- All output files exist in
  `.ai/archaeology/<repo-id>/`.
- `summary.json` is valid JSON.
- `summary.json.test_count` matches a
  fresh test run (the baseline is
  reproducible).
- `summary.json.coverage_pct` matches the
  coverage tool's output.
- Every dependency in `DEPENDENCIES.md`
  has a license + version + last-commit-
  date. A missing field is a quality-gate
  failure.
- Every entry in `RISKS.md` has a "what
  could go wrong" + a "how to detect
  early" + a "how to mitigate". A bare
  list of "files I don't like" is a
  quality-gate failure.
- `DOMAIN.md` is sourced from the code
  (file + line number per term). A
  vocabulary invented from imagination is
  a quality-gate failure.
- The duplication matrix has at least
  one row per concept in section 3. A
  missing concept is a quality-gate
  failure.
- The performance inspection has at least
  one finding per item in section 4. A
  "we didn't look" is a quality-gate
  failure.
- The security inspection has at least
  one finding per item in section 5. A
  "we didn't look" is a quality-gate
  failure.

## 10. Failure modes

- **The repo doesn't build.** Record the
  failure in `RISKS.md`; do NOT silently
  skip. The orchestrator decides whether
  to invest in fixing the build.
- **The repo has no tests.** Record the
  absence; do NOT invent a "test count"
  of zero. The `test_count` field is
  nullable; null means "no tests, not
  zero tests".
- **The repo is private / not accessible.**
  Surface the access problem to the
  orchestrator immediately. Do NOT proceed
  with assumptions.
- **The repo is monorepo with N services.**
  Produce one `STRUCTURE.md` per service.
  The `summary.json` becomes a top-level
  file with one entry per service.
- **A security defect is found in a
  production-bound surface.** The defect
  is escalated to skill 12 + the user
  immediately. The archaeology continues,
  but the defect is filed as a P0
  incident in `docs/foundry/risk-register.md`.

## 11. Coordination contract

- **Input from**: skill 00 (orchestrator),
  user.
- **Output to**: skill 00 (orchestrator),
  every other skill that consumes the
  repo.
- **Triggered by**: any first-time
  interaction with a repo, or a major
  restructure (the orchestrator decides).
- **Frequency**: once per repo (cached in
  `.ai/archaeology/`). Re-run only when
  the orchestrator requests an update.

## 12. Forbidden patterns

- **Inventing a domain vocabulary that
  does not exist in the code.** The
  vocabulary is sourced; it is not
  generated.
- **Skipping the dependency map because
  "the repo is small".** There is no
  size threshold. The map is always
  produced.
- **Skipping the risk map because "the
  code looks fine".** A repository
  without risks is a fiction. There are
  always risks; this skill surfaces them.
- **Touching code.** Archaeology is
  read-only. Any write is a violation and
  a contract failure.
- **Faking test results.** If the tests
  fail, the baseline is red. The
  orchestrator decides what to do; this
  skill does not hide red tests.
- **Treating undocumented code as
  documented.** A function with no
  docstring is undocumented, not
  "implicitly documented by the function
  name".
- **Skipping the ADR.** The baseline ADR
  is a hard contract. Every subsequent
  ADR references it. Skipping it is a
  contract failure.
- **Allowing two representations of the
  same concept to coexist in the
  output.** The duplication matrix MUST
  identify a canonical candidate. A
  matrix without a canonical candidate
  is rejected.
- **Hiding a security defect.** A
  security defect found in archaeology is
  filed + escalated. A "we'll fix it
  later" is a contract violation.

## 13. Anti-patterns in the wild (so this
skill can recognize them)

- **The "we don't need tests for this"
  repo.** Flag it. Tests are not
  optional.
- **The "we have one giant module" repo.**
  Flag it. Modular monolith or
  microservices, not a single 200k-line
  module.
- **The "we'll document it later" repo.**
  Flag it. Now is the time, or it
  never is.
- **The "we use a new framework every
  six months" repo.** Flag it. Volatile
  stack = brittle product.
- **The "everything is a singleton"
  repo.** Flag it. Testable code is not
  singleton.
- **The "we have 12 different
  serialization formats" repo.** Flag it.
  Pick one (or two, with a documented
  boundary).
- **The "we wrote our own auth / crypto /
  database" repo.** Flag it. Use the
  standard library; do not roll your own.
- **The "the database is the schema"
  repo.** Flag it. The schema is in the
  code; the database is an implementation
  detail.
- **The "we have 5 versions of `User`"
  repo.** Flag it. The drift is a
  feature, not a bug, until it is.
- **The "we added a column without a
  migration" repo.** Flag it. The old
  rows are now wrong.
- **The "the foreign key is implicit"
  repo.** Flag it. A relation without a
  typed field is a bug waiting to happen.

## 14. Working with this skill

When the orchestrator (or a user) invokes
archaeology on a repo, this skill:

1. Asks for (or reads) a time budget. If
   none is given, defaults to "as long as
   it takes to produce a complete
   inventory".
2. Runs the workflow in section 7.
3. Emits the outputs in section 6.
4. Files the baseline ADR.
5. Reports back to the orchestrator with
   the summary + a list of "first things
   to read" for the next skill that will
   touch the repo.

This skill does **not** modify production
architecture. The recommendation is filed;
the orchestrator decides.

**Do not modify production architecture
until the report is complete.**

## 15. Cross-references

- **Project gate G0 (Repository
  understood):** `.ai/AGENTS.md` section
  22. The archaeology report is the
  evidence for G0.
- **Current-state audit (per
  `docs/foundry/current-state-audit.md`):**
  the archaeology report is the input to
  the audit.
- **Target architecture (per
  `docs/foundry/target-architecture.md`):**
  the archaeology's recommended migration
  is the input to the bridges + cut-overs
  + rollbacks.
- **Domain ownership (per
  `docs/foundry/domain-ownership.md`):**
  the duplication matrix is the input to
  the ownership decision.
- **Risk register (per
  `docs/foundry/risk-register.md`):** the
  archaeology's findings are the input to
  the per-finding risk entries.
- **Dependency map (per
  `docs/foundry/dependency-map.md`):**
  the dependency manifest is the input to
  the cross-skill edge table.
