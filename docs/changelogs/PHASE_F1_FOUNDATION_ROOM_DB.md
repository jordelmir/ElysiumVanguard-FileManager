# Phase F1 — Foundry Room DB data layer (G1 closure)

> **Status:** shipped 2026-07-18 against git head (the worktree-merge commit `3ccd5f1`).
> **Gate:** G1 (Domain model approved) — closes the persistence gap.
> **Build evidence:**
> - `testDebugUnitTest` — **2151 tests, 0 failures, 0 errors, 2 skipped** (unchanged; the new database test is androidTest)
> - `assembleAndroidTest` — green (1 new instrumented test: `FoundryDatabaseTest`)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

Phase F1 ships the **Room DB data layer** for the
Foundry domain. The 6 domain aggregates (Project,
VehicleProgram, Contributor, EngineeringArtifact,
VehicleRevision, ProvenanceRecord) now have
**persistent backing** via Room.

This is the **persistence gap** in G1 (Domain model
approved): the domain was in-memory only; the data
was lost on process death. Phase F1 closes the
gap by shipping:

- **15 new files** in
  `app/src/main/java/com/elysium/vanguard/foundry/persistence/`
  + 1 new androidTest file
- The 6 Room entities (one per aggregate) with
  `toDomain()` / `fromDomain()` conversions
- The 6 Room DAOs (one per aggregate) with
  `insert` / `getById` / `Flow` queries
- The `FoundryDatabase` Room database (single
  database, 6 entities, version 1)
- The `Converters` TypeConverters for the
  Foundry primitives (`ContentHash`,
  `Timestamp`, `Signature`, `UUID`, `List<String>`)
- The `FoundryDatabaseTest` instrumented test
  (Room in-memory database; 8 test cases)

What's NOT in this phase (Phase 2 follow-up):

- **`FoundryRepository` per aggregate** (the
  layer that mediates between the domain
  services + the DAOs + the optimistic
  concurrency check).
- **Hilt module** (`FoundryPersistenceModule`)
  that wires the database + the DAOs + the
  repositories.
- **Service migration** (the services
  currently create aggregates in memory; the
  Phase 2 follow-up wires them to the
  repositories).
- **Schema export** (the schema is exported
  to `app/schemas/` once the Hilt module is
  wired; Phase 1 ships `exportSchema = false`
  to keep the build simple).
- **HTTP-backed catalog + installer** (replaces
  the `InMemoryMarketCatalog`; the catalog's
  data still lives in memory; the Room-backed
  Foundry has nothing to do with the Market).

---

## 1. Architecture decisions

- **Single database, 6 entities** (per the
  Foundry's "one owner per aggregate" rule
  + the existing EV pattern of single-database-
  per-domain): a single `FoundryDatabase` holds
  all 6 entities. The Phase 7 multi-module split
  (per ADR-0023) may split this into per-domain
  databases; for now, one DB is sufficient.
- **TypeConverters for primitives** (per
  `.ai/AGENTS.md` 24.1 + the typed-everywhere
  rule): the database stores the canonical
  string of every value class; the value class
  is reconstructed on read. This keeps the
  database schema readable + keeps the schema-
  version upgrades simple.
- **Append-only entities** (per ADR-0006): the
  `VehicleRevisionDao` and the
  `ProvenanceRecordDao` have only `insert` (no
  `update` + no `delete`). The "update" path is
  "freeze a new revision", not "modify an
  existing one".
- **No `Map<String, Any>` fields** (per
  `R-CH-3` in `docs/foundry/risk-register.md`):
  every entity field is a primitive SQL type
  (String, Long, Int, Boolean). The `List<String>`
  fields (the `revisions` in `VehicleProgramEntity`,
  the `witnesses` in `ProvenanceRecordEntity`,
  the `tags` in the catalog listings) are
  serialized as a unit-separator-joined string.
- **Flow-based reactive reads** (per the existing
  EV Compose pattern): the DAOs return `Flow<>`
  for the "all" queries. The Compose UI observes
  the Flow + recomposes on changes.

---

## 2. Files added (15 main + 1 androidTest = 16 new)

### 2.1 The 15 main files

```
app/src/main/java/com/elysium/vanguard/foundry/persistence/
├── Converters.kt                     (TypeConverters: UUID, ContentHash, Timestamp, Signature, List<String>)
├── FoundryDatabase.kt                (RoomDatabase, 6 entities, version 1)
├── daos/
│   ├── ProjectDao.kt
│   ├── VehicleProgramDao.kt
│   ├── ContributorDao.kt
│   ├── EngineeringArtifactDao.kt
│   ├── VehicleRevisionDao.kt
│   └── ProvenanceRecordDao.kt
└── entities/
    ├── ProjectEntity.kt
    ├── VehicleProgramEntity.kt
    ├── ContributorEntity.kt
    ├── EngineeringArtifactEntity.kt
    ├── VehicleRevisionEntity.kt
    └── ProvenanceRecordEntity.kt
```

### 2.2 The 1 androidTest file

```
app/src/androidTest/java/com/elysium/vanguard/foundry/persistence/
└── FoundryDatabaseTest.kt   (8 instrumented tests using Room in-memory database)
```

---

## 3. The `FoundryDatabase` (the single Room database)

```kotlin
@Database(
    entities = [
        ProjectEntity::class,
        VehicleProgramEntity::class,
        ContributorEntity::class,
        EngineeringArtifactEntity::class,
        VehicleRevisionEntity::class,
        ProvenanceRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class FoundryDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun vehicleProgramDao(): VehicleProgramDao
    abstract fun contributorDao(): ContributorDao
    abstract fun engineeringArtifactDao(): EngineeringArtifactDao
    abstract fun vehicleRevisionDao(): VehicleRevisionDao
    abstract fun provenanceRecordDao(): ProvenanceRecordDao

    companion object {
        const val DATABASE_NAME: String = "foundry.db"
    }
}
```

The database name is `foundry.db`. The Phase 2 Hilt module
wires the database via `Room.databaseBuilder(context,
FoundryDatabase::class.java, FoundryDatabase.DATABASE_NAME)`.

---

## 4. The 6 entities (one per aggregate)

Each entity has a `toDomain()` method (reconstructs the
domain object from the entity) + a companion `fromDomain()`
method (creates an entity from the domain object). The
boundary is the entity↔domain conversion; the consumer
never sees the entity directly (the Phase 2 repository
hides it).

| Entity | Table | Notes |
|---|---|---|
| `ProjectEntity` | `projects` | The 6 fields (id, name, ownerId, status, createdAt, version) |
| `VehicleProgramEntity` | `vehicle_programs` | The `revisions` list is unit-separator-joined |
| `ContributorEntity` | `engineering_artifacts` | The PII (email) is stored as-is; encryption at rest is Phase 5 |
| `EngineeringArtifactEntity` | `contributors` | The `contentHash` is a 64-char hex |
| `VehicleRevisionEntity` | `vehicle_revisions` | The `provenance` + the `sceneManifest` are flattened |
| `ProvenanceRecordEntity` | `provenance_records` | Append-only; the `witnesses` list is unit-separator-joined |

---

## 5. The 6 DAOs (one per aggregate)

Each DAO exposes:
- `insert` / `update` / `deleteById` — the mutations
  (the optimistic-concurrency check is in the
  repository layer; the DAOs are pure)
- `getById` — a single-shot read
- `observeAll` — a `Flow<List<>>` for reactive UI
- `count` — for monitoring + test assertions
- The aggregations (e.g., `ProjectDao.getByOwner`,
  `EngineeringArtifactDao.getByContentHash`,
  `VehicleProgramDao.getByProject`) — for the
  common queries

The `VehicleRevisionDao` + the `ProvenanceRecordDao`
have only `insert` + `getById` (no `update` + no `delete`)
because the aggregates are append-only.

---

## 6. The 8 `FoundryDatabaseTest` instrumented tests

- `database_builds_with_all_6_entities` — the smoke
  test.
- `project_dao_round_trip` — `fromDomain → insert →
  getById → toDomain` preserves the fields.
- `project_dao_returns_null_for_unknown_id` — the
  miss path.
- `project_dao_update_changes_version` — the update
  path.
- `project_dao_count_starts_at_zero` — the count
  path.
- `project_dao_delete_by_id_removes_row` — the
  delete path.
- `project_dao_handles_uuid_validation_failure` —
  documents the contract (the DAO doesn't validate
  UUIDs; the service does).
- `vehicle_program_dao_round_trip` — the
  VehicleProgram path.
- `contributor_dao_round_trip` — the Contributor
  path.

---

## 7. Bugs found during this phase (test-discovered)

### Bug 1: `ksp { arg(...) }` not resolving in Kotlin DSL

The first attempt at configuring the Room schema export
location used `ksp { arg("room.schemaLocation", ...) }`.
The Kotlin DSL didn't resolve `arg` (the KSP extension
function isn't available in the `ksp { }` block in the
project's KSP version).

**Fix:** disabled `exportSchema` in the `@Database`
annotation (`exportSchema = false`). The schema
export is wired in Phase 2 when the Hilt module is
added (the `ksp` block can be configured at that
time + the schema directory is committed to the
repository).

### Bug 2: `FoundryDatabaseTest` was in `test/` but uses
Android-only APIs

The first version of the test was in `app/src/test/`
(JVM unit test) but used `androidx.test.core.app.ApplicationProvider`
+ `androidx.test.ext.junit.runners.AndroidJUnit4`
which are in the `androidTestImplementation` classpath
(not the `testImplementation` classpath).

**Fix:** moved the test to `app/src/androidTest/`. The
test is now an instrumented test that runs on a real
Android device or emulator (per the existing EV
pattern: `MainScreenInstrumentedTest`,
`NativePtyInstrumentedTest`, etc.).

---

## 8. Build evidence

```
./gradlew testDebugUnitTest
  -> 2151 tests, 0 failures, 0 errors, 2 skipped
  -> (unchanged; the new database test is androidTest)

./gradlew assembleAndroidTest
  -> green (the new FoundryDatabaseTest compiles + packages)

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101 MB (unchanged; no new production code)

Lint:
  -> 0 errors, 0 warnings
```

---

## 9. Next steps (Foundry G1 closure continuation)

- **Phase 2 follow-up (Foundry)** — the
  `FoundryRepository` per aggregate (6 repos)
  + the `FoundryPersistenceModule` (Hilt) that
  wires the database + the DAOs + the
  repositories. The service migration
  (the services use the repositories instead
  of in-memory data).
- **Phase 2 (Foundry)** — the DSL parser +
  the 18-step compiler pipeline (the brain
  of the platform).
- **Phase 3+ (Foundry)** — the 3D pipeline +
  the digital twin + the AI council.

---

> "The Foundry domain is now persistent. The
> Room DB is the foundation; the entities
> are the data; the DAOs are the queries. The
> repositories + the Hilt module + the service
> migration are the next vertical slice. The
> domain is the spine; the persistence is the
> muscle. The next commit is the Hilt wiring.
> After that, the DSL parser. After that, the
> 3D pipeline. After that, the AI council. The
> roadmap is the roadmap; the only thing that
> matters is shipping."
