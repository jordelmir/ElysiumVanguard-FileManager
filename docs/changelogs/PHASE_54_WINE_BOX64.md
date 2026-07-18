# Phase 54 — Wine + Box64 Backend (Windows API Compatibility)

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1665 tests, 0 failures, 2 skipped.

## What landed

The Phase 53 orchestrator's `WINE_BOX64`
branch is now real. A user can hand the
runtime a Windows .exe and the Wine + Box64
backend launches a session. The orchestrator
returns a plan; the new `WineSessionRunner`
executes it.

The runtime now has a full Windows
strategy:

> 1. **Windows API compatibility** (Phase
>    54): Wine + Box64, per-app prefixes,
>    per-app config.
> 2. **GPU acceleration** (Phase 55+): DXVK
>    (D3D 9/10/11) + VKD3D-Proton (D3D 12)
>    for Vulkan-based graphics.
> 3. **Full virtualization** (Phase 22-23,
>    pre-existing): QEMU for software that
>    is incompatible with Wine.

The user was explicit: *"La ruta prioritaria
era Wine + traducción binaria, no emulación
completa"*. Phase 54 ships that priority path.

## Files

**Production (3 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/wine/WineStack.kt` —
  the value types. `WineStack` is the
  installed Wine + Box64 (optionally Box86)
  binaries; `WineStack.detect()` is a
  factory that probes standard locations
  and returns a populated stack iff `wine`
  is found. `WinePrefix` is the per-app
  Windows filesystem (the directory
  containing the fake `C:\` drive); the
  prefix's `initialise()` method creates the
  directory tree (`drive_c`, `windows`,
  `system32`). `Box64Config` is the
  translator's configuration
  (DEFAULT / DYNAREC translation mode,
  library overrides, custom env vars);
  `toEnvironment()` emits the right
  `BOX64_*` env vars for the chosen mode.

- `app/src/main/java/com/elysium/vanguard/core/runtime/wine/WineSessionBackend.kt` —
  the persistence + state-management
  seam. `WineSessionSpec` is the input
  (sessionId, manifestBinaryPath, args,
  env, prefix, box64, workspaceId).
  `WineSessionState` is the sealed class
  (Idle / Starting / Running / Stopping /
  Stopped / Error). `WineSessionBackend` is
  the interface (`start` / `state` /
  `stop`); `InProcessWineSessionBackend`
  is the production impl that delegates to
  the existing [ProcessLauncher] to spawn
  the Wine + Box64 process. The command
  line is `box64 wine <binary> <args>` with
  `WINEPREFIX=<prefix>`, `WINEARCH=win64`,
  and the Box64 environment.

- `app/src/main/java/com/elysium/vanguard/core/runtime/wine/WineSessionRunner.kt` —
  the runner that consumes an
  [ExecutionManifest] (from Phase 53) and
  starts a session. The runner validates
  the manifest's `runtime = WINE_BOX64`
  (rejects otherwise), derives a stable
  session id from the binary path (so a
  re-run uses the same Wine prefix), and
  delegates to the backend. Returns a
  `Result<WineSessionState>` with typed
  errors (`UnsupportedRuntime`,
  `BackendFailure`).

**ADR:**

- `docs/adr/ADR-025-wine-box64.md` —
  the design record. Captures the
  five-piece split (WineStack / WinePrefix
  / Box64Config / WineSessionBackend /
  WineSessionRunner), the
  `Box64 first, FEX second` decision
  (Box64 is the proven path; FEX is a
  follow-up), the per-application prefix
  rationale (isolation + reset), and the
  revisit triggers (FEX backend, DXVK
  integration, remote Wine).

**Tests (2 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/wine/WineStackTest.kt` —
  14 tests covering: WineStack init-block
  invariants (non-blank winePath);
  WineStack supports x86-64 when box64 is
  present; WineStack supports x86 when
  box86 is present; WineStack supports
  neither when no translator is present;
  WinePrefix rejects unknown architecture;
  WinePrefix accepts win64 / win32;
  WinePrefix driveC and system32 paths
  are correct; WinePrefix initialise
  creates the directory tree; WinePrefix
  initialise is idempotent; Box64Config
  DEFAULT mode emits no `BOX64_DYNAREC`;
  Box64Config DYNAREC mode emits
  `BOX64_DYNAREC=1`; Box64Config library
  overrides emit `BOX64_LD_LIBRARY_PATH`;
  Box64Config custom env vars pass through.
- `app/src/test/java/com/elysium/vanguard/core/runtime/wine/WineSessionRunnerTest.kt` —
  6 tests covering: start rejects a
  manifest with a non-WINE_BOX64 runtime;
  start delegates to the backend on a
  WINE_BOX64 manifest (verifies spec
  contents: binary, args, env, workspaceId);
  start derives a deterministic session id
  from the binary path (same binary → same
  id; re-runs use the same Wine prefix);
  start surfaces a backend Error as a
  typed `WineSessionError.BackendFailure`;
  stop delegates to the backend (verifies
  the stop call's sessionId matches the
  start's); state returns the backend's
  state for the manifest. Includes a
  `FakeWineSessionBackend` that records
  every call in a thread-safe list.

## Why this matters

The Phase 53 orchestrator said
"WINE_BOX64" but the runner was a no-op.
A user with a Windows .exe would get back
a plan and have no way to execute it.
Phase 54 closes the gap.

The runner is the executor for the
orchestrator's `WINE_BOX64` branch. The
end-to-end flow is now:

```kotlin
val plan = orchestrator.planExecution(
    binaryPath = "/path/to/setup.exe",
    capabilities = runtimeCapabilities
)
if (plan is ExecutionPlan.Ready) {
    val state = wineSessionRunner.start(plan.manifest).getOrNull()
    // state is WineSessionState.Running(pid, stop)
}
```

The user can install a Windows app, the
runtime picks Wine + Box64, the runner
starts a session. The orchestrator's
`WINE_BOX64` rule is no longer aspirational.

## Architectural invariants (Phase 54)

- **Per-application prefixes.** Each Wine
  app gets its own prefix at
  `<filesDir>/wine-prefixes/<sessionId>/`.
  A user with a "this app's Wine config is
  broken" complaint can delete the prefix
  for that one app without affecting
  others.

- **Stable session ids.** The session id is
  derived from the binary path's hash. A
  re-run of the same .exe uses the same
  prefix. The runtime does not create
  duplicate prefixes for the same app.

- **The runner validates the manifest.** A
  manifest with a non-WINE_BOX64 runtime
  is rejected with a typed
  `UnsupportedRuntime` error. The runner
  is the executor for one specific
  runtime; the orchestrator's
  `planExecution` is the source of truth
  for which runtime the plan targets.

- **The backend is the persistence seam.**
  The runner orchestrates; the backend
  owns state. A future phase can swap the
  backend for a remote-execution backend
  (the orchestrator's `REMOTE` branch)
  without touching the runner.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `WineStackTest` | 14 (new) | 0 |
| `WineSessionRunnerTest` | 6 (new) | 0 |
| **Project total** | **1665** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 54 is **Phase 55
— DXVK + VKD3D-Proton (GPU acceleration
for Wine)**:
- DXVK translates Direct3D 9/10/11 to
  Vulkan. A Phase 55 `DxvkConfig` adds
  `dxvkEnabled: Boolean` to the
  `WineSessionSpec`; the runner sets the
  right env vars when DXVK is enabled.
- VKD3D-Proton translates Direct3D 12 to
  Vulkan. Phase 55 ships the same config
  pattern.
- A future phase adds Mesa Turnip (the
  Adreno GPU driver) as the default
  Vulkan ICD; Phase 55 ships the seam.

After Phase 55 the Wine + Box64 backend
has GPU acceleration; the runtime can run
Direct3D-based Windows games with hardware
acceleration.
