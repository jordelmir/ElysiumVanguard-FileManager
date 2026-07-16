# ADR-011 — Hardware Hilt Wiring + Production Provider

Status: **Accepted** (Phase 21, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: none

## Context

Phases 18–20 added the four hardware access layers:

1. **Broker** (Phase 18) — pure-JVM decision engine.
2. **Enforcer interface** (Phase 19) — the seam between
   decision and dispatch.
3. **Service** (Phase 19) — broker + enforcer + audit
   log glue.
4. **Android enforcer** (Phase 20) — production
   implementation of the enforcer interface.

But none of these were wired into the runtime's DI graph.
A `Hilt`-managed `SingletonComponent` was the missing
piece. The runtime's Hilt module (`DistroModule`, etc.)
does not currently know about the hardware path; a
caller that wants a `HardwareEnforcementService` has
no place to ask.

## Decision

Add a new Hilt module — `HardwareModule` — that provides
five singletons into the runtime's DI graph:

- `PlatformHardwareProvider` — the production
  `AndroidPlatformHardwareProvider` (wraps the Android
  platform managers: `UsbManager`, `BluetoothManager`,
  `NfcAdapter`, `SensorManager`, `LocationManager`,
  `CameraManager`).
- `HardwareEnforcer` — the `AndroidHardwareEnforcer` from
  Phase 20, injected with the provider.
- `HardwareBroker` — a fresh `HardwareBroker` instance.
- `HardwareAuditLog` — a fresh `HardwareAuditLog` instance.
- `HardwareEnforcementService` — the user-facing entry
  point, injected with the broker + enforcer + audit log.

The module lives in
`com.elysium.vanguard.core.runtime.hardware.HardwareModule`
and is `@InstallIn(SingletonComponent::class)`. The
production provider takes a `@ApplicationContext` via
Hilt's qualifier.

### Why a new module, not an extension of `DistroModule`

`DistroModule` is the module for the distro management
path (Phase 17's `DistroManager` + provisioning
pipeline). The hardware path is orthogonal: it does not
depend on the distro manager, the launcher resolver,
or any of the `DistroStorage` types. Adding the
hardware providers to `DistroModule` would couple two
unrelated subsystems; a separate module keeps each
focused and lets the runtime's Hilt graph compose them
independently.

### Why the production provider checks permissions

`AndroidPlatformHardwareProvider` checks the relevant
Android permission via `ContextCompat.checkSelfPermission`
before each call. When the permission is not granted, the
method returns a "device absent" result (empty list, null
fix, false capability) rather than throwing. Two reasons:

1. The runtime's broker (Phase 18) is the policy layer.
   A broker decision of "BLOCKED" and an Android
   permission of "denied" are different concerns; the
   provider should not surface platform errors as
   runtime errors.
2. The audit log (Phase 18/19) records the *runtime's*
   decision. A "permission denied" from the OS is a
   separate signal that lives in the platform's
   logging layer.

The result is that a guest that gets a runtime Allow but
the platform denies the call sees a clean "device
absent" — not a stack trace. The UI surfaces the
runtime's audit log; the user toggles the platform
permission separately.

### Why the audit log is a Hilt singleton, not a service field

The audit log survives the service so the observability
layer (Phase 25) can drain it without holding a
service reference. A `Hilt`-managed `@Singleton` is
the natural shape: the graph holds one instance for
the lifetime of the process, and the service +
observability layer both inject it.

## Consequences

### Positive

- **Single source of truth for hardware wiring.** The
  Hilt module is the only place a new collaborator is
  added. The 8 unit tests in `HardwareModuleTest`
  pin the module's surface (the five `provide*`
  methods, the singleton scope, the implementation
  classes) without an Android device.
- **Provider exception is wrapped.** The
  `AndroidPlatformHardwareProvider` propagates
  exceptions from the platform managers; the
  `AndroidHardwareEnforcer` wraps them as
  `HardwareEnforcementResult.Error`. A platform
  failure surfaces as a typed result, not a crash.
- **USB device id is normalised.** Android's
  `UsbDevice.deviceId` is an Int; the provider
  serialises to String so the runtime can pass it
  around without an Int. The `UsbDeviceInfo.deviceId`
  field is the canonical string form.
- **Permission check is a defensive guard.** The
  provider checks each permission before each call
  and returns a clean "device absent" result. The
  runtime is not a permission gate; the OS is. A
  guest's runtime policy says Allow, the OS says
  Denied; the guest sees a clean empty result.

### Negative

- **`AndroidPlatformHardwareProvider` itself is not
  JVM-testable.** It uses `Context` (a stub in unit
  tests) and the Android platform managers. The
  tests verify the provider's *contract*
  (implements `PlatformHardwareProvider`, has the
  right class shape) but not its behaviour. The
  behaviour is tested in `androidTest/` via
  `HiltAndroidRule`.
- **The `Hilt` singleton scope is process-wide.** A
  guest that wants a *per-session* audit log cannot
  get one from this module. A future phase splits the
  audit log per session if per-session isolation is
  needed.
- **No telemetry on the Hilt graph.** A misconfigured
  provider (e.g. the wrong `PlatformHardwareProvider`
  implementation) is caught at runtime by the
  service's typed results, not at Hilt's compile
  time. Hilt does not enforce that the binding's
  type matches the consumer's type; that's the
  compiler's job. The 8 unit tests cover the
  contract; full integration is `androidTest/`.

## Alternatives considered

1. **Put the hardware providers in `DistroModule`.**
   Rejected: the hardware path is orthogonal to
   distros. Mixing them in one module couples two
   unrelated subsystems.
2. **Make the `AndroidPlatformHardwareProvider` a
   `Hilt`-managed class with `@Inject constructor`.**
   The module-then-`@Provides` style is more verbose
   but it lets the unit tests verify the module's
   structure (the 5 `provide*` methods) without
   instantiating the `Context`-dependent class. With
   `@Inject constructor`, the test would have to
   provide a fake `Context`, which is more work for
   the same JVM coverage.
3. **Skip the Hilt module; let callers instantiate
   the service directly.** Rejected: every consumer
   of the service would have to know about the
   three collaborators (broker, enforcer, audit
   log) and wire them. The Hilt module is one
   place to compose; consumers just `@Inject`
   `HardwareEnforcementService`.

## Revisit triggers

- A new hardware class (e.g. `IR`, `Thread` mesh) is
  added. The `HardwareBroker` (Phase 18) and
  `AndroidHardwareEnforcer` (Phase 20) gain a new
  branch; the `AndroidPlatformHardwareProvider`
  gains a new method. The Hilt module does not
  change (the provider + enforcer interfaces are
  the binding target).
- The audit log becomes per-session. The Hilt
  module drops the `@Singleton` scope on the log
  and adds a `SessionScoped` qualifier; the
  provider + enforcer + service take a
  `SessionId` parameter.
- The provider needs more Android permissions
  (e.g. `READ_PHONE_STATE` for IMEI-based device
  identity). The `hasPermission` helper is the
  single point to update.
