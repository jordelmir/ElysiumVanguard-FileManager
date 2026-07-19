# Phase 76 — Runtime Selector (Universal Execution Engine)

> **Status:** ✅ Shipped (`commit pending`)
> **Date:** 2026-07-19
> **Phase:** 76 / Universal Execution Engine (vision section 6)
> **Predecessor:** Phase 75 (Elysium Linux default repository)
> **Vertical:** Runtime Orchestrator (`com.elysium.vanguard.core.orchestrator.*`)

---

## TL;DR

The **Runtime Selector** is operational. The typed
component that picks the optimal runtime layer for a
capsule + a device profile.

Per the user's vision doc, the universal execution
engine is:

```
User Action
    ↓
File Type / Manifest Detection
    ↓
Compatibility Resolver
    ↓
Architecture Detection
    ↓
Runtime Selection     ← THIS PHASE
    ├── Android Runtime
    ├── Native ARM64 ELF
    ├── Linux PRoot/chroot
    ├── Wine + Box64/FEX
    ├── QEMU VM
    └── Remote Execution
    ↓
Sandbox and Mount Policy
    ↓
Process Supervisor
```

The `RuntimeSelector` is the **Runtime Selection** step.
The selector takes a `Capsule` (the runtime contract) +
a `DeviceProfile` (the device's capabilities) + returns
a typed `RuntimeSelection` with the recommended
translation layer.

---

## What shipped

### `RuntimeSelector` (class)

The orchestrator that picks the optimal runtime. The
class has:

```kotlin
class RuntimeSelector(
    private val capabilityMatrix: ElysiumAbiCapabilityMatrix,
    private val runtimeLayerCatalog: ElysiumRuntimeLayerCatalog,
) {
    fun select(capsule: Capsule, device: DeviceProfile): RuntimeSelection
}
```

The selector is **declarative** (not procedural). The
selector computes the selection from:
- The capsule's `runtime` (LINUX / WINDOWS / MACOS / WEB).
- The capsule's `architecture` (ARM64 / ARM32 / X86_64 / X86).
- The capsule's `gpu` (api + driver).
- The device's `abi` + `gpuVendor` + `hasRoot` +
  `availableMemoryMb`.
- The runtime layer catalog (per Phase 73 third half
  I-73.3.1).
- The ABI capability matrix (per Phase 73 third half
  I-73.3.3).

### `DeviceProfile` (data class)

The device-side input to the selector.

```kotlin
data class DeviceProfile(
    val abi: ElysiumAbi,
    val gpuVendor: GPUVendor? = null,
    val hasRoot: Boolean = false,
    val availableMemoryMb: Long = 0L,
)
```

The profile captures the **device's capabilities** (not
the capsule's). The selector combines the device +
the capsule to determine the optimal runtime.

### `RuntimeSelection` (sealed class, 3 cases)

The typed result of a runtime selection. The 3 cases
are:

- **`Native(layer)`** — the capsule can run **natively**
  on the device. No translation is needed.
- **`Translated(layer, translation, targetAbi)`** — the
  capsule needs a **translation layer** to run on the
  device. The translation is one of [TranslationType].
- **`Unsupported(reason)`** — the capsule **cannot run**
  on the device. The reason is a human-readable
  description.

### `TranslationType` (enum, 7 values)

The typed translation strategy. The values are:

| Type | Meaning |
| --- | --- |
| `BOX64` | x86_64 user-mode translation via Box64. |
| `FEX` | x86 user-mode translation via FEX-Emu. |
| `WINE` | Windows API re-implementation via Wine. |
| `PROOT` | PRoot-based filesystem root (user-mode syscalls). |
| `CHROOT` | chroot-based filesystem root (kernel isolation). |
| `QEMU` | Full system emulation (slowest fallback). |
| `REMOTE` | Remote execution on the Oracle Free build server. |

### The selection algorithm

The `select` method follows this algorithm:

1. **Map the capsule's architecture** to `ElysiumAbi`.
   `ANY` is rejected (the consumer must pick an actual
   ABI).
2. **If targetAbi == device.abi**, the capsule can
   run natively. The selector returns `Native(layer)`.
3. **If targetAbi != device.abi**, the capsule needs
   translation. The selector picks the appropriate
   translation based on the target ABI:
   - `X86_64` Linux → Box64.
   - `X86` Linux → FEX.
   - `ARM32` Linux → Unsupported (no ARM32-to-ARM64
     translation in the default catalog).
   - Windows on any ABI → Wine.
4. **For the native case**, the selector checks that
   the device supports all the capsule's required
   capabilities (EXECUTE_NATIVE, GPU_VULKAN, etc.).
   Missing capabilities → `Unsupported`.

---

## Design decisions

### Why is the selector a class, not an object?

The selector depends on **two runtime dependencies**:
the capability matrix + the runtime layer catalog. A
class captures the dependency injection (Hilt provides
both); an object would couple the selector to a
specific matrix + catalog (not testable).

The selector is **stateless** (no mutable fields) —
multiple selectors with the same dependencies produce
the same result. The class is thread-safe.

### Why a `DeviceProfile`, not a `DeviceCapabilities`?

The profile is **device-side** (the device declares
its capabilities). The capsule is **capsule-side** (the
capsule declares its requirements). The selector
combines both.

A single `DeviceCapabilities` class would conflate the
two perspectives; the selector would need to know
"which side is this?". The two-class design makes the
selector's input explicit + type-safe.

### Why is `RuntimeSelection` a sealed class, not a single class with a flag?

A sealed class is **exhaustive**. The consumer (the
orchestrator) uses `when (selection)` to dispatch by
case:
- `is Native` → run the capsule directly.
- `is Translated` → run the capsule via the translation
  layer.
- `is Unsupported` → display the reason to the user.

A single class with a flag would be lossy (the consumer
would need to check multiple fields). The sealed class
captures the **3 distinct outcomes** the selector can
produce.

### Why is `TranslationType` separate from `ElysiumRuntimeLayer`?

A translation type is a **strategy** (what to do); a
runtime layer is an **implementation** (the actual
binaries). The two are related but distinct:
- `TranslationType.BOX64` is the strategy ("use Box64 to
  translate x86_64 binaries").
- `ElysiumRuntimeLayer.Box64(version)` is the
  implementation ("the Box64 binary at version X").

A future increment can add multiple implementations per
translation type (e.g. a future Box64-Fast + Box64-Safe
+ Box64-Trace). The translation type is the **public
contract**; the layer is the **internal implementation**.

---

## Bug-fixes (test-discovered, fixed in this phase)

### 1. `!in` operator type mismatch (capability vs layer id)

**Symptom:** `Type inference failed` at compile time
on the line `if (ElysiumRuntimeCapability.EXECUTE_WINDOWS
!in available)`.

**Root cause:** The `available` set was
`Set<ElysiumRuntimeLayerId>` (from `capabilityMatrix.layersFor`),
but I was checking if an `ElysiumRuntimeCapability` was
in it. The `!in` operator tried to find the capability
in a `Set<LayerId>` — type mismatch.

**Fix:** Refactored `selectNative` to use the
**catalog** for capability lookup (not the matrix). The
catalog's `latestForAbi(layerId, abi)` returns the layer
manifest; the manifest's `capabilities` are the
typed capability set. The selector's `availableCapabilitiesFor(device)`
method computes the union of all installed layers'
capabilities.

### 2. Pure sandbox rejected by `Permissions` constructor

**Symptom:** `IllegalArgumentException: a capsule with no
storage AND no network is a pure sandbox` at the test
capsule construction.

**Root cause:** The test fixture used
`Permissions(network = false, storage = emptyList())`.
The `Permissions.init` block rejects a pure sandbox (per
the Phase 68 invariant).

**Fix:** Changed the test fixture to use
`Permissions(network = false, storage = listOf(StorageScope.APP_PRIVATE))`.

### 3. Backtick-quoted test name with `:` character

**Symptom:** `Name contains illegal characters: :` at
compile time.

**Root cause:** The test name `realistic scenario: Steam
(x86_64 Windows) on Adreno ARM64 device` contained a
`:` character; Kotlin backtick-quoted names reject `:`
(per the memory rule).

**Fix:** Renamed to `realistic scenario Steam (x86_64
Windows) on Adreno ARM64 device` (no `:` character).

### 4. `latest` method ambiguous overload

**Symptom:** `Type inference failed. The value of the
type parameter T should be mentioned in input types` at
the `latestLayer` helper.

**Root cause:** `runtimeLayerCatalog.latest(layerId)` had
multiple overloads (`latest(id)` + `latestForAbi(id,
abi)`), and the chained call `latest(layerId)?.version`
was ambiguous.

**Fix:** Used a block body with explicit intermediate
variable: `val latest = runtimeLayerCatalog.latest(layerId)
?: return null`.

---

## Tests

9 new tests in `RuntimeSelectorTest`. The tests cover:

- **Native selection** (2 tests): capsule ABI matches
  device ABI, Vulkan on Adreno.
- **Translated selection** (3 tests): Box64 for x86_64,
  FEX for x86, Wine for Windows.
- **Unsupported selection** (2 tests): ARM32 on ARM64,
  Windows on x86 without Wine.
- **GPU requirement** (1 test): Vulkan on Mali (no
  Vulkan layer for Mali) is unsupported.
- **Realistic scenario** (1 test): Steam (x86_64
  Windows) on Adreno ARM64 device → Wine + DXVK.

**Total project tests:** 3036 (was 3027, +9 new).

---

## What's next — Phase 76 second half (Universal Execution Engine)

`RuntimeDispatcher` — the orchestrator that takes a
`RuntimeSelection` + a `WorkspaceDefinition` +
launches the capsule. The dispatcher:

1. **For `Native`** — launches the capsule's
   `entrypoint.executable` directly.
2. **For `Translated`** — wraps the entrypoint with
   the translation layer (e.g. `box64 <executable>` for
   Box64, `wine <executable>` for Wine).
3. **For `Unsupported`** — returns a typed
   `RuntimeSelection.Unsupported` error.

The dispatcher is the **runtime equivalent** of the
selector: the selector decides WHAT to do; the
dispatcher DOES it. The two are the **typed bridge**
between the Capsule contract + the actual process
launch.

A future Phase 7+ increment can add a `RemoteDispatcher`
that dispatches to the Oracle Free build server for
heavy / incompatible workloads (per the vision's
"Remote Execution" + "Compilación remota efímera").

---

## Files

| File | Status | Role |
| --- | --- | --- |
| `app/src/main/java/com/elysium/vanguard/core/orchestrator/RuntimeSelector.kt` | new | selector class + device profile + selection + translation type |
| `app/src/test/java/com/elysium/vanguard/core/orchestrator/RuntimeSelectorTest.kt` | new | 9 JVM tests |

---

## The role in the bigger picture

The `RuntimeSelector` is the **typed heart** of the
Universal Execution Engine (per the vision doc section
6). The selector:

- **Detects the format** (capsule's `runtime`).
- **Detects the architecture** (capsule's `architecture`).
- **Detects the dependencies** (capsule's `gpu` + permissions).
- **Detects the ABI** (device's `abi`).
- **Detects the GPU** (device's `gpuVendor`).
- **Detects the memory** (device's `availableMemoryMb`).
- **Detects the security** (device's `hasRoot`).
- **Selects the optimal runtime** (the typed
  `RuntimeSelection`).

The selector's output is consumed by the
`RuntimeDispatcher` (Phase 76 second half) which
launches the actual process. The selector + dispatcher
together implement the **Runtime Selection** +
**Sandbox and Mount Policy** + **Process Supervisor**
steps in the vision doc.

The selector is the **bridge** between the typed
foundation (Phase 73) + the runtime infrastructure
(Phase 66-71) + the universal execution engine (this
phase). The bridge is **declarative** (the selector
computes the selection; it doesn't probe commands at
random) + **typed** (every input + output is a typed
value) + **testable** (pure-domain, no I/O, no Android
dependencies).
