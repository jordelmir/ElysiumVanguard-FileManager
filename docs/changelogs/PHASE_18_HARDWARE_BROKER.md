# Phase 18 — Hardware Broker

Date: 2026-07-15
Status: **Shipped** — `assembleDebug` green, 1124 tests, 0 failures, 2 skipped.

## What landed

The runtime now has a typed decision engine for host hardware
access. The `HardwareBroker` mirrors the `NetworkBroker` from
Phase 13: pure JVM, takes a `HardwarePolicy` and a request,
returns a typed `HardwareDecision`. The platform integration
(Android `UsbManager`, `BluetoothManager`, `NfcManager`) is
Phase 19; this ADR is only the decision engine.

### Files

**Production (5 new):**

- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwareClasses.kt`
  — `HardwareClass` enum: USB, BLUETOOTH, NFC, CAMERA,
  MICROPHONE, LOCATION, SENSORS.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwareAccess.kt`
  — `HardwareAccess` enum: BLOCKED, SILENT, READ_ONLY,
  READ_WRITE, CONFIRM.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwarePolicy.kt`
  — `HardwarePolicy` data class: `defaultMode` + per-class
  `classAccess` map + `rememberConfirmations` flag. Per-class
  overrides beat the default.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwareDecision.kt`
  — `HardwareDecision` sealed class: Allow /
  AllowWithConfirmation / Deny. The `permits` helper
  collapses Allow + AllowWithConfirmation to a single
  boolean.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwareAction.kt`
  — `HardwareAction` enum: LIST, READ, WRITE, CONNECT. The
  coarse label of what a request wants to do; the platform
  enforcer (Phase 19) translates the label into a specific
  API call.
- `app/src/main/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwareBroker.kt`
  — the broker. Plus `HardwareTargetId` (Any / Specific /
  WildcardUsb), `AuditEvent`, `HardwareAuditLog`.

**Tests (1 new):**

- `app/src/test/java/com/elysium/vanguard/core/runtime/hardware/broker/HardwareBrokerTest.kt`
  — 18 tests covering every access mode, the per-class
  override, every wildcard confirmation rule, the audit
  log, thread-safety, and value-type invariants.

**ADR (1 new):**

- `docs/adr/ADR-008-hardware-broker-pure-jvm.md` — context,
  decision, consequences, alternatives, revisit triggers.

### Why this matters

Before Phase 18, host hardware access had no typed model.
A guest that wanted `BluetoothAdapter.startDiscovery()` had
no runtime-mediated barrier between the request and the OS.
Phase 18 closes the decision half: every request goes
through the broker, the broker applies the policy, the
typed decision is what the platform enforcer (Phase 19)
will act on.

The two-axis model (class × action) is what makes the policy
expressive. A flat "USB mode" would force compound codes
like `READ_ONLY_EXCEPT_WRITE`; the action axis lets the
broker express the same intent cleanly: USB is READ_ONLY,
and the `WRITE` action is denied.

### The decision matrix

| Access | LIST / READ | WRITE / CONNECT |
|---|---|---|
| BLOCKED | Deny (no exception) | Deny (no exception) |
| SILENT | Allow (enforcer returns empty) | Allow (enforcer returns empty) |
| READ_ONLY | Allow | Deny |
| READ_WRITE | Allow (subject to wildcard rules) | Allow (subject to wildcard rules) |
| CONFIRM | AllowWithConfirmation | AllowWithConfirmation |

The wildcard rules (master order §18 "never allow access to
every device without explicit consent"):

- USB `LIST` or `CONNECT` with `WildcardUsb` target →
  AllowWithConfirmation (even under READ_WRITE).
- BLUETOOTH `LIST` with `Any` target → AllowWithConfirmation
  (even under READ_WRITE). A specific MAC is allowed.
- LOCATION `READ` with `Any` target → AllowWithConfirmation
  (even under READ_WRITE). A specific accuracy hint is
  allowed.

## Test count

| Suite | Tests | Failures |
|---|---|---|
| `HardwareBrokerTest` | 18 | 0 |
| **Project total** | **1124** | **0** |
| Skipped | 2 | (real-archive integration only) |

## Bug fix during this phase

One trivial compile error: a backtick-quoted test name
contained a `/` (`VID/PID`), which Kotlin rejects in test
method names. Renamed the test to drop the slash. The
assertion that checks for the `VID/PID` string in the
decision's reason still passes — the issue was only the
*test method's* name, not the test's content.

## Next phase

**Phase 19 — Hardware Enforcer (ADR-009).** The broker is
advisory without an enforcer. Phase 19 adds the
`HardwareEnforcer` interface and the platform adapter
(Android's `UsbManager.requestPermission`,
`BluetoothAdapter.startDiscovery`, `NfcAdapter` enable, etc.)
that translates the broker's decision into a real platform
API call. The seam is the same translator + backend split
as Phase 15 (firewall).
