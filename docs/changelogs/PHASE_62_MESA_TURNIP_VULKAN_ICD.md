# Phase 62 — Mesa Turnip Vulkan ICD

> **Status:** shipped 2026-07-18 against git head `560a156` (the Universal Desktop Shell).
> **Build evidence:**
> - `testDebugUnitTest` — **1820 tests, 0 failures, 0 errors, 2 skipped** (was 1792; +28 new in this commit)
> - `assembleDebug` — green, `app-debug.apk` 101 MB
> - **0 lint errors, 0 warnings**

---

## 0. What this phase is

The **Mesa Turnip Vulkan ICD** is the
platform's integration with the
open-source Vulkan driver for Qualcomm
Adreno GPUs. Mesa Turnip is the
production-grade Vulkan 1.3 driver based
on the `freedreno` project; it ships
binary `.so` files that the Android
Vulkan loader loads.

Phase 62 ships the **JVM-side** integration:
the `VulkanICD` interface, the
`VulkanICDFactory` (selects the right ICD
for the GPU), the `TurnipICD` (the
JVM-side wrapper for Mesa Turnip), the
`GPUVendor` enum (8 values: Adreno, Mali,
PowerVR, Intel, AMD, NVIDIA, Apple,
Unknown), the `VulkanCapability` (the
driver's reported capability), and the
build instructions for compiling Mesa
Turnip for Android.

The **native** side (the actual Mesa
build + cross-compile) is documented in
the `TurnipICD` class docstring + is a
follow-up deliverable. Mesa Turnip
requires the Android NDK + a cross-
compile of the Mesa source tree; the
JVM-side integration is the contract
that the native library satisfies.

---

## 1. Architecture decisions

- **JVM-side contract** (per
  `R-CH-3` in `docs/foundry/risk-register.md`):
  the `VulkanICD` interface is a typed
  contract (not a `Map<String, Any>`).
  The consumer (the desktop shell + the
  3D pipeline) uses the contract to
  query the driver for its capability.
- **Factory pattern** (per the existing
  EV pattern: `RuntimeModule`,
  `SyncModule`): the
  `VulkanICDFactory.create(vendor)`
  is the **only legitimate way** to
  create an ICD. The consumer does
  not instantiate the ICDs directly.
- **GPU vendor detection** (per the
  `Build.MANUFACTURER` heuristic): the
  `GPUVendor.detect` method determines
  the vendor from the manufacturer +
  model strings. The detection is
  best-effort; an unrecognized device
  falls back to `UNKNOWN` + the
  `NullICD` (which reports no Vulkan
  capability).
- **Stub-based stubs**: the non-Turnip
  ICDs (Mali, PowerVR, Intel, AMD,
  NVIDIA, Apple) are stubs that expose
  the metadata (vendor + library name
  + display name + API version) without
  the actual native library call. The
  Phase 2 implementation replaces the
  stubs with real JNI calls.

---

## 2. Files added (4 main + 1 test = 5 new)

```
app/src/main/java/com/elysium/vanguard/core/graphics/
├── GPUVendor.kt                (8-value enum + detect heuristic)
├── VulkanApiVersion.kt         (in VulkanCapability.kt; VK_1_0..VK_1_4 + UNKNOWN)
├── VulkanCapability.kt         (data class: API version + driver name + extensions)
├── VulkanICD.kt                (interface: vendor + library + display + loadCapability)
├── TurnipICD.kt                (the JVM-side Mesa Turnip wrapper)
└── VulkanICDFactory.kt         (factory + 7 stub ICDs + NullICD)

app/src/test/java/com/elysium/vanguard/core/graphics/
└── VulkanICDTest.kt            (28 tests)
```

---

## 3. The `GPUVendor` enum (8 values)

```kotlin
enum class GPUVendor {
    ADRENO,     // Qualcomm Adreno (Snapdragon)
    MALI,       // ARM Mali (MediaTek, Samsung Exynos)
    POWER_VR,   // Imagination PowerVR
    INTEL,      // Intel (x86 emulators, Chromebooks)
    AMD,        // AMD (some emulators, Steam Deck)
    NVIDIA,     // NVIDIA (Tegra, Shield)
    APPLE,      // Apple (M-series, emulated)
    UNKNOWN,    // No match; ICD is a stub
}
```

The detection (`GPUVendor.detect(manufacturer, model)`) uses
a best-effort heuristic: a `qualcomm` in the
manufacturer OR an `adreno` in the model
detects Adreno; a `mediatek` in the manufacturer
+ a `mali` or `dimensity` in the model detects
Mali; etc. The heuristic is loose: better to
over-detect and pick a reasonable ICD than
to under-detect and fall back to the stub.

---

## 4. The `VulkanApiVersion` enum (6 values)

```kotlin
enum class VulkanApiVersion(val major: Int, val minor: Int) {
    VK_1_0(1, 0),
    VK_1_1(1, 1),
    VK_1_2(1, 2),
    VK_1_3(1, 3),
    VK_1_4(1, 4),
    UNKNOWN(0, 0),
}
```

The `fromEncoded(encoded)` method decodes a
Vulkan API version (the `VK_MAKE_API_VERSION`
macro format: `(major << 22) | (minor << 12)
| patch`) into the enum value.

The `VulkanCapability.supportsVulkan11/12/13`
getters do an explicit comparison
(`apiVersion == VK_1_1 || supportsVulkan12`)
rather than enum comparison, because
`UNKNOWN` is in the enum (and enum order
would put it after `VK_1_4`, breaking the
comparison).

---

## 5. The Mesa Turnip build instructions

The `TurnipICD` class docstring contains
the full build instructions. The summary:

```bash
# 1. Get the Mesa source.
git clone https://gitlab.freedesktop.org/mesa/mesa.git
cd mesa

# 2. Set up the Android NDK cross-compile toolchain.
#    Set $ANDROID_NDK_HOME to the NDK root.

# 3. Configure Mesa for Android + Turnip.
meson setup build-android \
    --cross-file android-cross.ini \
    -Dvulkan-drivers=freedreno \
    -Dplatforms=android

# 4. Build.
ninja -C build-android

# 5. The output is libvulkan_adreno.so.
#    Copy it to app/src/main/jniLibs/<abi>/.
cp build-android/src/freedreno/vulkan/libvulkan_adreno.so \
    app/src/main/jniLibs/arm64-v8a/
```

The JVM-side wrapper exposes the library
as `vulkan.adreno` (per Android's Vulkan
loader convention). The Android Vulkan
loader finds the library in
`jniLibs/<abi>/libvulkan_adreno.so` +
loads it on demand.

---

## 6. The 28 tests cover

- Factory returns the right ICD for each
  vendor (8 tests, one per vendor).
- `TurnipICD` reports Vulkan 1.3 capability.
- `TurnipICD` reports the freedreno
  extensions (VK_KHR_swapchain,
  VK_KHR_dynamic_rendering, etc.).
- `TurnipICD` has the correct metadata
  (vendor + library name + display name).
- `GPUVendor.detect` identifies each
  vendor correctly (6 tests).
- `GPUVendor.detect` is case insensitive.
- `VulkanApiVersion.fromEncoded` decodes
  the spec values correctly (4 tests for
  VK_1_0/1_1/1_2/1_3 + 1 for unknown).
- `VulkanCapability.supportsVulkan11/12/13`
  report the correct values.
- `NullICD` reports unknown API version.
- Factory has 8 vendor mappings (every
  GPUVendor value has an ICD).

---

## 7. What's NOT in Phase 62 (deferred to later phases)

- **The actual Mesa build script**: a
  build script that automates the
  `meson setup` + `ninja` + `cp` steps.
  Phase 2 follow-up.
- **The native library packaging**:
  the `libvulkan_adreno.so` packaged
  per-ABI in `jniLibs/`. Phase 2.
- **The JNI call to the native library**:
  the actual Vulkan instance creation
  + the device selection + the queue
  creation. Phase 2.
- **The 3D pipeline integration**: the
  desktop shell's windowing composable
  that uses Vulkan to render. Phase 3+
  (depends on the Compose windowing
  primitives stabilizing).
- **Raytracing + mesh shader support**:
  the optional Vulkan extensions for
  the next-gen 3D features. Phase 3+.

---

## 8. Build evidence

```
./gradlew testDebugUnitTest
  -> 1820 tests, 0 failures, 0 errors, 2 skipped
  -> Graphics tests: 28 (new in this commit)
  -> EV + Foundry + Market + Desktop baseline: 1792

./gradlew assembleDebug
  -> BUILD SUCCESSFUL
  -> app-debug.apk: 101 MB

Lint:
  -> 0 errors, 0 warnings
```

---

## 9. Next steps (continuing the pending list)

- **Phase 63** — Security Zero Trust
  completion: the remaining hardening
  items (envelope encryption, secrets
  in vault, CVE monitoring, etc.).
- **Phase 64** — Instrumented test on
  real device: expand the `androidTest/`
  coverage to the Desktop Shell + the
  Market install flow + the Vulkan ICD
  detection.
- **Phase 65** — Multiple distros: the
  first batch of community distros in
  the catalog.

---

> "The Mesa Turnip ICD is the open-source
> Vulkan driver for Adreno. The JVM-side
> integration is the contract; the native
> build is the implementation. The factory
> picks the right driver for the GPU; the
> capability report tells the platform
> what's possible. Every GPU vendor has a
> path: Adreno -> Turnip, Mali -> ARM,
> PowerVR -> IMG, etc. The detection is
> best-effort; the fall-back is the
> NullICD. The foundation is solid; the
> native build is a follow-up."
