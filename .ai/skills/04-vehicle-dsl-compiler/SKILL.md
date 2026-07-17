---
name: vehicle-dsl-compiler
description: The DSL for assembling vehicle architectures. The DSL takes a text or visual input and produces a type-checked spec the rest of the pipeline consumes. The DSL is the user-facing surface for "build me a vehicle"; the compiler is the bridge to the ontology.
---

# Skill 04 — Vehicle DSL Compiler

## 1. Mission

Define and maintain the **Domain-Specific Language**
(DSL) users use to assemble vehicle architectures.
The DSL takes a text or visual input and produces a
type-checked spec the rest of the pipeline
consumes (3D pipeline, digital twin, marketplace).

The DSL is the user-facing surface for "build me a
vehicle". The compiler is the bridge to the
ontology (skill 03). The user never types a
foreign-key by hand; the user types "add a 75 kWh
battery pack to the powertrain subsystem" and the
compiler translates that into a typed
`Powertrain.Subsystem(BatteryPack(75.kWh))`.

## 2. In-scope

- Defining the DSL syntax (text + visual AST).
- Parsing + lexing + type-checking the DSL.
- Resolving identifiers against the ontology
  (skill 03).
- Producing a typed spec artifact (the input to
  skill 06 + skill 07).
- Providing editor support: syntax highlighting,
  autocomplete, go-to-definition, hover docs.
- Maintaining the DSL version. The DSL is
  backward-compatible (or filed as a breaking
  change with a migration plan).
- Translating between DSL and natural language
  (in collaboration with skill 05, the AI
  council).

## 3. Out-of-scope

- The 3D model itself (skill 06).
- The diagnostic fault model (skill 07).
- The royalty calculation (skill 09).
- The AI behaviour (skill 05).

The DSL says "this `Powertrain` HAS-A `BatteryPack`
with capacity 75 kWh". The 3D pipeline says "the
battery pack is a 2m × 1m × 0.3m prismatic cell
with 21700 cells". The diagnostic pipeline says
"the battery pack faults on cell voltage
imbalance". Each is its own concern.

## 4. Inputs

- The user input (text, voice, or visual graph).
- The ontology (skill 03) — the types the DSL
  references.
- The available parts catalog (a domain query
  against the marketplace, skill 10).
- The user's brand + project context (a `BrandId`
  + a `ProjectId`).

## 5. Outputs

- `Spec.Artifact` (the typed spec) — content-
  addressed, versioned, signed. The artifact
  contract (see `AGENTS.md` section 6) applies.
- A `CompilationReport` (errors, warnings, info
  notes). The user sees the report; the runtime
  acts on the artifact.
- A `SourceMap` (DSL line → ontology entity) for
  the editor support.
- A `DIFF` artifact (the diff between two
  versions of the same spec).

The artifact is the **only** output the rest of
the pipeline consumes. The DSL source, the
syntax tree, the errors, the source map — all
internal to this skill.

## 6. Workflow

1. **Receive a request.** From the user
   (text/voice/visual) or from skill 05 (the AI
   council translating natural language).
2. **Parse.** Lex + parse the input into a
   syntax tree. Errors here are syntax errors
   with a precise location.
3. **Resolve.** Walk the syntax tree, look up
   every identifier against the ontology, look
   up every part reference against the parts
   catalog. Errors here are resolution errors
   (unknown part, ambiguous reference).
4. **Type-check.** Walk the resolved tree,
   enforce the invariants. Errors here are
   semantic errors (a 75 kWh battery pack on a
   motorcycle that requires 12V).
5. **Emit the spec.** Produce the typed
   `Spec.Artifact`. Content-address it. Sign it.
   The artifact is the input to skill 06 + 07.
6. **Emit the report.** The `CompilationReport`
   goes to the user.
7. **Emit the source map.** For the editor
   support.
8. **Archive.** The DSL source, the syntax
   tree, the report, the source map land in
   `.ai/dsl/<id>/`. The artifact lands in the
   catalog (skill 09).

## 7. Quality gates

- The parser is total (no panics on any input;
  the worst case is a syntax error with a
  location).
- The resolver is total (no panics on any
  identifier; the worst case is an
  "unknown identifier" error with a suggestion
  list).
- The type-checker is total (no panics on any
  spec; the worst case is an invariant
  violation with a precise location).
- The parser + resolver + type-checker are
  testable end-to-end with golden files
  (`tests/golden/<dsl-source>.in` +
  `<spec-output>.out` + `<errors>.err`).
- The artifact is content-addressed, versioned,
  signed (per the global artifact contract).
- The DSL grammar is documented in
  `docs/dsl/grammar.md` (LL(k) or PEG with a
  railroad diagram).
- The DSL editor support is in a separate
  package (`dsl-editor/`) and does NOT pull in
  the compiler as a hard dep (it consumes the
  parser's public API).

## 8. Failure modes

- **The user's input is malformed.** The skill
  emits a `CompilationReport` with a precise
  error location and a suggestion. The user
  fixes the input.
- **The user references a part that is not in
  the catalog.** The skill emits a
  `CompilationReport` with the unknown part
  + a link to the marketplace. The user can
  buy the part (skill 10) or substitute a
  similar one.
- **The user's spec violates an invariant.** The
  skill emits a `CompilationReport` with the
  violation + a link to the relevant ADR. The
  user can request an exception (an ADR) or
  fix the spec.
- **The DSL version is bumped but the user's
  existing specs are not migrated.** The skill
  publishes a migration tool. A migration that
  cannot be done automatically is a
  user-blocking escalation.

## 9. Coordination contract

- **Input from**: the user, skill 05 (the AI
  council).
- **Output to**: skill 06 (3D), skill 07
  (digital twin), skill 09 (catalog), skill 10
  (marketplace).
- **Triggered by**: every "build me a vehicle" /
  "edit this spec" / "diff these two specs"
  request.
- **Frequency**: per spec, per revision.

## 10. Forbidden patterns

- **Two DSLs for the same concept.** A "visual
  DSL" + a "text DSL" + a "JSON spec" + a
  "YAML spec" is four ways to say the same
  thing. The DSL is one language with N
  surfaces (text, visual, voice, AI-translated).
- **Untyped spec output.** A `Spec.Artifact` is
  a typed value, not a `Map<String, Any>`. Every
  field has a type. The compiler enforces the
  type.
- **Silent lossy migrations.** A DSL migration
  that loses information is a user-blocking
  issue. The skill surfaces the loss to the
  user and asks for confirmation.
- **Custom serialization in the compiler.** The
  spec is serialized via the standard schema
  registry (skill 09). The compiler does not
  write its own JSON serializer.
- **DSL strings as identifiers.** A `Part`
  referenced by `name` is a smell. The DSL uses
  typed IDs.
- **The "we'll parse it in the UI" anti-pattern.**
  The compiler is the single source of truth
  for the spec. The UI consumes the artifact.
- **The "we'll add the invariant later"
  anti-pattern.** The invariant is in the
  grammar. Adding it later is a breaking
  change.

## 11. The DSL in the Elysium Automotive Foundry

The DSL is the user-facing surface for "build me a
vehicle". A first version supports:

- Brand + project declaration.
- Subsystem declaration (powertrain, chassis,
  electrical, electronics, software).
- Part declaration (battery pack, motor, ECU,
  sensor, harness, etc.).
- Assembly declaration (which parts are in which
  subsystems).
- Compatibility declaration (which parts are
  compatible with which other parts).
- Variant declaration (which revision of a part
  is in which variant of a vehicle).

A future version supports:

- Behaviour declaration (the parts' runtime
  behaviour, fed to the digital twin, skill 07).
- Failure declaration (the parts' failure modes,
  fed to the digital twin, skill 07).
- Diagnostic declaration (which fault codes map
  to which repair actions, fed to the field
  diagnostic flow, skill 11 + 07).

The DSL grammar is in `docs/dsl/grammar.md`. The
parser + type-checker + emitter live in
`compiler/`. The editor support lives in
`dsl-editor/`. The runtime consumes the artifact
in `runtime/`.

## 12. Working with this skill

When invoked, this skill:

1. Reads the user's input + the ontology +
   the parts catalog.
2. Parses + resolves + type-checks.
3. Emits the spec artifact + the report.
4. Returns the artifact ID to the orchestrator
   (the orchestrator hands it to skill 06 + 07).

This skill does **not** render the spec in 3D
(skill 06) or simulate it (skill 07). The skill
produces the typed spec; the next skills produce
the visual + the simulation.
