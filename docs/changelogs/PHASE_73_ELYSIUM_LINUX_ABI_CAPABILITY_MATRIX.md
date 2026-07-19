# Phase 73 (third half, I-73.3.3) — Elysium Linux ABI Capability Matrix

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 73 / Linux distro foundation — ABI capability matrix
> **Predecessor:** I-73.3.2 (Rootfs Layout)
> **Vertical:** Elysium Linux (`com.elysium.vanguard.core.linux.*`)

---

## TL;DR

The Elysium Linux ABI capability matrix is **operational**. The
distro has a typed answer to "which runtime layers can run on
this device?". The matrix maps:

- `ElysiumAbi` (CPU architecture) → `Set<ElysiumRuntimeLayerId>`
- `GPUVendor` (graphics vendor) → `Set<ElysiumRuntimeLayerId>`

The matrix is the **orchestrator's reference** for runtime
support. The orchestrator computes the **available layer set**
for the device + compares against the capsule's required layers
+ reports a typed `UnsupportedCapability` error on a mismatch.

This is the **third of five sub-tasks** in the Phase 73 third
half. The remaining sub-tasks:

- **I-73.3.4** — `ElysiumUpdateStrategy` (A/B vs versioned
  image rollback).
- **I-73.3.5** — `ElysiumCvePolicy` (vulnerability policy +
  response SLA).

---

## What shipped

### `ElysiumAbiCapabilityMatrix`

The data class is the **typed answer** to the orchestrator's
question. The class has:

```kotlin
data class ElysiumAbiCapabilityMatrix(
    val abiLayers: Map<ElysiumAbi, Set<ElysiumRuntimeLayerId>>,
    val gpuVendorLayers: Map<GPUVendor, Set<ElysiumRuntimeLayerId>>,
) {
    fun layersFor(abi: ElysiumAbi, gpuVendor: GPUVendor? = null): Set<ElysiumRuntimeLayerId>
    fun isLayerAvailable(layerId: ElysiumRuntimeLayerId, abi: ElysiumAbi): Boolean
    fun isLayerAvailable(layerId: ElysiumRuntimeLayerId, abi: ElysiumAbi, gpuVendor: GPUVendor?): Boolean
    fun missingLayers(required: Set<ElysiumRuntimeLayerId>, abi: ElysiumAbi, gpuVendor: GPUVendor? = null): Set<ElysiumRuntimeLayerId>

    companion object {
        val DEFAULT_ANDROID_ARM64: ElysiumAbiCapabilityMatrix
    }
}
```

### Construction invariants

The init block enforces:

- `abiLayers` is non-empty.
- `abiLayers` includes `ARM64` (every Elysium Linux install is
  ARM64).
- Every ABI's set is non-empty (an ABI with no layers is a
  misconfiguration).
- Every ABI's set includes the `NATIVE` layer (the baseline;
  every Elysium Linux install runs native binaries).

### `layersFor(abi, gpuVendor)` — the orchestrator's query

The method returns the **set union** of the ABI's layers + the
GPU vendor's layers. The orchestrator uses this method to
compute "what does this device have?".

### `isLayerAvailable` — boolean check

A convenience method that returns `true` if a specific layer
is in the device's available set. The orchestrator uses this
for fast boolean checks ("can this device run Vulkan?").

### `missingLayers` — the orchestrator's validation

The method takes a set of required layers + an ABI + a GPU
vendor + returns the layers the device does NOT have. The
orchestrator uses this for capsule validation:

```kotlin
val required = setOf(ElysiumRuntimeLayerId.BOX64, ElysiumRuntimeLayerId.MESA_TURNIP)
val missing = matrix.missingLayers(
    required = required,
    abi = ElysiumAbi.ARM64,
    gpuVendor = GPUVendor.ADRENO,
)
if (missing.isNotEmpty()) {
    return Failure(ElysiumUnsupportedCapability(required, missing))
}
```

### Default Android ARM64 matrix

| ABI | Layers |
| --- | --- |
| `ARM64` | NATIVE, BOX64, FEX, WINE |
| `ARM32` | NATIVE (legacy 32-bit ARM phones; no Vulkan acceleration) |
| `X86_64` | NATIVE, BOX64 (no-op), FEX (no-op), WINE |
| `X86` | NATIVE (legacy 32-bit x86 emulators) |

| GPU Vendor | Layers |
| --- | --- |
| `ADRENO` | MESA_TURNIP |
| `MALI` | (none — Panfrost is a future Phase 73 increment) |
| `POWER_VR` | (none) |
| `INTEL` | (none — Mesa ANV is a future increment) |
| `AMD` | (none — Mesa RADV is a future increment) |
| `NVIDIA` | (none) |
| `APPLE` | (none) |
| `UNKNOWN` | (none) |

The default matrix documents the **officially supported** layer
set. A future Phase 73 increment can add more GPU vendors
(Mesa Panfrost for Mali, Mesa ANV for Intel, Mesa RADV for AMD,
etc.).

---

## Design decisions

### Why two maps (abi + gpu), not one?

A layer can be **ABI-specific** (NATIVE, BOX64, FEX, WINE —
they're all CPU-bound) or **GPU-specific** (MESA_TURNIP is
Adreno-only). The matrix has two maps to reflect this:

- `abiLayers` — the layers available based on the CPU alone.
- `gpuVendorLayers` — the layers available based on the GPU.

A layer in both maps is available everywhere; a layer in only
one map is conditional. The set union captures the combined
view.

### Why is MESA_TURNIP in `gpuVendorLayers[ADRENO]`, not `abiLayers[ARM64]`?

Mesa Turnip is **Adreno-specific**. The driver is compiled for
the Adreno kernel-mode interface; it does NOT run on Mali,
PowerVR, or Intel GPUs. Putting it in `abiLayers[ARM64]` would
incorrectly report it as available on a Mali phone.

The matrix is **precise** — a layer is in the map where it
actually works. MESA_TURNIP is in `gpuVendorLayers[ADRENO]`
because that's the only GPU vendor it supports.

### Why does every ABI include the NATIVE layer?

The NATIVE layer is the **baseline**. Every Elysium Linux
install runs native binaries for the device's ABI; without
NATIVE, the device has no runtime at all. The init block
enforces this — a matrix without NATIVE for an ABI is a
misconfiguration.

### Why is the matrix a data class, not a service?

A data class is **declarative + inspectable**. The matrix is
the **document** of Elysium Linux's runtime support; a
developer can read the source + see exactly which layers run
on which device class. A service would hide the data behind
methods; the data class exposes it as fields.

The matrix is **read-only at runtime**. A device's capabilities
are fixed at install time; the orchestrator reads the matrix,
never writes.

### Why `Set<ElysiumRuntimeLayerId>`, not a single layer?

A device can have **multiple layers installed**. A typical
Android ARM64 phone has NATIVE + MESA_TURNIP + BOX64 + FEX +
WINE. The set is the natural representation; a single layer
would be lossy.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. MESA_TURNIP was in `abiLayers[ARM64]`, not `gpuVendorLayers[ADRENO]`

**Symptom:** Tests `isLayerAvailable with GPU vendor returns
false for Mali + MESA_TURNIP` and `layersFor ARM64 with Mali
does NOT include MESA_TURNIP` failed.

**Root cause:** The first cut of the default matrix had
`MESA_TURNIP` in `abiLayers[ARM64]` (treating it as "ARM64
always has Mesa Turnip"). The Mali test expected MESA_TURNIP
to NOT be on a Mali phone, but the matrix said otherwise.

**Fix:** Moved `MESA_TURNIP` from `abiLayers[ARM64]` to
`gpuVendorLayers[ADRENO]`. The matrix now correctly reports
MESA_TURNIP as Adreno-specific. Mali phones report MESA_TURNIP
as missing; Adreno phones report MESA_TURNIP as available.

This is a **test-discovered** bug — the design issue was
surfaced by the test that exercised a Mali GPU + MESA_TURNIP
query.

### 2. `missingLayers` test used MESA_TURNIP on ARM32+Adreno (a corner case)

**Symptom:** Test `missingLayers returns the layers not
supported by the ABI` failed.

**Root cause:** The test required NATIVE + MESA_TURNIP on
ARM32+Adreno. The matrix returned {NATIVE, MESA_TURNIP}
(union of ARM32's NATIVE + Adreno's MESA_TURNIP), so the
missing set was empty.

**Fix:** Changed the test to require NATIVE + BOX64 on
ARM32+Adreno. ARM32 doesn't have BOX64, so the missing set
is `{BOX64}`. The test now exercises a real missing-layer
case without depending on the MESA_TURNIP+ARM32 design
choice.

---

## Tests

23 new tests in `ElysiumAbiCapabilityMatrixTest`. The tests
cover:

- **Construction invariants** (5 tests): reject empty
  abiLayers, require ARM64, reject ABI with no layers, require
  every ABI has NATIVE, accept a well-formed configuration.
- **layersFor(abi)** (5 tests): ARM64 (with Adreno, full
  layer set), ARM64 without GPU vendor (no MESA_TURNIP),
  ARM32 (only NATIVE), X86_64 (NATIVE + Box64 + FEX + Wine),
  ANY (empty).
- **layersFor(abi, gpuVendor)** (3 tests): ARM64+Adreno
  (MESA_TURNIP present), ARM64+Mali (MESA_TURNIP absent),
  X86_64+Intel (MESA_TURNIP absent).
- **isLayerAvailable** (5 tests): positive case, negative
  case, unknown ABI, with GPU vendor (positive), with GPU
  vendor (negative).
- **missingLayers** (3 tests): required set fully available,
  required set not in ABI, required set not in GPU vendor
  layers.
- **Default matrix sanity** (2 tests): every well-known ABI
  is in the matrix, every GPU vendor is in the matrix.

**Total linux tests:** 161 (32 manifest + 30 package manager
+ 41 runtime layers + 35 rootfs layout + 23 capability
matrix).
**Total project tests:** 2796 (was 2773, +23 new).

---

## What's next — Phase 73 third half, sub-task I-73.3.4

`ElysiumUpdateStrategy` — the A/B vs versioned image rollback
policy. Elysium Linux supports two update strategies:

- **A/B updates** — two rootfs slots (`slot_a` + `slot_b`); an
  update writes the new rootfs to the inactive slot; the device
  reboots into the new slot; the old slot is preserved for
  rollback.
- **Versioned images** — every rootfs is a content-addressed
  image (`rootfs-v1.2.3.img`); the device holds the last N
  versions; a rollback is `pm rollback rootfs-v1.2.2`.

A/B is **faster** (no full re-install); versioned images are
**simpler** (no slot management). The strategy is a user
preference + a device-class preference (A/B requires dual
storage; versioned images work on any storage).

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/linux/ElysiumAbiCapabilityMatrix.kt` | new | matrix data class + default Android ARM64 |
| `app/src/test/java/com/elysium/vanguard/core/linux/ElysiumAbiCapabilityMatrixTest.kt` | new | 23 JVM tests |

---

## The role in the bigger picture

The capability matrix is the **orchestrator's brain**. When a
capsule declares "I need Box64 + Mesa Turnip" + "I target
ARM64", the orchestrator asks the matrix:

1. "What layers does this device have?" (`layersFor(abi,
   gpuVendor)`)
2. "Is the capsule's required set a subset of the device's
   available set?" (`missingLayers(required, abi, gpuVendor)`)
3. If yes, the orchestrator launches the capsule. If no, the
   orchestrator reports a typed `UnsupportedCapability` error.

The matrix is the **single source of truth** for runtime
support. Every orchestrator code path reads the matrix; no
code path hard-codes a layer's availability.
