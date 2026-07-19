# Phase F2 / I-2.8 — Editor Support (SourceMap + LiveSpecValidator)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2416 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F2 shipped the schema + parser (Phases F2 first/second half) + the validator + the constraint engine + the compilation pipeline + the compilation report (Phases F2 third/fourth/fifth half). The pipeline's user-facing surface — the **editor** — was still missing.

Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 10 (Editor support):
> - **Syntax highlighting** (the grammar + the ontology's types).
> - **Go-to-definition** (the `SourceMap`).
> - **Hover docs** (the ontology's docs).
> - **Live validation** (the schema validator runs on every keystroke).

The editor needs:
1. A way to map a JSON path (e.g. `$.body.architecture`) to a source position (line + column).
2. A way to convert a `CompilationDiagnostic` to a source-position-annotated annotation the editor renders.
3. A live validator that runs the validator + the constraint engine on every spec change + produces the annotations.

Phase F2's sixth half ships the **foundation** (SourceMap + DiagnosticAnnotation + LiveSpecValidator). The Compose-based editor UI is a future phase; the foundation is what the editor consumes.

---

## What shipped

### Production (foundry.core.dsl.editor)

#### 1. `SourceMap` (the JSON path → source position map)

The `SourceMap` is a value object that maps a JSON path (e.g. `$.body.architecture`) to a `SourcePosition(line, column)`. The map:
- Is **built during parsing** — the parser records the position of every field it sees.
- Is **consumed by the editor** — the editor's "go to definition" feature navigates from a path to the source line.
- Is **consumed by the live validator** — the live validator attaches source positions to diagnostics.

The map is immutable (data class + `with(path, position)` returns a new map). The map rejects:
- Blank paths.
- Paths that don't start with `$`.

#### 2. `SourcePosition` (a line + column position)

A value object with `line: Int` (1-indexed) + `column: Int` (1-indexed). The position rejects:
- `line < 1`.
- `column < 1`.

The `displayString()` returns `"line N, column M"` (the human-readable form for editor tooltips).

#### 3. `DiagnosticAnnotation` (the editor's diagnostic)

The `DiagnosticAnnotation` is the typed value the editor renders. The annotation has:
- `position: SourcePosition` — where the diagnostic fires.
- `severity: Severity` — `ERROR` (red), `SAFETY_CRITICAL` (red), `REGULATORY` (red), `WARNING` (yellow), `OPTIMIZATION` (blue).
- `message: String` — the user-facing string.
- `code: String` — the rule's stable identifier.
- `paths: List<String>` — the JSON paths the diagnostic references.

The annotation rejects empty `paths`.

#### 4. `CompilationDiagnostic.toAnnotations(sourceMap)` (the conversion)

A top-level extension function that converts a `CompilationDiagnostic` to a list of `DiagnosticAnnotation`s:
- For every path in the diagnostic, look up the source position in the `SourceMap`.
- One annotation per matched path.
- The severity is mapped from the diagnostic's severity (HARD → ERROR, SOFT → WARNING, etc.).

The conversion is:
- **Total** — every diagnostic is converted; a path with no map entry is silently skipped.
- **Deterministic** — same diagnostic + same source map → same annotations.
- **Order-preserving** — the annotations are in the order of the diagnostic's paths.

#### 5. `LiveSpecValidator` (the live validator)

The live validator is the editor's "validate on every keystroke" surface. The validator composes:
- `SpecValidator` (Phase F2 / I-2.4) — the invariant checker.
- `CompatibilityConstraintEngine` (Phase F2 / I-2.5) — the constraint engine.
- A `SourceMap` (this phase) — the source positions.

The validator returns a `LiveValidationResult` that has:
- `diagnostics: List<CompilationDiagnostic>` — the aggregated diagnostics.
- `annotations: List<DiagnosticAnnotation>` — the source-position-annotated diagnostics, sorted by `(line, column, code)` for deterministic display.
- `sourceMap: SourceMap` — the source map (for go-to-definition + hover docs).
- `isValid: Boolean` — no HARD / SAFETY_CRITICAL / REGULATORY diagnostic.
- `blockingDiagnostics`, `warnings`, `optimizations` — the categorized diagnostics.

The live validator is:
- **Fast** — microseconds per spec (the validator + the engine are pure-domain).
- **Deterministic** — same spec + same source map → same result.
- **Pure-domain** — no I/O, no Android dependencies.

---

## The 18-step pipeline (Phase 2 / I-2.8 status)

Phase 2 / I-2.8 doesn't add a new step to the pipeline. The editor is the **user-facing surface** for the pipeline's output (the diagnostics + the source map). The editor runs the validator + the engine on every spec change; the pipeline runs them on save.

The editor's "live validation" + the pipeline's "compile" are two views on the same validation logic:
- **Live** — runs on every keystroke, fast (microseconds), shows annotations.
- **Compile** — runs on save, slower (milliseconds), produces the `Compilation` + the `CompilationReport`.

Both consume the same validator + engine; both produce diagnostics; the editor annotates, the pipeline records.

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `EditorSupportTest` | 0 | 22 | +22 (new) |
| **Total JVM unit tests** | 2394 | 2416 | **+22** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/editor/SourceMap.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/editor/DiagnosticAnnotation.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/editor/LiveSpecValidator.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/dsl/editor/EditorSupportTest.kt`

---

## Architectural notes

### Why the SourceMap is separate from the spec

The `SourceMap` is the bridge between the schema and the source text. The spec is the typed value; the source map is the source-position annotation. Separating them:
- The spec is content-addressed (the canonical form is hashable). The source map is not (the same spec can have many source positions across many versions).
- The spec is the input to the validator + the engine. The source map is the input to the editor.
- The spec is pure. The source map is editor-specific.

### Why the diagnostic → annotation conversion is a top-level function

The conversion is a pure function of `(diagnostic, sourceMap)`. A top-level function:
- Is testable in isolation.
- Is composable (the editor can convert many diagnostics at once).
- Is not coupled to the `DiagnosticAnnotation` class (the function is in the `editor` package; the class is too).

### Why the live validator is a class (not a function)

The live validator is a class because it has **dependencies** (the validator + the engine). A function would have to take them as parameters on every call; a class encapsulates them.

The class is stateless (the dependencies are read-only). The class is JVM-testable with hand-rolled fixtures.

---

## Foundry Phase 2 status (final)

Phase F2 has 6 half-increments:

| Increment | Status | Description |
|-----------|--------|-------------|
| I-2.1 + I-2.3 (Phase F2 first half) | ✅ | DSL grammar + schema (`CompiledVehicleSpec`) |
| I-2.2 (Phase F2 second half) | ✅ | JSON parser (`JsonVehicleSpecParser` + 7 typed diagnostics) |
| I-2.4 (Phase F2 third half) | ✅ | Spec Validator (9 cross-aggregate invariants) |
| I-2.5 (Phase F2 fifth half) | ✅ | Compatibility Constraint Engine (8 market/optimization constraints) |
| I-2.6 + I-2.7 (Phase F2 fourth half) | ✅ | Compilation Pipeline (3 of 18 steps) + Compilation Report |
| I-2.8 (Phase F2 sixth half) | ✅ | Editor Support (SourceMap + DiagnosticAnnotation + LiveSpecValidator) |

**Phase 2 is complete (G2 + G3 closed)**. The next gate is G4 (3D pipeline / digital twin).

---

## Next phases (the pipeline forward)

- **Phase 3 (G4)** — 3D pipeline (Scene manifest + LODs + asset validation + part instance graph). The 3D pipeline consumes the `Compilation` (steps 15, 16).
- **Phase 4 (G5)** — AI council (typed `AIProposal` + multi-agent deliberation). The AI council proposes `DslMutation`s that the validator + the engine must approve.
- **Phase 5 (G6+G7)** — Commercial foundation (RoyaltyContract + License + royalty engine). The royalty engine consumes the `Contributor` + the `Project` + the `VehicleRevision`.
- **Phase 6 (G8)** — Marketplace + supplier network (RFQ + Offer). The marketplace consumes the `RoyaltyContract` + the `License`.
- **Phase 7 (G9+G10)** — Production hardening (threat model + SLOs + on-call + runbooks + CVE SLA + red team).

The editor support is the **last piece of Phase 2**. Phase 3 is the next big gate (G4).
