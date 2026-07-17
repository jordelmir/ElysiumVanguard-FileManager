---
name: vehicle-dsl-compiler
description: Implements the deterministic Vehicle Definition Language, validation engine, compatibility solver and digital-twin compiler.
---

# Skill 04 — Vehicle DSL and Compiler

## 1. Mission

Convert structured vehicle definitions
into **deterministic, versioned vehicle
graphs and scene manifests**.

**The LLM may produce a DSL proposal.
Only this compiler determines whether the
configuration is valid.** The model is a
draft; the compiler is the law (per
`.ai/AGENTS.md` section 8 +
`.ai/STANDARDS.md` section 5).

The DSL is the user-facing surface for
"build me a vehicle". The compiler is the
bridge to the ontology (skill 03). The
output is a typed, content-addressed,
signed `Spec.Artifact` that every
downstream skill (06, 07, 09, 10, 11)
consumes.

## 2. In-scope

- Defining the DSL syntax (text +
  visual + AI-translated).
- The **18-step compiler pipeline** (per
  section 6).
- The **determinism contract** (per
  section 7).
- The golden test suite (per
  section 9).
- The editor support (per section 10).
- The compatibility solver (per
  section 11).
- The constraint engine (per
  section 12).
- The scene-manifest generator (per
  section 13).
- The compilation report (per
  section 14).
- The artifact hash (per section 15).

## 3. Out-of-scope

- The 3D model itself (skill 06).
- The digital twin (skill 07).
- The royalty (skill 09).
- The marketplace (skill 10).
- The mobile UX (skill 11).
- The AI behaviour (skill 05).
- The ontology itself (skill 03).

The DSL says "this `Powertrain` HAS-A
`BatteryPack` with capacity 75 kWh". The
3D pipeline says "the battery pack is a
2m × 1m × 0.3m prismatic cell with 21700
cells". The digital twin says "the
battery pack faults on cell voltage
imbalance". Each is its own concern.

## 4. Inputs

- The user input (text, voice, or
  visual graph; or a typed proposal
  from skill 05).
- The ontology (skill 03) — the types
  the DSL references.
- The available parts catalog (a
  domain query against skill 10).
- The user's brand + project context
  (a `BrandId` + a `ProjectId`).
- The canonical units (per skill 03
  section 16).
- The alias tables (per skill 03
  section 17).
- The applicability rules (per skill
  03 section 18).

## 5. Outputs

- **`Spec.Artifact`** — the typed,
  content-addressed, signed output.
  The artifact contract (per
  `.ai/AGENTS.md` section 12)
  applies.
- **`CompilationReport`** — the
  errors, warnings, info notes.
- **`SourceMap`** — DSL line → ontology
  entity.
- **`DIFF`** — the diff between two
  versions of the same spec.
- **`SceneManifest`** — the typed
  output for skill 06 + skill 07.

## 6. Compiler pipeline

The compiler is an 18-step pipeline.
Every step is deterministic. Every
step's input is the previous step's
output. A step that fails halts the
pipeline + emits a typed
`FoundryError`.

1. **Lexing or structured decoding.**
   The DSL text is lexed into tokens;
   the structured input (JSON / YAML
   / Protobuf) is decoded.
2. **Parsing.** The tokens / structure
   are parsed into a syntax tree.
   Errors are syntax errors with a
   precise location.
3. **Schema validation.** The syntax
   tree is validated against the
   schema. Errors are schema errors.
4. **Unit normalization.** Every
   value with a unit is normalized
   to the canonical SI unit (per
   skill 03 section 16). A value
   without a unit is a typed
   `VehicleDefinitionInvalid` error.
5. **Alias resolution.** Every alias
   is resolved to a canonical name
   (per skill 03 section 17). An
   ambiguous alias is returned to
   the user with a list of
   candidates + confidence.
6. **Applicability resolution.**
   Every `PartDefinition` is checked
   against the applicability rules
   (per skill 03 section 18). An
   inapplicable part is a typed
   `CompatibilityConstraintViolation`
   error.
7. **Dependency expansion.** Every
   `PartDefinition` dependency is
   expanded (a `BatteryPack` brings
   in the `BatteryManagementSystem`
   + the `ThermalInterface`).
8. **Compatibility checking.** Every
   pair of `PartInstance`s in the
   `VehicleDefinition` is checked
   for compatibility. An
   incompatible pair is a typed
   `CompatibilityConstraintViolation`
   error.
9. **Constraint solving.** Every
   constraint (a "battery must be
   sized to the motor's power
   draw") is solved. An
   unsatisfiable constraint is a
   typed `VehicleDefinitionInvalid`
   error.
10. **Part selection.** When the
    user has not selected a specific
    part (a "give me the cheapest
    battery that fits"), the
    compiler selects one from the
    catalog. The selection is
    deterministic; the same input
    produces the same selection.
11. **Assembly graph construction.**
    The `PartInstance`s are arranged
    into an `AssemblyInstance` graph
    (the powertrain + the chassis +
    the electrical + the electronics
    + the software).
12. **Interface binding.** The
    `InterfacePort`s of adjacent
    `PartInstance`s are bound (per
    skill 03 section 15). An
    incompatible binding is a typed
    `CompatibilityConstraintViolation`
    error.
13. **Collision and packaging
    prechecks.** The compiler runs
    a coarse geometric precheck (a
    bounding-box collision check) +
    a packaging precheck (the parts
    fit in the chassis envelope). A
    precheck failure is a typed
    `VehicleDefinitionInvalid` error.
14. **Diagnostic binding.** Every
    `DiagnosticTarget` is bound to
    a `FaultCode` + a `RepairAction`
    + a `DiagnosticProcedure` (per
    skill 03 section 13.4 +
    `CompatibilityConstraint`).
15. **BOM generation.** A
    `BillOfMaterials` is generated.
    The BOM lists every
    `PartInstance` + its quantity +
    its cost (in `BigDecimal`).
16. **Scene-manifest generation.**
    A typed `SceneManifest` is
    generated for skill 06 + skill
    07. The manifest lists every
    `GeometryAsset` + its LODs +
    its bounds + its previews.
17. **Compilation report.** A
    `CompilationReport` is emitted
    to the user. The report lists
    the per-step errors + warnings
    + info notes.
18. **Artifact hashing.** The
    `Spec.Artifact` is content-
    addressed (SHA-256) + signed
    (Ed25519) + versioned
    (semver).

## 7. Determinism

Given:

- **Identical `VehicleDefinition`.**
- **Identical catalog revision.**
- **Identical compiler version.**
- **Identical rule-set version.**

The compiler MUST produce:

- **Identical logical graph.**
- **Identical stable IDs.**
- **Identical canonical serialization.**
- **Identical content hash.**

**Do not** use random identifiers inside
deterministic compilation. **Derive**
stable identifiers from canonical inputs
(a hash of the canonical `PartDefinition`
+ the canonical `VehicleRevisionId`).

A non-deterministic compiler is a
contract violation; the verifier
(skill 14) rejects the build.

## 8. Example definition

```yaml
apiVersion: elysium.vehicle/v1
kind: VehicleDefinition

metadata:
  projectId: PROJECT-001
  revision: 1

classification:
  representationLevel: PARAMETRIC_FUNCTIONAL

body:
  architecture: SEDAN
  doors: 4
  seats: 5
  wheelbase:
    value: 2.45
    unit: METER

propulsion:
  energySource: GASOLINE
  engine:
    configuration: INLINE_4
    displacement:
      value: 1.6
      unit: LITER
    orientation: TRANSVERSE

driveline:
  traction: FWD
  transmission: AUTOMATIC
```

The example is incomplete; the full
grammar is in `docs/dsl/grammar.md`. The
example demonstrates:

- The `apiVersion` + the `kind` (the
  schema version + the document type).
- The `metadata` (the `projectId` + the
  `revision`).
- The `classification.representationLevel`
  (per `.ai/AGENTS.md` section 7 +
  `.ai/STANDARDS.md` section 4).
- The `body` (architecture + doors +
  seats + wheelbase with unit).
- The `propulsion` (energySource +
  engine with configuration +
  displacement with unit + orientation).
- The `driveline` (traction +
  transmission).

A future version of the example adds:

- **Electrical architecture** (12V /
  48V / 400V / 800V / zonal).
- **Network bus** (CAN / LIN /
  FlexRay / Automotive Ethernet).
- **Sensors + actuators** (the
  ADAS set).
- **Software components** (the SBOM).
- **Diagnostic bindings** (the
  fault codes + the repair actions).
- **Compatibility constraints** (the
  rules that the engine evaluates).
- **Aliases** (the locale-aware
  names for every concept).
- **Applicability** (the per-
  manufacturer / per-platform /
  per-model applicability).

## 9. Golden tests

The pipeline is testable end-to-end
with golden files:

- `tests/golden/<dsl-source>.in` —
  the DSL input.
- `tests/golden/<spec-output>.out` —
  the expected `Spec.Artifact` (the
  canonical serialization).
- `tests/golden/<errors>.err` — the
  expected `CompilationReport`.
- `tests/golden/<scene-manifest>.json`
  — the expected `SceneManifest`.
- `tests/golden/<bom>.csv` — the
  expected `BOM`.

A golden test asserts the compiler
output is byte-identical to the
expected output. A diff is a CI
failure.

A test that runs the pipeline twice
on the same input asserts the
output is identical. A diff is a
CI failure.

## 10. Editor support

The editor support (per
`.ai/AGENTS.md` section 11.2 +
skill 00) is in a separate package
(`dsl-editor/`) and does NOT pull in
the compiler as a hard dependency
(it consumes the parser's public
API). The editor provides:

- **Syntax highlighting.**
- **Autocomplete** (the parser's
  grammar + the ontology's types).
- **Go-to-definition** (the
  `SourceMap`).
- **Hover docs** (the ontology's
  docs).
- **Live validation** (the schema
  validator runs on every
  keystroke).

## 11. Compatibility solver

The compatibility solver is the
component that evaluates the
`CompatibilityConstraint`s (per
skill 03 section 13.4). The solver
is:

- **Sound.** A pair that the solver
  marks as compatible is
  mechanically compatible.
- **Complete** (up to a documented
  timeout). A pair that the solver
  cannot evaluate returns a
  `CompatibilityConstraintViolation`
  with a "the solver timed out"
  reason.
- **Auditable.** The solver's
  decision is a typed result that
  records the constraint + the
  inputs + the result. The audit
  trail (skill 09) records the
  decision.

The solver MUST NOT use the 3D
viewer as a mechanical-compatibility
oracle (per `.ai/STANDARDS.md`
section 2.1). A mesh that visually
matches is not mechanically
compatible.

## 12. Constraint engine

The constraint engine is the
component that evaluates the
`CompatibilityConstraint`s (per
skill 03) + the project-level
constraints (the PRD's `MUST`
requirements, per skill 02). The
engine is:

- **Declarative.** A constraint is
  a typed expression; the engine
  evaluates the expression.
- **Deterministic.** A constraint
  that evaluates to `true` on the
  same input always evaluates to
  `true`.
- **Typed.** A constraint violation
  is a typed
  `CompatibilityConstraintViolation`
  error (per `.ai/STANDARDS.md`
  section 7).

## 13. Scene-manifest generator

The scene-manifest generator is the
component that produces the typed
`SceneManifest` for skill 06 +
skill 07. The manifest lists every
`GeometryAsset` + its LODs + its
bounds + its previews + its
transform.

The manifest is the **only** thing
the 3D pipeline (skill 06) reads
from the DSL. The DSL does not
produce 3D data directly.

## 14. Compilation report

The `CompilationReport` is the
user-facing report. The report
lists:

- **Errors** (the per-step errors;
  the `Spec.Artifact` is NOT
  emitted when there is an error).
- **Warnings** (the per-step
  warnings; the `Spec.Artifact` IS
  emitted but flagged).
- **Info notes** (the per-step
  info; the `Spec.Artifact` is
  emitted).

The report is in the user's locale
(skill 11's i18n bundle).

## 15. Artifact hashing

The `Spec.Artifact` is:

- **Content-addressed.** The ID is
  the SHA-256 of the canonical
  serialization.
- **Signed.** Ed25519 by the
  producing agent.
- **Versioned.** Semver; a breaking
  change is a major bump.
- **Append-only.** A new version
  is a new artifact; the old
  artifact is preserved.

The artifact contract (per
`.ai/AGENTS.md` section 12) is
enforced by skill 14 (quality).

## 16. Workflow

1. **Receive the request.** From
   the user or from skill 05.
2. **Parse.** Per pipeline step 2.
3. **Resolve.** Per pipeline step 3.
4. **Type-check.** Per pipeline
   step 4 + step 5 + step 6.
5. **Compile.** Per pipeline steps
   7-18.
6. **Emit the spec.** The
   `Spec.Artifact` + the
   `CompilationReport` + the
   `SourceMap` + the `SceneManifest`.
7. **Archive.** The DSL source, the
   syntax tree, the report, the
   source map land in
   `.ai/dsl/<id>/`. The artifact
   lands in the catalog (skill 09).

## 17. Quality gates

- The parser is total (no panics;
  the worst case is a syntax error
  with a location).
- The resolver is total (no panics;
  the worst case is an "unknown
  identifier" with a suggestion
  list).
- The type-checker is total (no
  panics; the worst case is an
  invariant violation with a
  precise location).
- The compiler is deterministic
  (per section 7).
- The compiler's golden tests pass.
- The `Spec.Artifact` is content-
  addressed + signed + versioned
  (per `.ai/AGENTS.md` section 12).
- The 18-step pipeline is testable
  end-to-end.
- A `vehicle` without a
  `representationLevel` is
  rejected (per `.ai/STANDARDS.md`
  section 4).
- A raw scalar where a fact is
  required is rejected (per
  `.ai/AGENTS.md` section 7).
- An `oem-exact` spec with an
  `AI_INFERRED` fact is rejected
  (per `.ai/STANDARDS.md` section
  3.2).

## 18. Failure modes

- **The user's input is malformed.**
  A `CompilationReport` with a
  precise error location + a
  suggestion.
- **The user references a part not
  in the catalog.** A
  `CompilationReport` with the
  unknown part + a link to the
  marketplace.
- **The user's spec violates an
  invariant.** A `CompilationReport`
  with the violation + a link to
  the relevant ADR.
- **The DSL version is bumped but
  the user's existing specs are
  not migrated.** A migration tool
  is published. A migration that
  cannot be done automatically is
  a user-blocking escalation.
- **The compiler produces a
  non-deterministic output.** The
  CI fails. The compiler is fixed
  before any further release.

## 19. Coordination contract

- **Input from**: the user, skill
  05 (AI council).
- **Output to**: skill 06 (3D),
  skill 07 (digital twin), skill
  09 (catalog), skill 10
  (marketplace), skill 11 (mobile).
- **Triggered by**: every "build me
  a vehicle" / "edit this spec" /
  "diff these two specs" request.
- **Frequency**: per spec, per
  revision.

## 20. Forbidden patterns

- **Two DSLs for the same concept.**
  A "visual DSL" + a "text DSL" + a
  "JSON spec" + a "YAML spec" is
  four ways to say the same thing.
  The DSL is one language with N
  surfaces.
- **Untyped spec output.** A
  `Spec.Artifact` is a typed value,
  not a `Map<String, Any>`. Every
  field has a type.
- **Silent lossy migrations.** A
  migration that loses information
  is a user-blocking issue.
- **Custom serialization in the
  compiler.** The spec is
  serialized via the standard
  schema registry (skill 09).
- **DSL strings as identifiers.** A
  `Part` referenced by `name` is a
  smell. The DSL uses typed IDs.
- **Non-deterministic compilation.**
  A `UUID.randomUUID()` inside the
  compiler is a contract violation.
  The IDs are derived from canonical
  inputs.
- **A `vehicle` without a
  `representationLevel`.** A
  missing level is a compile error.
- **A raw scalar where a fact is
  required.** A
  `part.capacity = 75` is rejected
  (per `.ai/STANDARDS.md` section
  3).
- **AI-injected OEM-bound facts
  without human review.** An
  `AI_INFERRED` fact in an
  `oem-exact` spec is rejected.

## 21. Working with this skill

When invoked, this skill:

1. Reads the user's input + the
   ontology + the parts catalog.
2. Parses + resolves + type-checks.
3. Compiles (per the 18-step
   pipeline).
4. Emits the spec artifact + the
   report + the source map + the
   scene manifest.
5. Returns the artifact ID to the
   orchestrator (the orchestrator
   hands it to skill 06 + 07).

This skill does **not** render the
spec in 3D (skill 06) or simulate
it (skill 07). The skill produces
the typed spec; the next skills
produce the visual + the simulation.

## 22. Cross-references

- **Ontology (skill 03):**
  `.ai/skills/03-vehicle-domain-ontology/SKILL.md`.
- **AI authority boundary:**
  `.ai/AGENTS.md` section 8 +
  `.ai/STANDARDS.md` section 5.
- **Artifact contract:**
  `.ai/AGENTS.md` section 12.
- **Required error model:**
  `.ai/AGENTS.md` section 10 +
  `.ai/STANDARDS.md` section 7.
- **PRD (skill 02):**
  `.ai/skills/02-product-requirements/SKILL.md`.
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
- **Quality (skill 14):**
  `.ai/skills/14-quality-verification/SKILL.md`.
