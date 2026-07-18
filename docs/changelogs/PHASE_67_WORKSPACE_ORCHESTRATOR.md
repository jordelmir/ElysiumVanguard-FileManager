# Phase 67 — Workspace Orchestrator (runtime bridge)

> **Status:** ✅ Shipped (commit pending)
> **Scope:** Phase 67 — the translator between `WorkspaceDefinition` (Phase 66) and `OrchestratedWorkspace` (the runtime plan)
> **Build quality:** 0 lint warnings · 2268 unit tests passing (was 2251, +17) · `assembleDebug` + `assembleDebugAndroidTest` green

---

## TL;DR

Phase 67 ships the **WorkspaceOrchestrator** — the typed
bridge between the user's intent (Phase 66's
`WorkspaceDefinition`) and the runtime's execution
(Phase 24's `WorkspaceManager` +
`LinuxProotSessionRunner` + `WindowsVmSessionRunner`).

The orchestrator is **pure-domain** (no I/O, no Android
dependencies, no Hilt). It takes a `WorkspaceDefinition`
and produces an `OrchestratedWorkspace` — the typed
plan that the existing runtime hooks can consume without
re-validation.

The orchestrator is the **typed seam** described in
sección 6 of the master vision ("Motor universal de
ejecución"). It is the boundary between the user's
declarative intent and the runtime's imperative
execution. The runtime hook becomes a thin wrapper that
consumes the orchestrated plan.

---

## What's new

### Production code (2 files)

| File | Purpose |
|---|---|
| `OrchestratedWorkspace.kt` | The runtime-ready plan: `OrchestratedWorkspace` (session + bind mounts + env + launch command + resource limits), `BindMount` (typed mount), `LaunchCommand` (typed command), `ResourceLimits` (typed limits) |
| `WorkspaceOrchestrator.kt` | The translator: takes a `WorkspaceDefinition` and produces an `OrchestratedWorkspace`. Dispatches by `RuntimeKind` (LINUX_PROOT / WINDOWS_VM / WINE_ON_LINUX) to the correct session variant |

### Test code (1 file)

| File | Tests | Coverage |
|---|---|---|
| `WorkspaceOrchestratorTest.kt` | 17 | Golden blender-linux case + all 3 runtime kinds + mount translation + env translation + launch command translation + resource limits + session id uniqueness + determinism (mounts/env/command/resource are deterministic; session id is fresh per call) |

---

## Test-discovered regression (this phase)

The `OrchestratedWorkspace` data class originally had a
`require(bindMounts.isNotEmpty())` invariant. The test
"bind mounts are non-empty even with no mounts in the
definition" was testing the orchestrator's behavior when
the input is an empty mounts list (e.g. for a
`WINDOWS_VM` workspace where the user is OK with the
runtime's default mounts). The orchestrator correctly
translates the empty input to an empty output, but the
data class rejected the empty output. Fix: remove the
`require(bindMounts.isNotEmpty())` invariant; the
runtime hook is responsible for the rootfs mount.

The orchestrator's contract is "translate the spec
verbatim"; the runtime hook is responsible for the
platform-level mounts (rootfs, /dev, /proc, etc.). The
two concerns are correctly separated.

---

## Why this phase matters

Per sección 6 of the master vision ("Motor universal de
ejecución"):
> "El corazón de la plataforma era un Runtime
> Orchestrator. Debía detectar: Formato ejecutable.
> Arquitectura: ARM64, ARM32, x86, x86-64. Dependencias.
> ABI. Requisitos gráficos. Memoria disponible.
> Compatibilidad del dispositivo. Runtime óptimo. Riesgos
> de seguridad.
> No debía 'probar comandos al azar'. Cada ejecución
> debía estar basada en un manifiesto declarativo y
> validado."

The orchestrator is the **typed seam** between the
declarative manifest and the imperative execution. The
manifest (`WorkspaceDefinition`) is what the user
writes; the orchestrated plan (`OrchestratedWorkspace`)
is what the runtime consumes. The orchestrator is
deterministic (the same manifest produces the same plan
modulo a fresh session id); the runtime hook is
idempotent (a re-run of the orchestrated plan produces
the same result).

The orchestrator is **pure-domain** (no I/O, no Android
dependencies). This is a critical property: the
orchestrator's contract is testable end-to-end with a
hand-rolled fixture. The runtime hook (which DOES
depend on Android Context + Hilt) is tested separately.

---

## Design decisions

### 1. The orchestrator is pure-domain

The orchestrator does NOT touch the filesystem, the
network, the database, or the Android Context. It
takes a `WorkspaceDefinition` (in-memory) and returns
an `OrchestratedWorkspace` (in-memory). The orchestrator
is JVM-testable end-to-end without Robolectric or
androidTest infrastructure.

The integration with the actual runtime hooks
(`LinuxProotSessionRunner`, `WindowsVmSessionRunner`)
is the next phase (Phase 68). The orchestrator's
contract is "translate the spec verbatim"; the
runtime hook is "execute the plan".

### 2. The orchestrator emits the runtime's vocabulary, not the user's

The orchestrator emits `BindMount` (host path + container
path + read-only), not `MountSpec` (the user's
declaration). The two are related but not identical:
- `MountSpec.readOnly` is a flag the user sets.
- `BindMount.readOnly` is the flag the runtime consumes.
  The translation is trivial (1:1 copy) but the
  separation matters: the user's vocabulary may evolve
  (e.g. add `MountMode.NFS` later) without breaking the
  runtime's vocabulary.

### 3. The session id is a fresh UUID

The orchestrator allocates a fresh session id
(`UUID.randomUUID().toString()`) on every call. The
runtime hook may override this if it has a
session-state recovery path. The fresh UUID is a
defense-in-depth: a caller cannot accidentally reuse a
session id from a previous orchestration.

The determinism test asserts the **non-session-id
parts** are deterministic (mounts, env, command,
resource limits) but the **session id is fresh**. This
is the correct contract: the orchestrator is
deterministic for everything except the session id.

### 4. `WINE_ON_LINUX` is a `LinuxProot` session

Per the master vision: a Wine-on-Linux workspace is a
Linux proot session with a Wine prefix layered on top.
The orchestrator emits a `LinuxProot` session with
`distroId = "__wine__"` and `profileId = "__wine__"`
markers. The runtime hook resolves the markers to a
real Linux distro + profile + Wine prefix at the
integration step (Phase 68).

### 5. The orchestrator does NOT auto-inject the rootfs mount

The orchestrator translates the user's mounts verbatim.
The rootfs mount is the runtime hook's responsibility
(it's a platform-level concern, not a user concern).
The orchestrator's `OrchestratedWorkspace.bindMounts` MAY
be empty (a `WINDOWS_VM` workspace has no user
mounts; the runtime hook supplies the VM's default
disk image).

The `WorkspaceDefinition` data class DOES require at
least one mount for `LINUX_PROOT` workspaces (the user
must explicitly bind the rootfs). This is a UX
decision: the user must consciously authorize a
mount.

---

## Test coverage breakdown

| Test class | Tests | Coverage |
|---|---|---|
| `WorkspaceOrchestratorTest` | 17 | Golden blender-linux case + 3 runtime kind variants + mount / env / launch command / resource limits translations + session id uniqueness + display name preservation + determinism (non-session-id parts) + empty-input handling |
| **Net new tests** | **+17** | |

### Test count delta

- Before: 2251 unit tests
- After: 2268 unit tests (+17)

---

## Build quality

- 0 lint warnings
- `./gradlew :app:testDebugUnitTest` — green (2268 passing, 2 skipped)
- `./gradlew :app:assembleDebug` — green
- `./gradlew :app:assembleDebugAndroidTest` — green

---

## What ships next (Phase 68 candidates)

The orchestrator is shipped. The next increments that
consume the orchestrated plan are:

- **Phase 68 — Runtime hook integration**: the
  `LinuxProotSessionRunner` and `WindowsVmSessionRunner`
  consume the `OrchestratedWorkspace` and produce a
  running session. The `BindMount` -> proot `-b` flag
  translation; the `LaunchCommand` -> proot exec
  translation; the `ResourceLimits` -> cgroup
  translation.
- **Phase 69 — Workspace UI**: a Compose
  `WorkspaceDefinitionScreen` that lets the user
  create / edit / delete workspace definitions.
  Hilt-injected `WorkspaceDefinitionViewModel`.
- **Phase 70 — Distro + profile resolver**: the
  orchestrator emits `__pending__` for the session's
  distro / profile. The resolver picks the actual
  distro + profile from the user's catalog.
- **Phase 71 — Wine prefix layer**: the orchestrator
  emits `__wine__` for the session's distro. The Wine
  layer picks the actual Wine prefix + the
  Wine-on-Linux runtime.

The vision is being shipped piece by piece. The
orchestrator is the foundation; the runtime hook
integration is the next layer.
