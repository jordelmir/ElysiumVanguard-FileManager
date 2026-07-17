---
name: repository-archaeology
description: Read a code base before touching it. Produces a dependency map, a risk map, and a baseline test report. The first skill invoked on any new repository.
---

# Skill 01 — Repository Archaeology

## 1. Mission

Understand a code base **before** any other skill
touches it. Produce a structured inventory of the
existing architecture, the dependency surface, the
risk hotspots, and the baseline test report. The
output is the input to every other skill's first
decision.

## 2. In-scope

- Inventorying the file structure, the languages,
  the frameworks, the build system.
- Mapping the dependency graph (direct + transitive).
- Identifying the test surface and the baseline
  green state.
- Surfacing the risk hotspots (modules with high
  complexity, low coverage, recent churn, or
  known issues).
- Producing a domain vocabulary (the existing
  names for the existing concepts — do not
  introduce synonyms).
- Producing the **first** ADR for the repository
  (an "architecture baseline" ADR).

## 3. Out-of-scope

- Refactoring. Archaeology is read-only. The
  refactor is a separate skill invocation.
- Writing code. Never.
- Writing tests. Skill 14.
- Making architectural decisions beyond "this is
  what the architecture is". The orchestrator
  makes the decisions; this skill surfaces the
  facts.

## 4. Inputs

- A repository (path, URL, or container image).
- A user's stated goal (one sentence: "I want to
  add X" / "I want to migrate Y" / "I want to
  understand Z").
- Optional: time budget. The user may say "I have
  30 minutes" — archaeology then prioritizes the
  critical 20% of the code base.

## 5. Outputs

All outputs land in `.ai/archaeology/<repo-id>/`:

- `STRUCTURE.md` — the file tree (depth 3 by
  default), annotated with the language / role
  of each top-level directory.
- `DEPENDENCIES.md` — the direct dependency
  manifest (extracted from `package.json`,
  `pom.xml`, `build.gradle.kts`, `Cargo.toml`,
  `go.mod`, etc.) with license + version + CVE
  history per dependency.
- `TESTS.md` — the test inventory, with the
  baseline test result (last green run, or a
  fresh `cargo test` / `go test` / `mvn test` /
  `gradle test`).
- `DOMAIN.md` — the domain vocabulary. The
  existing names for the existing concepts. The
  modules the codebase calls "Customer",
  "Vehicle", "Trip", etc.
- `RISKS.md` — the risk map. Modules with high
  complexity (cyclomatic), low coverage, recent
  churn (git log), known issues (TODO / FIXME /
  HACK comments), or single-owner code.
- `ADR-0000-baseline.md` — the first ADR. It
  describes the architecture as it stands. Future
  ADRs reference this one.
- `summary.json` — machine-readable summary the
  orchestrator can index. Schema:
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

## 6. Workflow

1. **Bootstrap.** Run the repo's standard "show
   me the structure" command. For a Git repo:
   `git log --oneline -1` (latest commit), `ls -la`
   (root), `find . -maxdepth 2 -type d` (depth-2
   tree). For a container: `uname -a`, `df -h`.
2. **Identify the build system.** `package.json`,
   `build.gradle.kts`, `pom.xml`, `go.mod`, etc.
   Open it. Note the language, the version, the
   major plugins / dependencies.
3. **Identify the test system.** Look for
   `test/`, `*_test.go`, `src/test`, `androidTest`.
   Run the test command once and record the
   baseline (green / red / skipped).
4. **Map the modules.** List the top-level
   directories. For each: a one-line "what does
   this module do". A module is a directory that
   can be reasoned about independently.
5. **Map the dependencies.** Run the dependency
   resolver. For each direct dep: record
   name, version, license, last-commit-date,
   known-CVE-list.
6. **Build the domain vocabulary.** Open 5–10
   core files. Extract the noun phrases. These
   are the domain entities. Record them with
   the file that defines each.
7. **Find the risk hotspots.** For each module:
   - Lines of code (`cloc` or `tokei`).
   - Cyclomatic complexity (`lizard` or
     equivalent).
   - Test coverage (`coverage.py` or language
     equivalent).
   - Recent churn (`git log --since=6.months
     --pretty=format: --name-only`).
   - TODO / FIXME / HACK comments.
8. **File the baseline ADR.** `ADR-0000-baseline.md`
   with: the language(s), the build system, the
   test system, the module map, the dependency
   map, the domain vocabulary, the risk map.
9. **Emit the summary.** `summary.json` for
   programmatic consumption.

## 7. Quality gates

- All output files exist in
  `.ai/archaeology/<repo-id>/`.
- `summary.json` is valid JSON.
- `summary.json.test_count` matches a fresh test
  run (the baseline is reproducible).
- `summary.json.coverage_pct` matches the coverage
  tool's output.
- Every dependency in `DEPENDENCIES.md` has a
  license + version + last-commit-date. A
  missing field is a quality-gate failure.
- Every entry in `RISKS.md` has a "what could go
  wrong" + "how to detect early" + "how to
  mitigate". A bare list of "files I don't like"
  is a quality-gate failure.
- `DOMAIN.md` is sourced from the code (file +
  line number per term). A vocabulary invented
  from imagination is a quality-gate failure.

## 8. Failure modes

- **The repo doesn't build.** Record the failure
  in `RISKS.md`; do NOT silently skip. The
  orchestrator decides whether to invest in
  fixing the build.
- **The repo has no tests.** Record the absence;
  do NOT invent a "test count" of zero. The
  `test_count` field is nullable; null means
  "no tests, not zero tests".
- **The repo is private / not accessible.**
  Surface the access problem to the orchestrator
  immediately. Do NOT proceed with assumptions.
- **The repo is monorepo with N services.** Produce
  one `STRUCTURE.md` per service. The
  `summary.json` becomes a top-level file with
  one entry per service.

## 9. Coordination contract

- **Input from**: skill 00 (orchestrator), user.
- **Output to**: skill 00 (orchestrator), every
  other skill that consumes the repo.
- **Triggered by**: any first-time interaction
  with a repo, or a major restructure (the
  orchestrator decides).
- **Frequency**: once per repo (cached in
  `.ai/archaeology/`). Re-run only when the
  orchestrator requests an update.

## 10. Forbidden patterns

- **Inventing a domain vocabulary that does not
  exist in the code.** The vocabulary is sourced;
  it is not generated.
- **Skipping the dependency map because "the
  repo is small".** There is no size threshold.
  The map is always produced.
- **Skipping the risk map because "the code
  looks fine".** A repository without risks is
  a fiction. There are always risks; this skill
  surfaces them.
- **Touching code.** Archaeology is read-only.
  Any write is a violation and a contract
  failure.
- **Faking test results.** If the tests fail,
  the baseline is red. The orchestrator decides
  what to do; this skill does not hide red
  tests.
- **Treating undocumented code as documented.**
  A function with no docstring is undocumented,
  not "implicitly documented by the function
  name".
- **Skipping the ADR.** The baseline ADR is a
  hard contract. Every subsequent ADR
  references it. Skipping it is a contract
  failure.

## 11. Anti-patterns in the wild (so this skill
can recognize them)

- **The "we don't need tests for this" repo.**
  Flag it. Tests are not optional.
- **The "we have one giant module" repo.** Flag
  it. Modular monolith or microservices, not
  a single 200k-line module.
- **The "we'll document it later" repo.** Flag
  it. Now is the time, or it never is.
- **The "we use a new framework every six months"
  repo.** Flag it. Volatile stack = brittle
  product.
- **The "everything is a singleton" repo.**
  Flag it. Testable code is not singleton.
- **The "we have 12 different serialization
  formats" repo.** Flag it. Pick one (or two,
  with a documented boundary).
- **The "we wrote our own auth / crypto /
  database" repo.** Flag it. Use the standard
  library; do not roll your own.

## 12. Working with this skill

When the orchestrator (or a user) invokes
archaeology on a repo, this skill:

1. Asks for (or reads) a time budget. If none is
   given, defaults to "as long as it takes to
   produce a complete inventory".
2. Runs the workflow in section 6.
3. Emits the outputs in section 5.
4. Files the baseline ADR.
5. Reports back to the orchestrator with the
   summary + a list of "first things to read" for
   the next skill that will touch the repo.

This skill does **not** implement features. It
produces the map other skills navigate by.
