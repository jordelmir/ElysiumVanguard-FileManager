# Phase F2 / I-2.4 — Vehicle Spec Validator (cross-aggregate invariants)

**Status**: ✅ SHIPPED
**Date**: 2026-07-18
**Commit**: (this commit)
**Builds**: `./gradlew :app:testDebugUnitTest` (2366 tests, 0 fail, 2 skip) ·
`./gradlew :app:assembleDebug` (0 warnings) ·
`./gradlew :app:assembleDebugAndroidTest` (0 warnings)

---

## Why

Phase F2's first half shipped the **DSL grammar + schema** (`CompiledVehicleSpec`). Phase F2's second half shipped the **JSON parser** (the lexer + the parser + 7 typed `CompilationDiagnostic` variants).

The parser catches:
- Syntax errors
- Missing fields
- Wrong types
- Unknown enum values
- NaN/Infinity in unit values
- Unknown units
- Sub-aggregate invariant violations (the data-class `init` block failures)

What the parser **does not** catch:
- **Cross-aggregate invariants** — value combinations that span multiple aggregates (e.g. `ELECTRIC` + `INLINE_4`, `V12` + `FWD`, `COUPE` + 4 doors).

The data-class `init` blocks catch **field-level** invariants (a single field is out of range). They don't catch **combination-level** invariants (a combination of fields is invalid). The validator fills that gap.

Phase F2's third half ships the **Spec Validator** — the cross-aggregate invariant checker that runs after the parser. The validator is the **last gate** before the spec is handed to the deterministic compiler (Phase F2's fourth half).

---

## What shipped

### Production (foundry.core.dsl.validator)

#### 1. `SpecValidator` interface (the contract)

The validator's contract: take a `CompiledVehicleSpec` and return a `ValidationResult` (a list of `CompilationDiagnostic` + an `isValid` flag).

The validator is:
- **Total** — every spec is checked; no spec is rejected without a typed diagnostic.
- **Deterministic** — same spec → same diagnostic list.
- **Pure-domain** — no I/O, no Android dependencies.
- **Pluggable** — a `SpecRule` is a discrete invariant check; the validator composes a `SpecRuleSet` of rules.

#### 2. `SpecRule` interface (a single rule)

A rule is a discrete, named invariant check. The rule has:
- A stable `code` (the `VCOMP-RULE-XXX-YYY` identifier the diagnostic carries)
- A human-readable `name` (the editor's "live validation" panel)
- A `check(spec)` function returning a list of `CompilationDiagnostic`

The rule is pure + deterministic. The rule is composable (the rule set is a list of rules).

#### 3. `DefaultSpecValidator` (the default implementation)

The default validator applies a `SpecRuleSet` of rules in order and aggregates the diagnostics. The default rule set is the 9 cross-aggregate invariants the platform ships.

#### 4. `SpecRuleSet` (a named collection of rules)

A `SpecRuleSet` is:
- Named + versioned (per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6 step 22 — Rule-set versioning).
- Validated at construction (unique rule codes, non-blank name + version).
- Reusable across validators.

#### 5. `ValidationResult` (the result envelope)

The validation result is a typed value:
- `diagnostics: List<CompilationDiagnostic>` — the full list
- `isValid: Boolean` — `true` when no HARD / SAFETY_CRITICAL / REGULATORY diagnostic is present
- `blockingDiagnostics` — the errors that block compilation (HARD + SAFETY_CRITICAL + REGULATORY)
- `warnings` — the SOFT diagnostics (the spec compiles, the user is informed)
- `optimizations` — the OPTIMIZATION diagnostics (the candidate is ranked)

#### 6. The 9 default rules (`DefaultRuleSet`)

The default rule set is the 9 cross-aggregate invariants the platform ships:

| Rule | Severity | Description |
|------|----------|-------------|
| `VCOMP-RULE-EV-TRANSMISSION` | HARD | EV propulsion requires SINGLE_SPEED or TWO_SPEED transmission |
| `VCOMP-RULE-V12-FWD` | SAFETY_CRITICAL | V10/V12 engine with FWD traction is a packaging impossibility |
| `VCOMP-RULE-ROADSTER-DOORS` | HARD | ROADSTER must have 2 doors |
| `VCOMP-RULE-COUPE-DOORS` | HARD | COUPE must have 2 doors |
| `VCOMP-RULE-2SEAT-BODY` | HARD | COUPE/ROADSTER must have at most 2 seats |
| `VCOMP-RULE-VAN-DOORS` | HARD | VAN must have 3+ doors |
| `VCOMP-RULE-PICKUP-3DOORS` | SOFT | PICKUP with 3 doors is unusual |
| `VCOMP-RULE-9SEAT-WAGON` | HARD | 9-seat WAGON must have 4+ doors |
| `VCOMP-RULE-GAS-SINGLE-SPEED` | SOFT | GASOLINE + SINGLE_SPEED transmission is unusual |

The rules are organized in three categories:
- **Drive-train rules** — engine + transmission + driveline combinations
- **Body shape rules** — body + doors + seats combinations
- **Combination rules** — propulsion + driveline combinations

### `CompilationDiagnostic` extension

A new variant: `CrossAggregateInvariantViolation`:
- `ruleCode: String` — the stable identifier of the rule that fired
- `reason: String` — the human-readable explanation
- `jsonPaths: List<String>` — the JSON paths the rule references
- `diagnosticSeverity: Severity` — the severity (HARD / SOFT / SAFETY_CRITICAL / etc.)

The new variant extends the `CompilationDiagnostic` sealed family without breaking the existing parser's diagnostics.

### Tests

22 new tests (`DefaultSpecValidatorTest`):
- 1 happy path (the Urban One spec passes all rules)
- 9 rule-specific tests (each rule fires on its triggering input + doesn't fire on a non-triggering input)
- 1 determinism test (same spec → same diagnostics across multiple calls)
- 1 result filter test (blocking diagnostics vs warnings are correctly separated)
- 5 rule set integrity tests (unique codes, non-blank name + version, duplicate code rejection)
- 1 SOFT warning test (gasoline + single-speed is a warning, not a block)
- 2 pass-through tests (roadster with 2 doors + 2 seats passes; pickup with 2 doors / 4 doors passes the 3-doors rule)

---

## The validator in context (the 18-step pipeline)

Per `.ai/skills/04-vehicle-dsl-compiler/SKILL.md` section 6, the compiler is an 18-step pipeline. Phase F2's third half fills **step 3 — Schema validation**:

```
Step 1: Lexing / structured decoding       (Phase F2 - parser)
Step 2: Parsing                            (Phase F2 - parser)
Step 3: Schema validation                  ← THIS PHASE
Step 4: Unit normalization                 (Phase F2 - parser)
Step 5: Alias resolution                   (future)
Step 6: Applicability resolution           (future)
Step 7: Dependency expansion               (future)
Step 8: Compatibility checking             (future - I-2.5)
Step 9: Constraint solving                 (future)
Step 10-12: Part selection + assembly + interface binding  (future)
Step 13-18: Collision, diagnostics, BOM, scene manifest, report, artifact hashing
```

The validator is the **gate** between the schema and the rest of the pipeline. A spec that fails the validator (HARD / SAFETY_CRITICAL / REGULATORY) is rejected before the more expensive steps run.

---

## Test-discovered bugs this phase

Phase F2's third half surfaced 3 test-discovered bugs:

1. **Data class `init` blocks shadowed the validator's intent** — the `PickupMustHave2PlusDoorsRule` was supposed to catch `PICKUP + doors=1`, but the `Body.init` already rejected `doors=1` (only `{2, 3, 4, 5}` allowed). The rule was **dead code** (could never fire). Fix: changed the rule to `PickupWith3DoorsRule` — a 3-door PICKUP is unusual (a 3-door asymmetric layout isn't a production design), SOFT severity, useful warning.

2. **`assertTrue(message)` parameter order** — JUnit's `assertTrue` is `(message, condition)`, not `(condition, message)`. The test's "expected exception" assertions used the wrong order, causing the test to silently pass (the condition was a String, the message was a Boolean). Fix: use `fail("expected X")` when the exception is expected; the catch block then uses `assertTrue("message", condition)`.

3. **`val paths` shadows parent** — the new `CrossAggregateInvariantViolation` data class had a `val paths: List<String>` parameter that shadowed the parent `CompilationDiagnostic.paths` val. Fix: renamed the parameter to `jsonPaths` to avoid the conflict.

All 3 are now in the cross-project engineering rules (see `engineering-gotchas.md`).

---

## Test counts

| Suite | Before | After | Delta |
|-------|--------|-------|-------|
| `DefaultSpecValidatorTest` | 0 | 22 | +22 (new) |
| **Total JVM unit tests** | 2344 | 2366 | **+22** |

**0 lint warnings, 0 test failures, 0 build errors.**

---

## Files

### New (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/validator/SpecValidator.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/validator/SpecRule.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/validator/DefaultSpecValidator.kt`
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/validator/rules/DefaultRuleSet.kt`

### New (test)
- `app/src/test/java/com/elysium/vanguard/foundry/core/dsl/validator/DefaultSpecValidatorTest.kt`

### Modified (production)
- `app/src/main/java/com/elysium/vanguard/foundry/core/dsl/parser/CompilationDiagnostic.kt` — added `CrossAggregateInvariantViolation` variant

---

## Next phases (the pipeline forward)

- **Phase F2 fourth half (I-2.6 + I-2.7)**: the deterministic compiler pipeline (replaces the SHA-256 stub in `DeterministicVehicleCompiler` with the full 18-step pipeline) + the compilation report (per-step errors + warnings + info notes)
- **Phase F2 fifth half (I-2.5)**: the compatibility rules / constraint engine (the part of step 8 that the validator doesn't cover — pairs of part instances)
- **Phase F2 sixth (I-2.8)**: editor support (the live-validation surface that consumes the validator's diagnostics)
- **Phase 3 (G4)**: 3D pipeline (Scene manifest + LODs + asset validation)
- **Phase 4 (G5)**: AI council (typed `AIProposal` + multi-agent deliberation)
- **Phase 5 (G6+G7)**: commercial foundation (RoyaltyContract + License + royalty engine)

The validator is the **gate** before the 18-step pipeline. The 18-step pipeline consumes a `CompiledVehicleSpec` that passed the validator + the parser.

---

## Architectural notes

### Why the validator is separate from the parser

The parser catches **shape** (the JSON has the right fields, the right types, the right enum values). The validator catches **combinations** (a value combination is invalid even though every individual value is valid).

Separating the two:
- The parser is a **pre-compile** gate. A failed parser = no spec at all.
- The validator is a **post-schema** gate. A failed validator = no compilation, but a typed diagnostic that points to the offending fields.

The split also makes the rules **discrete** (each rule is a separate file, separately testable) and **pluggable** (a custom validator can add OEM-specific rules).

### Why the validator is pure-domain

The validator is pure-domain (no I/O, no Android dependencies) because:
- The editor (Phase F2 / I-2.8) runs the validator on every keystroke. A pure-domain validator is fast (microseconds per check) and is testable in the JVM classpath.
- The compiler (Phase F2 / I-2.6) runs the validator as step 3 of the pipeline. The compiler is pure-domain too; the validator fits cleanly.
- The audit trail (Phase F2 / I-2.7) records the validator's diagnostics. The diagnostics are typed values; the audit trail stores them as values, not as side effects.

### Why the rule set is versioned

A breaking change to a rule (e.g. "the COUPE + 4 doors rule is no longer HARD") is a new rule set version + a migration path. The version is part of the rule set's identity; the compiler can target a specific rule set version (a `RuleSet-Version` field in the `Compilation`).

A non-versioned rule set is a smell (the rules can change silently; an old spec may pass or fail depending on the rule set version that ran the check).
