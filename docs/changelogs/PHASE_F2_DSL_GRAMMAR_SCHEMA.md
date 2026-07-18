# Phase F2 — DSL Grammar + Typed Schema (G2 first half)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** I-2.1 (DSL grammar) + I-2.3 (typed `CompiledVehicleSpec` schema)
> **Build quality:** 0 lint warnings · 2201 unit tests passing (was 2179, +22) · `assembleDebug` green

---

## TL;DR

Phase F2 (first half) ships the **VSL grammar doc** + the
**typed `CompiledVehicleSpec` schema** — the foundation that
the parser (I-2.2) and the 18-step compiler (I-2.6) will plug
into. The schema is the **canonical typed output** of the
compiler; every downstream skill (06 — 3D, 07 — digital twin,
09 — catalog, 10 — marketplace) consumes it.

What's new:

1. **`docs/dsl/grammar.md`** — the EBNF grammar for the
   Vehicle Spec Language. The grammar is the contract for both
   the text surface (YAML/JSON) and the visual AST.
2. **`CompiledVehicleSpec`** — the root typed data class. The
   `canonicalForm()` is the deterministic UTF-8 byte sequence
   that the compiler hashes to produce the `Spec.Artifact.id`.
3. **Sub-aggregates** — `SpecMetadata`, `SpecClassification`,
   `Body`, `Propulsion`, `Engine`, `Driveline`. Every field is
   a typed value (no `Map<String, Any>`).
4. **`UnitValue`** — the typed physical-unit value family
   (`Length`, `Volume`, `Mass`, `Energy`, `Power`, `Speed`).
   NaN / ±Infinity is rejected at construction.
5. **22 schema tests** — data-class invariants, NaN/Infinity
   rejection, deterministic canonical form, the golden Urban
   One form, and the `buildSpec` factory contract.

---

## Test-discovered regression (this phase)

The parent's `init` block runs **before** the child's
constructor parameters are assigned. The original `UnitValue`
had the NaN/Infinity check in the parent's `init`, but the
check would always see `value = 0.0` (the default for the
abstract `val`). Fix: the check is inlined into each child's
`init` block via a `UnitValue.requireFinite(value)` helper.

This was caught by the test `unit value rejects NaN` — the
test failed because the construction succeeded with a NaN
value. Surface as evidence the test suite is earning its
keep.

A second pre-existing test was updated to account for the
new `UNKNOWN` sentinel on `RepresentationLevel`:
- `representation level has five values` → `has six values including UNKNOWN sentinel`
- `representation level preserves enum order` → updated to put `UNKNOWN` at index 0

---

## Why this phase matters

Per `docs/foundry/implementation-roadmap.md` I-2.1 + I-2.3 +
`.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 5
("Outputs") + section 6 ("Compiler pipeline"):

- The DSL is the **user-facing surface** for "build me a
  vehicle". The grammar doc is the contract.
- The `CompiledVehicleSpec` is the **typed output** of the
  compiler. The schema is append-only (a breaking change is
  a new `apiVersion` + a migration tool).
- The schema is **content-addressed + signed + versioned**:
  the `canonicalForm()` is the deterministic byte sequence
  that the compiler hashes to produce the `Spec.Artifact.id`.
- A raw scalar where a fact is required is rejected: every
  numeric value with a physical dimension is a typed
  `UnitValue` with an explicit unit. `wheelbase: 2.45` (no
  unit) is a compile error.
- A `vehicle` without a `representationLevel` is rejected:
  the `SpecClassification.init` checks for
  `RepresentationLevel.UNKNOWN` and throws.

The parser (Phase F2 second half, I-2.2) is the next
increment — it converts the YAML/JSON text into the
`CompiledVehicleSpec` via the grammar. The 18-step compiler
(I-2.6) runs validation + canonicalization on top.

---

## Files added / modified

### New files (5)

| File | Purpose |
|---|---|
| `docs/dsl/grammar.md` | EBNF grammar for the Vehicle Spec Language (per skill 04 section 6) |
| `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/schema/CompiledVehicleSpec.kt` | The root typed schema + sub-aggregates (`Body`, `Propulsion`, `Engine`, `Driveline`) + `ApiVersion` + `SpecMetadata` + `SpecClassification` + enums + `buildSpec` factory |
| `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/schema/UnitValue.kt` | The sealed `UnitValue` family (`Length` / `Volume` / `Mass` / `Energy` / `Power` / `Speed`) + the unit enums + the `requireFinite` helper |
| `app/src/test/java/com/elysium/vanguard/foundry/core/dsl/schema/CompiledVehicleSpecTest.kt` | 22 schema tests (invariants + determinism + golden file) |
| `docs/changelogs/PHASE_F2_DSL_GRAMMAR_SCHEMA.md` | This changelog |

### Modified files (2)

| File | Change |
|---|---|
| `app/src/main/java/com/elysium/vanguard/foundry/core/ontology/primitives/RepresentationLevel.kt` | Added `UNKNOWN` sentinel at position 0 (the "level not set" indicator, per `.ai/STANDARDS.md` 4) |
| `app/src/test/java/com/elysium/vanguard/foundry/core/ontology/primitives/PrimitivesTest.kt` | Updated the 2 `RepresentationLevel` tests to account for the new `UNKNOWN` value |

---

## Schema design decisions

### 1. The schema is append-only

A breaking change is a new `apiVersion` (`elysium.vehicle/v2`,
`/v3`, ...). The migration tool is published alongside the
new version. A silent lossy migration is a contract
violation (per skill 04 section 20).

The `ApiVersion` value class enforces the `elysium.vehicle/vN`
format via a regex (`^elysium\.vehicle/v\d+(\.\d+)?$`). The
`API_VERSION_PATTERN` is exposed for the migration tool to
reuse.

### 2. The schema is the only path to a `Spec.Artifact`

Per skill 04 section 20: "Untyped spec output. A
`Spec.Artifact` is a typed value, not a `Map<String, Any>`.
Every field has a type."

The current `VehicleDefinition` (`Map<String, String>` of
parameters) is the **input** to the compiler (Phase 1
stub). The `CompiledVehicleSpec` is the **output** of the
compiler. Phase 2 wires the input → output via the parser.

### 3. `UnitValue` is a sealed family

Per skill 04 section 6 step 4 (unit normalization): every
numeric value with a physical dimension is a typed
`UnitValue` with an explicit unit. The sealed family ensures:
- A `Length` is paired with a length unit (e.g. `METER`),
  not a mass unit (e.g. `KILOGRAM`). The type system
  catches it at the boundary.
- The unit is preserved in the canonical form (the
  compiler does NOT silently convert units — `wheelbase:
  2.45 METER` is serialized as `wheelbase: 2.45 METER`).
- A non-finite value is rejected at construction (per
  skill 04 section 25 — security).

### 4. Sub-aggregate init checks (fail-fast pattern)

Every sub-aggregate has an `init` block that enforces its
own invariants (e.g. `Body.doors in {2, 3, 4, 5}`,
`Engine.displacement` for an electric engine is zero, etc.).
The pattern is: a bad value is rejected at the **earliest
possible** boundary, not deferred to the spec-level
validator.

This is the "fail-fast" pattern. The trade-off: a sub-
aggregate's `init` check fires before the `CompiledVehicleSpec`
constructor can catch it in a `try/catch`. The `buildSpec`
factory is a thin wrapper for the future case where a
spec-level invariant is added (the `try/catch` is in place
but currently idle).

### 5. `canonicalForm()` is the deterministic byte sequence

The `canonicalForm()` is the **deterministic UTF-8 byte
sequence** of the spec. The format is:
```
vsl:v1
|api=elysium.vehicle/v1
|metadata:projectId=<UUID>|revision=1
|classification:level=PARAMETRIC_FUNCTIONAL
|body:architecture=HATCHBACK|doors=5|seats=5|wheelbase=length:2.45|unit:METER
|propulsion:energySource=ELECTRIC|engine=configuration=ELECTRIC_NONE|displacement=volume:0.0|unit:LITER|orientation=LONGITUDINAL
|driveline:traction=FWD|transmission=SINGLE_SPEED
```

Same `CompiledVehicleSpec` -> same canonical form -> same
SHA-256 -> same `Spec.Artifact.id`. The compiler hashes the
canonical form to produce the artifact ID (per skill 04
section 15).

The canonical form has a **fixed field order**:
api → metadata → classification → body → propulsion →
driveline. The test `canonical form preserves fields in a
fixed order` asserts this.

---

## Test coverage breakdown

| Test class | Tests | Coverage |
|---|---|---|
| `CompiledVehicleSpecTest` | 22 | data-class invariants (UNKNOWN level, door count, seat count, wheelbase, electric engine, displacement, apiVersion format, NaN/Infinity) + canonical form determinism + golden Urban One form + buildSpec factory |
| `PrimitivesTest` (modified) | 2 | `RepresentationLevel` enum (6 values, fixed order with UNKNOWN at index 0) |
| **Net new tests** | **+22** | |
| **Modified tests** | **2** | |

### Test count delta

- Before: 2179 unit tests
- After: 2201 unit tests (+22)

---

## Build quality

- 0 lint warnings
- `./gradlew :app:testDebugUnitTest` — green (2201 passing, 2 skipped)
- `./gradlew :app:assembleDebug` — green

---

## What ships next (Phase F2 second half)

The schema + the grammar are the foundation. The next
increments are:

- **I-2.2 Parser** — a recursive-descent parser that turns
  the YAML/JSON text into the `CompiledVehicleSpec`. The
  parser consumes the grammar (the contract) + the schema
  (the target shape). The parser is **total**: every valid
  input parses; an invalid input is rejected with a typed
  `CompilationDiagnostic`.
- **I-2.4 Validator** — the per-step invariant checker (per
  skill 04 section 5). Validates that the spec satisfies
  the cross-reference rules (e.g. "a QUAD traction requires
  a TWO_SPEED transmission").
- **I-2.6 Deterministic compiler** — the 18-step pipeline
  (per skill 04 section 6). Replaces the SHA-256 stub in
  `DeterministicVehicleCompiler` with the real pipeline.
  The public interface stays the same.
- **I-2.7 Compilation report** — the user-facing report
  with the per-step errors + warnings + info notes.

G2 (DSL grammar + schema) is now half-closed. G3 (the
deterministic compiler) is the next gate.
