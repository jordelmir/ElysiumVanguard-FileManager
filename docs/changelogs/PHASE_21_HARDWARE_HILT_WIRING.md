# Phase 21 — Hardware Hilt Wiring + Production Provider

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1166 tests, 0 failures, 2 skipped.

## What landed

The hardware access path is now wired into the runtime's
Hilt graph. The `HardwareModule` provides five singletons
(production provider, enforcer, broker, audit log,
service) into `SingletonComponent`. The
`AndroidPlatformHardwareProvider` wraps the real Android
platform managers and checks each Android permission
before each call.

### Files

**Production (2 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/provider/AndroidPlatformHardwareProvider.kt`
  — the production provider. Wraps `UsbManager`,
  `BluetoothManager`, `NfcAdapter`, `SensorManager`,
  `LocationManager`, `CameraManager`, `AudioRecord` and
  adapts them to the small `PlatformHardwareProvider`
  interface. Each method checks the relevant Android
  permission via `ContextCompat.checkSelfPermission`
  and returns a "device absent" result when the
  permission is not granted. USB device id is
  normalised to String (Android's `UsbDevice.deviceId`
  is an Int).
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/HardwareModule.kt`
  — the Hilt module. `@InstallIn(SingletonComponent::class)`,
  five `@Provides @Singleton` functions:
  `providePlatformHardwareProvider`,
  `provideHardwareEnforcer`, `provideHardwareBroker`,
  `provideHardwareAuditLog`,
  `provideHardwareEnforcementService`.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/hardware/HardwareModuleTest.kt`
  — 8 unit tests pinning the module's contract: the
  production provider and enforcer implement their
  interfaces; the module exposes all five `provide*`
  functions; the broker + audit log provider methods
  return fresh instances; the service composes the
  three collaborators correctly; the service's Deny
  short-circuit works through the module.

**ADR (1 new):**

- `docs/adr/ADR-011-hardware-hilt-wiring.md` — context,
  decision, the new-module-vs-extend decision, the
  permission model, the per-process singleton scope,
  consequences, alternatives, revisit triggers.

### Why this matters

Phases 18–20 added the four hardware access layers in
isolation. Until Phase 21, no consumer could ask the
runtime for a `HardwareEnforcementService` — the Hilt
graph did not know about the hardware path. Phase 21
closes that gap. The Hilt module is the single place
the runtime composes the broker + enforcer + audit log
into the user-facing service.

The `AndroidPlatformHardwareProvider` is the production
adapter. It does not enforce policy (the runtime's broker
is the policy layer); it translates platform calls to
typed records and checks each Android permission as a
defensive guard. A guest that gets a runtime Allow but
the platform denies the call sees a clean "device
absent" — not a stack trace.

### The wiring

```
Hilt SingletonComponent
  ├── PlatformHardwareProvider  (AndroidPlatformHardwareProvider)
  ├── HardwareEnforcer          (AndroidHardwareEnforcer)
  ├── HardwareBroker            (HardwareBroker)
  ├── HardwareAuditLog          (HardwareAuditLog)
  └── HardwareEnforcementService (composes the four above)
```

A consumer injects `HardwareEnforcementService` and
gets the broker + enforcer + audit log via the service.
The Hilt graph is the only place a new collaborator is
added.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `HardwareModuleTest` | 8 | 0 |
| **Project total** | **1166** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bugs found and fixed during this phase

1. **Typo in the production provider** — `LocationFix(...)`
   was closed with `}` instead of `)`. The Kotlin
   parser reported "Expecting ')'" 3 lines after the
   typo. The fix was a one-character change.
2. **`UsbDevice.deviceId` is an Int, not a String.**
   The provider copied it directly into
   `UsbDeviceInfo.deviceId` (which is a String);
   the compiler caught the type mismatch. Fix:
   serialise with `device.deviceId.toString()`.
3. **BluetoothManager.adapter is nullable.** Calling
   `manager.adapter.bondedDevices` was a smart-cast
   failure. Fix: use the safe-call `manager.adapter ?: return emptyList()`.
4. **Hilt test for the `Context`-dependent provider.**
   Cannot instantiate `AndroidPlatformHardwareProvider(Context)`
   in JVM unit tests (Context is a stub). The 8 unit
   tests verify the *contract* (interface assignment,
   module structure) instead of the behaviour. The
   behaviour is covered in `androidTest/` via
   `HiltAndroidRule` (a follow-up phase).

## Next phase

The hardware path is complete (broker + enforcer +
service + provider + Hilt). The next big step is
**§19 WinLayer + §20 Windows VM** (master order) — the
Windows layer that lets Elysium run Windows guests
under a QEMU/KVM-backed VM. The hardware path is one
of the inputs to that layer (the Windows VM needs
USB passthrough, network bridge, etc.).

Or **§22 Workspaces** — the multi-session UI / state
isolation layer that the user's app will sit on top
of. Or **§36 remaining ADRs** (the master order lists
ADRs 001, 002, 005–015; we have 005, 006, 007, 008,
009, 010, 011 — 7 to go).
