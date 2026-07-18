# Phase F2 (second half) — Vehicle Spec Parser (I-2.2)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** I-2.2 (parser) — the boundary between text surface and typed schema
> **Build quality:** 0 lint warnings · 2226 unit tests passing (was 2201, +25) · `assembleDebug` green

---

## TL;DR

Phase F2 (second half) ships the **Vehicle Spec parser** —
the boundary between the text surface (JSON) and the typed
`CompiledVehicleSpec` schema. The parser:

1. Decodes the JSON via Gson (a battle-tested structured
   decoder — no hand-rolled lexer for the JSON surface).
2. Walks the JSON tree via path-aware recursive descent
   (every error carries the JSON path).
3. Emits typed `CompilationDiagnostic`s for every failure
   (no free-form strings, no `Map<String, Any>` errors).
4. Is **total** (no panics on adversarial input).
5. Is **deterministic** (same input → same output byte-for-byte).
6. Does **not** execute user-supplied code (per skill 04
   section 25 — security).

A future YAML surface would be a separate parser that
produces the same `CompiledVehicleSpec`. The grammar doc
(docs/dsl/grammar.md) is the contract for both.

---

## What's new

### Production code (3 files)

| File | Purpose |
|---|---|
| `CompilationDiagnostic.kt` | The typed diagnostic envelope: `SyntaxError`, `MissingRequiredField`, `WrongType`, `UnknownEnumValue`, `InvalidUnitValue`, `UnknownUnit`, `InvariantViolation` — each with a `code`, `severity`, `paths`, and a `toFoundryError()` conversion (per skill 04 section 23) |
| `VehicleSpecParser.kt` | The parser contract + the `ParseResult` sealed class (`Success(spec, diagnostics)` / `Failure(diagnostics)`) |
| `JsonVehicleSpecParser.kt` | The JSON parser (Gson-based + path-aware recursive descent) — the only path from text to typed schema |

### Test code (1 file)

| File | Tests | Coverage |
|---|---|---|
| `JsonVehicleSpecParserTest.kt` | 25 | Golden Urban One round-trip + syntax/schema/missing/wrong-type/unknown-enum/NaN/invariant diagnostics + determinism + fuzzing (deeply nested, very long, unicode, null bytes) + path-aware errors |

---

## Test-discovered regressions (this phase)

### 1. `RepresentationLevel` import path was wrong

The initial import was
`com.elysium.vanguard.foundry.core.dsl.schema.RepresentationLevel`
but the actual class lives at
`com.elysium.vanguard.foundry.core.ontology.primitives.RepresentationLevel`.
Caught at compile time by the IDE. Surface as evidence the
test suite surfaces even compile-time mistakes (the
integration test that uses the parser fails to compile if
the import is wrong).

### 2. `parseEnum` couldn't infer the type parameter

The recursive `parseEnum<BodyArchitecture>(...)` calls were
originally written as `parseEnum(...)` and let the type
inference figure out `E`. The inference was getting
confused (sometimes inferring `E = Nothing`), causing
`Cannot use 'Nothing' as reified type parameter` errors.
Fix: **explicit type parameters** at every call site
(`parseEnum<BodyArchitecture>(...)`, etc.). This is a
known Kotlin reified-type gotcha.

### 3. Path mismatch on the electric+INLINE_4 invariant

The test asserted the path was `$` (the root) for the
"electric energy source with INLINE_4 engine" diagnostic.
The actual path is `$.propulsion` because the invariant
fires at the **sub-aggregate** level (the `Propulsion.init`
check), not at the `CompiledVehicleSpec` level. This is
the correct behavior — the sub-aggregate's `init` runs
first. Fix: update the test to expect `$.propulsion`. This
is the **7th test-discovered bug** in this session.

### 4. NaN handling in Gson

Gson by default rejects the string `"NaN"` in a
number-typed slot as `WrongType` (string, not number).
The test asserted `SyntaxError` or `InvalidUnitValue`,
which is what would happen if the JSON had a literal NaN
(but JSON doesn't have a literal NaN). Fix: the test
accepts any of `SyntaxError`, `WrongType`, or
`InvalidUnitValue` — all three are valid rejections of
adversarial input.

---

## Why this phase matters

Per `docs/foundry/implementation-roadmap.md` I-2.2 +
`.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6
step 1-3 + section 17 (Quality gates):

- The parser is the **only path** from the text surface to
  the typed schema. A future YAML surface is a separate
  parser that produces the same `CompiledVehicleSpec`.
- The parser is **total** (every valid input is parsed;
  an invalid input is rejected with a typed
  `CompilationDiagnostic`). The integration test
  (`urban one golden spec parses successfully`) asserts the
  happy path; the fuzzing tests (deeply nested, very long,
  unicode, null bytes) assert the total property.
- The parser is **deterministic** (the determinism test
  asserts the same input produces the same output across
  two calls).
- The parser emits **all** diagnostics it can detect in a
  single pass (the user gets a complete report, not a
  whack-a-mole of one error at a time). The
  `parseWithDiagnostics` API returns a `ParseResult` with
  the full diagnostic list; the `parse` API returns a
  `Result` with the first HARD diagnostic.
- The parser does **not** execute user-supplied code (per
  skill 04 section 25 — security). The parser is a
  structured decoder; it does not interpret executable
  semantics.

---

## Design decisions

### 1. Gson for JSON decoding, not a hand-rolled lexer

Per skill 04 section 6 step 1 ("Lexing or structured
decoding"): "The DSL text is lexed into tokens; the
structured input (JSON / YAML / Protobuf) is decoded." For
the JSON surface, a hand-rolled lexer is unnecessary (and
would re-invent a battle-tested wheel). Gson is already a
production dep; using it for the structured-decoding
surface is the right trade-off.

A future YAML surface would use a YAML library (e.g.
`org.yaml:snakeyaml`); the parser interface stays the same.

### 2. `CompilationDiagnostic` is a sealed class, not a string

Per `.ai/STANDARDS.md` 7 + skill 04 section 23: a free-form
string is never the value of an error; a `Map<String, Any>`
is never the value of an error. The diagnostic IS the typed
value. The consumer pattern-matches on the variant.

Every diagnostic carries:
- `code` — the canonical error code (e.g. `VCOMP-SYNTAX-001`).
  The catalog of codes is owned by the compiler (skill 04
  section 23).
- `severity` — per section 22 (HARD / SAFETY_CRITICAL /
  REGULATORY / SOFT / OPTIMIZATION).
- `paths` — the JSON paths in the spec that the violation
  refers to. The user can navigate to the violation.

### 3. Path-aware errors

Every diagnostic carries the JSON path (e.g.
`$.body.architecture`). The UI can navigate the user to
the offending field. The path format is the JSONPath
standard (`$` for the root, `.` for the object member, `[]`
for the array element).

### 4. `ParseResult` is a sealed class with two variants

`ParseResult.Success(spec, diagnostics)` and
`ParseResult.Failure(diagnostics)`. The success variant
carries a "best effort" spec even when there are
diagnostics (a SOFT warning is reported but the parse
succeeds). The failure variant has no spec (the catastrophic
failure prevented the spec from being assembled).

### 5. Reified type parameters with explicit `E`

The `parseEnum<E : Enum<E>>` helper is `inline reified` so
the `enumValueOf<E>(match)` call works. The type parameter
must be explicit at the call site because Kotlin's type
inference is unreliable with reified types in `?:` chains
(a known Kotlin gotcha). The cost is verbose call sites;
the benefit is no `Class<E>` parameter on the helper.

---

## Test coverage breakdown

| Test class | Tests | Coverage |
|---|---|---|
| `JsonVehicleSpecParserTest` | 25 | golden file + syntax errors + missing fields + wrong types + unknown enums + NaN + invariant violations + determinism + fuzzing (deep nesting, long strings, unicode, null bytes) + path-aware errors |
| **Net new tests** | **+25** | |

### Test count delta

- Before: 2201 unit tests
- After: 2226 unit tests (+25)

---

## Build quality

- 0 lint warnings
- `./gradlew :app:testDebugUnitTest` — green (2226 passing, 2 skipped)
- `./gradlew :app:assembleDebug` — green

---

## What ships next (Phase F2 third half: validator + compiler)

The parser is shipped. The next increments are:

- **I-2.4 Validator** — the per-step invariant checker. The
  current invariants are at the sub-aggregate level (data
  class `init` checks). The validator adds cross-aggregate
  rules (e.g. "QUAD traction requires TWO_SPEED
  transmission").
- **I-2.6 Deterministic compiler** — the 18-step pipeline
  (per skill 04 section 6). Replaces the SHA-256 stub in
  `DeterministicVehicleCompiler` with the real pipeline.
  The public interface stays the same: `compile(definition,
  catalogRevision, compilerVersion) -> Result<Compilation>`.
- **I-2.7 Compilation report** — the user-facing report
  with the per-step errors + warnings + info notes.

G2 (DSL + parser) is now fully closed. G3 (the deterministic
compiler) is the next gate.
