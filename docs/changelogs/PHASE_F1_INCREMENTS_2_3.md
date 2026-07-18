# Phase F1 — Increments 2-3: VehicleProgram + Contributor + EngineeringArtifact + Optimistic Concurrency

> **Status:** shipped 2026-07-17 against git head `9683633` (the first vertical slice).
> **Increments:** I-1.4 (VehicleProgram) + I-1.6 (Contributor) + I-1.7 (EngineeringArtifact) + I-1.9 (optimistic concurrency strategy).
> **Gate:** G1 progress (8 of 9 increments now in place; only I-1.3 + I-1.5 + I-1.8 persistence + audit-trail wiring remain).
> **Build evidence:**
> - `testDebugUnitTest` — **1487 tests, 0 failures, 0 errors, 2 skipped** (1380 EV baseline + 107 Foundry; +34 new in this commit)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What's in this commit

This commit ships **3 new aggregates** + the **optimistic-concurrency
strategy** that locks in the platform's "no silent overwrite"
invariant. Every mutable aggregate now has a `version: Long` field
that the services check on every mutation.

### 3 new aggregates

| Aggregate | Owner | Purpose |
|---|---|---|
| `VehicleProgram` | skill 03 (ontology) | A vehicle family under a Project (e.g. "Urban Line"). Carries an append-only `revisions` list. |
| `Contributor` | skill 09 (IP / provenance) | A human or organization that contributed. Has PII (email) that is encrypted at rest in Phase 5. |
| `EngineeringArtifact` | skill 03 (ontology) | A typed reference to a content-addressed artifact (glTF / STEP / USD / PDF / image). |

### Optimistic concurrency strategy

| Component | Purpose |
|---|---|
| `OptimisticConcurrency.check(...)` | A pure helper that returns `null` on success or a `FoundryError.RevisionConflict` on mismatch. |
| `version: Long` field on every mutable aggregate | The version is `0L` on creation; every successful mutation increments it. |
| Every mutation method now takes `expectedVersion: Long` | The caller must supply the version they last read; the service checks it before mutating. |
| `FoundryError.RevisionConflict` has retry classification `RETRYABLE_IDEMPOTENT_ONLY` | Per `.ai/AGENTS.md` 24.4: the client MAY retry only if the operation is idempotent. |

The strategy implements **R-CO-2** in
`docs/foundry/risk-register.md` ("lost update on optimistic
concurrency"). A lost update is now a typed error, not a silent
data loss.

---

## 1. Architecture decisions (ADRs applied)

- **ADR-0001** (`Money` is `BigDecimal`): the `Unit` primitive
  + the contributor's PII handling both honor the discipline
  (the contributor's email is documented as "must be encrypted
  at rest"; the encryption is wired in Phase 5).
- **ADR-0006** (append-only): the `VehicleProgram.revisions` list
  is append-only; a revision is added, never removed.
- **ADR-0009** (`CorrelationId`): not affected in this commit;
  the helper is added in Phase 7 (per skill 15).

---

## 2. Files added (10 total)

### 2.1 VehicleProgram aggregate (2 files)

```
app/src/main/java/com/elysium/vanguard/foundry/core/program/
├── VehicleProgram.kt            (data class + VehicleProgramStatus enum)
└── VehicleProgramService.kt     (createProgram + addRevision + transitionStatus)
```

### 2.2 Contributor aggregate (2 files)

```
app/src/main/java/com/elysium/vanguard/foundry/core/contributor/
├── Contributor.kt               (data class + ContributorRole enum)
└── ContributorService.kt        (createContributor + updateRole)
```

### 2.3 EngineeringArtifact aggregate (2 files)

```
app/src/main/java/com/elysium/vanguard/foundry/core/artifact/
├── EngineeringArtifact.kt       (data class + EngineeringArtifactFormat enum, 10 values)
└── EngineeringArtifactService.kt (createArtifact + reassignSubject)
```

### 2.4 Optimistic concurrency (1 file)

```
app/src/main/java/com/elysium/vanguard/foundry/core/concurrency/
└── OptimisticConcurrency.kt     (check helper + RevisionConflict wiring)
```

### 2.5 Test files (5 files, 34 tests)

```
app/src/test/java/com/elysium/vanguard/foundry/
├── core/program/
│   └── VehicleProgramServiceTest.kt    (9 tests)
├── core/contributor/
│   └── ContributorServiceTest.kt       (8 tests)
├── core/artifact/
│   └── EngineeringArtifactServiceTest.kt  (7 tests)
├── core/concurrency/
│   └── OptimisticConcurrencyTest.kt    (5 tests)
└── core/project/
    └── ProjectServiceTest.kt           (extended; +5 tests = 10 total)
```

---

## 3. The 10-format `EngineeringArtifactFormat` enum

```kotlin
enum class EngineeringArtifactFormat {
    GLB,        // glTF binary container
    GLTF,       // glTF JSON + binary
    USD,        // Universal Scene Description
    USDZ,       // USD zipped
    STEP,       // ISO 10303 STEP file
    IGES,       // Initial Graphics Exchange Specification
    FBX,        // Autodesk FBX
    PDF,        // PDF document
    IMAGE,      // Raster image (PNG, JPG, WebP)
    OTHER,      // Other format (new formats are ADRs)
}
```

The 10 values cover the 3D pipeline + the regulatory pipeline +
the user-uploaded datasheets. New formats are added as ADRs.

---

## 4. The 5 `ContributorRole` values

```kotlin
enum class ContributorRole {
    DESIGNER,   // designs the vehicle via the DSL
    ENGINEER,   // reviews + signs off a design
    MECHANIC,   // diagnoses + repairs a vehicle in the field
    REVIEWER,   // approves a regulatory submission
    ADMIN,      // full administrative access
}
```

The role determines the contributor's permissions + the
contributor's visibility in the audit trail.

---

## 5. The optimistic concurrency contract

```kotlin
val program = service.createProgram(ProjectId.random(), "Urban Line").getOrThrow()
// program.version == 0L

val firstUpdate = service.addRevision(program, revisionId, expectedVersion = 0L).getOrThrow()
// firstUpdate.version == 1L

// Stale version: the client last saw v0; the server is at v1
val conflict = service.addRevision(firstUpdate, newRevisionId, expectedVersion = 0L)
assertTrue(conflict.isFailure)
val error = conflict.exceptionOrNull() as FoundryError.RevisionConflict
assertEquals("VehicleProgram", error.aggregateType)
assertEquals(0L, error.expectedVersion)
assertEquals(1L, error.actualVersion)
assertEquals(FoundryError.RetryClassification.RETRYABLE_IDEMPOTENT_ONLY, error.retryClassification)
```

The contract is enforced by:
1. Every mutable aggregate has a `version: Long` field.
2. Every mutation method takes `expectedVersion: Long`.
3. The service calls `OptimisticConcurrency.check(...)` before
   any mutation.
4. On mismatch, the service returns `Result.failure(RevisionConflict)`.
5. The client is expected to re-read + retry the operation
   (with the user's explicit consent).

---

## 6. The 3-aggregate impact on the integration test

The integration test
(`VehicleProjectToDigitalTwinIntegrationTest`) is **not
modified** in this commit. The 3 new aggregates + the
optimistic-concurrency strategy are additive; the existing
test continues to pass.

A future increment (I-1.3 + I-1.5 + I-1.8) will wire the
persistence + the audit trail, at which point the integration
test will be extended to cover the new aggregates.

---

## 7. Build evidence

```
./gradlew testDebugUnitTest
  -> 1487 tests, 0 failures, 0 errors, 2 skipped
  -> Foundry tests: 107 (was 73; +34 in this commit)
  -> EV baseline 1380 tests preserved (per ADR-0024)

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101 MB (unchanged from baseline)

Lint:
  -> 0 errors, 0 warnings
```

---

## 8. Next steps (Phase 1 increments remaining)

The remaining 3 Phase 1 increments are persistence + audit-trail
wiring:

- **I-1.3** Project aggregate persistence (Room DB + DAO + migration)
- **I-1.5** VehicleRevision aggregate persistence (Room DB + predecessor chain)
- **I-1.8** ProvenanceRecord aggregate persistence (Room DB + audit-trail
  storage + signature verification at load time)

These increments are the **next vertical slice** that will be
shipped in the next commit. The shape is:
- Add Room DB entities for Project, VehicleProgram, Contributor,
  EngineeringArtifact, VehicleRevision, ProvenanceRecord.
- Add DAOs.
- Add a migration V1__foundry_domain.sql.
- Add a Hilt module that wires the DB.
- Add a `FoundryRepository` that mediates between the domain
  services and the DB.
- Add tests that exercise the persistence path (with an
  in-memory Room DB).

The persistence + audit-trail layer is the **last vertical slice
of Phase 1**. After that, Phase 2 (DSL parser + full compiler
pipeline) begins.

---

> "The platform's foundation grows by addition, never by
> subtraction. Every increment is a vertical slice that ships
> end-to-end."
