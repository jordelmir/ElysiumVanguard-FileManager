# Phase F1 — Foundry Persistence Repositories (G1 closure)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** Persistence layer for the 6 Foundry aggregates
> **Build quality:** 0 lint warnings · 2179 unit tests passing (was 2151, +28) · 14 new androidTest tests · `assembleDebug` + `assembleDebugAndroidTest` green

---

## TL;DR

Phase F1 (G1 closure) delivers the **persistence layer** for the
Foundry platform. The 6 aggregates (Project, VehicleProgram,
Contributor, EngineeringArtifact, VehicleRevision,
ProvenanceRecord) now have a complete persistence story:

1. **6 repository interfaces** (one per aggregate) define the
   persistence contract.
2. **6 Room-backed implementations** wire the DAOs to the domain
   services, with optimistic-concurrency enforced at the
   boundary.
3. **6 in-memory implementations** mirror the Room contract for
   fast JVM unit tests.
4. **A Hilt module** wires the Room-backed impls into the
   production graph.
5. **3 new test suites** validate the contract end-to-end.

A test-discovered regression was fixed in this phase: the
`VehicleRevisionEntity.toDomain()` reconstruction was using the
**provenance record's id** as the **provenance's subjectId**
(losing the actual subjectId), and was dropping the
**witnesses** list. The new `provenance_subject_id` +
`provenance_witnesses` columns fix the round-trip; a new
test asserts `provenance.isComplete` after the round trip.

---

## Why this phase matters

Per `docs/foundry/implementation-roadmap.md` section 12 + I-1.5
+ `.ai/AGENTS.md` 24.1:

- The repository layer is the **only** path to a persistent
  mutation. Domain services are pure; the repository is the
  I/O boundary.
- The optimistic-concurrency check is enforced at the
  **persistence boundary** (not the service). The service
  produces a new `version = old.version + 1` snapshot; the
  repository verifies the caller's `expectedVersion` matches the
  stored version before writing.
- The `FoundryError` envelope is the **typed contract** — every
  mutation returns `Result<Unit, FoundryError>` so the
  orchestrator can pattern-match on the typed error.

This phase closes **G1** (the "data layer" gate) so the
orchestrator (skill 00) can compose the services +
repositories end-to-end.

---

## Files added / modified

### Production code (6 new files in `persistence/repository/`)

| File | Purpose |
|---|---|
| `FoundryRepository.kt` | Repository contract: `MutableAggregateRepository` + `AppendOnlyRepository` interfaces + 6 per-aggregate interfaces |
| `RoomProjectRepository.kt` | Room-backed Project repository |
| `RoomVehicleProgramRepository.kt` | Room-backed VehicleProgram repository |
| `RoomContributorRepository.kt` | Room-backed Contributor repository |
| `RoomEngineeringArtifactRepository.kt` | Room-backed EngineeringArtifact repository |
| `RoomVehicleRevisionRepository.kt` | Room-backed VehicleRevision repository (append-only) |
| `RoomProvenanceRecordRepository.kt` | Room-backed ProvenanceRecord repository (append-only) |
| `di/FoundryPersistenceModule.kt` | Hilt module: `FoundryDatabase` + the 6 repositories (all `@Singleton`) |

### Production code (1 modified file)

- `persistence/entities/VehicleRevisionEntity.kt` — **bug fix**:
  added `provenance_subject_id` + `provenance_witnesses`
  columns; `toDomain()` now reconstructs the correct
  `ProvenanceRecord.subjectId` and the full `witnesses` list.
- `persistence/daos/ProvenanceRecordDao.kt` — added
  `observeAll(): Flow<List<ProvenanceRecordEntity>>` (for the
  append-only repo's reactive read).

### Test code (3 new files)

| File | Tests | Purpose |
|---|---|---|
| `test/.../persistence/repository/InMemoryFoundryRepositories.kt` | (impl) | 6 in-memory repository implementations (JVM-friendly) |
| `test/.../persistence/repository/FoundryRepositoryContractTest.kt` | 21 | Contract test for the in-memory impls (CRUD + optimistic concurrency + reactive Flow) |
| `test/.../integration/FoundryServiceRepositoryIntegrationTest.kt` | 7 | Service + repository pipeline round-trip |
| `androidTest/.../persistence/FoundryRepositoryRoomTest.kt` | 14 | Room-backed round-trip end-to-end |

---

## Design decisions

### 1. Two interface families: `MutableAggregateRepository` vs `AppendOnlyRepository`

Per ADR-0006:
- `MutableAggregateRepository<T, ID>`: `Project`,
  `VehicleProgram`, `Contributor`, `EngineeringArtifact` —
  these have `version: Long` + support `update` + `delete`.
- `AppendOnlyRepository<T, ID>`: `VehicleRevision`,
  `ProvenanceRecord` — these are **immutable + signed**; the
  repository has only `append` + `getById` + `observeAll` +
  `count` + aggregate-specific queries. There is no `update` +
  no `delete`. The hard guard (`modifyFrozenRevision` +
  `ProvenanceIncomplete`) is at the service layer; the
  repository enforces the persistence-level guard.

### 2. Optimistic concurrency at the boundary

The repository **always** reads the stored version before
writing. The `OptimisticConcurrency.check()` helper
(Phase F1 increments 2-3) returns a typed
`FoundryError.RevisionConflict` on mismatch. The repository
maps the error to `Result.failure(...)` so the orchestrator
can pattern-match.

The pattern (per service + repo pair):
```
val result = service.rename(project, newName, expectedVersion = 0L)
if (result.isSuccess) {
    val newProject = result.getOrThrow()  // version = 1L
    val persisted = projectRepo.update(newProject, expectedVersion = 0L)
    if (persisted.isFailure) {
        // Concurrent writer won the race; re-read + retry (with user consent)
    }
}
```

The check is **defense-in-depth**: the SQLite update is
serialized at the storage level; the version check is the
read-side guard against stale reads.

### 3. `VehicleRevisionEntity` bug fix (the test-discovered regression)

The original `toDomain()` reconstruction was lossy:
```kotlin
// BEFORE (bug)
subjectId = provenanceId  // <-- record id, not subject id
witnesses = emptyList()    // <-- dropped
```

The new entity has two additional columns:
```kotlin
@ColumnInfo(name = "provenance_subject_id")
val provenanceSubjectId: String,

@ColumnInfo(name = "provenance_witnesses")
val provenanceWitnesses: String,  // unit-separator-joined signatures
```

`toDomain()` now reconstructs the correct `ProvenanceRecord`:
- `subjectId = provenanceSubjectId` (the compilation's
  content hash)
- `witnesses = provenanceWitnesses.split("\u001F").map(::Signature)`

The new test `room_vehicle_revision_repository_append_then_getById_preserves_provenance`
asserts `provenance.isComplete == true` after the Room
round-trip. Without the bug fix, the test would fail because
`isComplete` requires `subjectId.isNotBlank() &&
signature.value.isNotEmpty() && witnesses.isNotEmpty()`.

### 4. Reactive reads with `Flow`

Both Room and in-memory impls return `Flow<List<...>>` for
the "all" reads. The in-memory impl uses `MutableStateFlow`
which replays the current snapshot on subscription. The Room
impl uses Room's native `Flow` support. The contract is
identical from the consumer's perspective.

### 5. `getBySubject` ordering

The Room DAO orders by `created_at_epoch_ms ASC` (the
audit-trail insertion order). The in-memory impl sorts by
`createdAt.epochMs` to match. The contract test
`provenance record repository append then getBySubject
preserves records in insertion order` uses **distinct
timestamps** to make the assertion deterministic regardless
of insertion order.

---

## Test coverage breakdown

### JVM unit tests (`testDebugUnitTest`)

| Test class | New tests | Coverage |
|---|---|---|
| `FoundryRepositoryContractTest` | 21 | CRUD + duplicate reject + optimistic concurrency + Flow reactivity for all 6 repositories |
| `FoundryServiceRepositoryIntegrationTest` | 7 | Service creates + repo persists + stale update rejected (full pipeline for Project, VehicleProgram, Contributor, EngineeringArtifact, ProvenanceRecord, VehicleRevision) |
| **Total new JVM tests** | **+28** | |

### Instrumented tests (`androidTest`)

| Test class | New tests | Coverage |
|---|---|---|
| `FoundryRepositoryRoomTest` | 14 | Room round-trip for all 6 repositories + provenance.isComplete + scene manifest content hash stability |
| **Total new androidTest tests** | **+14** | |

### Test count delta

- Before: 2151 unit tests
- After: 2179 unit tests (+28)
- Plus 14 new androidTest tests
- All passing · 0 failures · 2 skipped (unchanged)

---

## Build quality

- 0 lint warnings (compile + lint clean)
- `./gradlew :app:testDebugUnitTest` — green
- `./gradlew :app:assembleDebug` — green
- `./gradlew :app:assembleDebugAndroidTest` — green

---

## What ships next (Phase F2 — DSL + real compiler)

The repository layer is the seam. Phase F2 ships:
- The 18-step deterministic compiler pipeline (per skill 04)
- The DSL grammar (per skill 02)
- The orchestrator that composes the services + repositories

The G1 (data layer) gate is now closed; the next gate is G2
(DSL + compiler real). The orchestrator (skill 00) is the
glue that composes the layers.
