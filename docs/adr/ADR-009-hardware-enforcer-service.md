# ADR-009 — Hardware Enforcer + Service

Status: **Accepted** (Phase 19, 2026-07-15)
Owners: Runtime
Supersedes: none
Superseded by: ADR-010 (Android platform adapter, pending)

## Context

Phase 18 added the `HardwareBroker`, a pure-JVM decision
engine that says yes / no / require-confirmation given a
`HardwarePolicy` and a request. Without an enforcer, the
broker is advisory: a guest that called
`BluetoothAdapter.startDiscovery()` directly (bypassing the
broker) would not be stopped. The runtime's hardware
guarantees depended on every guest pathologically respecting
the broker — an unrealistic assumption.

The challenge: enforcement requires the host platform
(`UsbManager.requestPermission`, `BluetoothAdapter.startDiscovery`,
`NfcAdapter.enable`, `SensorManager.registerListener`, etc.).
These are Android-specific and may not be available in unit
tests. We need a **seam** that keeps the policy logic
JVM-testable end-to-end while letting the production backend
talk to the platform.

## Decision

We split hardware access into three layers:

1. **Broker** (Phase 18) — `HardwareBroker`. Pure-JVM
   decision engine. Says yes / no / require-confirmation.
2. **Enforcer** (this ADR) — `HardwareEnforcer` interface
   + `RecordingHardwareEnforcer` (test impl) +
   `HardwareEnforcementService` glue. Receives the
   broker's decision + the original request, returns a
   typed `HardwareEnforcementResult`. The enforcer
   *translates* — it does not re-decide.
3. **Adapter** (Phase 20, ADR-010, pending) —
   `AndroidHardwareEnforcer` that wraps the platform APIs
   and implements the interface. The runtime wires this in
   production; the test suite uses the recording enforcer.

The service is the user-facing entry point:

```kotlin
class HardwareEnforcementService(
    private val broker: HardwareBroker,
    private val enforcer: HardwareEnforcer,
    private val audit: HardwareAuditLog
) {
    fun request(...): HardwareEnforcementResult {
        val decision = broker.decide(...)
        return when (decision) {
            is Deny -> Denied (recorded by service)
            is Allow,
            is AllowWithConfirmation -> enforcer.enforce(...)
        }
    }
}
```

### The four enforcer results

```kotlin
sealed class HardwareEnforcementResult {
    data class Granted(val handle: HardwareHandle?) : ...     // API call succeeded
    data class PendingConsent(val consentId: String) : ...   // UI is showing a dialog
    object Denied : ...                                      // broker or platform refused
    data class Error(val cause: Throwable) : ...              // non-permission error
}
```

- **Granted** — the platform call succeeded. The optional
  `handle` is for long-lived resources (USB `openDevice`,
  Bluetooth `createRfcommSocket`); null for read-only
  operations (LIST, READ).
- **PendingConsent** — the broker said
  `AllowWithConfirmation`; the enforcer dispatched a
  consent dialog. The runtime polls or subscribes to a
  `ConsentEventBus` (Phase 25) for the matching
  `consentId` and re-calls `request` when the user
  decides.
- **Denied** — the broker said Deny, OR the platform
  call itself returned a "permission denied" error.
  Indistinguishable from the caller's perspective; the
  audit log distinguishes them.
- **Error** — a non-permission error (USB device
  disconnected, Bluetooth radio off). The caller surfaces
  the `cause` to the user.

### Why a handle value class

A USB device connection is not a serialisable value — it's
a live platform object. The enforcer's `Granted(handle)`
hands back an opaque id the caller passes back to the
enforcer for follow-up operations. The enforcer resolves
the id to the live platform object via a private map. The
handle's id is a string so it survives JSON serialisation
(a future phase persists in-flight consent dialogs across
reboots).

### Why the service records the deny

The broker's `deny` path is documented to skip the audit
log ("the caller is expected to audit themselves on the
deny path with full context"). The service is that caller
— it has the full `HardwareRequest` and is the one
returning the typed result. Recording the deny in the
service keeps the audit log complete without making the
broker do duplicate work.

### Why a separate `RecordingHardwareEnforcer`

The test suite needs to assert on what the enforcer saw
(per-class, per-action, per-target) and control the
response (Granted vs PendingConsent vs Denied vs Error).
A "real" enforcer that always returns Granted is enough
for some tests; a "per-request" response (USB returns
Granted with a handle, BLUETOOTH returns Denied) is what
the wildcard-confirmation tests need. The recording
enforcer supports both: a default response + an optional
per-request function.

## Consequences

### Positive

- **JVM-testable end-to-end.** The broker is pure JVM
  (Phase 18), the enforcer is an interface (this phase),
  the test impl is a 5-line `RecordingHardwareEnforcer`.
  The whole service is unit-tested without an Android
  device.
- **Deny short-circuits the enforcer.** The enforcer is
  never called for a Deny decision. The platform API
  call is not made; the audit log captures the decision
  via the service.
- **Audit log is complete.** The service records all
  three decision kinds (allow, confirmation, deny). The
  audit log shape matches the network audit log
  (Phase 13) — a future observability phase ships a
  single `AuditLogReader` for both.
- **Thread-safe.** The recording enforcer uses a
  `synchronized` lock; the test exercises 8 × 50
  concurrent requests from multiple sessions with no
  races.
- **Seam for the Android adapter.** Phase 20 adds
  `AndroidHardwareEnforcer` (Android `UsbManager`,
  `BluetoothManager`, `NfcManager`, `SensorManager`)
  without touching the broker or the service.

### Negative

- **No Android adapter yet.** Until Phase 20, the
  service is wired with the recording enforcer and the
  runtime has no real hardware enforcement. The broker
  + service are still useful in the meantime: the
  network broker (Phase 13) follows the same shape, and
  the consent UI is a separate concern.
- **`PendingConsent` requires a polling or event-bus
  glue.** The runtime needs a `ConsentEventBus` to
  surface the user's choice. Phase 25 (observability)
  adds this. For now, the test suite asserts the
  `PendingConsent.consentId` directly.
- **Handle is a string id, not a real platform object.**
  The production Android adapter (Phase 20) maintains
  the id → live object map. A misbehaving adapter could
  leak the underlying object; the audit log + a future
  resource-lifecycle phase track this.

## Alternatives considered

1. **Inline the enforcer in the service.** A single
   `HardwareEnforcementService` that does both decide
   + dispatch. Rejected: the seam between the decision
   (pure-JVM) and the dispatch (platform-specific) is
   the same one the firewall (Phase 15) uses; collapsing
   them loses the testability win.
2. **Have the enforcer re-decide.** The enforcer takes
   the policy + request, calls the broker internally,
   and dispatches. Rejected: the broker's
   `AllowWithConfirmation` may need a UI round-trip;
   the enforcer's dispatch model is fundamentally
   different from the broker's synchronous decision.
   Keeping them split lets each be testable in
   isolation.
3. **Skip the recording enforcer; use mockito.** The
   existing `engineering-gotchas.md` flags mockito as
   fragile in this codebase. A 60-line
   `RecordingHardwareEnforcer` is simpler, has no
   runtime cost, and makes the test's intent obvious
   from the test code.

## Revisit triggers

- The first real on-device install of a guest that uses
  USB / Bluetooth / NFC reveals a platform API we
  forgot to wrap. Phase 20 adds the missing adapter.
- The `HardwareHandle` id space collides between USB
  and Bluetooth. We namespace handles (`usb:1234:5678`,
  `bt:11:22:33:44:55:66`) and the adapter switches on
  the prefix.
- The "remember my choice" semantics becomes a
  requirement (per-device consent memory). The service
  gains a `ConsentMemory` collaborator; the policy's
  `rememberConfirmations` flag becomes the gate.
