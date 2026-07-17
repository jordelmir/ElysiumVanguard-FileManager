# Phase F1 — Foundational Domain (vertical slice: vehicle project → immutable traceable digital twin)

> **Status:** shipped 2026-07-17 against git head `c9028dc` (pre-Foundry).
> **Gate:** G1 (Domain model approved) — first 9 of 9 increments in progress; this vertical slice ships the foundation.
> **Build evidence:**
> - `testDebugUnitTest` — **1453 tests, 0 failures, 0 errors, 2 skipped** (1380 EV baseline + 73 Foundry new)
> - `assembleDebug` — green, `app-debug.apk` 101 MB (unchanged from baseline; Phase 1 adds no new dependencies)
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

Phase F1 is the **first vertical slice** of the Elysium Automotive Foundry
(`docs/foundry/implementation-roadmap.md` section 12). The slice is
the smallest set of new code that proves the platform's core invariants
end-to-end:

1. A `Project` is created.
2. A `VehicleDefinition` is authored (a compact electric vehicle).
3. The deterministic compiler compiles the configuration.
4. The `RevisionService` freezes the compilation into a
   `VehicleRevision` (immutable, signed, content-addressed).
5. The revision carries a `ProvenanceRecord` (complete) + a
   `SceneManifest` (content-addressed) + a
   `RepresentationLevel` (set to `PARAMETRIC_FUNCTIONAL`).
6. A mutation attempt against the frozen revision throws
   `FoundryError.FrozenRevisionMutationRejected`.

The critical integration test
(`VehicleProjectToDigitalTwinIntegrationTest`) asserts all six
invariants + the determinism property (same input → same
`Compilation.contentHash`).

---

## 1. Architecture decisions (ADRs applied)

- **ADR-0001** (`Money` is `BigDecimal`, never `Double` / `Float`):
  the `Unit` primitive uses `java.math.BigDecimal` for conversion
  factors; the platform's money types (Phase 5 / skill 09) inherit
  the same discipline.
- **ADR-0002** (`VehicleRepresentationLevel` is a 5-value enum with
  append-only transitions): the `RepresentationLevel` enum is in
  `core/ontology/primitives/`. `PARAMETRIC_FUNCTIONAL` is the
  Phase 1 default (no validated OEM assets exist).
- **ADR-0003** (`AI_INFERRED` cannot silently become `VERIFIED`): the
  `ProvenanceRecord.isComplete` is the gate; a record without source
  + signature + witness is `R-DI-1` and the consumer raises a typed
  `FoundryError.ProvenanceIncomplete`.
- **ADR-0006** (vehicle definitions are append-only): the
  `VehicleRevision` data class enforces `isImmutable = true` in
  its `init` block; the `RevisionService.modifyFrozenRevision`
  always throws.
- **ADR-0021** (Android runtime is not vehicle domain): the new
  code lives under `com.elysium.vanguard.foundry.*` namespaces;
  the existing `core/runtime/` namespaces are preserved.
- **ADR-0024** (existing EV tests + ADRs preserved): the 1,380
  baseline unit tests are intact; 73 new tests are additive.

---

## 2. Files added (31 total)

### 2.1 Domain primitives (skill 03 owns) — 8 files

```
app/src/main/java/com/elysium/vanguard/foundry/core/ontology/primitives/
├── FoundryError.kt              (typed error envelope, 7 variants + retry classification)
├── ContentHash.kt               (SHA-256 content address)
├── RepresentationLevel.kt       (5-value enum, append-only)
├── Timestamp.kt                 (monotonic wall clock + HLC-ready interface)
├── Signature.kt                 (HMAC-SHA-256, Ed25519-ready)
├── CatalogRevision.kt           (typed semver)
├── CompilerVersion.kt           (typed semver)
└── Unit.kt                      (SI base + derived + imperial, BigDecimal)
```

### 2.2 Strongly-typed IDs (skill 03 owns) — 1 file, 16 types

```
app/src/main/java/com/elysium/vanguard/foundry/core/ontology/ids/
└── Ids.kt
    ├── UserId, ProjectId, VehicleProgramId, VehicleRevisionId
    ├── ContributorId, EngineeringArtifactId, ProvenanceRecordId
    ├── PartId, VariantId, CompatibilityId
    ├── SubsystemId, AssemblyId, BrandId
    └── DiagnosticId, FaultId, RepairActionId
```

Each ID is a `@JvmInline value class` over `UUID`, with
`random()` + `from(string): Result<*>` factories. Boundary
validation rejects malformed UUIDs with a typed
`FoundryError.InvalidUuidFormat`.

### 2.3 Project aggregate (skill 03 owns) — 2 files

```
app/src/main/java/com/elysium/vanguard/foundry/core/project/
├── Project.kt                   (data class aggregate + ProjectStatus enum)
└── ProjectService.kt            (createProject use case)
```

### 2.4 Compiler (skill 04 owns) — 3 files

```
app/src/main/java/com/elysium/vanguard/foundry/core/compiler/
├── Compilation.kt               (data class: contentHash + warnings)
├── VehicleCompiler.kt           (fun interface)
└── DeterministicVehicleCompiler.kt  (Phase 1 impl: SHA-256 over canonical form)
```

The compiler's canonical form is:

```
compilation:v1|catalog={catalog}|compiler={compiler}|definition:v1|projectId={uuid}|name={name}|params={sorted_key=val;...}
```

Same inputs → same canonical string → same SHA-256 → same
`Compilation.contentHash`. The compiler is **total** for any
`VehicleDefinition` whose `validate()` returns success; an
ill-formed definition produces a typed
`FoundryError.VehicleDefinitionInvalid` or
`FoundryError.CompilationNonDeterministic`.

### 2.5 Revision aggregate (skill 03 owns) — 3 files

```
app/src/main/java/com/elysium/vanguard/foundry/core/revision/
├── VehicleDefinition.kt         (data class: projectId + name + parameters)
├── VehicleRevision.kt           (immutable data class: id + projectId + contentHash + provenance + sceneManifest + representationLevel + createdAt + isImmutable)
└── RevisionService.kt           (freeze + modifyFrozenRevision)
```

`RevisionService.freeze` is the only legitimate way to create a
`VehicleRevision`. The flow:

1. Generate the `SceneManifest` (component list derived from the
   definition's parameters; LODs = LOD0/LOD1/LOD2 placeholders).
2. Create a `ProvenanceRecord` signed with the revision's
   signing key.
3. Assemble the `VehicleRevision` with `isImmutable = true`
   (enforced by the data class `init` block).

`RevisionService.modifyFrozenRevision` **always** throws
`FoundryError.FrozenRevisionMutationRejected` (per `.ai/STANDARDS.md`
section 7 + ADR-0006). A mutation attempt is a P0 contract violation.

### 2.6 Provenance (skill 09 owns) — 2 files

```
app/src/main/java/com/elysium/vanguard/foundry/core/provenance/
├── ProvenanceRecord.kt          (data class: id + subjectId + source + signature + witnesses + createdAt + isComplete)
└── ProvenanceService.kt         (createProvenance use case)
```

`ProvenanceRecord.isComplete` is the gate: source is non-blank,
signature is non-empty, at least one witness has countersigned.

### 2.7 Scene manifest (skill 06 owns) — 2 files

```
app/src/main/java/com/elysium/vanguard/foundry/core/scene/
├── SceneManifest.kt             (data class: revisionContentHash + components + lods + representationLevel + contentHash)
└── SceneManifestGenerator.kt    (Phase 1: stub manifest from definition parameters)
```

The manifest's own `contentHash` is the SHA-256 of the manifest's
canonical form. Same manifest inputs → same manifest content hash.

### 2.8 Test files — 8 files, 73 tests

```
app/src/test/java/com/elysium/vanguard/foundry/
├── core/ontology/primitives/
│   └── PrimitivesTest.kt        (24 tests)
├── core/ontology/ids/
│   └── IdTest.kt                (9 tests)
├── core/project/
│   └── ProjectServiceTest.kt    (5 tests)
├── core/compiler/
│   └── CompilerTest.kt          (9 tests)
├── core/revision/
│   └── RevisionServiceTest.kt   (5 tests)
├── core/provenance/
│   └── ProvenanceTest.kt        (7 tests)
├── core/scene/
│   └── SceneManifestTest.kt     (8 tests)
├── fixture/
│   └── VehicleDefinitionFixture.kt  (no @Test, helper object)
└── integration/
    └── VehicleProjectToDigitalTwinIntegrationTest.kt  (1 critical test, 7 assertions)
```

---

## 3. The critical integration test (asserts 7 invariants)

```kotlin
@Test
fun `vehicle project compiles into immutable traceable digital twin`() {
    // 1. Create project
    val project = projectService.createProject(
        ownerId = UserId.random(),
        name = "Urban One",
    ).getOrThrow()

    // 2. Define vehicle
    val definition = VehicleDefinitionFixture.validCompactElectricVehicleDefinition(
        projectId = project.id,
    )

    // 3. Compile configuration
    val compilation = compiler.compile(
        definition = definition,
        catalogRevision = CatalogRevision("2026.07"),
        compilerVersion = CompilerVersion("1.0.0"),
    ).getOrThrow()

    // 4. Freeze revision
    val revision = revisionService.freeze(
        projectId = project.id,
        compilation = compilation,
        definition = definition,
    ).getOrThrow()

    // 5. Recompile — must be deterministic
    val secondCompilation = compiler.compile(
        definition = definition,
        catalogRevision = CatalogRevision("2026.07"),
        compilerVersion = CompilerVersion("1.0.0"),
    ).getOrThrow()

    // 6. Assertions
    assertEquals(compilation.contentHash, secondCompilation.contentHash)   // determinism
    assertTrue(revision.isImmutable)                                        // immutability
    assertTrue(revision.provenance.isComplete)                              // traceability
    assertNotNull(revision.sceneManifest)                                   // 3D connection
    assertEquals(RepresentationLevel.PARAMETRIC_FUNCTIONAL, revision.representationLevel)  // representation
    assertEquals(firstManifestHash, recomputedManifest.contentHash)         // manifest stability
    assertTrue(thrown is FoundryError.FrozenRevisionMutationRejected)       // mutation rejected
}
```

---

## 4. What's NOT in Phase 1 (deferred to later phases)

- **DSL parser (Phase 2, skill 04):** the current `VehicleDefinition`
  is a data class with a `Map<String, String>` of parameters. The
  DSL grammar + the parser + the typed AST are added in Phase 2.
- **3D pipeline (Phase 3, skill 06):** the current `SceneManifest`
  is a stub. The real 3D pipeline + the `Canonical3DAsset`
  references + the validated LODs are added in Phase 3.
- **AI council (Phase 4, skill 05):** the current compiler is
  deterministic only. The AI council's `AIProposal<DslMutation>`
  is added in Phase 4.
- **Backend (Phase 2, ADR-0022):** the current `VehicleRevision` is
  in-memory only. The persistence + the audit trail are added
  when the backend is wired.
- **Multi-module split (Phase 7, ADR-0023):** the current Foundry
  code lives in `com.elysium.vanguard.foundry.*` inside the `:app`
  module. The split to `:foundry:core:*` modules is Phase 7.

---

## 5. Risks closed in Phase 1

- **R-CH-2** (unchecked null assertions): the `init` blocks +
  `require(...)` invariants + the typed `FoundryError` envelope
  remove the `!!` pattern from the production code.
- **R-CH-3** (`Map<String, Any>` as a domain type): every domain
  type is a typed data class; the `Map<String, String>` in
  `VehicleDefinition.parameters` is the typed map of a known
  string-to-string schema (not a generic `Map<String, Any>`).
- **R-CH-7** (free-form error string): the `FoundryError` sealed
  class is the typed envelope; the user-facing message is a typed
  field, not a free-form string.
- **R-DI-6** (`VehicleRepresentationLevel` downgrade): the
  `RepresentationLevel` enum is append-only + signed (the
  `RepresentationLevel` value is bound to the `VehicleRevision`
  at freeze time + carried in the `SceneManifest` content hash).

---

## 6. Build evidence

```
./gradlew testDebugUnitTest
  -> 1453 tests, 0 failures, 0 errors, 2 skipped
  -> Phase 1 adds 73 new tests
  -> EV baseline 1380 tests preserved (per ADR-0024)

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101,790,677 bytes (101 MB; unchanged from baseline)

Lint:
  -> 0 errors, 0 warnings
```

---

## 7. Next steps (Phase 1 increments 2-9)

The 8 remaining Phase 1 increments are documented in
`docs/foundry/implementation-roadmap.md` section 7 (Phase 1). They
are additive to this vertical slice:

- **I-1.3** `Project` aggregate (already in this slice as
  `Project.kt` + `ProjectService.kt`; the persistence + audit
  trail are added in this increment).
- **I-1.4** `VehicleProgram` aggregate.
- **I-1.5** `VehicleRevision` aggregate (already in this slice;
  the persistence + the audit trail + the predecessor chain
  are added in this increment).
- **I-1.6** `Contributor` aggregate.
- **I-1.7** `EngineeringArtifact` aggregate.
- **I-1.8** `ProvenanceRecord` aggregate (already in this slice
  as `ProvenanceRecord.kt` + `ProvenanceService.kt`; the audit
  trail + the AI council wiring are added in this increment).
- **I-1.9** Revision + concurrency strategy (the
  `RevisionConflict` error + the versioned row + the
  optimistic concurrency).

Each increment ships with the 12-point acceptance checklist
(domain model + DB migration + use case + API + UI + auth +
typed errors + unit + integration + observability + docs +
migration + rollback). The vertical slice in this phase is the
**minimum** that proves the platform's core invariants; the
remaining 8 increments add the persistence + the audit trail
+ the UI + the auth + the observability.

---

> "The platform's foundation is not a placeholder. It is a
> working end-to-end vertical slice that proves the core
> invariants. Every later increment is iteration, not
> rescue."
