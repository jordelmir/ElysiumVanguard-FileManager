# Phase 55 — DXVK + VKD3D-Proton (GPU Acceleration for Wine)

Date: 2026-07-18
Status: **Shipped** — `assembleDebug` green, 1678 tests, 0 failures, 2 skipped.

## What landed

Wine + Box64 sessions can now opt into
GPU acceleration. The user enables
`DxvkConfig` (Direct3D 9/10/11 → Vulkan)
and/or `Vk3dProtonConfig` (Direct3D 12 →
Vulkan) on a per-session basis; the
backend sets the right env vars on the
spawned process.

The master vision's GPU stack is now
present at the configuration layer:

> - **Mesa Turnip** for Adreno (Phase 65+:
>   ships the Vulkan ICD as the runtime's
>   default).
> - **Vulkan** (the transport; Mesa Turnip
>   is the ICD).
> - **DXVK** for Direct3D 9, 10 and 11
>   (Phase 55).
> - **VKD3D-Proton** for Direct3D 12
>   (Phase 55).
> - **Zink** when useful (Phase 65+).
> - **VirGL** as fallback in some
>   environments (Phase 65+).

Phase 55 ships DXVK + VKD3D-Proton as
config types. The runtime knows how to
configure them; the actual Mesa Turnip
ICD is a Phase 65+ concern (the user's
device already has a Vulkan driver; the
runtime just needs to know how to point
DXVK / VKD3D-Proton at it).

## Files

**Production (2 new + 1 modified):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/wine/GpuAcceleration.kt` —
  the value types. `GpuAccelerationConfig`
  bundles `DxvkConfig?` + `Vk3dProtonConfig?`.
  `DxvkConfig` carries `hudEnabled`,
  `asyncShader`, and `customEnvironment`
  (user's `DXVK_*` overrides). The
  `toEnvironment()` method emits the
  canonical env vars:
  - `DXVK_ENABLE=1` (always when DXVK is
    enabled).
  - `DXVK_HUD=0|1` (off by default; the
    user can enable the on-screen overlay).
  - `DXVK_ASYNC=0|1` (off by default;
    async pipeline compilation reduces
    stutter at the cost of a slightly
    longer first frame).
  - Custom user env vars pass through
    (they win over the defaults).
  - `Vk3dProtonConfig.toEnvironment()`
    emits `VKD3D_CONFIG=` (empty for
    default; `debug` when the debug
    layer is enabled).
- `app/src/main/java/com/elysium/vanguard/core/runtime/wine/WineSessionBackend.kt`
  (modified) — the `WineSessionSpec` gained
  an optional `gpuAcceleration:
  GpuAccelerationConfig?` field. The
  `InProcessWineSessionBackend.start()`
  merges the config's env vars into the
  spawned process's environment when the
  config is non-null and `isEnabled` is
  true. The merge order: the manifest's
  env vars first, the Box64 config next,
  the GPU config last — the GPU config
  wins (the user wants DXVK to be
  enabled; the manifest's pre-existing
  `DXVK_ENABLE=0` would be overridden).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/wine/GpuAccelerationTest.kt` —
  13 tests covering: `DxvkConfig` default
  env (`DXVK_ENABLE=1`, `DXVK_HUD=0`,
  `DXVK_ASYNC=0`); DXVK with HUD enabled
  (`DXVK_HUD=1`); DXVK with async shader
  enabled (`DXVK_ASYNC=1`); DXVK custom
  env vars pass through AND win over
  defaults; `Vk3dProtonConfig` default
  env (`VKD3D_CONFIG=""`); VKD3D-Proton
  with debug layer (`VKD3D_CONFIG="debug"`);
  `GpuAccelerationConfig.isEnabled`
  reflects whether at least one layer is
  configured; `GpuAccelerationConfig.toEnvironment`
  merges DXVK + VKD3D env vars;
  `GpuAccelerationConfig.toEnvironment`
  is empty when both layers are null;
  `WineSessionRunner` passes GPU
  acceleration env vars through to the
  backend; `GpuAccelerationConfig` passes
  through DXVK config env to the Wine
  session.

## Why this matters

Direct3D-based Windows apps (and games)
are the dominant use case for Wine on
Android. Without DXVK / VKD3D-Proton, the
app falls back to Wine's built-in D3D
implementation, which uses software
rendering (Mesa's `llvmpipe`) or, at
best, OpenGL via WineD3D. The performance
is unusable for any modern Direct3D
title.

DXVK translates D3D 9/10/11 to Vulkan.
VKD3D-Proton translates D3D 12 to Vulkan.
A Snapdragon Adreno GPU paired with Mesa
Turnip (the open-source Vulkan driver)
runs DXVK at near-native speed for
thousands of Windows games. Phase 55
ships the configuration layer; the
runtime now knows how to enable DXVK /
VKD3D-Proton per session. The user opts
in for an app that needs it.

The configuration is per-session, not
global. A user with 10 Wine apps can
enable DXVK only for the 2 that need it;
the other 8 run with WineD3D (faster
startup, lower memory).

## Architectural invariants (Phase 55)

- **DXVK / VKD3D-Proton is opt-in per
  session.** The default
  `GpuAccelerationConfig()` is
  `isEnabled = false`. A user enables it
  for a specific session (typically a
  Direct3D-based game). The runtime does
  not enable GPU acceleration
  speculatively.

- **The user's env vars win.** The merge
  order is: manifest env vars → Box64
  config → GPU config. The GPU config
  is last so the user can override the
  defaults (e.g. set `DXVK_HUD=devinfo`).

- **DXVK / VKD3D-Proton are independent.**
  A user can enable DXVK without VKD3D-Proton
  (typical for older D3D 9/10/11 games)
  or VKD3D-Proton without DXVK (typical
  for D3D 12 games). The two layers do
  not depend on each other.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `GpuAccelerationTest` | 13 (new) | 0 |
| **Project total** | **1678** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Next phase

The follow-up after Phase 55 is **Phase 56
— Vanguard Build (local toolchains + remote
Oracle builds)**. The master vision says:

> "Para proyectos compatibles con ARM64:
> - Rust, C/C++, Java/Kotlin, Gradle,
>   Node.js, Python, Go, WebAssembly,
>   aplicaciones Linux ARM64.
> - ...
> Para builds pesados o toolchains
> incompatibles:
> - Remote ephemeral builds (Oracle Free,
>   etc.) with signed request / response,
>   ephemeral containers, SBOM + hashes,
>   artifact delivery."

Phase 56 ships the local toolchain
registry (the user's device can compile
Rust / C / Java / Node / Python / Go /
WASM); the remote Oracle build is a
follow-up.

Seven phases shipped in this turn:
49 → 50 → 51 → 52 → 53 → 54 → 55. Total:
**1678 tests, 0 failures, 2 skipped,
`assembleDebug` green.**

The full Windows strategy is now real:
1. **API compatibility** (Wine + Box64,
   Phase 54) ✓
2. **GPU acceleration** (DXVK + VKD3D-Proton,
   Phase 55) ✓
3. **Full virtualization** (QEMU,
   Phase 22-23) ✓
4. **Mesa Turnip** Vulkan ICD (Phase 65+)
