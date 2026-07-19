# Phase 68 — Capsule (universal package manifest)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** Phase 68 — the typed `Capsule` schema + JSON codec + sample
> **Build quality:** 0 lint warnings · 2302 unit tests passing (was 2268, +34) · `assembleDebug` + `assembleDebugAndroidTest` green

---

## TL;DR

Phase 68 ships the **Capsule** — the universal package
manifest for the Elysium Vanguard Universal Platform.
The Capsule is the **bridge** between:

- `MarketListing` (Phase 59, "what's in the catalog" — signed distribution)
- `WorkspaceDefinition` (Phase 66, "how the user runs it locally" — orchestration spec)
- `OrchestratedWorkspace` (Phase 67, "the runtime plan")

A creator publishes a **Capsule** with a typed manifest
that declares: what the package is, where it runs, how
it starts, what hardware it needs, and what access it
needs. The JSON layout matches the master vision
doc's literal example:

```json
{
  "id": "com.elysium.blender.arm64",
  "runtime": "linux",
  "architecture": "arm64",
  "distribution": "elysium-linux-1",
  "entrypoint": "/usr/bin/blender",
  "gpu": { "api": "vulkan", "driver": "turnip" },
  "permissions": { "network": false, "storage": ["user-selected"] }
}
```

---

## What's new

### Production code (2 files)

| File | Purpose |
|---|---|
| `Capsule.kt` | The root typed schema + sub-schemas: `CapsuleApiVersion`, `CapsuleId`, `Runtime` (LINUX / WINDOWS / MACOS / WEB), `Architecture` (ARM64 / ARM32 / X86_64 / X86 / ANY), `Distribution`, `EntryPoint`, `GpuConfig`, `GpuApi`, `GpuDriver`, `Permissions`, `StorageScope` |
| `CapsuleCodec.kt` | The JSON serializer/deserializer (Gson + custom type adapters) + the typed `CapsuleCodecException` error envelope |

### Test code (1 file)

| File | Tests | Coverage |
|---|---|---|
| `CapsuleTest.kt` | 34 | Data-class invariants (id format, semver, entrypoint absolute, signature/contentHash non-blank) + 4 runtimes + 5 architectures + 8 GPU drivers + 4 storage scopes round-trip + permissions enforcement (sandbox rejected) + codec round-trip + determinism + golden file + typed error envelope |

### Sample + docs (2 files)

| File | Purpose |
|---|---|
| `docs/capsule/samples/blender.arm64.json` | The canonical sample from the master vision doc — `com.elysium.blender.arm64` with VULKAN/TURNIP GPU, USER_SELECTED storage, no network |
| `docs/changelogs/PHASE_68_CAPSULE.md` | This changelog |

---

## Test-discovered regressions (this phase)

### 1. `ContentHash` / `Signature` import path

The Capsule lives in the `runtime/` package, but
`ContentHash` and `Signature` live in the `foundry/`
package. Initial import was the wrong path. Caught at
compile time.

### 2. Sample hash was 60 chars, not 64

The `ContentHash` primitive requires a 64-char hex
string. The sample was 60 chars. The codec tests caught
it (5 tests failed with the same error). Fix: extend
the sample to 64 chars in both the test + the JSON
sample file.

### 3. Permissions: the rejected case was the wrong case

The `Permissions.init` originally had
`require(storage.isNotEmpty() || !network)`. This is
logically equivalent to `!(storage.isEmpty() && network)`,
which rejects a "network-only CLI" — exactly the case
the vision doc says is valid (a CLI that talks to a
remote server). The correct rejection is a "pure
sandbox" — `storage.isEmpty() && !network`. Fix:
inverted the condition + updated the error message.

### 4. Error-message case-sensitivity

The tests asserted `e.message!!.contains("id")` for
errors thrown by the `CapsuleId` value class. The
actual message is `"CapsuleId.value must match ..."`
(capital C). Caught by 3 tests. Fix: assert on
`"CapsuleId"` (capital C) and `"ContentHash"` /
`"Signature"` (capital initial).

---

## Why this phase matters

Per sección 11 of the master vision ("Marketplace
universal"):
> "Un creador podría publicar un 'capsule' con:
> {
>   'id': 'com.elysium.blender.arm64',
>   'runtime': 'linux',
>   'architecture': 'arm64',
>   ...
> }"

The Capsule is the **runtime contract** for a published
package. The `MarketListing` is the **distribution
contract** (signed + content-addressed); the Capsule is
the **execution contract** (typed manifest).

The Capsule is **typed** (per `.ai/AGENTS.md` 24.1):
every field has a type. A free-form string is never the
value of a permission, a path, or an enum. The Capsule
enforces:

- `CapsuleId` is a Java package name (e.g. `com.elysium.blender.arm64`).
- `version` is semver (with optional pre-release tag).
- `entrypoint.executable` is an absolute path.
- `gpu.api` + `gpu.driver` are typed enums (the runtime
  matches against the device's actual capabilities).
- `permissions` declares the **minimum** access the
  capsule needs (the runtime enforces it).

The Capsule is **versioned** (`apiVersion: elysium.capsule/v1`):
a breaking change is a new version + a migration tool.

---

## Design decisions

### 1. The Capsule JSON layout matches the vision doc exactly

The vision doc's example uses lowercase strings for
`runtime`, `architecture`, `gpu.api`, `gpu.driver`, and
`storage` (e.g. `"linux"`, `"arm64"`, `"vulkan"`,
`"turnip"`, `"user-selected"`). The Kotlin types use
uppercase enum names (`LINUX`, `ARM64`, `VULKAN`,
`TURNIP`, `USER_SELECTED`). The codec maps
uppercase ↔ lowercase (the JSON is the vision-doc
shape; the Kotlin is the typed surface).

A future phase can add a `--strict-mode` flag for
publishers who want uppercase in the JSON; the default
is the vision-doc shape.

### 2. The Capsule is content-addressed + signed

The Capsule carries `signature: Signature` +
`contentHash: ContentHash`. The platform verifies
both at install time. A capsule with a missing
signature is rejected; a capsule whose content hash
doesn't match the bytes is rejected.

### 3. The Capsule is the runtime contract, not the storage contract

The Capsule declares **what runs** (the entrypoint +
the GPU + the permissions). The Capsule does NOT
declare **where the bytes live** — that's the
`MarketListing` (signed distribution channel). The
separation matters: a creator publishes one
`MarketListing` (the storage) + one `Capsule` (the
runtime contract). The Capsule is content-addressed;
the listing may wrap multiple capsules (e.g. a
distribution package with multiple apps).

### 4. `Architecture.ANY` for interpreted runtimes

`Architecture.ANY` is the value for runtimes that don't
have a CPU-arch constraint (a JVM app, a Python app,
a WebAssembly app). The orchestrator (Phase 67)
matches `ANY` against the device's actual arch.

### 5. `Distribution.ANY` for distro-agnostic capsules

`Distribution.ANY` means "the orchestrator picks the
user's preferred distro". `Distribution.ELYSIUM_LINUX_1`
means "this capsule targets the Elysium Vanguard
Linux distro specifically".

### 6. `Permissions.storage` is an allowlist

`Permissions.storage` is the list of `StorageScope`s
the capsule can access. The runtime enforces the
allowlist: any access to a storage scope not in the
list is denied. The vision doc's example declares
`["user-selected"]` — the user picks the paths at
install time (the runtime shows a file picker).

### 7. Pure sandbox is rejected

A capsule with `storage = []` AND `network = false`
is a pure sandbox (no I/O at all). The runtime rejects
this as a deployment error: a capsule that wants
zero access should declare it explicitly via a
different mechanism, not by silently declaring empty
permissions. This is a guard against accidentally
publishing a broken capsule.

---

## Test coverage breakdown

| Test class | Tests | Coverage |
|---|---|---|
| `CapsuleTest` | 34 | Data-class invariants (8) + CapsuleId format (3) + CapsuleApiVersion format (3) + Permissions enforcement (4) + Distribution (3) + EntryPoint (3) + Codec round-trip + determinism + JSON shape (1) + 4 runtimes round-trip (1) + 5 architectures round-trip (1) + 8 GPU drivers round-trip (1) + 4 storage scopes round-trip (1) + 2 inequality tests (2) + 2 error envelope tests (2) |
| **Net new tests** | **+34** | |

### Test count delta

- Before: 2268 unit tests
- After: 2302 unit tests (+34)

---

## Build quality

- 0 lint warnings
- `./gradlew :app:testDebugUnitTest` — green (2302 passing, 2 skipped)
- `./gradlew :app:assembleDebug` — green
- `./gradlew :app:assembleDebugAndroidTest` — green

---

## What ships next (Phase 69 candidates)

The Capsule is shipped. The next increments that
consume the Capsule are:

- **Phase 69 — Capsule catalog**: a `CapsuleCatalog`
  (in-memory + file-backed) that mirrors the
  `MarketCatalog` pattern. The user installs a
  Capsule from the Market; the Capsule is added to
  the local catalog; the user creates a
  `WorkspaceDefinition` that references the Capsule.
- **Phase 70 — Capsule runtime**: the
  `LinuxProotSessionRunner` / `WindowsVmSessionRunner`
  consume a `Capsule` + produce a `WorkspaceSession`.
  The Capsule's `gpu` + `permissions` are checked
  against the device's actual capabilities.
- **Phase 71 — Capsule installer**: an Android UI
  for browsing + installing + updating Capsules
  from the Market.
- **Phase 72 — Critical E2E test** (the 8-step test
  from the master vision): download signed distro →
  verify hash → create workspace → run ARM64 binary
  → user-selected mount → stop → snapshot → confirm
  no writes outside authorized.

The vision's E2E test (Phase 72) is the final
definition of done. Every phase is a step toward it.
