# Phase 66 — Workspace Definition (typed orchestration schema)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** Phase 66 — the typed `WorkspaceDefinition` + JSON codec + file-backed store
> **Build quality:** 0 lint warnings · 2251 unit tests passing (was 2226, +25) · `assembleDebug` + `assembleDebugAndroidTest` green

---

## TL;DR

Phase 66 ships the **typed `WorkspaceDefinition`** — the
contract for one reproducible app environment inside the
Elysium Vanguard Universal Platform. The schema is the
user-facing structure described in the master vision:

```
/workspaces/
  blender-linux/
    rootfs/         ← distro rootfs (Phase 24 workspaces)
    mounts.json     ← list of MountSpec
    env.json        ← list of EnvSpec (or env file)
    launcher.json   ← LauncherSpec (command + args)
```

Per the vision: "Cada programa tendría un workspace
reproducible". This phase ships the typed schema that
makes the vision a runtime reality — the file format, the
in-memory representation, the JSON codec, and the
file-backed store.

In this phase, the three specs (mounts + env + launcher)
are bundled into a single `WorkspaceDefinition` and
persisted as a single JSON file
(`<baseDir>/workspaces/<id>.json`). A future phase can
split the file into three per the vision's exact structure
if a user wants to share `mounts.json` across workspaces.

---

## What's new

### Production code (3 files)

| File | Purpose |
|---|---|
| `WorkspaceDefinition.kt` | The root typed schema + sub-schemas: `ApiVersion`, `RuntimeKind` (LINUX_PROOT / WINDOWS_VM / WINE_ON_LINUX), `MountSpec`, `EnvSpec`, `LauncherSpec`, `ResourceSpec` |
| `WorkspaceDefinitionCodec.kt` | The JSON serializer/deserializer (Gson + custom type adapters) + the typed `WorkspaceDefinitionCodecException` error envelope + atomic file writes (temp + rename) |
| `WorkspaceDefinitionStore.kt` | The persistence seam: `WorkspaceDefinitionStore` interface + `FileWorkspaceDefinitionStore` (file-backed) + `InMemoryWorkspaceDefinitionStore` (5-line test fixture) |

### Test code (1 file)

| File | Tests | Coverage |
|---|---|---|
| `WorkspaceDefinitionTest.kt` | 25 | Data-class invariants (id, name, mount path absolute, env var non-blank, secret value non-blank, command non-blank, working dir absolute, memory positive, cpu priority in 0-100, apiVersion regex) + codec round-trip + determinism + golden `blender-linux` sample + file-backed store save/reload/list/delete/overwrite + in-memory store + typed error envelope |

### Sample + docs (2 files)

| File | Purpose |
|---|---|
| `docs/workspace_def/samples/blender-linux.json` | The canonical sample from the vision doc — Blender on Linux with 3 mounts, 2 env vars, 1 launcher, and resource bounds |
| `docs/changelogs/PHASE_66_WORKSPACE_DEFINITION.md` | This changelog |

---

## Test-discovered regression (this phase)

The `ApiVersion` companion's `V1` field was originally
declared before the `API_VERSION_PATTERN_REGEX`. The
companion-object init order crashed with
`ExceptionInInitializerError` — the regex was null when
`V1` was constructed. Fix: declare the regex first, then
`V1`. Same pattern as the Phase F2 schema's `ApiVersion`.

---

## Why this phase matters

Per the master vision (Elysium Vanguard Universal Platform):
> "Cada programa tendría un workspace reproducible:
> /workspaces/
>   blender-linux/
>     rootfs/
>     mounts.json
>     env.json
>     launcher.json
> Cada entorno definiría: Runtime. Arquitectura. Variables
> de entorno. Directorios montados. Límites de memoria.
> Prioridad de CPU."

This phase ships the **schema** that backs this structure.
The runtime hooks (Phase 24's `WorkspaceManager`,
`LinuxProotSessionRunner`, `WindowsVmSessionRunner`) can
now consume a typed `WorkspaceDefinition` instead of
ad-hoc parameters.

The schema is **typed** (per `.ai/AGENTS.md` 24.1): every
field has a type. Mount paths are absolute. Env vars are
non-blank. Memory is positive. CPU priority is in 0..100.
A free-form string is never the value of a path or an env
var.

The schema is **versioned** (`apiVersion: elysium.workspace/v1`):
a breaking change is a new version + a migration tool.
A silent lossy migration is a contract violation.

---

## Schema design decisions

### 1. The three sub-schemas (mounts + env + launcher) bundled into one file

The vision's user-facing structure is three files
(`mounts.json`, `env.json`, `launcher.json`). The Phase 66
implementation bundles them into a single `WorkspaceDefinition`
JSON file. A future phase can split the file if a user wants
to share `mounts.json` across workspaces.

### 2. `MountSpec` paths are absolute (container side)

Per the vision: "Directorios montados". The host path is
the path on the Android filesystem (e.g. `/sdcard/...`).
The container path MUST be absolute (`/workspace/...`).
A relative path is rejected at construction.

### 3. `EnvSpec.secret` flag for sensitive values

A `secret = true` env var stores a non-blank value. A
`secret = true` env var with a blank value is rejected
(per skill 12 — secrets are not empty strings). A
non-secret env var can have an empty value (e.g. `DEBUG=`).
The Phase 63 `SecretStore` is the source of secret values;
the workspace definition stores either a literal or a
reference (future phase).

### 4. `LauncherSpec` is the entry point

Per the vision: "Cada entorno definiría: Runtime".
The `LauncherSpec` declares the command + args + working
directory. The `command` is the only required field; the
`args` and `workingDirectory` default to `[]` and `/`
respectively.

### 5. `ResourceSpec` enforces memory + CPU bounds

Per the vision: "Límites de memoria. Prioridad de CPU."
The `maxMemoryMb` is positive. The `cpuPriority` is in
`0..100` (0 = lowest, 100 = highest; 50 = normal).

### 6. `RuntimeKind` covers the three vision runtimes

The vision describes three universal runtimes: Android,
Linux, Windows. The Android runtime is the host — the
workspace IS an Android-side concept. The `RuntimeKind`
covers the two orchestration-relevant runtimes:
- `LINUX_PROOT` — a Linux proot session (Phase 24)
- `WINDOWS_VM` — a Windows QEMU session (Phase 31)
- `WINE_ON_LINUX` — a Wine prefix on a Linux proot host

The Android runtime is implicit (the host); the
`WorkspaceDefinition` orchestrates non-Android runtimes.

### 7. File-backed store: atomic write + malformed tolerance

The store writes the JSON file atomically (temp file +
rename) to prevent torn writes on crash. A malformed
file is treated as "not present" (the `load` returns
`null`; the `list` skips it). The user can re-create
the workspace without losing the rest of the catalog.

---

## Test coverage breakdown

| Test class | Tests | Coverage |
|---|---|---|
| `WorkspaceDefinitionTest` | 25 | Data-class invariants (12 tests) + codec round-trip + determinism + golden sample + file-backed store (4 tests) + in-memory store + typed error envelope (2 tests) + JSON shape (1 test) |
| **Net new tests** | **+25** | |

### Test count delta

- Before: 2226 unit tests
- After: 2251 unit tests (+25)

---

## Build quality

- 0 lint warnings
- `./gradlew :app:testDebugUnitTest` — green (2251 passing, 2 skipped)
- `./gradlew :app:assembleDebug` — green
- `./gradlew :app:assembleDebugAndroidTest` — green

---

## What ships next (Phase 67 candidates)

The `WorkspaceDefinition` is the schema. The next
increments that consume the schema are:

- **WorkspaceDefinitionViewModel** (Hilt-injected) — the
  Compose UI that lets the user create / edit / delete
  workspace definitions.
- **WorkspaceOrchestrator** — the runtime-side hook
  that takes a `WorkspaceDefinition` and produces a
  `LinuxProot` or `WindowsVm` session (per Phase 24's
  `WorkspaceSession`).
- **Split into 3 files** (vision's exact structure) —
  a user-facing option to split `mounts.json`, `env.json`,
  `launcher.json` for cross-workspace sharing.
- **Git repository clone/compile/deploy** (vision line
  6) — a `RepoSource` schema + the clone/compile pipeline.

The vision is being shipped piece by piece. The schema
is the foundation; the runtime hooks are the next layer.
