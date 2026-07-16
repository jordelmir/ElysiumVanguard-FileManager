# ADR-010 — Android Hardware Enforcer + Platform Provider

Status: **Accepted** (Phase 20, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Phase 19 added the `HardwareEnforcer` interface and the
`HardwareEnforcementService` glue. The service asks the
broker, then hands the decision to the enforcer. The
enforcer is the seam between the broker's per-request
decision and the actual platform API.

Until Phase 20 there was no production enforcer — the
service had no platform backend to dispatch to. The
recording enforcer was the test seam; the production
wiring was missing. A guest that wanted
`UsbManager.requestPermission` had no path from the
runtime's policy to the actual platform call.

The challenge: the production enforcer talks to Android's
`UsbManager`, `BluetoothManager`, `NfcManager`,
`SensorManager`, `LocationManager`, `CameraManager`,
`AudioRecord` — all `Context`-dependent classes that are
not available in unit tests. The engineering gotcha
prescribes a small interface that captures only the values
the JVM-testable code actually reads.

## Decision

We split the production enforcer into two layers:

1. **`PlatformHardwareProvider`** — a small interface
   (10 methods) that captures only the values the
   JVM-testable enforcer reads:
   - `listUsbDevices()`, `requestUsbPermission()`,
     `openUsbDevice()` (USB)
   - `listBluetoothDevices()`, `isBluetoothEnabled()` (BT)
   - `isNfcEnabled()` (NFC)
   - `hasCamera()`, `hasMicrophone()` (camera + mic)
   - `lastKnownLocation(accuracy)` (location)
   - `listSensors()` (sensors)

2. **`AndroidHardwareEnforcer`** — the production
   enforcer that implements `HardwareEnforcer` and uses
   the provider. The enforcer itself does NOT depend on
   `Context`; the provider does. The enforcer is
   JVM-testable with a 5-line `FakePlatformHardwareProvider`.

The provider's methods return typed records (`UsbDeviceInfo`,
`BluetoothDeviceInfo`, `LocationFix`, `SensorInfo`) instead
of platform objects. The enforcer stores these records as
snapshots; the caller reads them via `enforcer.readSnapshot(key)`.

### Per-class dispatch

The enforcer's per-class dispatch is small and uniform:

| Class | LIST | READ | WRITE / CONNECT |
|---|---|---|---|
| USB | `provider.listUsbDevices()` → snapshot | `provider.requestUsbPermission()` → Granted/PendingConsent/Denied | `provider.openUsbDevice()` → Granted with handle |
| Bluetooth | `provider.isBluetoothEnabled()` + `listBluetoothDevices()` → snapshot | synthetic Granted (caller queries provider) | synthetic handle (BT socket not yet modeled) |
| NFC | `provider.isNfcEnabled()` → Granted or Error | same as LIST | synthetic handle |
| Camera | `provider.hasCamera()` → Granted or Error | same as LIST | synthetic handle |
| Microphone | `provider.hasMicrophone()` → Granted or Error | same as LIST | synthetic handle |
| Location | n/a | `provider.lastKnownLocation(accuracy)` → snapshot | n/a |
| Sensors | `provider.listSensors()` → snapshot | snapshot + handle | handle |

The enforcer wraps every call in `try { ... } catch (e: Throwable) { Error(e) }` — a platform exception surfaces as
`HardwareEnforcementResult.Error` with the original cause.

### Why a small interface, not a god-interface

The engineering gotcha for Context-dependent Android code
is: define a small interface that captures only the
values the JVM-testable code reads. A "god-interface"
that exposes `UsbManager`, `BluetoothManager`, etc.
directly would couple the enforcer to Android and make
unit testing impossible. The 10-method `PlatformHardwareProvider`
is the minimum surface the enforcer needs; the production
adapter wraps the real managers, the test adapter is a
5-line hand-rolled fake.

### Why a `FakePlatformHardwareProvider` and not mockito

The codebase's `engineering-gotchas.md` flags mockito as
fragile. A 5-line hand-rolled fake is simpler, has no
runtime cost, and the test's intent is obvious from the
test code (every method on the fake is a one-liner
returning a configurable field).

### Why the enforcer stores snapshots

The enforcer hands back a `HardwareHandle` (an opaque
id) for long-lived resources (USB connection, BT
socket). The caller can pass the handle back to the
enforcer for follow-up operations. The enforcer stores
the typed records (device info, sensor list, location
fix) in a private `snapshots: Map<String, Any?>` keyed
by `class:targetId:sessionId`. The caller reads via
`enforcer.readSnapshot<T>(key)`.

The map is thread-safe (synchronized on a private lock);
the enforcer's `enforce` is callable from multiple
session coroutines concurrently. The test exercises
8 × 50 concurrent CONNECT requests with no races.

## Consequences

### Positive

- **JVM-testable end-to-end.** The enforcer depends on
  the small provider interface; the test uses a 5-line
  fake. No `Context` is needed. The 20 unit tests cover
  every class + every action + every result shape.
- **Defensive Deny short-circuit.** A caller that hands
  the enforcer a `Deny` decision (the service never
  does, but a future caller might) returns `Denied`
  without calling the provider. The defensive guard is
  a single `if (decision is Deny) return Denied`.
- **Provider exception is wrapped.** A platform
  exception (`IllegalStateException("USB subsystem not
  available")`) surfaces as
  `HardwareEnforcementResult.Error(cause)`. The caller
  decides whether to retry, log, or surface the error
  to the user.
- **Snapshot pattern keeps handles opaque.** The
  `HardwareHandle` id is a string; the enforcer stores
  the live platform object (in production) or the typed
  record (in tests) under that id. The caller never
  holds a live `UsbDeviceConnection`; the enforcer owns
  the lifecycle.
- **Per-class dispatch is uniform.** The enforcer's
  `when (request.hardwareClass)` is a 7-branch table.
  Adding a new class is a single-branch addition.

### Negative

- **Bluetooth `WRITE / CONNECT` is a stub.** The
  provider does not model `BluetoothSocket`; the
  enforcer returns a synthetic handle. A future phase
  adds `openBluetoothSocket(address): BluetoothSocket?`
  to the provider.
- **`Camera` / `Microphone` WRITE / CONNECT are
  stubs.** The enforcer returns a synthetic handle;
  the provider does not model the `CameraDevice` or
  `AudioRecord` open path. A future phase adds the
  open methods.
- **Location WRITE is not modeled.** A guest that wants
  to *set* the location (mock location for testing) is
  not currently supported; the master order does not
  list mock-location as a runtime feature.
- **No production Android adapter in this phase.** The
  `PlatformHardwareProvider` interface exists; the
  production impl (`AndroidPlatformHardwareProvider`
  that wraps `UsbManager`, `BluetoothManager`, etc.)
  is a follow-up phase (Phase 21). The Hilt module
  wires the production impl; the test module wires the
  fake.

## Alternatives considered

1. **Have `AndroidHardwareEnforcer` depend on `Context`
   directly.** A single class that takes `Context` and
   reaches for `context.getSystemService(UsbManager::class.java)`
   internally. Rejected: not JVM-testable; the
   engineering gotcha is a hard rule for this codebase.
2. **Have the enforcer talk to a `HardwareManagerFacade`
   that wraps all the Android managers.** A single
   god-interface. Rejected: same JVM-testability
   problem, plus the facade would be hundreds of
   methods wide. The small-interface-per-need pattern
   is what the gotcha prescribes.
3. **Skip the snapshot pattern; pass the live platform
   object back as a handle.** The handle is a
   `UsbDeviceConnection`, not a string. Rejected: the
   handle must be serialisable (the future audit +
   consent phases will persist it), and live platform
   objects are not.

## Revisit triggers

- The first real on-device install needs a hardware
  class we don't model (e.g. `IR` blaster, `Thread`
  mesh). We add a `HardwareClass` enum entry + a
  provider method + an enforcer branch + tests.
- The synthetic handle for Bluetooth WRITE / CONNECT
  is exercised by a real guest. We add
  `openBluetoothSocket(address)` to the provider +
  the corresponding branch in the enforcer.
- The Hilt module for the production
  `AndroidPlatformHardwareProvider` is wired. We add a
  Hilt-injected `Context` parameter; the test
  environment provides a fake context (or uses
  `RuntimeEnvironment.application`).
