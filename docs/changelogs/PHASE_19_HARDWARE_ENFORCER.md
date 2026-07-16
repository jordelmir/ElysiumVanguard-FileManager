# Phase 19 — Hardware Enforcer + Service

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1138 tests, 0 failures, 2 skipped.

## What landed

The runtime's hardware access path now has a typed enforcer
seam. The `HardwareBroker` (Phase 18) said yes / no /
require-confirmation; the new `HardwareEnforcementService`
hands the broker's decision to a `HardwareEnforcer` that
translates it into a platform call. The Phase 20 Android
adapter plugs into the same seam.

### Files

**Production (3 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/enforcer/HardwareRequest.kt`
  — `HardwareRequest` data class (sessionId + policy +
  class + action + target + decision), the opaque
  `HardwareHandle` value class, and the `HardwareEnforcementResult`
  sealed class (Granted / PendingConsent / Denied / Error).
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/enforcer/HardwareEnforcer.kt`
  — the `HardwareEnforcer` interface and the
  `RecordingHardwareEnforcer` test impl. The recording
  enforcer supports a default response + an optional
  per-request response function.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/enforcer/HardwareEnforcementService.kt`
  — the runtime's user-facing entry point. The service
  asks the broker, then dispatches to the enforcer. Deny
  decisions short-circuit; the service records the deny
  in the audit log to keep the log complete.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/hardware/enforcer/HardwareEnforcementServiceTest.kt`
  — 14 tests pinning: Allow reaches the enforcer with
  the right request baked in; AllowWithConfirmation
  reaches the enforcer and surfaces `PendingConsent`; Deny
  never reaches the enforcer; enforcer Error propagates
  with the cause; enforcer Denied is the result when the
  platform refuses; the audit log records all 3 decision
  kinds; the service is thread-safe under 8 × 50
  concurrent requests; the recording enforcer's default
  + per-request response overrides.

**ADR (1 new):**

- `docs/adr/ADR-009-hardware-enforcer-service.md` —
  context, decision, the four result shapes, the
  deny-audit short-circuit, consequences, alternatives,
  revisit triggers.

### Why this matters

Before Phase 19, the broker from Phase 18 was advisory: a
guest that called `BluetoothAdapter.startDiscovery()`
directly (bypassing the broker) was not stopped. The
runtime's hardware guarantees depended on every guest
pathologically respecting the broker — an unrealistic
assumption.

Phase 19 closes the gap. The enforcer is the seam
between the broker's decision and the platform API call.
A guest that wants to use USB, Bluetooth, NFC, or any
other hardware class *must* go through the service. The
service asks the broker, then dispatches the typed
decision. A Deny short-circuits the platform call. The
audit log is complete (allow + confirmation + deny).

The Phase 20 Android adapter (`AndroidHardwareEnforcer`)
plugs into the same seam — production wires the adapter,
tests wire the recording enforcer. The decision logic
stays JVM-testable end-to-end.

### The four result shapes

| Result | When |
|---|---|
| `Granted(handle)` | The platform call succeeded. `handle` is non-null for long-lived resources (USB `openDevice`, BT `createRfcommSocket`), null for LIST / READ. |
| `PendingConsent(consentId)` | The broker said `AllowWithConfirmation`; the enforcer dispatched a consent dialog. The runtime polls or subscribes for the matching `consentId`. |
| `Denied` | The broker said Deny, OR the platform call itself returned "permission denied". |
| `Error(cause)` | A non-permission error (USB device disconnected, Bluetooth radio off). |

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `HardwareEnforcementServiceTest` | 14 | 0 |
| **Project total** | **1138** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bug fix during this phase

One test used `RecordingHardwareEnforcer().apply { fail(...) }`
to "prove the enforcer is not called". The `apply` block
runs eagerly on construction, so `fail()` threw on
construction — not on dispatch. Replaced with a real
recording enforcer + `enforcer.size() == 0` assertion,
which is what the test was actually trying to prove.

## Next phase

**Phase 20 — Android Hardware Adapter (ADR-010).** The
production wiring of `HardwareEnforcer`:
`AndroidHardwareEnforcer` wraps `UsbManager.requestPermission`,
`BluetoothAdapter.startDiscovery` / `createRfcommSocket`,
`NfcAdapter.enable`, `SensorManager.registerListener`, and
the corresponding `CONNECT` / `READ` / `WRITE` operations.
The recording enforcer is swapped out for the real one in
the Hilt module; the service contract is unchanged.

Then **§19 WinLayer + §20 Windows VM** (master order). The
Windows layer is the WSL/equivalent that lets Elysium run
Windows guests under a QEMU/KVM-backed VM.
