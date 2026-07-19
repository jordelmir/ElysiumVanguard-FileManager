# Phase 74 (second half) — Elysium Linux Capsule

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 74 / Market integration — runtime contract
> **Predecessor:** Phase 74 first half (Elysium Linux distro listing)
> **Vertical:** Elysium Linux + Capsule (`com.elysium.vanguard.core.runtime.capsule`)

---

## TL;DR

The Elysium Linux Capsule is the **runtime contract** for the
Elysium Linux distro. The Capsule is the typed manifest the
orchestrator reads to launch the distro:

- `runtime = LINUX` — the distro is a Linux binary.
- `architecture = ARM64` — the dominant Android ABI.
- `entrypoint = /usr/bin/elysium-pm init` — the Elysium Linux
  package manager.
- `gpu = VULKAN / TURNIP` — Mesa Turnip on Adreno.
- `permissions.network = true` — for repository downloads.
- `permissions.storage = []` — storage is per-workspace.

The Capsule is the **runtime contract** (how the orchestrator
runs the distro); the listing (Phase 74 first half) is the
**distribution contract** (what's in the catalog). The two are
linked by `distribution.id`:

- Listing: `id = "com.elysium.linux:distro:1.0.0"`.
- Capsule: `distribution.id = "com.elysium.linux:distro:1.0.0"`.

The orchestrator matches the listing + the capsule by
distribution id; a mismatched pair is a deployment error.

---

## What shipped

### `ElysiumLinuxCapsule` (object)

The typed factory for the Elysium Linux Capsule. The object
has:

- **`API_VERSION`** — `CapsuleApiVersion.V1`
  (`"elysium.capsule/v1"`).
- **`ID`** — `CapsuleId("com.elysium.linux")` (the Capsule's
  reverse-DNS id).
- **`NAME`** — `"Elysium Linux"` (the display name).
- **`VERSION`** — `"1.0.0"` (matches the rootfs version).
- **`DESCRIPTION`** — the user-facing description.
- **`DISTRIBUTION_ID`** — `"com.elysium.linux:distro:1.0.0"`
  (matches the listing id; the orchestrator's join key).
- **`RUNTIME`** — `Runtime.LINUX`.
- **`ARCHITECTURE`** — `Architecture.ARM64`.
- **`ENTRYPOINT`** — `EntryPoint("/usr/bin/elysium-pm",
  ["init"], "/")`.
- **`GPU`** — `GpuConfig(VULKAN, TURNIP)`.
- **`PERMISSIONS`** — `Permissions(network = true, storage =
  [])`.
- **`CONTENT_HASH`** — placeholder (non-blank).
- **`SIGNATURE`** — placeholder (non-blank).
- **`build()`** — factory method that returns a `Capsule`
  with all fields populated.

### The `build()` factory

```kotlin
fun build(): Capsule = Capsule(
    apiVersion = API_VERSION,
    id = ID,
    name = NAME,
    version = VERSION,
    description = DESCRIPTION,
    runtime = RUNTIME,
    architecture = ARCHITECTURE,
    distribution = Distribution(DISTRIBUTION_ID),
    entrypoint = ENTRYPOINT,
    gpu = GPU,
    permissions = PERMISSIONS,
    signature = SIGNATURE,
    contentHash = CONTENT_HASH,
)
```

The factory is **pure-domain** (no I/O, no Android
dependencies). The publisher calls `build()` to get the
unsigned Capsule, then signs + publishes it.

### The runtime contract

| Field | Value | Why |
| --- | --- | --- |
| `runtime` | `LINUX` | The distro is a Linux binary. |
| `architecture` | `ARM64` | The dominant Android ABI; the Elysium Linux rootfs is compiled for ARM64. |
| `entrypoint.executable` | `/usr/bin/elysium-pm` | The Elysium Linux package manager (per Phase 73 second half). |
| `entrypoint.args` | `["init"]` | The package manager's init subcommand (sets up the rootfs at launch). |
| `entrypoint.workingDirectory` | `"/"` | The rootfs root. |
| `gpu.api` | `VULKAN` | The distro's default GPU API. |
| `gpu.driver` | `TURNIP` | Mesa Turnip for Adreno (per Phase 62 + Phase 73 third half I-73.3.1). |
| `permissions.network` | `true` | The package manager needs network for repository downloads. |
| `permissions.storage` | `[]` | Storage is per-workspace (the user picks at workspace creation time). |

### The distribution linkage

The Capsule's `distribution.id` MUST match the listing's `id`.
The orchestrator uses the linkage to:

1. Find the listing in the catalog (by id).
2. Find the Capsule for the listing (by distribution.id).
3. Verify the listing's contentHash matches the Capsule's
   contentHash.
4. Verify the listing's signature is valid for the Capsule.

A mismatched pair is a deployment error; the orchestrator
rejects the launch.

---

## Design decisions

### Why is the entrypoint `elysium-pm init`?

The orchestrator runs `/usr/bin/elysium-pm init` to launch
the Elysium Linux distro. The `init` subcommand sets up the
rootfs:

- Verifies the runtime layer catalog.
- Verifies the package database.
- Mounts the FHS filesystem tree.
- Starts the per-process init (a small init that supervises
  the long-running services).

The orchestrator treats the Capsule's entrypoint as
**opaque** — it runs the binary + args verbatim. The
`elysium-pm` binary is the canonical entry point; the
`init` subcommand is the canonical first command.

### Why is `permissions.storage` empty?

The Elysium Linux rootfs is **device-level** (installed at
`/opt/elysium/...` or similar). The Capsule does NOT need
per-workspace storage — the workspace is a separate concept
(per Phase 66 `WorkspaceDefinition`).

A user who runs a workspace from Elysium Linux declares
the storage paths at workspace creation time (the
`WorkspaceDefinition.storage` field). The Capsule's empty
storage list means "I do not need any storage at launch";
the workspace's storage list is what the runtime uses.

### Why is `gpu.driver = TURNIP` and not `VKD3D_PROTON` or `DXVK`?

`TURNIP` is the **Mesa Turnip Vulkan driver** for Adreno
GPUs. The Elysium Linux distro's default GPU config is
TURNIP because the distro is built for Android ARM64
devices (where Adreno is the dominant GPU).

`DXVK` + `VKD3D_PROTON` are **Wine-specific** GPU drivers
(per Phase 68's `GpuDriver` enum). They are NOT the
distro's default; they are used when the user launches a
Wine capsule (per the orchestrator's runtime translation
logic, Phase 73 third half I-73.3.3).

### Why is the signature a placeholder?

The real signature is set when the Capsule is signed by
the publisher's key (a future Phase 7+ increment). Until
then, the signature is a non-blank placeholder that the
Capsule type accepts (per Phase 68's `Capsule.init` block,
which only requires `signature.value.isNotBlank()`).

The placeholder pattern matches the listing's placeholder
contentHash — both are non-blank values that the real
signed/published values will replace.

### Why is the Capsule a single object, not a class?

The Elysium Linux distro is a **single concrete runtime**.
There's only one Elysium Linux Capsule (the canonical
runtime contract). The `object` keyword captures the
"single canonical instance" semantics; the `build()`
factory returns a fresh `Capsule` instance with the
declared fields.

A future Phase 7+ increment may add a class for **derived
Capsules** (e.g. a Capsule with custom permissions, a
Capsule with a different entrypoint). For now, the single
`object` is the right abstraction.

---

## Tests

17 new tests in `ElysiumLinuxCapsuleTest`. The tests cover:

- **Capsule identity** (4 tests): id, name, version,
  apiVersion.
- **Runtime + architecture** (2 tests): LINUX, ARM64.
- **Distribution linkage** (1 test): distribution id matches
  the listing id.
- **Entrypoint** (3 tests): executable, args, working
  directory.
- **GPU config** (2 tests): VULKAN, TURNIP.
- **Permissions** (2 tests): network = true, storage = [].
- **Placeholder content + signature** (2 tests): non-blank
  content hash, non-blank signature.
- **build() factory** (1 test): returns a valid Capsule with
  all fields populated.

**Total capsule tests:** 98 (34 Capsule + 24 catalog + 23
registry + 17 Elysium Linux Capsule).
**Total project tests:** 2895 (was 2878, +17 new).

---

## Phase 74 — closed

With I-74.2 (Elysium Linux Capsule) shipped, **Phase 74 is
closed**. The Elysium Linux distro has both contracts:

- **Distribution contract** (Phase 74 first half):
  `ElysiumLinuxDistroListing` — the listing in the
  Market catalog.
- **Runtime contract** (Phase 74 second half):
  `ElysiumLinuxCapsule` — the Capsule the orchestrator
  reads.

The two are linked by `distribution.id`. The user can now
discover + install + run the Elysium Linux distro through
the existing Market + Capsule + Workspace infrastructure.

The next concrete work is the **Capsule installer UI**
(Phase 74 third half) — the Compose screen where the user
picks Elysium Linux from the catalog + sees the listing
details + clicks "Install". This is the user-facing
completion of Phase 74.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/runtime/capsule/ElysiumLinuxCapsule.kt` | new | Elysium Linux Capsule (typed factory + build) |
| `app/src/test/java/com/elysium/vanguard/core/runtime/capsule/ElysiumLinuxCapsuleTest.kt` | new | 17 JVM tests |

---

## The role in the bigger picture

The Elysium Linux Capsule is the **runtime contract** that
completes the bridge between the Elysium Linux distro
foundation (Phase 73) and the platform's existing runtime
infrastructure (Phases 66-71). The bridge makes the new
distro:

- **Discoverable** — the listing is in the Market catalog.
- **Installable** — the `MarketInstaller` reads the listing
  + downloads the image.
- **Runnable** — the `WorkspaceOrchestrator` reads the
  Capsule + dispatches by `runtime` (LINUX → proot).
- **Auditable** — the `CriticalE2EOrchestrator` runs the
  critical 8-step E2E on the Capsule (per Phase 70-71).

The Capsule is the **final piece** of the typed Elysium
Linux foundation. The Phase 73 types describe the distro;
the Phase 74 listing + capsule connect the distro to the
platform. The remaining work is the **real binaries** (a
future Phase 73 increment for the actual rootfs +
Mesa/Turnip + Box64/FEX + Wine) and the **Capsule
installer UI** (a future Phase 74 third half for the
Compose screen).
