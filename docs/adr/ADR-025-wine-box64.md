# ADR-025 — Wine + Box64 Backend (Windows API Compatibility Layer)

Status: **Accepted** (Phase 54, 2026-07-18)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

The master vision doc names three Windows
strategies in priority order:

> 1. **Windows API compatibility**: Wine,
>    Box64 (x86-64 on ARM64), Box86 (x86
>    on ARM64), FEX (alternative translator),
>    per-application prefixes, isolated
>    registry + DLL + Windows-version +
>    env config.
> 2. **GPU acceleration**: Mesa Turnip
>    (Adreno), Vulkan, DXVK (D3D 9/10/11),
>    VKD3D-Proton (D3D 12), Zink, VirGL.
> 3. **Full virtualization** (QEMU): for
>    software that is incompatible with
>    Wine. Existing infrastructure (Phase
>    22-23).

The user was explicit about the priority:

> "La ruta prioritaria era Wine + traducción
> binaria, no emulación completa."

Until Phase 54 the runtime had only the
third strategy (QEMU for full VMs) and an
orchestrator that *knew* about Wine
(`RuntimeKind.WINE_BOX64`) but had no
backend to make that branch real. A user
with a "run this Windows .exe" request
would get back an `ExecutionPlan` saying
"WINE_BOX64" but no runner to execute it.

Phase 54 ships the Wine + Box64 backend.
The Phase 53 orchestrator's `WINE_BOX64`
branch becomes real: a user installs a
Windows app, the orchestrator picks
WINE_BOX64, and the new
`WineSessionRunner` starts a session.

## Decision

We split the Wine + Box64 backend into
five small pieces:

1. **`WineStack`** — the installed
   binaries. The stack is the value type
   that holds the paths to the `wine`,
   `box64`, and (optionally) `box86`
   binaries. The stack also has a
   `detect()` factory that probes standard
   locations (`/system/bin/wine`,
   `/vendor/bin/wine`, etc.) and returns
   either a populated stack or a "Wine not
   installed" rejection.

2. **`WinePrefix`** — the per-app Windows
   filesystem. A prefix is a directory
   that contains a fake `C:\` drive with
   `windows`, `system32`, `Program Files`,
   and a per-user `users/<name>/`. Each
   Wine app gets its own prefix; the user-
   visible "app" is the prefix + the
   installed app inside it. The
   `WinePrefix` value type carries the
   prefix path, the Windows version
   override (e.g. `Windows 10`), and the
   architecture override (e.g. `win64`).

3. **`Box64Config`** — the Box64
   translator's configuration. Box64
   can translate x86-64 to ARM64 at
   runtime; the config controls the
   translation mode (default vs
   `BOX64_DYNAREC`), the library override
   list, and the env vars. The
   `Box64Config` value type is a record
   that the `WineSessionRunner` consumes.

4. **`WineSessionBackend`** (interface) —
   the persistence + state-management
   seam. Parallels
   [com.elysium.vanguard.core.runtime.runner.DistroSessionBackend]
   (Phase 30) and
   [com.elysium.vanguard.core.runtime.runner.WindowsVmSessionBackend]
   (Phase 31). The interface defines
   `start(spec)`, `state(specId)`, and
   `stop(specId)`. The production impl is
   `InProcessWineSessionBackend` (Phase 54+
   wires the real Wine invocation); the
   test impl is a hand-rolled fake.

5. **`WineSessionRunner`** — the runner
   that consumes an `ExecutionManifest`
   (from Phase 53) and starts a session.
   The runner:
   1. Validates the manifest's `runtime =
      WINE_BOX64` (rejects otherwise).
   2. Resolves the Wine prefix (creates
      one if missing).
   3. Builds the command line:
      `box64 wine <prefix>/drive_c/<path> <args>`.
   4. Calls the existing
      [com.elysium.vanguard.core.runtime.runner.ProcessLauncher]
      to start the process.
   5. Publishes
      [RuntimeEvent.SessionStartedEvent] +
      [RuntimeEvent.SessionStoppedEvent] +
      the new `WineSessionStartedEvent` +
      `WineSessionStoppedEvent` (Phase 54+
      adds the Wine-specific events).

   The runner returns a `LaunchedProcess`
   (from Phase 30). The orchestrator's
   plan is the input; the runner is the
   executor.

### Why a separate `WineSessionBackend`

The backend is the persistence + state-
management seam. The runner is the
orchestration. Splitting them lets:

- A future Phase swap the backend for a
  remote-execution backend (the
  orchestrator's `REMOTE` branch) without
  touching the runner.
- A future Phase add a Wine prefix
  manager (a separate component that
  owns the lifecycle of prefixes) without
  touching the runner.
- The test fakes the backend and asserts
  the runner's orchestration in JVM tests
  without actually invoking `wine` (the
  Wine binary is not on the test host).

### Why Box64, not FEX, as the default translator

Box64 is the more mature translator
(2024+) and has wider compatibility for
x86-64 Windows apps. FEX is a more
ambitious project (also ARM64 translation)
but has fewer compat reports. The runtime
ships Box64 as the default; a future phase
adds FEX as an alternative (the
orchestrator's `WINE_FEX` branch).

The decision is `Box64 first, FEX second`
because Box64 is the proven path. The
orchestrator's `WINE_FEX` branch is a
follow-up; Phase 54 ships the `WINE_BOX64`
backend as the primary.

### Why per-application prefixes

The master vision:

> "Prefixes aislados por aplicación.
> Configuración independiente de DLL,
> registro, versión de Windows y
> variables."

Each Wine app gets its own prefix. The
runtime creates one prefix per
`ExecutionManifest` (one per app) on
first run; subsequent runs of the same
app reuse the prefix. The prefix lives
at `<filesDir>/wine-prefixes/<app-id>/`.

A user with a "this app's Wine config is
broken" complaint can `rm -rf` the
prefix for that one app without
affecting other Wine apps. This is the
same model Android's Storage Access
Framework uses: each app's data is
isolated.

### Why the orchestrator's plan is the input

The Phase 53 orchestrator returns an
`ExecutionManifest` for every binary. The
manifest is the contract. The
`WineSessionRunner` consumes the
manifest; the runner does NOT re-inspect
the binary. The runtime does not duplicate
work.

A user who wants to skip the orchestrator
can hand-write a manifest with
`runtime = WINE_BOX64` and pass it to
`planFromManifest` + the runner directly.
The orchestrator is a convenience; the
runner is the executor.

## Consequences

Positive:

- The Phase 53 orchestrator's `WINE_BOX64`
  branch is now real. A user with a Windows
  .exe can install it via the orchestrator
  and the runner starts a Wine + Box64
  session.
- Per-app prefixes isolate Wine's
  Windows-side state. A broken Wine
  config in one app does not affect
  others. A user can `rm -rf` a single
  prefix to reset.
- The backend is the seam for future
  Wine integrations: DXVK (Phase 55),
  Wine staging / proton, FEX (Phase 56+).

Negative:

- Phase 54 ships the data classes, the
  interface, and the runner; the actual
  `wine` + `box64` binary invocation is
  not exercised in the JVM tests (the
  host has no Wine + Box64 installed). A
  future instrumented test on a real
  Android device (Phase 58+) will exercise
  the binary invocation end-to-end.
- The Wine prefix bootstrap is a
  simplified version. A real Wine
  installation does `wineboot --init`
  on first run; Phase 54's prefix init
  creates the directory structure but
  does not run `wineboot`. A future Phase
  adds the boot step.

## Revisit triggers

- If a future phase adds a Wine + FEX
  backend (the orchestrator's `WINE_FEX`
  branch), the runner interface stays
  unchanged. A new `FexSessionRunner` is
  added; the registry dispatches by
  `ExecutionManifest.runtime`.
- If a future phase adds DXVK (Direct3D
  9/10/11 → Vulkan) for GPU acceleration,
  the runner gains a `dxvkEnabled: Boolean`
  parameter. Phase 55 lands DXVK.
- If a future phase adds a remote-execution
  Wine backend (the orchestrator's
  `REMOTE` branch), the backend interface
  gains a `RemoteWineSessionBackend` impl.
