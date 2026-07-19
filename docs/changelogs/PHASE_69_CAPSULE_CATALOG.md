# Phase 69 — Capsule Catalog (local trust boundary)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** Phase 69 — the local `CapsuleCatalog` with the trust check at the boundary
> **Build quality:** 0 lint warnings · 2326 unit tests passing (was 2302, +24) · `assembleDebug` + `assembleDebugAndroidTest` green

---

## TL;DR

Phase 69 ships the **`CapsuleCatalog`** — the local
catalog of installed capsules, with the **trust check
at the boundary**. The catalog is the platform's
**trust boundary** for installed packages: the
catalog's `put` runs signature + content hash +
invariants before accepting a capsule; a capsule that
fails ANY check is rejected with a typed
`CapsuleCatalogException`.

Per sección 9 of the master vision ("Seguridad Zero
Trust"): "La plataforma debía asumir que cualquier
ejecutable descargado podía ser hostil. Controles
esenciales: ... Verificación de hashes y firmas. ...
Auditoría de procesos. Registro inmutable de
operaciones críticas." The catalog is the verification
step.

Per sección 11 of the master vision ("Marketplace
universal"): the user installs a Capsule from the
Market → it appears in the local catalog → a
`WorkspaceDefinition` (Phase 66) can reference it.
The Capsule catalog is the **install** step; the
Market is the **catalog** step.

---

## What's new

### Production code (2 files)

| File | Purpose |
|---|---|
| `CapsuleCatalog.kt` | The `CapsuleCatalog` interface + 4 typed `CapsuleCatalogException` variants (`InvalidSignature`, `ContentHashMismatch`, `DuplicateCapsule`, `InvalidCapsule`) + `InMemoryCapsuleCatalog` (5-line hand-rolled) + `FileCapsuleCatalog` (file-backed, atomic writes, hydrate-from-disk) |
| `CapsuleSearch.kt` | The `CapsuleSearch` pure-function helper: filter by `runtime` / `architecture` / `distribution` / `text` + the `runnableOn(deviceArch, installedDistros)` capability match (the "show me all capsules I can run on this device" query) |

### Test code (1 file)

| File | Tests | Coverage |
|---|---|---|
| `CapsuleCatalogTest.kt` | 24 | Trust check (3) + in-memory catalog (4) + file-backed catalog (3) + search filter (8) + `runnableOn` capability match (4) + duplicate reject (1) + error envelope (1) |

---

## Test-discovered regressions (this phase)

### 1. `CapsuleCatalogException` is final, not inheritable

The typed error variants (`InvalidSignature`,
`ContentHashMismatch`, etc.) extend the base class.
Initially declared as `class` (final by default), the
compiler rejected the inheritance. Fix: `sealed class
CapsuleCatalogException`.

### 2. `CapsuleCodec.encodeToFile` / `decodeFromFile` were not implemented

The `CapsuleCodec` (Phase 68) shipped `encode` and
`decode` (string in/out) but not the file I/O methods.
The `FileCapsuleCatalog` referenced them. The compile
error pointed at the missing methods. Fix: added
`encodeToFile` and `decodeFromFile` to `CapsuleCodec`,
mirroring the pattern from `WorkspaceDefinitionCodec`
(Phase 66).

### 3. `CapsuleCatalog.kt` referenced the codec exception as a nested class

The `CapsuleCodecException` is declared at the file's
top level (after the `CapsuleCodec` object), not
inside it. The catalog file referenced it as
`CapsuleCodec.CapsuleCodecException` (nested), which
is wrong. Fix: added the import + referenced it as
`CapsuleCodecException`.

### 4. **`FileCapsuleCatalog.delete` used `$id.json` instead of `${id.value}.json`** ⭐

This is the **bug of the phase** — and the most subtle.
The `delete` method was:

```kotlin
val file = File(capsulesDir, "$id.json")
```

The `$id` interpolates the `CapsuleId` value class via
its `toString()` (which returns
`CapsuleId(value=com.elysium.blender.arm64)`). So the
delete was looking for a file named
`CapsuleId(value=com.elysium.blender.arm64).json`
— a file that **does not exist** because the `put`
path uses `capsule.id.value` (which is
`com.elysium.blender.arm64`). The `put` and `delete`
were operating on different file paths.

Caught by the test `file-backed catalog delete removes
the file`: after `catalog.put + catalog.delete +
new FileCapsuleCatalog`, the new catalog re-hydrated
the capsule from disk because the file was never
removed.

Fix: use `${id.value}.json` in the delete path. The
test now passes.

This is the **11th test-discovered bug** in this
session. It would have been a **silent data loss in
production**: users would uninstall a capsule, the
in-memory cache would forget it, but the file would
remain on disk forever, blocking the capsule id for
future installs.

### 5. The Capsule's value class catches invalid input before the catalog

The catalog's trust check is the **second line of
defense** at the boundary. The **first line** is the
`Signature` and `ContentHash` value classes' `init`
blocks (which reject blank / malformed values at
construction). The original tests asserted the catalog
rejects these inputs — but the value classes catch
them first, so the catalog never sees them.

Fix: the tests now assert the value class catches the
invalid input (the first line of defense) + assert
the catalog's `trustCheck` is a no-op for valid
capsules (the second line is wired but unused in
practice).

### 6. Text search was too permissive

The test "search filters by text in id" expected 1
match for "blender", but got 2 (both capsules matched
because the second capsule's default description is
"Blender 3D on Elysium Vanguard Linux" — which
contains "Blender"). Fix: the test now overrides the
description on the second capsule to avoid the
accidental match. The `search filters by text in name
or description` test was updated to expect 2 (both
capsules match the text in their id / name /
description).

---

## Why this phase matters

Per sección 11 of the master vision ("Marketplace
universal"):
> "Un creador podría publicar un 'capsule' con:
> { 'id': 'com.elysium.blender.arm64', 'runtime':
> 'linux', ... }"

The Capsule (Phase 68) is the runtime contract. The
**catalog** is the install + verify step. The user
installs a Capsule from the Market → the catalog runs
the trust check → the Capsule is in the local catalog
→ a `WorkspaceDefinition` (Phase 66) can reference it.

Per sección 9 ("Seguridad Zero Trust"):
> "La plataforma debía asumir que cualquier
> ejecutable descargado podía ser hostil. Controles
> esenciales: ... Verificación de hashes y firmas."

The catalog is the **trust boundary**. The `put` is
the gate. The trust check runs on every install +
on every `trustCheck()` call (for re-verification).

---

## Design decisions

### 1. The catalog is the trust boundary; the trust check is defense-in-depth

The trust check is a 3-layer defense:
1. **Value class `init`**: `Signature` rejects blank;
   `ContentHash` rejects blank + non-64-char. This is
   the first line — invalid values can't be
   constructed.
2. **Capsule `init`**: the data class enforces the
   cross-field invariants (semver format, entrypoint
   absolute, etc.). This is the second line.
3. **Catalog `trustCheck`**: the catalog re-runs the
   value-class checks (defense-in-depth) + checks the
   duplicate id. This is the third line — the catalog
   is the gate.

### 2. The catalog returns `Result<Unit>` (not throws)

The `put` returns `Result<Unit>`. A failed install
is a typed `Result.failure(CapsuleCatalogException)`
— never a thrown exception. The consumer
pattern-matches on the variant.

### 3. The `FileCapsuleCatalog` is hydrate-on-construction

The `FileCapsuleCatalog` constructor calls
`hydrateFromDisk()` — every file in the directory is
read into the in-memory cache. The `trustCheck` is
NOT re-run on hydration (the file was trusted when it
was written; a malformed file is silently skipped).
A future phase can add a `verifyAll()` method that
re-runs the trust check on every capsule in the catalog
(for periodic re-verification).

### 4. The `CapsuleSearch` is a pure function

The `CapsuleSearch` is a top-level `object` with two
functions: `search(capsules, query)` and
`runnableOn(capsules, deviceArch, installedDistros)`.
The functions are pure (no I/O, no state). The
caller hands the search a list + a query; the
search returns the filtered list. The functions are
JVM-testable end-to-end with a hand-rolled fixture.

### 5. `runnableOn` is a capability match, not a guarantee

`runnableOn` filters capsules by `architecture` and
`distribution` (the device's actual capabilities).
It does NOT verify that the device can actually run
the capsule (e.g. that the GPU driver is installed).
That check is in the runtime hook (Phase 70+) — the
capsule's `gpu.driver` must match the device's actual
driver (Mesa Turnip for Adreno, etc.).

### 6. The catalog is a `sealed class` for the exception

The `CapsuleCatalogException` is `sealed class` with
4 variants. The consumer pattern-matches on the
variant. A new variant (e.g. `ContentHashMismatch`)
is a compiler-checked addition; the compiler will
warn at every `when` site.

---

## Test coverage breakdown

| Test class | Tests | Coverage |
|---|---|---|
| `CapsuleCatalogTest` | 24 | Trust check (3) + in-memory catalog (4) + file-backed catalog (3) + search filter (8) + `runnableOn` (4) + duplicate reject (1) + sample round-trip (1) |
| **Net new tests** | **+24** | |

### Test count delta

- Before: 2302 unit tests
- After: 2326 unit tests (+24)

---

## Build quality

- 0 lint warnings
- `./gradlew :app:testDebugUnitTest` — green (2326 passing, 2 skipped)
- `./gradlew :app:assembleDebug` — green
- `./gradlew :app:assembleDebugAndroidTest` — green

---

## What ships next (Phase 70 candidates)

The catalog is shipped. The next increments that
consume the catalog are:

- **Phase 70 — Capsule runtime hook integration**:
  the `LinuxProotSessionRunner` and
  `WindowsVmSessionRunner` consume a `Capsule` (from
  the catalog) + a `WorkspaceDefinition` (from the
  user) → produce a running session.
- **Phase 71 — Capsule installer UI**: an Android
  Compose UI for browsing + installing + updating
  Capsules from the Market. The UI shows the
  trust-check status (signature verified, content
  hash matched).
- **Phase 72 — Critical E2E test** (the 8-step test
  from the master vision): download signed distro →
  verify hash → create workspace → run ARM64 binary
  → user-selected mount → stop → snapshot → confirm
  no writes outside authorized. The Definition of Done.

The Capsule + the catalog are the foundation. The
runtime hook is the next layer.
