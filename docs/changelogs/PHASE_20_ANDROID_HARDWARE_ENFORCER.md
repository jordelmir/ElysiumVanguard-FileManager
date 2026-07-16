# Phase 20 — Android Hardware Enforcer + Platform Provider

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1158 tests, 0 failures, 2 skipped.

## What landed

The runtime's hardware access path now has the production
backend. The `AndroidHardwareEnforcer` implements
`HardwareEnforcer` (Phase 19) and uses a small
`PlatformHardwareProvider` interface to talk to the
platform. The provider is the seam that keeps the enforcer
JVM-testable (no `Context` dependency) while letting the
production adapter wrap the real Android managers.

### Files

**Production (2 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/provider/PlatformHardwareProvider.kt`
  — the small interface (10 methods) and the typed records
  (`UsbDeviceInfo`, `BluetoothDeviceInfo`, `LocationFix`,
  `SensorInfo`, `LocationAccuracy`). The interface is what
  the engineering gotcha prescribes for Context-dependent
  Android code: capture only the values the JVM-testable
  code actually reads.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/enforcer/AndroidHardwareEnforcer.kt`
  — the production enforcer. Implements `HardwareEnforcer`,
  uses `PlatformHardwareProvider` via dependency
  injection. Per-class dispatch: USB / Bluetooth / NFC /
  Camera / Microphone / Location / Sensors, each with a
  LIST / READ / WRITE / CONNECT path. The enforcer stores
  typed records as snapshots keyed by `class:targetId:sessionId`
  and returns opaque `HardwareHandle` ids for long-lived
  resources. Thread-safe (synchronized on a private lock).

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/hardware/enforcer/AndroidHardwareEnforcerTest.kt`
  — 20 tests covering every class + every action + every
  result shape. The test uses a 5-line
  `FakePlatformHardwareProvider` (per the gotcha). Tests
  pin: USB LIST/READ/CONNECT paths (granted, pending,
  denied, no device), Bluetooth LIST (enabled/disabled),
  NFC LIST, Camera/Microphone, LOCATION READ with fix and
  without, SENSORS LIST, provider exception → Error, Deny
  short-circuit, thread-safety under 8 × 50 concurrent
  CONNECT, and the consent lifecycle (`resolveConsent`
  removes the pending entry).

**ADR (1 new):**

- `docs/adr/ADR-010-android-hardware-enforcer.md` — context,
  decision, per-class dispatch table, the small-interface
  rationale, snapshot pattern, consequences, alternatives,
  revisit triggers.

### Why this matters

Before Phase 20 the `HardwareEnforcementService` (Phase 19)
had no production backend. A guest that wanted
`UsbManager.requestPermission` had no path from the
runtime's policy to the actual platform call. Phase 20
closes the seam.

The `AndroidHardwareEnforcer` is the production adapter.
It does NOT depend on `Context` — it depends on the small
`PlatformHardwareProvider` interface, which captures
exactly the values the enforcer reads. The production
adapter for the provider (which wraps the real Android
managers) is a follow-up phase (Phase 21) that adds the
`AndroidPlatformHardwareProvider` and the Hilt wiring.

The test uses a 5-line `FakePlatformHardwareProvider`
that the test class instantiates once and reconfigures
per test. The 20 tests cover every class + every action
+ every result shape end-to-end, without an Android
device.

### The per-class dispatch

| Class | LIST / READ | WRITE / CONNECT |
|---|---|---|
| USB | `provider.listUsbDevices()` → snapshot; READ calls `requestUsbPermission()` → Granted / PendingConsent (with stable `consentId`) / Denied | `provider.openUsbDevice()` → Granted with `HardwareHandle` |
| Bluetooth | enabled check + `listBluetoothDevices()` → snapshot | synthetic handle (BT socket not yet modeled) |
| NFC | enabled check → Granted or Error | synthetic handle |
| Camera / Microphone | `hasCamera()` / `hasMicrophone()` → Granted or Error | synthetic handle |
| Location | `lastKnownLocation(accuracy)` → snapshot (or null when no fix) | n/a |
| Sensors | `listSensors()` → snapshot | handle + snapshot |

Every dispatch is wrapped in `try { ... } catch (e: Throwable) { Error(e) }` — a platform exception surfaces as
`HardwareEnforcementResult.Error` with the original
cause.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `AndroidHardwareEnforcerTest` | 20 | 0 |
| **Project total** | **1158** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bugs found and fixed during this phase

1. **`value as Any` cast threw NPE when the snapshot
   value was null.** The `storeSnapshotAndGrant` helper
   had `snapshots[key] = value as Any`; multiple call
   sites (NFC, camera, microphone) pass `value = null`.
   Changed the map to `Map<String, Any?>` and dropped
   the cast.
2. **Test data used hex `0x1234` for VID/PID but
   compared against the decimal form `"1234:5678"`.**
   The enforcer formats `${vendorId}:${productId}` as
   decimal, so the comparison `"4660:22136" == "1234:5678"`
   failed. Changed the test data to use decimal
   `1234` / `5678` to match the lookup format.
3. **Thread-safety test assumed USB CONNECT writes a
   snapshot.** It doesn't (CONNECT returns a `Granted(handle)`
   directly). The real concurrent-safety signal is "no
   exception was thrown under contention". Replaced the
   snapshot-count assertion with "all 400 requests
   returned Granted".

## Next phase

**Phase 21 — Hilt module for the production
`AndroidPlatformHardwareProvider`.** The Hilt module
wires the production provider (which wraps
`UsbManager`, `BluetoothManager`, etc.) into the
`HardwareEnforcementService`. The interface contract
is unchanged; only the production adapter changes.

Then **§19 WinLayer + §20 Windows VM** (master order).
The Windows layer is the WSL/equivalent that lets
Elysium run Windows guests under a QEMU/KVM-backed VM.
