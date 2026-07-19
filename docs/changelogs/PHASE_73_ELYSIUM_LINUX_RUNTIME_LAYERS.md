# Phase 73 (third half, I-73.3.1) — Elysium Linux Runtime Layers

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 73 / Linux distro foundation — runtime layers
> **Predecessor:** Phase 73 second half (package manager + repository)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

The Elysium Linux runtime layer system is **operational**. The
distro can now describe its five built-in translation layers as
typed, versioned, content-addressed, signed manifests:

- **Native ARM64** — the baseline. Every Elysium Linux install
  has it.
- **Mesa Turnip** — the open-source Vulkan driver for Qualcomm
  Adreno GPUs. GPU acceleration for Vulkan capsules.
- **Box64** — the user-mode x86_64 binary translator for ARM64.
  Run x86_64 Linux binaries (Steam, Blender x86_64) without a VM.
- **FEX-Emu** — the user-mode x86 (32-bit) binary translator for
  ARM64. Run legacy 32-bit x86 binaries.
- **Wine** — the Windows API re-implementation for Linux. Run
  Windows PE binaries (`.exe`, `.msi`).

The layer system is **typed** (sealed class with 5 cases, not
free-form strings), **versioned** (semver per layer), **signed**
(HMAC-SHA-256, same trust chain as packages), and **capability-driven**
(the orchestrator asks the catalog "can this device run this
capsule?" and the catalog answers from the layer capabilities).

This is the **first of five sub-tasks** in the Phase 73 third
half. The remaining sub-tasks:

- **I-73.3.2** — `ElysiumRootfsLayout` (canonical directory
  structure for the rootfs).
- **I-73.3.3** — `ElysiumAbiCapabilityMatrix` (which layer on
  which ABI).
- **I-73.3.4** — `ElysiumUpdateStrategy` (A/B vs versioned
  image rollback).
- **I-73.3.5** — `ElysiumCvePolicy` (vulnerability policy +
  response SLA).

---

## What shipped

### `ElysiumRuntimeLayer` (sealed class, 5 cases)

The runtime layer envelope. The sealed class has 5 cases:

```kotlin
sealed class ElysiumRuntimeLayer {
    abstract val id: ElysiumRuntimeLayerId
    abstract val version: ElysiumPackageVersion
    abstract val hostAbi: ElysiumAbi
    abstract val displayName: String
    abstract val capabilities: Set<ElysiumRuntimeCapability>

    data class Native(version, hostAbi = ElysiumAbi.ARM64) : ElysiumRuntimeLayer()
    data class MesaTurnip(version, hostAbi = ElysiumAbi.ARM64) : ElysiumRuntimeLayer()
    data class Box64(version, hostAbi = ElysiumAbi.ARM64) : ElysiumRuntimeLayer()
    data class Fex(version, hostAbi = ElysiumAbi.ARM64) : ElysiumRuntimeLayer()
    data class Wine(version, hostAbi = ElysiumAbi.ARM64) : ElysiumRuntimeLayer()
}
```

Every layer has:

- **`id`** — the canonical id (`"native"`, `"mesa-turnip"`,
  `"box64"`, `"fex"`, `"wine"`). Pattern: `^[a-z][a-z0-9-]*$`.
- **`version`** — the semver (reuses `ElysiumPackageVersion` from
  Phase 73 first half).
- **`hostAbi`** — the ABI the layer is compiled for. A Box64
  layer is `ElysiumAbi.ARM64` (Box64 itself is an ARM64 binary;
  it *translates* x86_64 *guest* binaries).
- **`displayName`** — the human-readable name (e.g. "Mesa Turnip
  24.1.0").
- **`capabilities`** — the typed features the layer provides
  (e.g. `EXECUTE_X86_64`, `GPU_VULKAN_TURNIP`).

### `ElysiumRuntimeLayerId` (string value class)

```kotlin
data class ElysiumRuntimeLayerId(val value: String) {
    companion object {
        val NATIVE: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("native")
        val MESA_TURNIP: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("mesa-turnip")
        val BOX64: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("box64")
        val FEX: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("fex")
        val WINE: ElysiumRuntimeLayerId = ElysiumRuntimeLayerId("wine")
    }
}
```

The id is **distinct from the package name** — a layer is a typed
Elysium Linux concept, not a package. The package manager may
ship the layer as a package (e.g. `com.elysium.runtime.mesa-turnip`),
but the layer's identity is the layer id, not the package name.

### `ElysiumRuntimeCapability` (enum, 9 values)

The machine-readable features a runtime layer provides:

```kotlin
enum class ElysiumRuntimeCapability {
    EXECUTE_NATIVE,
    EXECUTE_X86_64,
    EXECUTE_X86,
    EXECUTE_WINDOWS,
    GPU_VULKAN,
    GPU_VULKAN_TURNIP,
    GPU_VULKAN_DXVK,        // future phase
    GPU_VULKAN_VKD3D,       // future phase
    GPU_OPENGL_ES,
}
```

The orchestrator computes the **capability set** of the installed
layers + checks whether the capsule's declared requirements are
satisfied. A Vulkan capsule requires `GPU_VULKAN`; a Windows
capsule requires `EXECUTE_WINDOWS`; an x86_64 capsule requires
`EXECUTE_X86_64`.

### `ElysiumRuntimeLayerManifest`

The signed contract between the Elysium Linux distribution team
(the publisher) and the orchestrator (the consumer). The manifest
has the same shape as `ElysiumPackageManifest` (Phase 73 first
half):

- `id: ElysiumRuntimeLayerId`
- `version: ElysiumPackageVersion`
- `hostAbi: ElysiumAbi`
- `displayName: String`
- `capabilities: Set<ElysiumRuntimeCapability>`
- `dependencies: List<ElysiumPackageDependency>` — the Elysium
  packages the layer requires.
- `provides: List<String>` — the Elysium packages the layer
  provides.
- `files: List<ElysiumPackageFile>` — the files the layer
  installs in the rootfs.
- `description: String` — the user-facing description.
- `homepage: String` — the upstream project's homepage.
- `contentHash: ContentHash` — the SHA-256 of the layer tarball.
- `signature: Signature` — the signature on the canonical form.

**Trust chain:** same as packages — HMAC-SHA-256 over the
canonical form, which excludes the signature. `verifySignature`
rebuilds the canonical form + signs + compares.

**Canonical form:**
```
elysium-runtime-layer:v1|id=<id>|version=<ver>|hostAbi=<abi>|...|contentHash=<hash>
```

### `ElysiumRuntimeLayerCatalog`

The in-memory collection of available layers. Thread-safe
(`ConcurrentHashMap`). Operations:

| Method | Returns | Notes |
| --- | --- | --- |
| `addLayer(manifest, expectedKey)` | `Result<Unit>` | verifies signature on add |
| `find(id, version)` | `ElysiumRuntimeLayerManifest?` | exact lookup |
| `latest(id)` | `ElysiumRuntimeLayerManifest?` | highest semver |
| `latestForAbi(id, hostAbi)` | `ElysiumRuntimeLayerManifest?` | highest semver for the ABI |
| `listVersions(id)` | `List<...>` sorted descending | all versions of a layer |
| `listLayerIds()` | `List<ElysiumRuntimeLayerId>` sorted alphabetically | every layer id |
| `size()` | `Int` | total manifests across all ids |
| `asLayer(id, version)` | `ElysiumRuntimeLayer?` | reconstructs the typed layer from the manifest |

### `ElysiumRuntimeLayerDefaults` (object)

The default seed catalog. The user starts with these 5 layers
installed:

| Layer | Version | Capabilities | Files |
| --- | --- | --- | --- |
| Native | 1.0.0 | EXECUTE_NATIVE | `/lib/ld-linux-aarch64.so.1`, `/usr/lib/elysium/runtime/native/1.0.0/manifest.json` |
| Mesa Turnip | 24.1.0 | GPU_VULKAN, GPU_VULKAN_TURNIP | `libvulkan_adreno.so`, `icd.d/adreno_icd.json` |
| Box64 | 0.3.2 | EXECUTE_X86_64 | `/usr/bin/box64`, `libbox64.so` |
| FEX-Emu | 2404.0.0 | EXECUTE_X86 | `/usr/bin/FEXInterpreter`, `libFEX.so` |
| Wine | 9.0.0 | EXECUTE_WINDOWS | `/usr/bin/wine`, `wine/x86_64-unix/wine` |

The defaults are **placeholder manifests** for the architecture —
the real downloads come from the Elysium Linux repository
(Phase 73 third half sub-task I-73.3.2 will populate the real
binaries). The placeholder manifests establish the **shape** of
the catalog.

### `ElysiumRuntimeLayerVerificationError` (sealed, 3 variants)

```kotlin
sealed class ElysiumRuntimeLayerVerificationError : RuntimeException {
    data class SignatureMismatch(layerId, version, expected, actual)
    data class ContentHashMismatch(layerId, version, expected, actual)
    data class UnsupportedAbi(layerId, version, layerHostAbi, requestedHostAbi)
}
```

The error envelope follows the same pattern as
`ElysiumPackageVerificationError` (Phase 73 first half) — but
scoped to runtime layers. `UnsupportedAbi` is a new variant:
e.g. the user requested Mesa Turnip for X86_64, but Turnip is
Adreno-only — the catalog reports the mismatch with both ABIs
in the message.

---

## Design decisions

### Why a sealed class with 5 cases, not a string id?

A `sealed class` is **type-safe**: the compiler knows that a
layer is one of exactly 5 kinds. A `when` on the layer is
**exhaustive** — adding a 6th case is a compile error in
every consumer that hasn't been updated.

The 5 cases reflect the **5 distinct translation layers** Elysium
Linux supports. They are not just ids — each case carries its
own metadata (e.g. `MesaTurnip` carries the Vulkan version;
`Wine` carries the Wine major release).

### Why `hostAbi` on the layer, not `guestAbi`?

A layer's `hostAbi` is the ABI the layer **runs on** (the device's
ABI). For Box64, `hostAbi = ARM64` (Box64 itself is an ARM64
binary). The **guest ABI** is implicit: Box64 translates
`EXECUTE_X86_64` guests; FEX translates `EXECUTE_X86` guests;
Wine runs Windows PE guests.

The `hostAbi` lets the catalog filter layers by the device's
ABI. A future `x86_64` desktop Elysium Linux install would
have Box64 with `hostAbi = X86_64` (Box64 is also compiled for
x86_64 hosts, where it can do native x86_64 runs without
translation).

### Why `Set<ElysiumRuntimeCapability>`, not a single capability?

A layer can have **multiple capabilities**. Mesa Turnip provides
`GPU_VULKAN` (the umbrella) **and** `GPU_VULKAN_TURNIP` (the
specific driver). The umbrella capability is what the
orchestrator checks ("does any layer provide Vulkan?"); the
specific capability is what the UI shows ("Mesa Turnip 24.1.0
is installed; you can run Vulkan on Adreno").

The set is **immutable** (Kotlin `Set` is read-only by default;
`mutableSetOf` would be a smell).

### Why pattern `^[a-z][a-z0-9-]*$`, not Java package style?

A layer id is **short + DNS-like** (`mesa-turnip`, `box64`).
It is **not** a fully-qualified Java package name. The simpler
pattern makes the id easier to type in a CLI (`pm layer list mesa-turnip`).

The id is **distinct from the package name**. The package name
is the reverse-DNS pattern from Phase 73 first half
(`com.elysium.runtime.mesa-turnip`); the layer id is the short
form (`mesa-turnip`).

### Why HMAC-SHA-256, not Ed25519?

The trust chain is **symmetric** in Phase 73 first half. The
production hardening is in Phase 7 (per `.ai/AGENTS.md` section
14 + skill 12):

1. **Phase 1** (current) — HMAC-SHA-256 (symmetric, deterministic).
2. **Phase 2** (next) — Ed25519 (asymmetric, fast).
3. **Phase 3** (final) — ML-DSA-65 (post-quantum-ready).

The HMAC choice is a **deliberate deferral** — the signature
serves to bind the manifest to a publisher, and HMAC-SHA-256
is sufficient for that purpose until the production key
distribution is in place.

### Why `ElysiumRuntimeLayerManifest` is **not** a `ElysiumPackageManifest`?

A layer is a **typed concept** distinct from a package:

- A package has `name`, `version`, `abi`, `dependencies`, `files`,
  etc. A layer has the same fields **plus** `capabilities` and
  `homepage`. A layer's `id` is a short string, not a reverse-DNS
  name.
- A package is installed by the package manager. A layer is
  **discovered** via the catalog (which may be backed by packages
  — the user installs `com.elysium.runtime.mesa-turnip` and the
  catalog adds the `mesa-turnip` layer).
- A package is signed once + verified on install. A layer is
  signed once + verified on **catalog add** + on **every load**.

The two are **related but distinct** — packages are the
distribution unit; layers are the runtime concept. The package
manager may ship a layer as a package, but the layer's identity
+ capabilities + trust model are its own.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. FEX version "2404.0" failed `ElysiumPackageVersion.parse`

**Symptom:** `ExceptionInInitializerError` at the
`ElysiumRuntimeLayerDefaults` object init. The cause was
`IllegalArgumentException: expected MAJOR.MINOR.PATCH, got:
2404.0`.

**Root cause:** FEX uses CalVer ("2404" = April 2024), not
semver. I had abbreviated the FEX version as `"2404.0"` (2
parts) but the parser requires exactly 3 parts
(`MAJOR.MINOR.PATCH`).

**Fix:** Use `"2404.0.0"` (3 parts). The semver representation
is `2404.4.0` (year.month.0 — the patch level is unused for
FEX's CalVer).

This is a **test-discovered** bug — the test that iterated
over `ElysiumRuntimeLayerDefaults.ALL` failed at object init
time, surfacing the CalVer vs semver mismatch.

---

## Tests

41 new tests in `ElysiumRuntimeLayerTest`. The tests cover:

- **ElysiumRuntimeLayerId** (5 tests): blank rejection,
  uppercase rejection, underscore rejection, valid id
  acceptance, well-known constants.
- **ElysiumRuntimeLayer per-kind capabilities** (8 tests):
  Native (EXECUTE_NATIVE), MesaTurnip (GPU_VULKAN +
  GPU_VULKAN_TURNIP), Box64 (EXECUTE_X86_64), Fex
  (EXECUTE_X86), Wine (EXECUTE_WINDOWS), default hostAbi
  (Native + Box64).
- **ElysiumRuntimeLayerManifest invariants** (5 tests):
  blank displayName / description / homepage / empty file
  list / blank provides entry.
- **ElysiumRuntimeLayerManifest signature** (5 tests):
  correct key passes, wrong key fails, canonical excludes
  signature, canonical is deterministic, canonical is
  sensitive to version, canonical sorts capabilities.
- **ElysiumRuntimeLayerCatalog** (12 tests): addLayer
  (signature verification), find (hit + miss), latest
  (hit + miss), latestForAbi (hit + miss), listVersions
  (sorted + empty), listLayerIds (sorted), size, asLayer
  (hit + miss).
- **ElysiumRuntimeLayerDefaults** (4 tests): every default
  verifies with the default key, every default has files,
  every default has capabilities, every well-known layer is
  in the catalog.
- **ElysiumRuntimeLayerVerificationError** (1 test):
  UnsupportedAbi error message mentions both ABIs.

**Total linux tests:** 103 (32 manifest + 30 package manager
+ 41 runtime layers).
**Total project tests:** 2738 (was 2697, +41 new).

---

## What's next — Phase 73 third half, sub-task I-73.3.2

`ElysiumRootfsLayout` — the canonical directory structure for
the Elysium Linux rootfs. The layout defines:

- `/usr/lib/elysium/runtime/<layer>/<version>/` — runtime
  layer binaries.
- `/opt/elysium/packages/` — installed package files.
- `/etc/elysium/runtime/` — runtime config.
- `/var/elysium/state/` — runtime state.
- `/var/elysium/logs/` — runtime logs.

The layout is the **canonical filesystem shape** the orchestrator
expects. The rootfs builder (I-73.3.3) will produce a rootfs
matching this layout.

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumRuntimeLayer.kt` | new | sealed class + id + capability + manifest + error |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumRuntimeLayerCatalog.kt` | new | catalog + defaults |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumRuntimeLayerTest.kt` | new | 41 JVM tests |

---

## The role in the bigger picture

Phase 73 third half is the **runtime layer system** — the
translation layer between the user's binaries and the device's
hardware. The 5 layers compose the full Elysium Linux capability
surface:

| User's binary | Required layer | Required capability |
| --- | --- | --- |
| Native ARM64 ELF | Native | EXECUTE_NATIVE |
| Native ARM64 Vulkan SO | Mesa Turnip | GPU_VULKAN_TURNIP |
| x86_64 Linux ELF | Box64 | EXECUTE_X86_64 |
| x86 (32-bit) Linux ELF | FEX | EXECUTE_X86 |
| Windows PE (.exe) | Wine | EXECUTE_WINDOWS |
| Windows PE with D3D11 | Wine + DXVK | EXECUTE_WINDOWS + GPU_VULKAN_DXVK |

The runtime layer catalog is the **first piece of the puzzle**.
The remaining pieces — rootfs layout, ABI capability matrix,
update strategy, CVE policy — close out the third half.
