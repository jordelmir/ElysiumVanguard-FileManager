# Phase 104 — Workspace Policy Fields (GPU, Network, Backup)

**Vision gap closed**: #4 (Entornos aislados — workspaces JSON parcialmente)
**Status**: shipped
**Date**: 2026-07-20

## The gap

The `WorkspaceDefinition` schema (Phase 66) declared a complete
user-facing workspace layout — `mounts.json`, `env.json`,
`launcher.json` — plus a `ResourceSpec` with `maxMemoryMb` and
`cpuPriority`. The vision calls out the missing fields:

> "Límites de memoria. Prioridad de CPU. Acceso a GPU. Políticas
>  de red. Política de backup."

Three of those were missing. Phase 104 closes them with three
new typed data classes + their typed enums + their JSON codec
plumbing.

## What shipped

Three new specs added to `WorkspaceDefinition` (as new fields
with sensible safe defaults so old JSON files still decode):

| Spec | Field on WorkspaceDefinition | Default | Vision field |
|---|---|---|---|
| `GpuAccessSpec` | `gpu` | `GpuAccessSpec.NONE` | "Acceso a GPU" |
| `NetworkPolicySpec` | `network` | `NetworkPolicySpec.DEFAULT` (DENY_ALL) | "Políticas de red" |
| `BackupPolicySpec` | `backup` | `BackupPolicySpec.NONE` | "Política de backup" |

The defaults are the **safe direction** per the vision's
"red denegada por defecto" + "GPU opt-in" principles. A Phase 66
JSON file (no policy keys) decodes with these defaults; a Phase 104
JSON file with explicit keys round-trips byte-stable.

### Production code (1 modified)

| File | Change |
|---|---|
| `core/runtime/workspace_def/WorkspaceDefinition.kt` (modified) | 3 new typed data classes + 5 new enums + 1 modified `WorkspaceDefinition` data class (3 new fields with defaults). Each spec has `init` block invariants that surface misconfigurations at construction time. |
| `core/runtime/workspace_def/WorkspaceDefinitionCodec.kt` (modified) | The `WorkspaceDefinitionAdapter` now serializes + deserializes the 3 new fields. Deserialization calls the data class constructor directly (not Gson's reflection path) so the `init` blocks run and reject invalid combinations. Back-compat: a JSON file without the new keys decodes with the safe defaults. |

### Tests (2 new files, **+30 tests**)

| File | Tests |
|---|---|
| `WorkspacePolicySpecsTest.kt` (new) | 25 tests covering each spec's invariants: GpuAccessSpec NONE rejection of vendor + env overrides, GpuAccessKind/GpuVendor token round-trips, NetworkPolicySpec DENY_ALL rejection of allowedHosts/allowedPorts, NetworkPolicySpec ALLOW_LIST rejection of empty allowedHosts, NetworkPolicySpec port range 1..65535, NetworkPolicySpec OPEN preset, NetworkAccessMode token round-trip, BackupPolicySpec schedule interval bounds, BackupPolicySpec snapshot count bounds, BackupStrategy token round-trip, WorkspaceDefinition defaults. |
| `WorkspaceDefinitionPolicyCodecTest.kt` (new) | 5 tests: round-trip with full custom policies, round-trip with default policies, **back-compat decode a Phase 66 file (no policy keys) yielding the safe defaults**, decode rejects malformed NetworkPolicySpec via typed codec exception, encode writes the three new keys explicitly. |

### The 3 new specs in detail

**`GpuAccessSpec`** — GPU access level. Three levels:
- `NONE` (default) — no GPU, software renderer. Safe + cheap.
- `BASIC_2D` — framebuffer + GLES2. OK for desktop apps.
- `FULL_3D` — OpenGL 4 / Vulkan / DXVK. Required for Blender / Unity / Unreal.

Plus optional `vendor: GpuVendor?` (Adreno / Mali / PowerVR / etc.) and
`driverEnvOverrides: Map<String, String>` (e.g.
`MESA_LOADER_DRIVER_OVERRIDE=panfrost`).

**Invariants**: `NONE + vendor` → reject. `NONE + driverEnvOverrides` → reject.

**`NetworkPolicySpec`** — Network access mode. Three modes:
- `DENY_ALL` (default) — no outbound network. The "red denegada por defecto" principle.
- `ALLOW_LIST` — only `allowedHosts` reachable. Plus `allowedPorts` (1..65535) + `dnsAllowed`.
- `ALLOW_ALL` — open internet (rare; `OPEN` preset).

**Invariants**:
- `ALLOW_LIST + empty allowedHosts` → reject (the allow-list is the policy; empty list is meaningless).
- `DENY_ALL + non-empty allowedHosts` → reject (the allow-list is dead code).
- `DENY_ALL + non-empty allowedPorts` → reject (same).
- Port must be in 1..65535.

**`BackupPolicySpec`** — Backup strategy. Three strategies:
- `NONE` (default) — no backups. Ephemeral.
- `ON_EXIT` — snapshot on workspace close.
- `SCHEDULED` — periodic snapshot on `scheduleIntervalMinutes` (1..60), keep `maxSnapshotCount` (1..10).

**Invariants**:
- Schedule interval in 1..60 minutes (bounds disk usage).
- Snapshot count in 1..10 (bounds disk usage).

### The `init` block + Gson gotcha

Gson's default reflection-based deserializer uses
`sun.misc.Unsafe.allocateInstance` + field setters, which **bypasses
the constructor and the `init` block**. Without intervention, a
JSON file with `mode: DENY_ALL + allowedHosts: [...]` would
silently decode (the `require(...)` in the init block never ran).

**Fix**: in `WorkspaceDefinitionAdapter.deserialize`, we call the
data class constructor directly for the three new policy specs
(`GpuAccessSpec(...)`, `NetworkPolicySpec(...)`, `BackupPolicySpec(...)`).
The constructor runs the init block; an invalid combination
throws `IllegalArgumentException`, which the codec's outer
try/catch wraps in `WorkspaceDefinitionCodecException`.

The other data classes (MountSpec, EnvSpec, LauncherSpec,
ResourceSpec) still use Gson's default path — they have no
mutual-exclusion invariants that would be silently broken
by JDK unsafe.

## Test counts

- Before: 3586 tests
- After: **3616 tests**, 0 new failures (+25 + 5 = 30 new tests)
- Pre-existing flake: 1 (`FoundryServiceRepositoryIntegrationTest`,
  unchanged from `f08dad5`)

## Build

- `compileDebugKotlin`: green
- `assembleDebug`: green (98MB APK)
- `testDebugUnitTest`: 3616/3616 green

## What this enables

- **GPU access is now typed**: a workspace can ask for
  `FULL_3D + Adreno` and the platform can pick the right
  driver. Phase 109+ will wire this to Box64 / FEX / DXVK.
- **Network is deny-by-default**: a new workspace can
  NOT talk to the network unless the creator explicitly
  added an allow-list. The vision's "red denegada por
  defecto" principle is now encoded in the schema, not
  just in the runtime policy.
- **Backup is opt-in**: a workspace can ask for ON_EXIT
  or SCHEDULED snapshots. The platform stores up to
  `maxSnapshotCount` (default 3) rolling snapshots, so
  disk usage is bounded.
- **Back-compat preserved**: every Phase 66 JSON file
  decodes with the safe defaults (no GPU, no network,
  no backup). No migration tool needed.

## What's still missing (next phases)

- **Wire the policy fields into the launch path**
  (Phase 105+): the launcher should read `gpu` /
  `network` / `backup` and apply them. Today the
  `WorkspaceDefinition` schema is the source of truth,
  but the runtime doesn't yet consume the new fields.
- **Wire `network` into `NetworkPolicyFirewall`**
  (Phase 106+): the firewall already has a policy
  engine; the workspace's allow-list should be the
  source of the firewall's outbound rules. Today's
  firewall reads its own internal config; the
  workspace's spec is a separate, uncoordinated
  source.
- **Wire `backup` into a `WorkspaceBackupService`**
  (Phase 107+): no service yet takes the snapshot.
  The `BackupPolicySpec` is a passive value type.
- **GPU pass-through via VirGL / panfrost**
  (Phase 109+): the `vendor` + `driverEnvOverrides`
  fields are typed but not yet consumed.
